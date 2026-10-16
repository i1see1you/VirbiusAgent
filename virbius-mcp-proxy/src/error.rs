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
    ArgTransformFailed = -32013,
    DelegatedEgressDenied = -32014,
    DelegatedLocalExecDisabled = -32015,
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
            Self::ArgTransformFailed => "arg_transform_failed",
            Self::DelegatedEgressDenied => "delegated_egress_denied",
            Self::DelegatedLocalExecDisabled => "local_exec_disabled",
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
            | Self::MemoryWriteBlocked
            | Self::ArgTransformFailed
            | Self::DelegatedEgressDenied
            | Self::DelegatedLocalExecDisabled => 403,
            Self::SchemaViolation => 400,
            Self::RateExceeded => 429,
        }
    }

    /// A human-readable sentence for the error `message` / `data.reason`.
    ///
    /// `detail` is the reason the path knows (a precheck sentence, the license
    /// `Display` text, or the engine's `reason_code`); it falls back to the
    /// machine code so the sentence is never empty. The machine code always also
    /// travels in `data.reason_code` — machines read that, humans/LLMs read this.
    /// The wording is deliberately plain and factual (no imperative "do not
    /// retry"): the fact of a policy refusal already implies futility, and we do
    /// not want product/instruction phrasing baked into the wire protocol.
    pub fn human_message(&self, detail: Option<&str>) -> String {
        let raw = detail
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| self.message());
        // Cap the detail: the message reaches the LLM, and some details (e.g. a
        // license Display carrying long app ids) can be very long. Truncate at
        // the source so `message` and `data.reason` stay identical.
        const DETAIL_MAX_CHARS: usize = 512;
        let d: String = if raw.chars().count() > DETAIL_MAX_CHARS {
            raw.chars().take(DETAIL_MAX_CHARS).collect()
        } else {
            raw.to_string()
        };
        let lead = match self {
            Self::LicenseInvalid | Self::LicenseRequired => "The agent credential was rejected",
            Self::RateExceeded | Self::RiskThreshold => "The tool call was not executed",
            Self::ChallengeRequired => "Approval is required before this tool call runs",
            Self::SchemaViolation | Self::ArgTransformFailed => {
                "The tool call could not be executed"
            }
            _ => "The tool call was blocked by policy",
        };
        format!("{lead}: {d}.")
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

/// Convenience: build a Virbius policy/operational error with the standard data
/// fields. `message` and `data.reason` carry a human sentence; `data.reason_code`
/// carries the stable machine code. `rule_id` is intentionally internal and not
/// surfaced here.
pub fn jsonrpc_error_simple(
    code: VirbiusErrorCode,
    id: serde_json::Value,
    tool_name: &str,
    trace_id: &str,
    session_risk: u32,
    reason: Option<&str>,
) -> serde_json::Value {
    let human = code.human_message(reason);
    let data = serde_json::json!({
        "tool_name": tool_name,
        "rule_id": null,
        "trace_id": trace_id,
        "session_risk_score": session_risk,
        "http_analog": code.http_analog(),
        "reason_code": code.message(),
        "reason": human,
    });
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": {
            "code": code as i32,
            "message": human,
            "data": data,
        }
    })
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
        assert_eq!(VirbiusErrorCode::ArgTransformFailed as i32, -32013);
    }

    #[test]
    fn test_error_code_messages() {
        assert_eq!(
            VirbiusErrorCode::LicenseInvalid.message(),
            "license_invalid"
        );
        assert_eq!(
            VirbiusErrorCode::LicenseRequired.message(),
            "license_required"
        );
        assert_eq!(
            VirbiusErrorCode::MemoryWriteBlocked.message(),
            "memory_write_blocked"
        );
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
        assert_eq!(VirbiusErrorCode::ArgTransformFailed.http_analog(), 403);
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
    fn test_jsonrpc_error_simple_carries_human_message_and_code() {
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
        // Machine code + human sentence, and the top-level message is the human one.
        assert_eq!(err["error"]["data"]["reason_code"], "rate_exceeded");
        assert_eq!(
            err["error"]["data"]["reason"],
            "The tool call was not executed: too many requests."
        );
        assert_eq!(
            err["error"]["message"],
            "The tool call was not executed: too many requests."
        );
        // rule_id stays internal.
        assert!(err["error"]["data"]["rule_id"].is_null());
    }

    #[test]
    fn test_jsonrpc_error_simple_no_reason_falls_back_to_code() {
        let err = jsonrpc_error_simple(
            VirbiusErrorCode::LicenseRequired,
            serde_json::json!(null),
            "tool_a",
            "trace-x",
            0,
            None,
        );
        assert_eq!(err["error"]["data"]["reason_code"], "license_required");
        assert_eq!(
            err["error"]["message"],
            "The agent credential was rejected: license_required."
        );
    }

    #[test]
    fn test_human_message_categories() {
        use VirbiusErrorCode::*;
        assert!(NotInAllowlist
            .human_message(Some("tool 'x' not in license allowlist"))
            .starts_with("The tool call was blocked by policy:"));
        assert!(LicenseInvalid
            .human_message(None)
            .starts_with("The agent credential was rejected:"));
        assert!(RateExceeded
            .human_message(None)
            .starts_with("The tool call was not executed:"));
        assert!(ChallengeRequired
            .human_message(None)
            .starts_with("Approval is required"));
        assert!(SchemaViolation
            .human_message(None)
            .starts_with("The tool call could not be executed:"));
        // blank detail falls back to the machine code, never an empty sentence
        assert_eq!(
            NotInAllowlist.human_message(Some("  ")),
            "The tool call was blocked by policy: not_in_allowlist."
        );
        // very long detail is capped at the source
        let long = "x".repeat(2000);
        let msg = NotInAllowlist.human_message(Some(&long));
        assert_eq!(
            msg.chars().count(),
            "The tool call was blocked by policy: ".len() + 512 + 1
        );
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
