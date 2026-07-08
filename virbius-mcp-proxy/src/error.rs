/// VirbiusAgent JSON-RPC error codes (-32000 ~ -32099, JSON-RPC 2.0 reserved range).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum VirbiusErrorCode {
    LicenseInvalid = -32001,
    LicenseRequired = -32002,
    HighRiskNoLicense = -32003,
    NotInAllowlist = -32004,
    SchemaViolation = -32005,
    EngineBlocked = -32006,
    RateExceeded = -32007,
    RiskThreshold = -32008,
    OutputReviewBlocked = -32009,
    FallbackBlocked = -32010,
    ChallengeRequired = -32011,
}

impl VirbiusErrorCode {
    pub fn message(&self) -> &'static str {
        match self {
            Self::LicenseInvalid => "license_invalid",
            Self::LicenseRequired => "license_required",
            Self::HighRiskNoLicense => "high_risk_without_license",
            Self::NotInAllowlist => "not_in_allowlist",
            Self::SchemaViolation => "schema_violation",
            Self::EngineBlocked => "engine_blocked",
            Self::RateExceeded => "rate_exceeded",
            Self::RiskThreshold => "risk_threshold",
            Self::OutputReviewBlocked => "output_review_blocked",
            Self::FallbackBlocked => "fallback_blocked",
            Self::ChallengeRequired => "challenge_required",
        }
    }

    pub fn http_analog(&self) -> u16 {
        match self {
            Self::LicenseInvalid | Self::LicenseRequired => 401,
            Self::HighRiskNoLicense
            | Self::NotInAllowlist
            | Self::EngineBlocked
            | Self::RiskThreshold
            | Self::FallbackBlocked
            | Self::OutputReviewBlocked
            | Self::ChallengeRequired => 403,
            Self::SchemaViolation => 400,
            Self::RateExceeded => 429,
        }
    }
}

/// Build a JSON-RPC 2.0 error response.
pub fn jsonrpc_error(code: VirbiusErrorCode, id: serde_json::Value, data: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": {
            "code": code as i32,
            "message": code.message(),
            "data": data
        }
    })
}

/// Convenience: build error with standard data fields.
pub fn jsonrpc_error_simple(
    code: VirbiusErrorCode,
    id: serde_json::Value,
    tool_name: &str,
    trace_id: &str,
    session_risk: u32,
    reason: Option<&str>,
) -> serde_json::Value {
    let mut data = serde_json::json!({
        "tool_name": tool_name,
        "rule_id": null,
        "trace_id": trace_id,
        "session_risk_score": session_risk,
        "http_analog": code.http_analog(),
    });
    if let Some(r) = reason {
        data["reason"] = serde_json::Value::String(r.to_string());
    }
    jsonrpc_error(code, id, data)
}
