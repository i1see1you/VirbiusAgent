/// Security pipeline: License -> precheck -> fast-path -> engine -> audit.
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use tracing::{debug, warn};

use virbius_core::license::{License, LicenseError};
use virbius_core::precheck::{self, PrecheckResult, ToolCall};

use crate::audit::{AuditEvent, SharedAuditSink};
use crate::config::{
    FailoverConfig, FallbackPolicy, FastPathConfig, OutputReviewConfig, HIGH_RISK_TOOLS,
};
use crate::error::VirbiusErrorCode;
use crate::session::Session;

/// Result of the security pipeline check.
#[derive(Debug, Clone)]
pub enum PipelineResult {
    /// Tool call is allowed — forward to upstream MCP Server.
    Allow {
        reason: String,
        rule_id: Option<String>,
        /// Updated session risk score from the engine (if evaluated).
        risk_score: Option<u32>,
    },
    /// Tool call is denied — return JSON-RPC error to Agent.
    Deny {
        code: VirbiusErrorCode,
        reason: String,
        rule_id: Option<String>,
        /// Updated session risk score from the engine (if evaluated).
        risk_score: Option<u32>,
    },
    /// Tool call requires a challenge — return JSON-RPC error with challenge_id.
    Challenge {
        challenge_id: String,
        args_hash: String,
        rule_id: Option<String>,
        reason: String,
        risk_score: u32,
    },
}

impl PipelineResult {
    pub fn allow(reason: &str) -> Self {
        Self::Allow {
            reason: reason.to_string(),
            rule_id: None,
            risk_score: None,
        }
    }

    pub fn deny(code: VirbiusErrorCode, reason: &str) -> Self {
        Self::Deny {
            code,
            reason: reason.to_string(),
            rule_id: None,
            risk_score: None,
        }
    }

    pub fn challenge(challenge_id: &str, args_hash: &str, reason: &str) -> Self {
        Self::Challenge {
            challenge_id: challenge_id.to_string(),
            args_hash: args_hash.to_string(),
            rule_id: None,
            reason: reason.to_string(),
            risk_score: 0,
        }
    }
}

/// Engine evaluate request body.
#[derive(Debug, Serialize)]
struct EvaluateRequest<'a> {
    trace_id: &'a str,
    session_id: &'a str,
    app_id: &'a str,
    tenant_id: &'a str,
    tool_name: &'a str,
    args: &'a Value,
    args_json: String,
    license_risk_quota: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    content: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    role: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    user_id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    device_id: Option<&'a str>,
}

/// Engine evaluate response body.
#[derive(Debug, Deserialize)]
pub(crate) struct EvaluateResponse {
    pub(crate) effective_action: String,
    #[serde(default)]
    pub(crate) rule_id: Option<String>,
    #[serde(default)]
    pub(crate) reason: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    pub(crate) risk_score_delta: i32,
    #[serde(default)]
    pub(crate) session_risk_score: u32,
    #[serde(default)]
    pub(crate) challenge_id: Option<String>,
    #[serde(default)]
    pub(crate) args_hash: Option<String>,
}

/// Engine memory check request body (LLM-based injection detection).
#[derive(Debug, Serialize)]
struct MemoryCheckRequest<'a> {
    trace_id: &'a str,
    session_id: &'a str,
    app_id: &'a str,
    tenant_id: &'a str,
    content: &'a str,
    tool_name: &'a str,
}

/// Engine memory check response body.
#[derive(Debug, Deserialize)]
pub(crate) struct MemoryCheckResponse {
    pub(crate) allowed: bool,
    #[serde(default)]
    pub(crate) block_reason: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    pub(crate) risk_score: Option<i32>,
    #[serde(default)]
    #[allow(dead_code)]
    pub(crate) model: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    pub(crate) metadata: Option<String>,
}

/// HTTP client for calling virbius-engine.
pub struct EngineClient {
    pub(crate) url: String,
    pub(crate) http: reqwest::Client,
    pub(crate) timeout: Duration,
}

impl EngineClient {
    pub fn new(url: &str, timeout_ms: u64) -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_millis(timeout_ms))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self {
            url: format!("{}/v1/evaluate", url.trim_end_matches('/')),
            http,
            timeout: Duration::from_millis(timeout_ms),
        }
    }

    async fn evaluate(&self, req: &EvaluateRequest<'_>) -> Result<EvaluateResponse, EngineError> {
        let resp = self
            .http
            .post(&self.url)
            .json(req)
            .timeout(self.timeout)
            .send()
            .await
            .map_err(EngineError::Http)?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(EngineError::Status(status, body));
        }

        resp.json::<EvaluateResponse>()
            .await
            .map_err(EngineError::Http)
    }
}

#[derive(Debug)]
pub enum EngineError {
    Http(reqwest::Error),
    Status(u16, String),
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Http(e) => write!(f, "engine http error: {e}"),
            Self::Status(code, body) => write!(f, "engine returned {code}: {body}"),
        }
    }
}

/// The security pipeline orchestrator.
pub struct SecurityPipeline {
    license_pubkey_pem: String,
    engine: EngineClient,
    fast_path: FastPathConfig,
    failover: FailoverConfig,
    fallback_policy: FallbackPolicy,
    audit: SharedAuditSink,
    output_review: OutputReviewConfig,
}

impl SecurityPipeline {
    pub fn new(
        license_pubkey_pem: String,
        engine_url: &str,
        fast_path: FastPathConfig,
        failover: FailoverConfig,
        fallback_policy: FallbackPolicy,
        audit: SharedAuditSink,
        output_review: OutputReviewConfig,
    ) -> Self {
        let engine = EngineClient::new(engine_url, failover.engine_timeout_ms);
        Self {
            license_pubkey_pem,
            engine,
            fast_path,
            failover,
            fallback_policy,
            audit,
            output_review,
        }
    }

    /// Run the full security pipeline for a `tools/call` request.
    pub async fn check_tool_call(
        &self,
        session: &Session,
        tool_name: &str,
        args: &Value,
    ) -> PipelineResult {
        // 1. License verification
        if session.has_license() {
            match License::verify(
                &session.license_jwt,
                &self.license_pubkey_pem,
                &session.app_id,
            ) {
                Ok(license) => {
                    // 2. Edge precheck (allowlist + JSON Schema)
                    let call = ToolCall {
                        tool_name: tool_name.to_string(),
                        args: args.clone(),
                        session_id: session.session_id.clone(),
                    };
                    let pre = precheck::precheck(&license, &call);
                    if !pre.allowed {
                        let reason = pre.reason.unwrap_or_default();
                        self.audit_tool_call(session, tool_name, "block", None, Some(&reason))
                            .await;
                        return PipelineResult::deny(VirbiusErrorCode::NotInAllowlist, &reason);
                    }

                    // 3. Fast path check
                    if self.is_fast_path(session, &pre, tool_name) {
                        self.audit_tool_call(session, tool_name, "allow", None, Some("fast_path"))
                            .await;
                        return PipelineResult::allow("fast_path");
                    }

                    // 4. Engine evaluate
                    return self
                        .check_engine(session, tool_name, args, license.claims.risk_quota, &pre)
                        .await;
                }
                Err(e) => {
                    let reason = format!("{:?}", e);
                    self.audit_tool_call(session, tool_name, "block", None, Some(&reason))
                        .await;
                    let code = match e {
                        LicenseError::Expired
                        | LicenseError::Revoked(_)
                        | LicenseError::InvalidSignature => VirbiusErrorCode::LicenseInvalid,
                        _ => VirbiusErrorCode::LicenseInvalid,
                    };
                    return PipelineResult::deny(code, &reason);
                }
            }
        }

        // No License — apply fallback policy
        self.apply_fallback(session, tool_name, args).await
    }

    /// Check with the engine (cloud layer terminal decision).
    async fn check_engine(
        &self,
        session: &Session,
        tool_name: &str,
        args: &Value,
        risk_quota: u32,
        pre: &PrecheckResult,
    ) -> PipelineResult {
        // Serialize args once; reuse as `content` so the Engine's
        // PromptInjectionDetector / TrustViolationDetector / MatchContext
        // actually receive the tool-call text (previously `content: None`
        // caused both detectors to no-op).  role="tool_call" distinguishes
        // the input path from the output-review path (role="output").
        let args_json = serde_json::to_string(args).unwrap_or_default();
        let req = EvaluateRequest {
            trace_id: &session.trace_id,
            session_id: &session.session_id,
            app_id: &session.app_id,
            tenant_id: &session.tenant_id,
            tool_name,
            args,
            args_json: args_json.clone(),
            license_risk_quota: risk_quota,
            content: Some(&args_json),
            role: Some("tool_call"),
            user_id: session.user_id.as_deref(),
            device_id: session.device_id.as_deref(),
        };

        match self.engine.evaluate(&req).await {
            Ok(resp) => {
                if resp.effective_action == "block" {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "block",
                        resp.rule_id.as_deref(),
                        resp.reason.as_deref(),
                    )
                    .await;
                    return PipelineResult::Deny {
                        code: VirbiusErrorCode::EngineBlocked,
                        reason: resp.reason.unwrap_or_else(|| "engine_blocked".to_string()),
                        rule_id: resp.rule_id,
                        risk_score: Some(resp.session_risk_score),
                    };
                }

                if resp.effective_action == "challenge" {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "challenge",
                        resp.rule_id.as_deref(),
                        resp.reason.as_deref(),
                    )
                    .await;
                    return PipelineResult::Challenge {
                        challenge_id: resp.challenge_id.unwrap_or_default(),
                        args_hash: resp.args_hash.unwrap_or_default(),
                        rule_id: resp.rule_id.clone(),
                        reason: resp
                            .reason
                            .unwrap_or_else(|| "challenge_required".to_string()),
                        risk_score: resp.session_risk_score,
                    };
                }

                // Check risk threshold
                if resp.session_risk_score >= risk_quota {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "block",
                        resp.rule_id.as_deref(),
                        Some("risk_threshold_exceeded"),
                    )
                    .await;
                    return PipelineResult::Deny {
                        code: VirbiusErrorCode::RiskThreshold,
                        reason: "session risk score exceeded quota".to_string(),
                        rule_id: None,
                        risk_score: Some(resp.session_risk_score),
                    };
                }

                self.audit_tool_call(
                    session,
                    tool_name,
                    "allow",
                    resp.rule_id.as_deref(),
                    Some("engine:allow"),
                )
                .await;
                PipelineResult::Allow {
                    reason: "engine".to_string(),
                    rule_id: resp.rule_id,
                    risk_score: Some(resp.session_risk_score),
                }
            }
            Err(e) => {
                warn!("engine evaluate failed: {e}");
                // Failover logic
                if pre.sandbox_type == "none" && self.failover.low_risk_fail_open {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "allow",
                        None,
                        Some("fail_open:engine_unavailable"),
                    )
                    .await;
                    PipelineResult::allow("fail_open")
                } else if self.failover.high_risk_fail_closed {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "block",
                        None,
                        Some("fail_closed:engine_unavailable"),
                    )
                    .await;
                    PipelineResult::deny(
                        VirbiusErrorCode::EngineBlocked,
                        "engine unavailable and tool is high-risk (fail-closed)",
                    )
                } else {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "allow",
                        None,
                        Some("fail_open:engine_unavailable"),
                    )
                    .await;
                    PipelineResult::allow("fail_open")
                }
            }
        }
    }

    /// Apply fallback policy when no License is provided.
    async fn apply_fallback(
        &self,
        session: &Session,
        tool_name: &str,
        _args: &Value,
    ) -> PipelineResult {
        match self.fallback_policy {
            FallbackPolicy::MinimumPrivilege => {
                if HIGH_RISK_TOOLS.contains(&tool_name) {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "block",
                        None,
                        Some("high_risk_without_license"),
                    )
                    .await;
                    PipelineResult::deny(
                        VirbiusErrorCode::HighRiskNoLicense,
                        "high-risk tool requires a valid License",
                    )
                } else {
                    self.audit_tool_call(
                        session,
                        tool_name,
                        "allow",
                        None,
                        Some("fallback:minimum_privilege"),
                    )
                    .await;
                    PipelineResult::allow("fallback:minimum_privilege")
                }
            }
            FallbackPolicy::DefaultDeny => {
                self.audit_tool_call(session, tool_name, "block", None, Some("license_required"))
                    .await;
                PipelineResult::deny(
                    VirbiusErrorCode::LicenseRequired,
                    "a valid License is required (default_deny policy)",
                )
            }
            FallbackPolicy::AuditOnly => {
                self.audit_tool_call(session, tool_name, "allow", None, Some("audit_only"))
                    .await;
                PipelineResult::allow("audit_only")
            }
        }
    }

    /// Check if a challenge token is present in the request headers/meta.
    ///
    /// The token is passed as `X-Virbius-Challenge-Token` HTTP header or
    /// `_meta.challenge_token` in the JSON-RPC params.
    pub fn extract_challenge_token(meta: &Value) -> Option<String> {
        meta.get("challenge_token")
            .and_then(|v| v.as_str())
            .map(String::from)
    }

    /// Check a memory write with the Engine (LLM-based injection detection).
    ///
    /// Called by the router after local Memory Interceptor checks pass.
    pub(crate) async fn check_memory(
        &self,
        session: &Session,
        tool_name: &str,
        content: &str,
    ) -> Result<MemoryCheckResponse, EngineError> {
        let req = MemoryCheckRequest {
            trace_id: &session.trace_id,
            session_id: &session.session_id,
            app_id: &session.app_id,
            tenant_id: &session.tenant_id,
            content,
            tool_name,
        };
        let url = self.engine.url.replace("/v1/evaluate", "/v1/memory/check");
        let resp = self
            .engine
            .http
            .post(&url)
            .json(&req)
            .timeout(self.engine.timeout)
            .send()
            .await
            .map_err(EngineError::Http)?;

        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(EngineError::Status(status, body));
        }

        resp.json::<MemoryCheckResponse>()
            .await
            .map_err(EngineError::Http)
    }

    /// Verify a challenge token with the Engine.
    pub async fn verify_challenge_token(
        &self,
        token: &str,
        tool_name: &str,
        args: &Value,
        session: &Session,
    ) -> Result<bool, EngineError> {
        let args_json = serde_json::to_string(args).unwrap_or_default();
        let args_hash = sha256_hex(&format!("{}:{}", tool_name, args_json));
        let verify_req = serde_json::json!({
            "token": token,
            "tool_name": tool_name,
            "args_hash": args_hash,
            "session_id": session.session_id,
        });
        let url = self
            .engine
            .url
            .replace("/v1/evaluate", "/v1/challenge/verify");
        let resp = self
            .engine
            .http
            .post(&url)
            .json(&verify_req)
            .timeout(self.engine.timeout)
            .send()
            .await
            .map_err(EngineError::Http)?;

        if !resp.status().is_success() {
            return Ok(false);
        }

        let result: serde_json::Value = resp.json().await.map_err(EngineError::Http)?;
        Ok(result
            .get("valid")
            .and_then(|v| v.as_bool())
            .unwrap_or(false))
    }

    /// Determine if this call qualifies for the fast path.
    fn is_fast_path(&self, session: &Session, pre: &PrecheckResult, tool_name: &str) -> bool {
        if !self.fast_path.enabled {
            return false;
        }
        // Cold start: first N calls always go through full pipeline
        if session.tool_call_count < self.fast_path.warmup_calls {
            return false;
        }
        // Fast path only for low-risk tools with sandbox_type=none
        if !pre.fast_path || pre.sandbox_type != "none" {
            return false;
        }
        // Check session risk
        if session.session_risk_score >= self.fast_path.risk_threshold {
            return false;
        }
        debug!(
            "fast_path hit: tool={}, session={}",
            tool_name, session.session_id
        );
        true
    }

    /// Emit an audit event for a tool call decision.
    async fn audit_tool_call(
        &self,
        session: &Session,
        tool_name: &str,
        action: &str,
        rule_id: Option<&str>,
        reason: Option<&str>,
    ) {
        let event = AuditEvent::tool_call(session, tool_name, action, rule_id, reason);
        self.audit.report(event).await;
    }

    /// Check if output review should be triggered for the given text and risk score.
    ///
    /// Review is triggered when either:
    /// - Text length >= `min_text_length` (default 512 chars), or
    /// - Session risk score >= `min_risk_score` (default 50)
    pub fn should_review_output(&self, text: &str, session_risk_score: u32) -> bool {
        if !self.output_review.enabled {
            return false;
        }
        text.len() >= self.output_review.min_text_length
            || session_risk_score >= self.output_review.min_risk_score
    }

    /// Review tool output content via the Engine (reuses `POST /v1/evaluate`).
    ///
    /// Sends the tool result text as `content` with `role = "output"`,
    /// allowing the Engine's existing prompt/groovy rule pipeline to
    /// perform LLM content safety classification (qwen3guard) and
    /// deterministic pattern matching.
    ///
    /// Returns the engine's evaluate response, or an error on failure.
    pub(crate) async fn review_output(
        &self,
        session: &Session,
        tool_name: &str,
        content: &str,
    ) -> Result<EvaluateResponse, EngineError> {
        let req = EvaluateRequest {
            trace_id: &session.trace_id,
            session_id: &session.session_id,
            app_id: &session.app_id,
            tenant_id: &session.tenant_id,
            tool_name,
            args: &serde_json::Value::Null,
            args_json: String::new(),
            license_risk_quota: session.risk_quota,
            content: Some(content),
            role: Some("output"),
            user_id: session.user_id.as_deref(),
            device_id: session.device_id.as_deref(),
        };
        self.engine.evaluate(&req).await
    }

    /// Returns the output review configuration.
    pub fn output_review_config(&self) -> &OutputReviewConfig {
        &self.output_review
    }
}

pub type SharedPipeline = Arc<SecurityPipeline>;

/// Compute SHA-256 hex digest of the input string.
fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let result = hasher.finalize();
    format!("sha256:{}", hex::encode(&result))
}

/// Minimal hex encoding (avoids adding `hex` crate dependency).
mod hex {
    pub fn encode(bytes: &[u8]) -> String {
        let mut s = String::with_capacity(bytes.len() * 2);
        for b in bytes {
            s.push_str(&format!("{:02x}", b));
        }
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audit::{AuditBackend, AuditSink};
    use crate::config::FailoverConfig;
    use std::sync::Arc;

    fn make_session() -> Session {
        let meta = serde_json::json!({
            "session_id": "test-session",
            "app_id": "test-app",
            "tenant_id": "test-tenant",
        });
        Session::from_meta(&meta)
    }

    fn make_pipeline(fallback_policy: FallbackPolicy) -> SecurityPipeline {
        let audit = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0));
        SecurityPipeline::new(
            "".to_string(),
            "http://localhost:0",
            FastPathConfig {
                enabled: true,
                warmup_calls: 0,
                risk_threshold: 30,
            },
            FailoverConfig {
                high_risk_fail_closed: true,
                low_risk_fail_open: true,
                engine_timeout_ms: 1000,
            },
            fallback_policy,
            audit,
            OutputReviewConfig {
                enabled: true,
                min_text_length: 512,
                min_risk_score: 50,
                fail_open: true,
            },
        )
    }

    #[test]
    fn test_pipeline_result_allow() {
        let r = PipelineResult::allow("ok");
        match r {
            PipelineResult::Allow {
                reason,
                rule_id,
                risk_score,
            } => {
                assert_eq!(reason, "ok");
                assert!(rule_id.is_none());
                assert!(risk_score.is_none());
            }
            _ => panic!("expected Allow"),
        }
    }

    #[test]
    fn test_pipeline_result_deny() {
        let r = PipelineResult::deny(VirbiusErrorCode::EngineBlocked, "blocked");
        match r {
            PipelineResult::Deny { code, reason, .. } => {
                assert_eq!(code, VirbiusErrorCode::EngineBlocked);
                assert_eq!(reason, "blocked");
            }
            _ => panic!("expected Deny"),
        }
    }

    #[test]
    fn test_pipeline_result_challenge() {
        let r = PipelineResult::challenge("ch-1", "abc123", "need verification");
        match r {
            PipelineResult::Challenge {
                challenge_id,
                args_hash,
                reason,
                ..
            } => {
                assert_eq!(challenge_id, "ch-1");
                assert_eq!(args_hash, "abc123");
                assert_eq!(reason, "need verification");
            }
            _ => panic!("expected Challenge"),
        }
    }

    #[test]
    fn test_sha256_hex_format() {
        let hash = sha256_hex("hello");
        assert!(hash.starts_with("sha256:"));
        // SHA-256 hex is 64 chars, plus "sha256:" prefix = 71
        assert_eq!(hash.len(), 71);
    }

    #[test]
    fn test_sha256_hex_deterministic() {
        assert_eq!(sha256_hex("test"), sha256_hex("test"));
        assert_ne!(sha256_hex("test"), sha256_hex("different"));
    }

    #[test]
    fn test_hex_encode() {
        assert_eq!(hex::encode(b"\x00\x01\xff"), "0001ff");
        assert_eq!(hex::encode(b""), "");
        assert_eq!(hex::encode(b"hello"), "68656c6c6f");
    }

    #[test]
    fn test_extract_challenge_token_present() {
        let meta = serde_json::json!({"challenge_token": "tok-abc"});
        assert_eq!(
            SecurityPipeline::extract_challenge_token(&meta),
            Some("tok-abc".to_string())
        );
    }

    #[test]
    fn test_extract_challenge_token_missing() {
        let meta = serde_json::json!({});
        assert_eq!(SecurityPipeline::extract_challenge_token(&meta), None);
    }

    #[test]
    fn test_extract_challenge_token_wrong_type() {
        let meta = serde_json::json!({"challenge_token": 42});
        assert_eq!(SecurityPipeline::extract_challenge_token(&meta), None);
    }

    #[test]
    fn test_should_review_output_disabled() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.output_review.enabled = false;
        assert!(!pipeline.should_review_output("x".repeat(1000).as_str(), 100));
    }

    #[test]
    fn test_should_review_output_by_text_length() {
        let pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        // Text shorter than min_text_length (512) and low risk score → no review
        assert!(!pipeline.should_review_output("short", 0));
        // Text length >= 512 → review
        let long = "x".repeat(512);
        assert!(pipeline.should_review_output(&long, 0));
    }

    #[test]
    fn test_should_review_output_by_risk_score() {
        let pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        // Low risk, short text → review
        assert!(!pipeline.should_review_output("short", 0));
        // High risk (>= 50) → review regardless of length
        assert!(pipeline.should_review_output("short", 50));
        assert!(pipeline.should_review_output("short", 100));
    }

    #[test]
    fn test_is_fast_path_disabled() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.fast_path.enabled = false;
        let session = make_session();
        let pre = PrecheckResult {
            allowed: true,
            reason: None,
            fast_path: true,
            sandbox_type: "none".to_string(),
            timeout_ms: 5000,
        };
        assert!(!pipeline.is_fast_path(&session, &pre, "read_file"));
    }

    #[test]
    fn test_is_fast_path_warmup() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.fast_path.warmup_calls = 5;
        let mut session = make_session();
        session.tool_call_count = 3;
        let pre = PrecheckResult {
            allowed: true,
            reason: None,
            fast_path: true,
            sandbox_type: "none".to_string(),
            timeout_ms: 5000,
        };
        assert!(!pipeline.is_fast_path(&session, &pre, "read_file"));
    }

    #[test]
    fn test_is_fast_path_high_risk() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.fast_path.warmup_calls = 0;
        let mut session = make_session();
        session.tool_call_count = 10;
        session.session_risk_score = 50;
        let pre = PrecheckResult {
            allowed: true,
            reason: None,
            fast_path: true,
            sandbox_type: "none".to_string(),
            timeout_ms: 5000,
        };
        assert!(!pipeline.is_fast_path(&session, &pre, "read_file"));
    }

    #[test]
    fn test_is_fast_path_sandbox_not_none() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.fast_path.warmup_calls = 0;
        let session = make_session();
        let pre = PrecheckResult {
            allowed: true,
            reason: None,
            fast_path: true,
            sandbox_type: "docker".to_string(),
            timeout_ms: 5000,
        };
        assert!(!pipeline.is_fast_path(&session, &pre, "read_file"));
    }

    #[test]
    fn test_is_fast_path_fast_path_false_in_precheck() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.fast_path.warmup_calls = 0;
        let session = make_session();
        let pre = PrecheckResult {
            allowed: true,
            reason: None,
            fast_path: false,
            sandbox_type: "none".to_string(),
            timeout_ms: 5000,
        };
        assert!(!pipeline.is_fast_path(&session, &pre, "read_file"));
    }

    #[test]
    fn test_is_fast_path_hit() {
        let mut pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        pipeline.fast_path.warmup_calls = 0;
        let mut session = make_session();
        session.tool_call_count = 10;
        session.session_risk_score = 0;
        let pre = PrecheckResult {
            allowed: true,
            reason: None,
            fast_path: true,
            sandbox_type: "none".to_string(),
            timeout_ms: 5000,
        };
        assert!(pipeline.is_fast_path(&session, &pre, "read_file"));
    }

    #[tokio::test]
    async fn test_apply_fallback_minimum_privilege_low_risk() {
        let pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        let session = make_session();
        let result = pipeline
            .apply_fallback(&session, "list_files", &serde_json::json!({}))
            .await;
        assert!(matches!(result, PipelineResult::Allow { .. }));
    }

    #[tokio::test]
    async fn test_apply_fallback_minimum_privilege_high_risk() {
        let pipeline = make_pipeline(FallbackPolicy::MinimumPrivilege);
        let session = make_session();
        let result = pipeline
            .apply_fallback(&session, "shell", &serde_json::json!({}))
            .await;
        assert!(matches!(result, PipelineResult::Deny { .. }));
        if let PipelineResult::Deny { code, .. } = result {
            assert_eq!(code, VirbiusErrorCode::HighRiskNoLicense);
        }
    }

    #[tokio::test]
    async fn test_apply_fallback_default_deny() {
        let pipeline = make_pipeline(FallbackPolicy::DefaultDeny);
        let session = make_session();
        let result = pipeline
            .apply_fallback(&session, "list_files", &serde_json::json!({}))
            .await;
        assert!(matches!(result, PipelineResult::Deny { .. }));
        if let PipelineResult::Deny { code, .. } = result {
            assert_eq!(code, VirbiusErrorCode::LicenseRequired);
        }
    }

    #[tokio::test]
    async fn test_apply_fallback_audit_only() {
        let pipeline = make_pipeline(FallbackPolicy::AuditOnly);
        let session = make_session();
        let result = pipeline
            .apply_fallback(&session, "shell", &serde_json::json!({}))
            .await;
        assert!(matches!(result, PipelineResult::Allow { .. }));
    }
}
