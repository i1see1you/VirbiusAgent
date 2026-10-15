package io.virbius.engine.api;

import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authoritative internal decision API (HTTP/JSON). This is the single decision
 * path consumed by the MCP proxy, the gateway (compiled {@code engine_url}),
 * and the Control-side simulate/challenge facades. The former gRPC mirror
 * (port 50051) was removed — see CHANGELOG.
 *
 * <p><b>Review roles</b> (see PROTOCOL "evaluate role side-effect matrix"):
 * <ul>
 *   <li>{@code role=input} — content review of a user message before it reaches
 *       the model. Feeds session risk (enforced hits only, once per trace);
 *       never creates challenges, counters or tool-call history.</li>
 *   <li>{@code role=output} — content review of a model answer, routed through
 *       {@link OutputReviewService}: zero side effects and the response carries
 *       {@code disposal_action} (+ {@code sanitized_content} for
 *       {@code redact}) which callers apply verbatim.</li>
 * </ul>
 *
 * <p>{@code role} is a closed enum: {@code input | output | tool_call}. Blank
 * (or absent) defaults to {@code tool_call} semantics; any other value —
 * including the legacy {@code user} — is rejected with HTTP 400
 * ({@code unsupported_role}) so a mis-integrated caller fails loudly instead
 * of silently running an undocumented role.
 */
@RestController
public class EvaluateHttpController {

    private static final java.util.Set<String> SUPPORTED_ROLES =
            java.util.Set.of("input", "output", "tool_call");

    private final EvaluateOrchestrator orchestrator;
    private final OutputReviewService outputReview;
    private final EvaluateRateLimiter rateLimiter;

    public EvaluateHttpController(
            EvaluateOrchestrator orchestrator,
            OutputReviewService outputReview,
            EvaluateRateLimiter rateLimiter) {
        this.orchestrator = orchestrator;
        this.outputReview = outputReview;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/v1/evaluate")
    public ResponseEntity<?> evaluate(
            @RequestBody EvaluateRequestDto request,
            HttpServletRequest http) {
        String role = request.role() == null
                ? "" : request.role().trim().toLowerCase(java.util.Locale.ROOT);
        if (!role.isBlank() && !SUPPORTED_ROLES.contains(role)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unsupported_role");
            body.put("role", request.role());
            body.put("supported_roles", List.of("input", "output", "tool_call"));
            return ResponseEntity.badRequest().body(body);
        }

        // Verified-subject handling (P0-min): claims take precedence over
        // self-reported fields; conflicts are rejected (subject_mismatch).
        io.virbius.engine.auth.TrustedIssuerRegistry.IssuedTokenClaims subject =
                io.virbius.engine.auth.EvaluateAuthFilter.subject(http);
        if (subject != null) {
            String sid = io.virbius.engine.auth.EvaluateAuthFilter.requiredSid(http);
            if (sid != null && request.sessionId() != null && !sid.equals(request.sessionId())) {
                return errorBody(HttpStatus.UNAUTHORIZED, "unauthorized", "sid_mismatch");
            }
            ResponseEntity<?> mismatch = enforceClaims(subject, request);
            if (mismatch != null) {
                return mismatch;
            }
            request = withVerifiedSubject(request, subject);
        }

        java.util.Optional<Duration> limited = rateLimiter.check(
                subject != null ? subject.agentAid() : request.sessionId(),
                clientIp(http), request.traceId(), request.streamChunk(), subject != null);
        if (limited.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "rate_limited");
            body.put("retry_after_seconds", limited.get().toSeconds());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(limited.get().toSeconds()))
                    .body(body);
        }
        boolean isOutputReview = "output".equals(role);
        EvaluateResponseDto response = isOutputReview
                ? outputReview.review(request)
                : orchestrator.evaluate(request);
        return ResponseEntity.ok(response);
    }

    /** P1.2: STI Taint detection endpoint for tool return values. */
    @PostMapping("/v1/evaluate/tool-result")
    public io.virbius.engine.eval.ToolResultResponseDto evaluateToolResult(
            @RequestBody io.virbius.engine.eval.ToolResultRequestDto request) {
        return orchestrator.evaluateToolResult(request);
    }

    /** P1.3: LLM-based injection detection endpoint for memory writes. */
    @PostMapping("/v1/memory/check")
    public io.virbius.engine.eval.MemoryCheckResponseDto checkMemory(
            @RequestBody io.virbius.engine.eval.MemoryCheckRequestDto request) {
        return orchestrator.checkMemory(request);
    }

    /**
     * Claims precedence + capability enforcement (P0-min).
     *
     * <ul>
     *   <li><b>subject_mismatch</b> — a self-reported tenant/app that conflicts
     *       with the token is rejected (agent tokens).</li>
     *   <li><b>Service-token exception</b> — {@code sub=service:*} tokens have
     *       no tenant of their own; cross-tenant operation is their purpose, so
     *       the request body's tenant is trusted (audited as service_token).</li>
     *   <li><b>capability_denied</b> — {@code role=tool_call} requires
     *       {@code tool_name ∈ scope.allowed_tools} (B2: input/output roles are
     *       exempt — chat_input/chat_completion are not MCP tools); the
     *       self-reported {@code license_risk_quota} is clamped to the token's
     *       scope.</li>
     * </ul>
     */
    private ResponseEntity<?> enforceClaims(
            io.virbius.engine.auth.TrustedIssuerRegistry.IssuedTokenClaims subject,
            EvaluateRequestDto request) {
        boolean serviceToken = subject.subject() != null && subject.subject().startsWith("service:");
        if (!serviceToken) {
            if (subject.tenantId() != null
                    && request.tenantId() != null && !request.tenantId().isBlank()
                    && !subject.tenantId().equals(request.tenantId())) {
                return errorBody(HttpStatus.FORBIDDEN, "subject_mismatch", "tenant_id");
            }
            if (subject.appId() != null
                    && request.vars() != null) {
                Object reportedApp = request.vars().get("app_id");
                if (reportedApp != null && !reportedApp.toString().isBlank()
                        && !subject.appId().equals(reportedApp.toString())) {
                    return errorBody(HttpStatus.FORBIDDEN, "subject_mismatch", "app_id");
                }
            }
        }
        // Capability check (B2): ONLY role=tool_call; input/output are exempt.
        if ("tool_call".equals(request.role() == null ? "" : request.role().trim().toLowerCase(java.util.Locale.ROOT))
                && subject.allowedTools() != null && !subject.allowedTools().isEmpty()
                && !subject.allowedTools().contains("*")
                && !subject.allowedTools().contains(request.toolName())) {
            return errorBody(HttpStatus.FORBIDDEN, "capability_denied", request.toolName());
        }
        return null;
    }

    /** Rebuild the request with token-derived fields (claims precedence).
     *  The verified-subject attribution rides the vars map under the
     *  {@code virbius.} namespace so AuditWriter can persist it without
     *  changing the DTO shape. */
    private EvaluateRequestDto withVerifiedSubject(
            EvaluateRequestDto req,
            io.virbius.engine.auth.TrustedIssuerRegistry.IssuedTokenClaims subject) {
        boolean serviceToken = subject.subject() != null && subject.subject().startsWith("service:");
        String tenant = serviceToken || subject.tenantId() == null ? req.tenantId() : subject.tenantId();
        int quota = subject.riskQuota() != null && req.riskQuota() > subject.riskQuota()
                ? subject.riskQuota() : req.riskQuota();
        java.util.Map<String, String> vars = new java.util.HashMap<>(
                req.vars() != null ? req.vars() : java.util.Map.of());
        if (subject.agentAid() != null) {
            vars.put("virbius.agent_aid", subject.agentAid());
        }
        if (subject.appId() != null) {
            vars.put("virbius.app_id", subject.appId());
        }
        vars.put("virbius.subject_source",
                serviceToken ? "service_token" : "token");
        vars.put("virbius.token_iss", subject.issuer());
        if (subject.jti() != null) {
            vars.put("virbius.token_jti", subject.jti());
        }
        vars.put("virbius.sid_binding",
                subject.sid() != null && !subject.sid().isBlank() ? "bound" : "none");
        return new EvaluateRequestDto(
                tenant, req.role(), req.sessionId(), req.content(), req.streamChunk(),
                req.priorSignals(), req.traceId(), req.userId(), req.deviceId(), vars,
                req.routeUri(), req.upstreamId(), req.consumerId(), req.apiKeyGroup(),
                req.toolName(), req.argsJson(), quota, req.attachments());
    }

    private static ResponseEntity<Map<String, Object>> errorBody(
            HttpStatus status, String error, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("reason", reason);
        return ResponseEntity.status(status).body(body);
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
