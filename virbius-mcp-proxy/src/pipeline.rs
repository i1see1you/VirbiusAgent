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
use crate::config::{FailoverConfig, FastPathConfig, FallbackPolicy, HIGH_RISK_TOOLS};
use crate::error::{jsonrpc_error_simple, VirbiusErrorCode};
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
}

/// Engine evaluate response body.
#[derive(Debug, Deserialize)]
struct EvaluateResponse {
    effective_action: String,
    #[serde(default)]
    rule_id: Option<String>,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    risk_score_delta: i32,
    #[serde(default)]
    session_risk_score: u32,
    #[serde(default)]
    challenge_id: Option<String>,
    #[serde(default)]
    args_hash: Option<String>,
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
}

impl SecurityPipeline {
    pub fn new(
        license_pubkey_pem: String,
        engine_url: &str,
        fast_path: FastPathConfig,
        failover: FailoverConfig,
        fallback_policy: FallbackPolicy,
        audit: SharedAuditSink,
    ) -> Self {
        let engine = EngineClient::new(engine_url, failover.engine_timeout_ms);
        Self {
            license_pubkey_pem,
            engine,
            fast_path,
            failover,
            fallback_policy,
            audit,
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
                        self.audit_tool_call(session, tool_name, "block", None, Some(&reason)).await;
                        return PipelineResult::deny(
                            VirbiusErrorCode::NotInAllowlist,
                            &reason,
                        );
                    }

                    // 3. Fast path check
                    if self.is_fast_path(session, &pre, tool_name) {
                        self.audit_tool_call(session, tool_name, "allow", None, Some("fast_path")).await;
                        return PipelineResult::allow("fast_path");
                    }

                    // 4. Engine evaluate
                    return self.check_engine(session, tool_name, args, license.claims.risk_quota, &pre).await;
                }
                Err(e) => {
                    let reason = format!("{:?}", e);
                    self.audit_tool_call(session, tool_name, "block", None, Some(&reason)).await;
                    let code = match e {
                        LicenseError::Expired | LicenseError::Revoked(_) | LicenseError::InvalidSignature => {
                            VirbiusErrorCode::LicenseInvalid
                        }
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
        let req = EvaluateRequest {
            trace_id: &session.trace_id,
            session_id: &session.session_id,
            app_id: &session.app_id,
            tenant_id: &session.tenant_id,
            tool_name,
            args,
            args_json: serde_json::to_string(args).unwrap_or_default(),
            license_risk_quota: risk_quota,
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
                        reason: resp.reason.unwrap_or_else(|| "challenge_required".to_string()),
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
                self.audit_tool_call(
                    session,
                    tool_name,
                    "block",
                    None,
                    Some("license_required"),
                )
                .await;
                PipelineResult::deny(
                    VirbiusErrorCode::LicenseRequired,
                    "a valid License is required (default_deny policy)",
                )
            }
            FallbackPolicy::AuditOnly => {
                self.audit_tool_call(
                    session,
                    tool_name,
                    "allow",
                    None,
                    Some("audit_only"),
                )
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
        let url = self.engine.url.replace("/v1/evaluate", "/v1/challenge/verify");
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
        Ok(result.get("valid").and_then(|v| v.as_bool()).unwrap_or(false))
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
