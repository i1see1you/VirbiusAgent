/// Session management: maps session_id (String) to session state.
///
/// Session lifecycle is decoupled from TCP connections:
/// - Created on `initialize`
/// - Persists across TCP reconnects (within TTL)
/// - Cleaned up by background task when TTL expires
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use base64::Engine;
use dashmap::DashMap;
use tracing::debug;

/// Default session TTL: 30 minutes.
const DEFAULT_TTL_SECS: u64 = 1800;

/// Per-session state (security context, call counters, risk score).
#[derive(Debug, Clone)]
pub struct Session {
    pub session_id: String,
    pub app_id: String,
    pub tenant_id: String,
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    pub license_jwt: String,
    pub trace_id: String,
    pub tool_call_count: u32,
    /// Per-upstream initialization state: upstream_name → initialized.
    pub upstream_initialized: HashMap<String, bool>,
    /// Current session risk score (updated by engine responses).
    pub session_risk_score: u32,
    /// Tools allowed by the License (extracted from JWT payload).
    /// Empty means all tools allowed (or no License).
    pub allowed_tools: Vec<String>,
    /// Risk quota from the License JWT (0 = unset, engine falls back to 100).
    pub risk_quota: u32,
    /// Last active timestamp (for TTL cleanup).
    pub last_active: Instant,
    /// Creation timestamp.
    pub created_at: Instant,
    /// Monotonic step counter for trace chain (incremented per tool_call).
    pub step_seq: u32,
    /// Last step_id in the trace chain (for parent linking).
    pub last_step_id: Option<String>,
}

impl Session {
    /// Create a new session from `initialize` params `_meta`.
    pub fn from_meta(meta: &serde_json::Value) -> Self {
        let now = Instant::now();
        let session_id = meta
            .get("session_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let app_id = meta
            .get("app_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let tenant_id = meta
            .get("tenant_id")
            .and_then(|v| v.as_str())
            .unwrap_or("default")
            .to_string();
        let license_jwt = meta
            .get("license_jwt")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let user_id = meta.get("user_id").and_then(|v| v.as_str()).map(String::from);
        let device_id = meta
            .get("device_id")
            .and_then(|v| v.as_str())
            .map(String::from);

        let session_id = if session_id.is_empty() {
            uuid::Uuid::new_v4().to_string()
        } else {
            session_id
        };

        let (allowed_tools, risk_quota) = if license_jwt.is_empty() {
            (Vec::new(), 0)
        } else {
            (
                extract_allowed_tools_from_jwt(&license_jwt),
                extract_risk_quota_from_jwt(&license_jwt),
            )
        };

        Self {
            session_id,
            app_id,
            tenant_id,
            user_id,
            device_id,
            license_jwt,
            trace_id: uuid::Uuid::new_v4().to_string(),
            tool_call_count: 0,
            upstream_initialized: HashMap::new(),
            session_risk_score: 0,
            allowed_tools,
            risk_quota,
            last_active: now,
            created_at: now,
            step_seq: 0,
            last_step_id: None,
        }
    }

    /// Check if this session has a valid License JWT.
    pub fn has_license(&self) -> bool {
        !self.license_jwt.is_empty()
    }

    /// Increment the tool call count (for warmup detection).
    pub fn increment_calls(&mut self) {
        self.tool_call_count += 1;
    }

    /// Allocate the next step sequence number for trace chain.
    pub fn next_step_seq(&mut self) -> u32 {
        self.step_seq += 1;
        self.step_seq
    }

    /// Record the last step_id for parent linking in the trace chain.
    pub fn set_last_step_id(&mut self, step_id: String) {
        self.last_step_id = Some(step_id);
    }

    /// Update last_active to now.
    pub fn touch(&mut self) {
        self.last_active = Instant::now();
    }

    /// Check if a specific upstream has been initialized for this session.
    pub fn is_upstream_initialized(&self, name: &str) -> bool {
        self.upstream_initialized
            .get(name)
            .copied()
            .unwrap_or(false)
    }

    /// Mark an upstream as initialized for this session.
    pub fn mark_upstream_initialized(&mut self, name: &str) {
        self.upstream_initialized.insert(name.to_string(), true);
    }

    /// Apply a fallback License JWT (from config file) when the Agent did not
    /// pass `_meta.license_jwt` in the `initialize` request.
    ///
    /// Extracts `app_id`, `tenant_id`, and `allowed_tools` from the JWT claims
    /// (without signature verification — verification happens at `tools/call`
    /// time in the security pipeline). If the Agent already provided an
    /// `app_id` in `_meta`, it is kept; otherwise the `app_id` from the JWT
    /// claims is used (required for `License::verify` to succeed).
    pub fn apply_fallback_license(&mut self, jwt: &str) {
        self.license_jwt = jwt.to_string();
        self.allowed_tools = extract_allowed_tools_from_jwt(jwt);
        self.risk_quota = extract_risk_quota_from_jwt(jwt);

        if let Some(claims) = extract_claims_from_jwt(jwt) {
            // app_id must match between session and JWT for License::verify.
            // If Agent didn't provide app_id, use the one from JWT.
            if self.app_id.is_empty() {
                if let Some(app_id) = claims.get("app_id").and_then(|v| v.as_str()) {
                    self.app_id = app_id.to_string();
                }
            }
            // Similarly for tenant_id.
            if self.tenant_id.is_empty() || self.tenant_id == "default" {
                if let Some(tenant_id) = claims.get("tenant_id").and_then(|v| v.as_str()) {
                    self.tenant_id = tenant_id.to_string();
                }
            }
        }
    }
}

/// Decode the JWT payload (base64url) and extract claims as a JSON Value.
///
/// This does **not** verify the signature — it is used only for display-level
/// filtering in `tools/list` and fallback license injection. The actual
/// security enforcement happens at `tools/call` time via full License
/// verification in the security pipeline.
fn extract_claims_from_jwt(jwt: &str) -> Option<serde_json::Value> {
    let parts: Vec<&str> = jwt.split('.').collect();
    if parts.len() != 3 {
        return None;
    }

    // Try URL-safe no-pad first, then standard with manual padding
    let payload_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(parts[1])
        .or_else(|_| {
            let mut s = parts[1].to_string();
            match s.len() % 4 {
                2 => s.push_str("=="),
                3 => s.push('='),
                _ => {}
            }
            base64::engine::general_purpose::STANDARD.decode(&s)
        })
        .unwrap_or_default();

    if payload_bytes.is_empty() {
        return None;
    }

    serde_json::from_slice(&payload_bytes).ok()
}

/// Decode the JWT payload (base64url) and extract `allowed_tools`.
///
/// This does **not** verify the signature — it is used only for display-level
/// filtering in `tools/list`. The actual security enforcement happens at
/// `tools/call` time via full License verification in the security pipeline.
fn extract_allowed_tools_from_jwt(jwt: &str) -> Vec<String> {
    let claims = match extract_claims_from_jwt(jwt) {
        Some(v) => v,
        None => return Vec::new(),
    };

    claims
        .get("allowed_tools")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default()
}

/// Decode the JWT payload (base64url) and extract `risk_quota`.
///
/// This does **not** verify the signature — it is used only for populating
/// `Session.risk_quota` for risk scoring. The actual security enforcement
/// happens at `tools/call` time via full License verification in the security
/// pipeline. Returns 0 if the JWT is invalid or the field is absent.
fn extract_risk_quota_from_jwt(jwt: &str) -> u32 {
    let claims = match extract_claims_from_jwt(jwt) {
        Some(v) => v,
        None => return 0,
    };
    claims
        .get("risk_quota")
        .and_then(|v| v.as_u64())
        .unwrap_or(0) as u32
}

/// Concurrent session manager keyed by session_id (String).
///
/// Sessions are created on `initialize` and cleaned up when TTL expires.
/// This decouples session lifetime from TCP connection lifetime.
pub struct SessionManager {
    sessions: DashMap<String, Session>,
    ttl: Duration,
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            sessions: DashMap::new(),
            ttl: Duration::from_secs(DEFAULT_TTL_SECS),
        }
    }

    pub fn with_ttl(ttl: Duration) -> Self {
        Self {
            sessions: DashMap::new(),
            ttl,
        }
    }

    pub fn insert(&self, session_id: String, session: Session) {
        self.sessions.insert(session_id, session);
    }

    pub fn get(&self, session_id: &str) -> Option<Session> {
        self.sessions.get(session_id).map(|r| r.clone())
    }

    pub fn update(&self, session_id: String, session: Session) {
        self.sessions.insert(session_id, session);
    }

    pub fn remove(&self, session_id: &str) {
        self.sessions.remove(session_id);
    }

    /// Update last_active for the given session.
    pub fn touch(&self, session_id: &str) {
        if let Some(mut s) = self.sessions.get_mut(session_id) {
            s.touch();
        }
    }

    /// Remove all expired sessions and return their session_ids.
    ///
    /// Called by the background cleanup task. The returned IDs are used to
    /// also clean up corresponding upstream connections.
    pub fn cleanup_expired(&self) -> Vec<String> {
        let now = Instant::now();
        let mut expired = Vec::new();

        let to_remove: Vec<String> = self
            .sessions
            .iter()
            .filter(|entry| now.duration_since(entry.value().last_active) > self.ttl)
            .map(|entry| entry.key().clone())
            .collect();

        for sid in &to_remove {
            self.sessions.remove(sid);
            expired.push(sid.clone());
            debug!("session expired: {}", sid);
        }

        expired
    }

    /// Number of active sessions.
    pub fn len(&self) -> usize {
        self.sessions.len()
    }

    /// Check if there are no active sessions.
    pub fn is_empty(&self) -> bool {
        self.sessions.is_empty()
    }

    /// Check if a session exists and is not expired.
    pub fn is_valid(&self, session_id: &str) -> bool {
        if let Some(s) = self.sessions.get(session_id) {
            Instant::now().duration_since(s.last_active) <= self.ttl
        } else {
            false
        }
    }
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

pub type SharedSessionManager = Arc<SessionManager>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_session_from_meta() {
        let meta = serde_json::json!({
            "session_id": "test-session",
            "app_id": "app1",
            "license_jwt": "jwt-token"
        });
        let s = Session::from_meta(&meta);
        assert_eq!(s.session_id, "test-session");
        assert_eq!(s.app_id, "app1");
        assert!(s.has_license());
    }

    #[test]
    fn test_session_no_meta() {
        let s = Session::from_meta(&serde_json::Value::Null);
        assert!(!s.session_id.is_empty());
        assert!(!s.has_license());
    }

    #[test]
    fn test_session_manager_insert_get() {
        let mgr = SessionManager::new();
        let s = Session::from_meta(&serde_json::json!({"app_id": "a"}));
        let sid = s.session_id.clone();
        mgr.insert(sid.clone(), s);
        assert!(mgr.get(&sid).is_some());
        assert!(mgr.is_valid(&sid));
    }

    #[test]
    fn test_session_manager_cleanup_expired() {
        let mgr = SessionManager::with_ttl(Duration::from_millis(10));
        let s = Session::from_meta(&serde_json::json!({"app_id": "a"}));
        let sid = s.session_id.clone();
        mgr.insert(sid.clone(), s);

        // Not expired yet
        assert_eq!(mgr.cleanup_expired().len(), 0);

        // Wait for expiry
        std::thread::sleep(Duration::from_millis(20));

        // Now expired
        let expired = mgr.cleanup_expired();
        assert_eq!(expired.len(), 1);
        assert_eq!(expired[0], sid);
        assert!(mgr.get(&sid).is_none());
    }

    #[test]
    fn test_touch_extends_ttl() {
        let mgr = SessionManager::with_ttl(Duration::from_millis(30));
        let s = Session::from_meta(&serde_json::json!({"app_id": "a"}));
        let sid = s.session_id.clone();
        mgr.insert(sid.clone(), s);

        std::thread::sleep(Duration::from_millis(15));
        mgr.touch(&sid); // extend
        std::thread::sleep(Duration::from_millis(20)); // 35ms total since insert, 20ms since touch

        // Should still be valid (touched at 15ms, TTL is 30ms)
        assert_eq!(mgr.cleanup_expired().len(), 0);
    }

    #[test]
    fn test_extract_allowed_tools_from_jwt() {
        // Construct a fake JWT: header.payload.signature
        // Payload contains allowed_tools: ["read_file", "search"]
        let payload = serde_json::json!({
            "app_id": "test-agent",
            "allowed_tools": ["read_file", "search"],
            "risk_quota": 60,
            "exp": 9999999999i64,
        });
        let payload_bytes = serde_json::to_vec(&payload).unwrap();
        let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&payload_bytes);
        let jwt = format!("eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.{}.sig", payload_b64);

        let tools = extract_allowed_tools_from_jwt(&jwt);
        assert_eq!(tools, vec!["read_file", "search"]);
    }

    #[test]
    fn test_extract_allowed_tools_empty_jwt() {
        let tools = extract_allowed_tools_from_jwt("not-a-jwt");
        assert!(tools.is_empty());
    }

    #[test]
    fn test_extract_allowed_tools_no_tools_field() {
        let payload = serde_json::json!({"app_id": "test"});
        let payload_bytes = serde_json::to_vec(&payload).unwrap();
        let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&payload_bytes);
        let jwt = format!("header.{}.sig", payload_b64);

        let tools = extract_allowed_tools_from_jwt(&jwt);
        assert!(tools.is_empty());
    }

    #[test]
    fn test_session_allowed_tools_from_meta() {
        // Build a JWT with allowed_tools
        let payload = serde_json::json!({
            "app_id": "test-agent",
            "allowed_tools": ["read_file", "search", "execute_python"],
            "risk_quota": 60,
            "exp": 9999999999i64,
        });
        let payload_bytes = serde_json::to_vec(&payload).unwrap();
        let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&payload_bytes);
        let jwt = format!("eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.{}.sig", payload_b64);

        let meta = serde_json::json!({
            "app_id": "test-agent",
            "license_jwt": jwt,
        });
        let s = Session::from_meta(&meta);
        assert!(s.has_license());
        assert_eq!(
            s.allowed_tools,
            vec!["read_file", "search", "execute_python"]
        );
    }

    #[test]
    fn test_session_no_license_has_empty_allowed_tools() {
        let s = Session::from_meta(&serde_json::Value::Null);
        assert!(!s.has_license());
        assert!(s.allowed_tools.is_empty());
    }
}
