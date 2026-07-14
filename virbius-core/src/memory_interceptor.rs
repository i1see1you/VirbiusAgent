//! Memory Interceptor — intercepts Agent memory write operations.
//!
//! Provides PII desensitization, size limit enforcement, and credential
//! pattern detection for content being written to Agent memory (long-term
//! memory, vector store, etc.).
//!
//! LLM-based injection detection is delegated to the Engine via HTTP.
//! This module only performs local (synchronous, <0.5ms) checks.

use std::time::Duration;

use regex::Regex;

use crate::dlp;
use crate::manifest;

/// Configuration for the Memory Interceptor, loaded from the edge manifest.
#[derive(Debug, Clone)]
pub struct MemoryPolicies {
    pub enabled: bool,
    pub desensitize_on_write: bool,
    pub detect_injection_on_write: bool,
    pub max_entry_size: usize,
    /// Minimum content length to trigger LLM injection detection.
    pub min_llm_check_length: usize,
    pub credential_patterns: Vec<CredentialPattern>,
}

/// A compiled credential detection pattern.
#[derive(Debug, Clone)]
pub struct CredentialPattern {
    pub name: String,
    pub regex: Regex,
}

impl Default for MemoryPolicies {
    fn default() -> Self {
        let patterns = default_credential_patterns();
        Self {
            enabled: false,
            desensitize_on_write: true,
            detect_injection_on_write: true,
            max_entry_size: 4096,
            min_llm_check_length: 128,
            credential_patterns: patterns,
        }
    }
}

fn default_credential_patterns() -> Vec<CredentialPattern> {
    vec![
        CredentialPattern {
            name: "api_key".into(),
            regex: Regex::new(r#"(?i)(?:api[_-]?key|apikey)\s*[:=]\s*['\"]?[a-zA-Z0-9]{32,}"#).unwrap(),
        },
        CredentialPattern {
            name: "bearer_token".into(),
            regex: Regex::new(r#"(?i)bearer\s+[a-zA-Z0-9._\-]{20,}"#).unwrap(),
        },
        CredentialPattern {
            name: "aws_access_key".into(),
            regex: Regex::new(r"AKIA[0-9A-Z]{16}").unwrap(),
        },
        CredentialPattern {
            name: "private_key".into(),
            regex: Regex::new(r#"-----BEGIN\s+(RSA|EC|OPENSSH|DSA)?\s*PRIVATE\s+KEY-----"#).unwrap(),
        },
        CredentialPattern {
            name: "password_assignment".into(),
            regex: Regex::new(r#"(?i)(?:password|passwd|pwd)\s*[:=]\s*['\"]\S{6,}"#).unwrap(),
        },
    ]
}

/// Context for a memory write operation.
#[derive(Debug, Clone)]
pub struct MemoryContext {
    pub session_id: String,
    pub trace_id: String,
    pub tool_name: String,
}

/// Result of intercepting a memory write.
#[derive(Debug, Clone)]
pub struct MemoryWriteResult {
    /// Whether the write is allowed.
    pub allowed: bool,
    /// The (possibly desensitized) content to write. Only meaningful when `allowed` is true.
    pub sanitized_content: String,
    /// Reason for blocking (when `allowed` is false).
    pub block_reason: Option<String>,
    /// Whether PII was found and desensitized.
    pub pii_found: bool,
    /// Whether a credential pattern was detected.
    pub credential_detected: bool,
    /// Whether the caller should invoke the Engine for LLM-based injection detection.
    pub need_llm_check: bool,
}

impl MemoryWriteResult {
    fn allowed(content: String, pii_found: bool, need_llm: bool) -> Self {
        Self {
            allowed: true,
            sanitized_content: content,
            block_reason: None,
            pii_found,
            credential_detected: false,
            need_llm_check: need_llm,
        }
    }

    fn blocked(reason: &str) -> Self {
        Self {
            allowed: false,
            sanitized_content: String::new(),
            block_reason: Some(reason.to_string()),
            pii_found: false,
            credential_detected: false,
            need_llm_check: false,
        }
    }
}

/// The Memory Interceptor: performs local checks on memory write content.
///
/// LLM-based injection detection is NOT performed here — the caller should
/// check `need_llm_check` on the result and call the Engine if needed.
pub struct MemoryInterceptor {
    policies: MemoryPolicies,
}

impl MemoryInterceptor {
    /// Create a new interceptor with the given policies.
    pub fn new(policies: MemoryPolicies) -> Self {
        Self { policies }
    }

    /// Create an interceptor from the current edge manifest configuration.
    pub fn from_manifest() -> Self {
        let cfg = manifest::effective_sdk_config();
        let policies = MemoryPolicies {
            enabled: cfg.memory_interceptor_enabled,
            desensitize_on_write: cfg.memory_desensitize_on_write,
            detect_injection_on_write: cfg.memory_detect_injection_on_write,
            max_entry_size: if cfg.memory_max_entry_size > 0 {
                cfg.memory_max_entry_size
            } else {
                4096
            },
            min_llm_check_length: 128,
            credential_patterns: default_credential_patterns(),
        };
        Self::new(policies)
    }

    /// Check if the interceptor is enabled.
    pub fn is_enabled(&self) -> bool {
        self.policies.enabled
    }

    /// Check if a tool name looks like a memory write operation.
    pub fn is_memory_write_tool(&self, tool_name: &str) -> bool {
        let lower = tool_name.to_lowercase();
        // Prefix match for known memory write tool patterns
        let write_prefixes = [
            "memory_save",
            "memory_write",
            "memory_store",
            "mem_save",
            "mem_write",
            "vector_store",
            "vector_write",
            "vector_add",
            "embedding_store",
            "embedding_add",
            "recall_save",
            "long_term_memory",
            "save_memory",
            "store_memory",
        ];
        if write_prefixes.iter().any(|p| lower == *p || lower.starts_with(p)) {
            return true;
        }
        // Also check config-defined patterns
        let cfg = manifest::effective_sdk_config();
        cfg.memory_tool_patterns.iter().any(|p| lower.starts_with(p))
    }

    /// Intercept a memory write: size check → credential detection → PII desensitization.
    ///
    /// Returns a result indicating whether the write is allowed, the (possibly
    /// desensitized) content, and whether the caller should invoke the Engine
    /// for LLM-based injection detection.
    pub fn intercept_write(
        &self,
        content: &str,
        ctx: &MemoryContext,
    ) -> MemoryWriteResult {
        if !self.policies.enabled {
            // Disabled — pass through
            return MemoryWriteResult::allowed(content.to_string(), false, false);
        }

        if content.is_empty() {
            return MemoryWriteResult::allowed(content.to_string(), false, false);
        }

        // 1. Size check
        if content.len() > self.policies.max_entry_size {
            return MemoryWriteResult::blocked(&format!(
                "memory_entry_too_large: {} > {}",
                content.len(),
                self.policies.max_entry_size
            ));
        }

        // 2. Credential detection
        for pattern in &self.policies.credential_patterns {
            if pattern.regex.is_match(content) {
                return MemoryWriteResult::blocked(&format!(
                    "credential_detected: {}",
                    pattern.name
                ));
            }
        }

        // 3. PII desensitization (if enabled)
        let (sanitized, pii_found) = if self.policies.desensitize_on_write {
            let manifest = manifest::load();
            let ttl = Duration::from_millis(manifest.sdk_config.dlp_vault_ttl_ms);
            let result = dlp::desensitize_in(
                content,
                &ctx.trace_id,
                &manifest.dlp_rules,
                ttl,
                Some(&ctx.session_id),
            );
            (result.text, result.masked)
        } else {
            (content.to_string(), false)
        };

        // 4. Decide whether LLM injection check is needed
        let need_llm = self.policies.detect_injection_on_write
            && sanitized.len() >= self.policies.min_llm_check_length;

        MemoryWriteResult::allowed(sanitized, pii_found, need_llm)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_ctx() -> MemoryContext {
        MemoryContext {
            session_id: "test-session".into(),
            trace_id: "test-trace".into(),
            tool_name: "memory_save".into(),
        }
    }

    #[test]
    fn disabled_passes_through() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: false,
            ..MemoryPolicies::default()
        });
        let result = interceptor.intercept_write("hello", &make_ctx());
        assert!(result.allowed);
        assert_eq!(result.sanitized_content, "hello");
        assert!(!result.need_llm_check);
    }

    #[test]
    fn blocks_oversized_content() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            max_entry_size: 10,
            ..MemoryPolicies::default()
        });
        let result = interceptor.intercept_write("this is way too long", &make_ctx());
        assert!(!result.allowed);
        assert!(result.block_reason.as_ref().unwrap().contains("too_large"));
    }

    #[test]
    fn blocks_credential_api_key() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let content = "config: api_key = 'a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6'";
        let result = interceptor.intercept_write(content, &make_ctx());
        assert!(!result.allowed);
        assert!(result.block_reason.as_ref().unwrap().contains("credential_detected"));
    }

    #[test]
    fn blocks_bearer_token() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let content = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig";
        let result = interceptor.intercept_write(content, &make_ctx());
        assert!(!result.allowed);
        assert!(result.block_reason.as_ref().unwrap().contains("credential_detected"));
    }

    #[test]
    fn blocks_private_key() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let content = "key: -----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...";
        let result = interceptor.intercept_write(content, &make_ctx());
        assert!(!result.allowed);
    }

    #[test]
    fn blocks_aws_access_key() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let content = "deploying with key AKIAIOSFODNN7EXAMPLE";
        let result = interceptor.intercept_write(content, &make_ctx());
        assert!(!result.allowed);
    }

    #[test]
    fn blocks_password_assignment() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let content = "db config: password='supersecret123'";
        let result = interceptor.intercept_write(content, &make_ctx());
        assert!(!result.allowed);
    }

    #[test]
    fn allows_safe_short_content() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let result = interceptor.intercept_write("hello world", &make_ctx());
        assert!(result.allowed);
        assert!(!result.need_llm_check); // too short for LLM check
    }

    #[test]
    fn need_llm_check_for_long_content() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            min_llm_check_length: 50,
            ..MemoryPolicies::default()
        });
        let content = "This is a long enough piece of content that should trigger LLM check because it exceeds the minimum length threshold.";
        let result = interceptor.intercept_write(content, &make_ctx());
        assert!(result.allowed);
        assert!(result.need_llm_check);
    }

    #[test]
    fn is_memory_write_tool_matches() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies::default());
        assert!(interceptor.is_memory_write_tool("memory_save"));
        assert!(interceptor.is_memory_write_tool("memory_store"));
        assert!(interceptor.is_memory_write_tool("vector_store_write"));
        assert!(interceptor.is_memory_write_tool("embedding_add"));
        assert!(!interceptor.is_memory_write_tool("read_file"));
        assert!(!interceptor.is_memory_write_tool("http_get"));
    }

    #[test]
    fn empty_content_passes() {
        let interceptor = MemoryInterceptor::new(MemoryPolicies {
            enabled: true,
            ..MemoryPolicies::default()
        });
        let result = interceptor.intercept_write("", &make_ctx());
        assert!(result.allowed);
    }
}
