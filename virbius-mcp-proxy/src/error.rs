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
    MemoryWriteBlocked = -32012,
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
            Self::MemoryWriteBlocked => "memory_write_blocked",
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
            | Self::ChallengeRequired
            | Self::MemoryWriteBlocked => 403,
            Self::SchemaViolation => 400,
            Self::RateExceeded => 429,
        }
    }
}

/// Build a JSON-RPC 2.0 error response.
pub fn jsonrpc_error(
    code: VirbiusErrorCode,
    id: serde_json::Value,
    data: serde_json::Value,
) -> serde_json::Value {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_code_values() {
        assert_eq!(VirbiusErrorCode::LicenseInvalid as i32, -32001);
        assert_eq!(VirbiusErrorCode::LicenseRequired as i32, -32002);
        assert_eq!(VirbiusErrorCode::HighRiskNoLicense as i32, -32003);
        assert_eq!(VirbiusErrorCode::NotInAllowlist as i32, -32004);
        assert_eq!(VirbiusErrorCode::SchemaViolation as i32, -32005);
        assert_eq!(VirbiusErrorCode::EngineBlocked as i32, -32006);
        assert_eq!(VirbiusErrorCode::RateExceeded as i32, -32007);
        assert_eq!(VirbiusErrorCode::RiskThreshold as i32, -32008);
        assert_eq!(VirbiusErrorCode::OutputReviewBlocked as i32, -32009);
        assert_eq!(VirbiusErrorCode::FallbackBlocked as i32, -32010);
        assert_eq!(VirbiusErrorCode::ChallengeRequired as i32, -32011);
        assert_eq!(VirbiusErrorCode::MemoryWriteBlocked as i32, -32012);
    }

    #[test]
    fn test_error_code_messages() {
        assert_eq!(VirbiusErrorCode::LicenseInvalid.message(), "license_invalid");
        assert_eq!(VirbiusErrorCode::LicenseRequired.message(), "license_required");
        assert_eq!(VirbiusErrorCode::MemoryWriteBlocked.message(), "memory_write_blocked");
    }

    #[test]
    fn test_http_analog_mapping() {
        assert_eq!(VirbiusErrorCode::LicenseInvalid.http_analog(), 401);
        assert_eq!(VirbiusErrorCode::LicenseRequired.http_analog(), 401);
        assert_eq!(VirbiusErrorCode::HighRiskNoLicense.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::SchemaViolation.http_analog(), 400);
        assert_eq!(VirbiusErrorCode::RateExceeded.http_analog(), 429);
        assert_eq!(VirbiusErrorCode::EngineBlocked.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::RiskThreshold.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::FallbackBlocked.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::OutputReviewBlocked.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::ChallengeRequired.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::MemoryWriteBlocked.http_analog(), 403);
        assert_eq!(VirbiusErrorCode::NotInAllowlist.http_analog(), 403);
    }

    #[test]
    fn test_jsonrpc_error_structure() {
        let err = jsonrpc_error(
            VirbiusErrorCode::LicenseInvalid,
            serde_json::json!("req-1"),
            serde_json::json!({"tool": "read_file"}),
        );
        assert_eq!(err["jsonrpc"], "2.0");
        assert_eq!(err["id"], "req-1");
        assert_eq!(err["error"]["code"], -32001);
        assert_eq!(err["error"]["message"], "license_invalid");
        assert_eq!(err["error"]["data"]["tool"], "read_file");
    }

    #[test]
    fn test_jsonrpc_error_with_null_id() {
        let err = jsonrpc_error(
            VirbiusErrorCode::EngineBlocked,
            serde_json::Value::Null,
            serde_json::json!({}),
        );
        assert!(err["id"].is_null());
        assert_eq!(err["error"]["code"], -32006);
    }

    #[test]
    fn test_jsonrpc_error_simple_includes_http_analog() {
        let err = jsonrpc_error_simple(
            VirbiusErrorCode::RateExceeded,
            serde_json::json!(1),
            "http_get",
            "trace-abc",
            75,
            Some("too many requests"),
        );
        assert_eq!(err["error"]["data"]["http_analog"], 429);
        assert_eq!(err["error"]["data"]["tool_name"], "http_get");
        assert_eq!(err["error"]["data"]["trace_id"], "trace-abc");
        assert_eq!(err["error"]["data"]["session_risk_score"], 75);
        assert_eq!(err["error"]["data"]["reason"], "too many requests");
    }

    #[test]
    fn test_jsonrpc_error_simple_no_reason() {
        let err = jsonrpc_error_simple(
            VirbiusErrorCode::LicenseRequired,
            serde_json::json!(null),
            "tool_a",
            "trace-x",
            0,
            None,
        );
        assert!(err["error"]["data"].get("reason").is_none());
    }

    #[test]
    fn test_error_code_equality() {
        assert_eq!(
            VirbiusErrorCode::LicenseInvalid,
            VirbiusErrorCode::LicenseInvalid
        );
        assert_ne!(
            VirbiusErrorCode::LicenseInvalid,
            VirbiusErrorCode::LicenseRequired
        );
    }

    #[test]
    fn test_error_code_debug_format() {
        let s = format!("{:?}", VirbiusErrorCode::HighRiskNoLicense);
        assert_eq!(s, "HighRiskNoLicense");
    }
}
