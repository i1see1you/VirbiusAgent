/// Session management: maps connection IDs to session state.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

use dashmap::DashMap;
use serde::{Deserialize, Serialize};

/// Monotonic connection ID for internal tracking.
static NEXT_CONN_ID: AtomicU32 = AtomicU32::new(1);

pub fn next_connection_id() -> u64 {
    NEXT_CONN_ID.fetch_add(1, Ordering::Relaxed) as u64
}

/// Per-connection session state.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Session {
    pub session_id: String,
    pub app_id: String,
    pub tenant_id: String,
    pub license_jwt: String,
    pub trace_id: String,
    pub tool_call_count: u32,
    pub upstream_initialized: bool,
    /// Current session risk score (updated by engine responses).
    pub session_risk_score: u32,
}

impl Session {
    /// Create a new session from `initialize` params `_meta`.
    pub fn from_meta(meta: &serde_json::Value) -> Self {
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

        let session_id = if session_id.is_empty() {
            uuid::Uuid::new_v4().to_string()
        } else {
            session_id
        };

        Self {
            session_id,
            app_id,
            tenant_id,
            license_jwt,
            trace_id: uuid::Uuid::new_v4().to_string(),
            tool_call_count: 0,
            upstream_initialized: false,
            session_risk_score: 0,
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
}

/// Concurrent session manager keyed by connection ID.
pub struct SessionManager {
    sessions: DashMap<u64, Session>,
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            sessions: DashMap::new(),
        }
    }

    pub fn insert(&self, conn_id: u64, session: Session) {
        self.sessions.insert(conn_id, session);
    }

    pub fn get(&self, conn_id: u64) -> Option<Session> {
        self.sessions.get(&conn_id).map(|r| r.clone())
    }

    pub fn update(&self, conn_id: u64, session: Session) {
        self.sessions.insert(conn_id, session);
    }

    pub fn remove(&self, conn_id: u64) {
        self.sessions.remove(&conn_id);
    }
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

pub type SharedSessionManager = Arc<SessionManager>;
