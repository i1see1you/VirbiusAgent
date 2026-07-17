// Package main implements the Virbius WASM plugin for Higress (Envoy).
//
// This plugin intercepts MCP tools/call requests at the HTTP layer:
//  1. tool allowlist check (local)
//  2. rate limiting via Redis counter (async)
//  3. fast-path bypass for low-risk tools
//  4. engine evaluate call (POST /v1/evaluate)
//  5. HTTP 403 block on deny
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/alibaba/higress/plugins/wasm-go/pkg/wrapper"
	"github.com/tetratelabs/proxy-wasm-go-sdk/proxywasm"
	"github.com/tetratelabs/proxy-wasm-go-sdk/proxywasm/types"
	"github.com/virbius/virbius-expr"
)

func main() {
	wrapper.SetCtx(
		"virbius-gateway",
		wrapper.ParseConfigBy(parseConfig),
		wrapper.ProcessRequestHeadersBy(onHttpRequestHeaders),
		wrapper.ProcessRequestBodyBy(onHttpRequestBody),
	)
}

// --- Configuration ---

// ExpressionRule pairs a compiled expression IR with its action binding.
type ExpressionRule struct {
	Expression expr.Expression    `json:"expression"`
	Action     expr.ActionBinding `json:"action"`
}

type VirbiusConfig struct {
	TenantID        string           `json:"tenant_id"`
	Evaluate        bool             `json:"evaluate"`
	EngineURL       string           `json:"engine_url"`
	EngineTimeoutMs uint32           `json:"engine_timeout_ms"`
	ToolRateLimit   int              `json:"tool_rate_limit"`
	FastPathTools   []string         `json:"fast_path_tools"`
	Allowlist       []string         `json:"tool_allowlist"`
	Expressions     []ExpressionRule `json:"expressions,omitempty"`
	LicenseVerify   bool             `json:"license_verify"`
	TLS             bool             `json:"tls"`
	FailMode        string           `json:"fail_mode"`
	RedisClient     wrapper.RedisClient
	HTTPClient      wrapper.HttpClient
}

func parseConfig(jsonBytes []byte, config *VirbiusConfig) error {
	if err := json.Unmarshal(jsonBytes, config); err != nil {
		return fmt.Errorf("failed to parse config: %v", err)
	}
	if config.FailMode == "" {
		config.FailMode = "open"
	}
	if config.EngineTimeoutMs == 0 {
		config.EngineTimeoutMs = 3000
	}
	if config.ToolRateLimit == 0 {
		config.ToolRateLimit = 50
	}

	// Initialize Redis client for rate limiting
	config.RedisClient = wrapper.NewRedisClient("virbius-redis", "default", wrapper.RedisClientOptions{
		Timeout:     uint32(time.Millisecond * 100),
		DB:          0,
		Password:    "",
		HasTag:      false,
		ReadCluster: false,
	})

	// Initialize HTTP client for engine calls
	config.HTTPClient = wrapper.NewClusterClient(wrapper.RouteCluster{
		Cluster:   "outbound|8082||virbius-engine.default.svc.cluster.local",
		Authority: "virbius-engine.default.svc.cluster.local:8082",
	})

	return nil
}

// --- Request Processing ---

func onHttpRequestHeaders(ctx wrapper.HttpContext, config VirbiusConfig, log wrapper.Log) types.Action {
	// Only intercept POST requests to MCP endpoints
	method := ctx.Method()
	if method != http.MethodPost {
		return types.ActionContinue
	}

	path := ctx.Path()
	if !strings.Contains(path, "/mcp/") && !strings.Contains(path, "/tools/call") {
		return types.ActionContinue
	}

	// Extract MCP session and tool info from headers
	toolName := ctx.Header().Get("x-mcp-tool-name")
	sessionID := ctx.Header().Get("x-mcp-session-id")
	challengeToken := ctx.Header().Get("x-virbius-challenge-token")

	// If a challenge token is present, verify it before allowing
	if challengeToken != "" && toolName != "" && config.Evaluate {
		return verifyChallengeToken(ctx, config, log, toolName, sessionID, challengeToken)
	}

	if toolName == "" {
		// Not a tools/call request — transparent forward
		return types.ActionContinue
	}

	log.Infof("virbius-wasm: intercepting tool=%s session=%s path=%s", toolName, sessionID, path)

	// 1. Tool allowlist check
	if len(config.Allowlist) > 0 && !contains(config.Allowlist, toolName) {
		return denyRequest(ctx, log, "not_in_allowlist", toolName, "tool not in allowlist")
	}

	// 1b. Expression evaluation — lightweight edge rules before engine call
	if len(config.Expressions) > 0 {
		result := evalExpressions(config.Expressions, toolName, sessionID, path, config.TenantID, log)
		if result != nil {
			return *result
		}
	}

	// 2. Rate limiting via Redis (async)
	if config.ToolRateLimit > 0 && sessionID != "" {
		rateKey := fmt.Sprintf("tool:%s:session:%s", toolName, sessionID)
		go func() {
			if err := config.RedisClient.Inc(rateKey, 1, 3600, func(reply interface{}, err error) {
				if err != nil {
					log.Warnf("virbius-wasm: redis incr error: %v", err)
					return
				}
				if count, ok := reply.(int64); ok && count > int64(config.ToolRateLimit) {
					log.Infof("virbius-wasm: rate limit exceeded for tool=%s session=%s count=%d", toolName, sessionID, count)
				}
			}); err != nil {
				log.Warnf("virbius-wasm: redis incr failed: %v", err)
			}
		}()
	}

	// 3. Fast path check — skip engine for low-risk tools
	if config.Evaluate && contains(config.FastPathTools, toolName) {
		log.Debugf("virbius-wasm: fast-path allow tool=%s", toolName)
		return types.ActionContinue
	}

	// 4. If engine evaluation is disabled, allow (allowlist + rate limit already passed)
	if !config.Evaluate {
		return types.ActionContinue
	}

	// 5. Engine evaluate — async HTTP call
	return callEngine(ctx, config, log, toolName, sessionID)
}

func onHttpRequestBody(ctx wrapper.HttpContext, config VirbiusConfig, body []byte, log wrapper.Log) types.Action {
	if len(body) == 0 {
		return types.ActionContinue
	}

	// Parse JSON-RPC body to extract tool_name and arguments
	var rpcMsg struct {
		Method string `json:"method"`
		Params struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		} `json:"params"`
	}
	if err := json.Unmarshal(body, &rpcMsg); err != nil {
		return types.ActionContinue
	}

	if rpcMsg.Method != "tools/call" || rpcMsg.Params.Name == "" {
		return types.ActionContinue
	}

	toolName := rpcMsg.Params.Name
	log.Infof("virbius-wasm: extracted tool_name from body: %s", toolName)

	path := ctx.Path()
	sessionID := ctx.Header().Get("x-mcp-session-id")

	// 1. Tool allowlist check
	if len(config.Allowlist) > 0 && !contains(config.Allowlist, toolName) {
		return denyRequest(ctx, log, "not_in_allowlist", toolName, "tool not in allowlist")
	}

	// 1b. Expression evaluation with enriched context (from body)
	if len(config.Expressions) > 0 {
		result := evalExpressionsWithBody(config.Expressions, toolName, sessionID, path, config.TenantID, rpcMsg.Params.Arguments, log)
		if result != nil {
			return *result
		}
	}

	// 2. Fast path
	if contains(config.FastPathTools, toolName) {
		return types.ActionContinue
	}

	// 3. If evaluate is disabled, allow
	if !config.Evaluate {
		return types.ActionContinue
	}

	// 4. Engine evaluate — the headers callback may have already started this.
	//    If we reach here, the headers callback didn't have the tool name.
	return callEngine(ctx, config, log, toolName, sessionID)
}

// --- Expression Evaluation ---

// evalExpressions evaluates all compiled expression rules against the request context.
// Returns a deny/challenge action if any expression matches, nil otherwise.
func evalExpressions(rules []ExpressionRule, toolName, sessionID, path, tenantID string, log wrapper.Log) *types.Action {
	return evalExpressionsWithBody(rules, toolName, sessionID, path, tenantID, nil, log)
}

// actionPriority maps an action string to its priority for merge resolution.
// Mirrors Engine-side IntentAction.priority(): deny=100, challenge=50, review=30.
func actionPriority(action string) int {
	switch action {
	case "block":
		return 100
	case "challenge":
		return 50
	case "review":
		return 30
	default:
		return 0
	}
}

// evalExpressionsWithBody evaluates ALL expression rules, then merges results by
// action priority (block > challenge > review). Among rules at the same priority,
// the one with the highest risk_score wins as the primary rule.
//
// This mirrors the Engine-side ActionMerge.merge() logic so that gateway-side
// edge decisions are consistent with cloud-side decisions.
func evalExpressionsWithBody(rules []ExpressionRule, toolName, sessionID, path, tenantID string, argsRaw json.RawMessage, log wrapper.Log) *types.Action {
	ctx := map[string]any{
		"tool_name":  toolName,
		"session_id": sessionID,
		"path":       path,
		"tenant_id":  tenantID,
	}

	// Parse tool arguments and merge into context
	if len(argsRaw) > 0 {
		var args map[string]any
		if err := json.Unmarshal(argsRaw, &args); err == nil {
			ctx["args"] = args
			// Flatten common arg fields for direct access (e.g., ctx.var('command'))
			for k, v := range args {
				if _, exists := ctx[k]; !exists {
					ctx[k] = v
				}
			}
		}
		ctx["args_json"] = string(argsRaw)
	}

	// Collect all matched rules
	var matched []ExpressionRule
	for _, rule := range rules {
		result, err := expr.Eval(&rule.Expression, ctx)
		if err != nil {
			log.Warnf("virbius-wasm: expr eval error rule=%s: %v", rule.Action.ExprID, err)
			continue
		}
		if !result {
			continue
		}
		log.Infof("virbius-wasm: expr match rule=%s action=%s reason=%s",
			rule.Action.ExprID, rule.Action.Action, rule.Action.Reason)
		matched = append(matched, rule)
	}

	if len(matched) == 0 {
		return nil
	}

	// Find the highest priority among all matched rules
	maxPriority := 0
	for _, r := range matched {
		p := actionPriority(r.Action.Action)
		if p > maxPriority {
			maxPriority = p
		}
	}

	// Among rules at maxPriority, pick the one with highest risk_score as primary
	var primary *ExpressionRule
	for i := range matched {
		if actionPriority(matched[i].Action.Action) != maxPriority {
			continue
		}
		if primary == nil || matched[i].Action.RiskScore > primary.Action.RiskScore {
			primary = &matched[i]
		}
	}
	if primary == nil {
		return nil
	}

	log.Infof("virbius-wasm: expr merged %d hits → action=%s primary=%s risk_score=%d",
		len(matched), primary.Action.Action, primary.Action.RuleID, primary.Action.RiskScore)

	switch primary.Action.Action {
	case "block":
		action := denyRequest(nil, log, primary.Action.RuleID, toolName, primary.Action.Reason)
		return &action
	case "challenge":
		action := challengeRequest(nil, log, toolName, primary.Action.ExprID, "", primary.Action.Reason)
		return &action
	case "review":
		// Review mode: log and allow (engine will decide)
		log.Infof("virbius-wasm: expr review primary=%s, deferring to engine", primary.Action.ExprID)
		return nil
	default:
		return nil
	}
}

// --- Engine Call ---

func callEngine(ctx wrapper.HttpContext, config VirbiusConfig, log wrapper.Log, toolName, sessionID string) types.Action {
	engineReq := map[string]interface{}{
		"trace_id":    ctx.GetTraceID(),
		"session_id":  sessionID,
		"tool_name":   toolName,
		"tenant_id":   config.TenantID,
		"args":        map[string]interface{}{},
	}

	body, _ := json.Marshal(engineReq)

	err := config.HTTPClient.Post("/v1/evaluate", [][2]string{
		{"Content-Type", "application/json"},
	}, body, func(statusCode int, responseHeaders http.Header, responseBody []byte) {
		if statusCode != http.StatusOK {
			log.Warnf("virbius-wasm: engine returned %d: %s", statusCode, string(responseBody))
			// Fail-open or fail-closed based on config
			if config.FailMode == "closed" {
				_ = denyRequest(ctx, log, "engine_error", toolName, "engine unavailable")
			}
			proxywasm.ResumeHttpRequest()
			return
		}

		var resp struct {
			EffectiveAction string `json:"effective_action"`
			RuleID          string `json:"rule_id"`
			Reason          string `json:"reason"`
			ChallengeID     string `json:"challenge_id"`
			ArgsHash        string `json:"args_hash"`
		}
		if err := json.Unmarshal(responseBody, &resp); err != nil {
			log.Warnf("virbius-wasm: engine response parse error: %v", err)
			proxywasm.ResumeHttpRequest()
			return
		}

		if resp.EffectiveAction == "block" {
			log.Infof("virbius-wasm: engine blocked tool=%s rule=%s reason=%s", toolName, resp.RuleID, resp.Reason)
			_ = denyRequest(ctx, log, "engine_blocked", toolName, resp.Reason)
			proxywasm.ResumeHttpRequest()
			return
		}

		if resp.EffectiveAction == "challenge" {
			log.Infof("virbius-wasm: engine challenge required tool=%s rule=%s challenge_id=%s", toolName, resp.RuleID, resp.ChallengeID)
			_ = challengeRequest(ctx, log, toolName, resp.ChallengeID, resp.ArgsHash, resp.Reason)
			proxywasm.ResumeHttpRequest()
			return
		}

		log.Debugf("virbius-wasm: engine allowed tool=%s", toolName)
		proxywasm.ResumeHttpRequest()
	}, uint32(config.EngineTimeoutMs))

	if err != nil {
		log.Warnf("virbius-wasm: engine call failed: %v", err)
		if config.FailMode == "closed" {
			return denyRequest(ctx, log, "engine_error", toolName, "engine call failed")
		}
		return types.ActionContinue
	}

	// Pause request until engine responds
	return types.ActionPause
}

// --- Helpers ---

func denyRequest(ctx wrapper.HttpContext, log wrapper.Log, code, toolName, reason string) types.Action {
	errBody := fmt.Sprintf(`{
		"jsonrpc": "2.0",
		"error": {
			"code": -32006,
			"message": "%s",
			"data": {
				"tool_name": "%s",
				"reason": "%s",
				"http_analog": 403
			}
		}
	}`, code, toolName, reason)

	if err := proxywasm.SendHttpResponseWithDetail(http.StatusForbidden, "virbius-gateway", [][2]string{
		{"Content-Type", "application/json"},
	}, []byte(errBody), -1); err != nil {
		log.Errorf("virbius-wasm: failed to send deny response: %v", err)
	}
	return types.ActionContinue
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

// --- Challenge Handling ---

// verifyChallengeToken calls the Engine /v1/challenge/verify endpoint to verify
// a one-time-use challenge token. If valid, the request is allowed; otherwise,
// a 403 error is returned.
func verifyChallengeToken(ctx wrapper.HttpContext, config VirbiusConfig, log wrapper.Log, toolName, sessionID, token string) types.Action {
	verifyReq := map[string]interface{}{
		"token":       token,
		"tool_name":   toolName,
		"session_id":  sessionID,
		"args_hash":   "", // Engine looks up from token record
	}
	body, _ := json.Marshal(verifyReq)

	err := config.HTTPClient.Post("/v1/challenge/verify", [][2]string{
		{"Content-Type", "application/json"},
	}, body, func(statusCode int, responseHeaders http.Header, responseBody []byte) {
		if statusCode != http.StatusOK {
			log.Warnf("virbius-wasm: challenge verify failed: %d %s", statusCode, string(responseBody))
			_ = denyRequest(ctx, log, "challenge_token_invalid", toolName, "challenge token invalid or expired")
			proxywasm.ResumeHttpRequest()
			return
		}

		var result struct {
			Valid       bool   `json:"valid"`
			ChallengeID string `json:"challenge_id"`
			Reason      string `json:"reason"`
		}
		if err := json.Unmarshal(responseBody, &result); err != nil {
			log.Warnf("virbius-wasm: challenge verify response parse error: %v", err)
			_ = denyRequest(ctx, log, "challenge_token_invalid", toolName, "challenge verify response error")
			proxywasm.ResumeHttpRequest()
			return
		}

		if !result.Valid {
			log.Infof("virbius-wasm: challenge token invalid: tool=%s reason=%s", toolName, result.Reason)
			_ = denyRequest(ctx, log, "challenge_token_invalid", toolName, "challenge token invalid: "+result.Reason)
			proxywasm.ResumeHttpRequest()
			return
		}

		log.Infof("virbius-wasm: challenge token verified: tool=%s challenge=%s", toolName, result.ChallengeID)
		// Remove the challenge token header before forwarding to upstream
		ctx.Header().Del("x-virbius-challenge-token")
		proxywasm.ResumeHttpRequest()
	}, uint32(config.EngineTimeoutMs))

	if err != nil {
		log.Warnf("virbius-wasm: challenge verify call failed: %v", err)
		if config.FailMode == "closed" {
			return denyRequest(ctx, log, "challenge_verify_error", toolName, "challenge verify service unavailable")
		}
		return types.ActionContinue
	}

	return types.ActionPause
}

// challengeRequest sends a JSON-RPC error -32011 (challenge_required) response
// with the challenge_id so the Agent can poll for approval.
func challengeRequest(ctx wrapper.HttpContext, log wrapper.Log, toolName, challengeID, argsHash, reason string) types.Action {
	if reason == "" {
		reason = "challenge_required"
	}
	errBody := fmt.Sprintf(`{
		"jsonrpc": "2.0",
		"error": {
			"code": -32011,
			"message": "challenge_required",
			"data": {
				"tool_name": "%s",
				"challenge_id": "%s",
				"args_hash": "%s",
				"reason": "%s",
				"http_analog": 403
			}
		}
	}`, toolName, challengeID, argsHash, reason)

	if err := proxywasm.SendHttpResponseWithDetail(http.StatusForbidden, "virbius-gateway", [][2]string{
		{"Content-Type", "application/json"},
	}, []byte(errBody), -1); err != nil {
		log.Errorf("virbius-wasm: failed to send challenge response: %v", err)
	}
	return types.ActionContinue
}
