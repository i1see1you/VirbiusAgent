/// Tool pre-check: allowlist, args validation, fast path detection.
use crate::license::License;
use crate::manifest; // ToolPolicy used through manifest::tool_policy
use serde::Deserialize;

#[derive(Debug, Clone)]
pub struct PrecheckResult {
    pub allowed: bool,
    pub reason: Option<String>,
    pub fast_path: bool,
    pub sandbox_type: String,
    pub timeout_ms: u64,
}

#[derive(Debug, Deserialize)]
pub struct ToolCall {
    pub tool_name: String,
    #[serde(default)]
    pub args: serde_json::Value,
    #[serde(default)]
    pub session_id: String,
}

impl PrecheckResult {
    pub fn allow() -> Self {
        Self {
            allowed: true,
            reason: None,
            fast_path: false,
            sandbox_type: "none".into(),
            timeout_ms: 5000,
        }
    }

    pub fn deny(reason: impl Into<String>) -> Self {
        Self {
            allowed: false,
            reason: Some(reason.into()),
            fast_path: false,
            sandbox_type: "none".into(),
            timeout_ms: 0,
        }
    }
}

pub fn precheck(license: &License, tool_call: &ToolCall) -> PrecheckResult {
    if !license.is_tool_allowed(&tool_call.tool_name) {
        return PrecheckResult::deny(format!(
            "tool '{}' not in license allowlist",
            tool_call.tool_name
        ));
    }

    let policy = manifest::tool_policy(&tool_call.tool_name);
    let mut result = PrecheckResult::allow();

    if let Some(ref p) = policy {
        result.sandbox_type = p.sandbox_type.clone();
        result.timeout_ms = p.timeout_ms;

        if let Some(ref schema) = p.allowed_args_schema {
            if let Err(err) = validate_args(&tool_call.args, schema) {
                return PrecheckResult::deny(format!(
                    "args validation failed for '{}': {}",
                    tool_call.tool_name, err
                ));
            }
        }

        if p.fast_path {
            result.fast_path = true;
        }
    }

    result
}

fn validate_args(args: &serde_json::Value, schema: &serde_json::Value) -> Result<(), String> {
    if schema.is_null() || schema.as_object().map(|o| o.is_empty()).unwrap_or(true) {
        return Ok(());
    }

    // args must be a JSON object whenever a non-empty schema is present.
    let args_obj = args.as_object().ok_or("args must be a JSON object")?;

    // 1. required field presence.
    if let Some(required) = schema.get("required").and_then(|r| r.as_array()) {
        for field in required {
            let field_name = field.as_str().ok_or("required field name must be string")?;
            if !args_obj.contains_key(field_name) {
                return Err(format!("missing required field '{}'", field_name));
            }
        }
    }

    // 2. properties type check.  This must run independently of `required`;
    //    previously this block was nested inside the `required` branch and
    //    was silently skipped when a schema declared only `properties`.
    if let Some(properties) = schema.get("properties").and_then(|p| p.as_object()) {
        for (key, prop) in properties {
            if let Some(expected_type) = prop.get("type").and_then(|t| t.as_str()) {
                if let Some(value) = args_obj.get(key) {
                    if !type_matches(value, expected_type) {
                        return Err(format!("field '{}' expected type '{}'", key, expected_type));
                    }
                }
            }
        }
    }

    Ok(())
}

fn type_matches(value: &serde_json::Value, expected: &str) -> bool {
    match expected {
        "string" => value.is_string(),
        "number" => value.is_number(),
        "integer" => value.is_i64() || value.is_u64(),
        "boolean" => value.is_boolean(),
        "array" => value.is_array(),
        "object" => value.is_object(),
        "null" => value.is_null(),
        _ => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::license::LicenseClaims;

    fn make_license(allowed: Vec<&str>) -> License {
        License {
            claims: LicenseClaims {
                app_id: "test".into(),
                tenant_id: "test".into(),
                agent_name: String::new(),
                agent_aid: String::new(),
                allowed_tools: allowed.into_iter().map(String::from).collect(),
                risk_quota: 60,
                tool_rate_limit: 50,
                exp: 9999999999,
                iat: 1700000000,
            },
            raw_jwt: String::new(),
        }
    }

    #[test]
    fn test_precheck_deny_unlicensed_tool() {
        let license = make_license(vec!["read_file"]);
        let call = ToolCall {
            tool_name: "curl".into(),
            args: serde_json::json!({}),
            session_id: "sess1".into(),
        };
        let result = precheck(&license, &call);
        assert!(!result.allowed);
        assert!(result.reason.unwrap().contains("not in license"));
    }

    #[test]
    fn test_precheck_allow_licensed_tool() {
        let license = make_license(vec!["read_file"]);
        let call = ToolCall {
            tool_name: "read_file".into(),
            args: serde_json::json!({"path": "/tmp/test"}),
            session_id: "sess1".into(),
        };
        let result = precheck(&license, &call);
        assert!(result.allowed);
    }

    #[test]
    fn test_validate_required_fields() {
        let schema = serde_json::json!({
            "required": ["path"],
            "properties": { "path": { "type": "string" } }
        });
        let valid = serde_json::json!({"path": "/tmp/test"});
        assert!(validate_args(&valid, &schema).is_ok());

        let invalid = serde_json::json!({"other": "value"});
        assert!(validate_args(&invalid, &schema).is_err());
    }

    // Regression: a schema declaring only `properties` (no `required`) used to
    // skip type validation entirely because the properties block was nested
    // inside the `required` branch.
    #[test]
    fn test_validate_properties_only_schema_type_mismatch() {
        let schema = serde_json::json!({
            "properties": { "path": { "type": "string" } }
        });
        // Wrong type -> must be rejected (previously passed).
        let invalid = serde_json::json!({"path": 123});
        assert!(validate_args(&invalid, &schema).is_err());

        // Correct type -> still allowed.
        let valid = serde_json::json!({"path": "/tmp/test"});
        assert!(validate_args(&valid, &schema).is_ok());

        // Missing optional field -> allowed (presence is `required`'s job).
        let missing = serde_json::json!({"other": "value"});
        assert!(validate_args(&missing, &schema).is_ok());
    }

    // Regression: non-object args must be rejected whenever a non-empty schema
    // is present, regardless of whether `required` is declared.
    #[test]
    fn test_validate_non_object_args_rejected_without_required() {
        let schema = serde_json::json!({
            "properties": { "path": { "type": "string" } }
        });
        let array_args = serde_json::json!([1, 2, 3]);
        let err = validate_args(&array_args, &schema).unwrap_err();
        assert!(err.contains("args must be a JSON object"), "got: {err}");
    }

    // Sanity: required + properties still denies a missing required field
    // after the restructuring.
    #[test]
    fn test_validate_required_plus_properties_missing_field() {
        let schema = serde_json::json!({
            "required": ["path"],
            "properties": { "path": { "type": "string" } }
        });
        let missing = serde_json::json!({"other": "value"});
        assert!(validate_args(&missing, &schema).is_err());
    }
}
