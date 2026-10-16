/// Audit event reporting to Kafka (best-effort, non-blocking).
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, warn};

const AUDIT_QUEUE_SIZE: usize = 512;
const KAFKA_TIMEOUT_SECS: u64 = 5;

/// Wire schema matches virbius-control's `tb_audit_events` / the engine
/// `AuditWriter` contract (canonical field names). See PROXY_CONFIG.md.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEvent {
    pub event_id: String,
    pub trace_id: String,
    pub layer: String,
    pub event_type: String,
    pub tool_name: String,
    pub effective_action: String,
    pub rule_id: Option<String>,
    pub reason_code: Option<String>,
    pub session_id: String,
    pub app_id: String,
    pub tenant_id: String,
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    pub max_risk_score: u32,
    pub intercepted_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transformed: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transform_applied: Option<serde_json::Value>,
}

/// Control's `tb_audit_events.reason_code` is VARCHAR(64); richer deny reasons
/// (e.g. the license app_id-mismatch text) would make MySQL strict mode reject
/// the whole audit row. Cap the wire value; the full text still reaches the
/// JSON-RPC caller.
fn reason_for_wire(reason: Option<&str>) -> Option<String> {
    reason.map(|r| r.chars().take(64).collect())
}

impl AuditEvent {
    pub fn tool_call(
        session: &crate::session::Session,
        tool_name: &str,
        action: &str,
        rule_id: Option<&str>,
        reason: Option<&str>,
    ) -> Self {
        let intercepted_at = chrono::Utc::now().to_rfc3339();
        Self {
            event_id: uuid::Uuid::new_v4().to_string(),
            trace_id: session.trace_id.clone(),
            layer: "edge".to_string(),
            event_type: "tool_call".to_string(),
            tool_name: tool_name.to_string(),
            effective_action: action.to_string(),
            rule_id: rule_id.map(String::from),
            reason_code: reason_for_wire(reason),
            session_id: session.session_id.clone(),
            app_id: session.app_id.clone(),
            tenant_id: session.tenant_id.clone(),
            user_id: session.user_id.clone(),
            device_id: session.device_id.clone(),
            max_risk_score: session.session_risk_score,
            intercepted_at,
            transformed: None,
            transform_applied: None,
        }
    }

    pub fn memory_write(
        session: &crate::session::Session,
        tool_name: &str,
        action: &str,
        rule_id: Option<&str>,
        reason: Option<&str>,
        _pii_found: bool,
        _risk_score: Option<i32>,
    ) -> Self {
        let intercepted_at = chrono::Utc::now().to_rfc3339();
        Self {
            event_id: uuid::Uuid::new_v4().to_string(),
            trace_id: session.trace_id.clone(),
            layer: "edge".to_string(),
            event_type: "memory_write".to_string(),
            tool_name: tool_name.to_string(),
            effective_action: action.to_string(),
            rule_id: rule_id.map(String::from),
            reason_code: reason_for_wire(reason),
            session_id: session.session_id.clone(),
            app_id: session.app_id.clone(),
            tenant_id: session.tenant_id.clone(),
            user_id: session.user_id.clone(),
            device_id: session.device_id.clone(),
            max_risk_score: session.session_risk_score,
            intercepted_at,
            transformed: None,
            transform_applied: None,
        }
    }
}

/// Backend type for audit event delivery.
#[derive(Debug, Clone)]
pub enum AuditBackend {
    Disabled,
    Kafka { brokers: String, topic: String },
}

/// Async audit sink: writes events to a configurable backend via a background task.
pub struct AuditSink {
    sender: Option<mpsc::Sender<AuditEvent>>,
    sample_rate: f64,
}

impl AuditSink {
    pub fn new(backend: AuditBackend, sample_rate: f64) -> Self {
        let sender = match backend {
            AuditBackend::Disabled => {
                debug!("audit sink disabled");
                None
            }
            AuditBackend::Kafka { brokers, topic } => {
                let (tx, rx) = mpsc::channel::<AuditEvent>(AUDIT_QUEUE_SIZE);
                tokio::spawn(async move {
                    kafka_audit_worker(brokers, topic, rx).await;
                });
                Some(tx)
            }
        };

        Self {
            sender,
            sample_rate,
        }
    }

    pub async fn report(&self, event: AuditEvent) {
        // Status ring first: it must reflect every decision, even ones the
        // sampling below drops from the audit bus.
        record_recent(&event);
        if event.effective_action == "allow" && rand_sample() > self.sample_rate {
            return;
        }
        if let Some(ref tx) = self.sender {
            if tx.try_send(event).is_err() {
                warn!("audit queue full, dropping event");
            }
        }
    }

    pub fn enabled(&self) -> bool {
        self.sender.is_some()
    }
}

pub type SharedAuditSink = Arc<AuditSink>;

// ─── Recent decisions (GET /status surface) ────────────────────────────

/// One entry of the bounded recent-decisions ring surfaced on `GET /status`.
/// Deliberately records EVERY decision — including allows dropped by the
/// audit sampling — so operators can see "what did the proxy just decide and
/// why" without grepping Kafka.
#[derive(Debug, Clone, Serialize)]
pub struct RecentDecision {
    pub intercepted_at: String,
    pub event_type: String,
    pub tool_name: String,
    pub effective_action: String,
    pub reason_code: Option<String>,
    pub trace_id: String,
}

/// Capacity of the recent-decisions ring.
pub const RECENT_DECISIONS_CAP: usize = 50;

fn recent_decisions_store() -> &'static std::sync::Mutex<std::collections::VecDeque<RecentDecision>>
{
    static STORE: std::sync::OnceLock<
        std::sync::Mutex<std::collections::VecDeque<RecentDecision>>,
    > = std::sync::OnceLock::new();
    STORE.get_or_init(|| {
        std::sync::Mutex::new(std::collections::VecDeque::with_capacity(
            RECENT_DECISIONS_CAP,
        ))
    })
}

fn record_recent(event: &AuditEvent) {
    let mut store = recent_decisions_store()
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if store.len() == RECENT_DECISIONS_CAP {
        store.pop_front();
    }
    store.push_back(RecentDecision {
        intercepted_at: event.intercepted_at.clone(),
        event_type: event.event_type.clone(),
        tool_name: event.tool_name.clone(),
        effective_action: event.effective_action.clone(),
        reason_code: event.reason_code.clone(),
        trace_id: event.trace_id.clone(),
    });
}

/// Snapshot of the most recent proxy decisions, oldest first.
pub fn recent_decisions() -> Vec<RecentDecision> {
    let store = recent_decisions_store()
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    store.iter().cloned().collect()
}

// ─── Kafka worker ──────────────────────────────────────────────────────

async fn kafka_audit_worker(brokers: String, topic: String, mut rx: mpsc::Receiver<AuditEvent>) {
    debug!("audit kafka worker started, brokers={}", brokers);

    let mut cfg = rdkafka::config::ClientConfig::new();
    cfg.set("bootstrap.servers", &brokers)
        .set("message.timeout.ms", "5000")
        .set("queue.buffering.max.ms", "10");
    crate::sink_common::apply_kafka_security(&mut cfg);
    let producer: rdkafka::producer::FutureProducer = match cfg.create() {
        Ok(p) => p,
        Err(e) => {
            warn!("audit kafka producer creation failed: {e}, disabling audit");
            while rx.recv().await.is_some() {} // drain
            return;
        }
    };

    while let Some(event) = rx.recv().await {
        let json = match serde_json::to_string(&event) {
            Ok(j) => j,
            Err(e) => {
                warn!("audit serialize error: {e}");
                continue;
            }
        };
        let key = &event.tenant_id;
        let fut = producer.send(
            rdkafka::producer::FutureRecord::to(&topic)
                .key(key)
                .payload(&json),
            Duration::from_secs(KAFKA_TIMEOUT_SECS),
        );
        if let Err((e, _)) = fut.await {
            warn!("audit kafka send failed: {e}");
        }
    }
}

// ─── Sampling ──────────────────────────────────────────────────────────

fn rand_sample() -> f64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut h = DefaultHasher::new();
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
        .hash(&mut h);
    (h.finish() as f64 / u64::MAX as f64).fract()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::Session;

    fn make_session() -> Session {
        let meta = serde_json::json!({
            "session_id": "sid-1",
            "app_id": "app-1",
            "tenant_id": "tenant-1",
        });
        Session::from_meta(&meta)
    }

    #[test]
    fn test_audit_event_tool_call() {
        let session = make_session();
        let event =
            AuditEvent::tool_call(&session, "read_file", "allow", Some("rule-1"), Some("ok"));
        assert!(!event.event_id.is_empty());
        assert_eq!(event.trace_id, session.trace_id);
        assert_eq!(event.layer, "edge");
        assert_eq!(event.event_type, "tool_call");
        assert_eq!(event.tool_name, "read_file");
        assert_eq!(event.effective_action, "allow");
        assert_eq!(event.rule_id.as_deref(), Some("rule-1"));
        assert_eq!(event.reason_code.as_deref(), Some("ok"));
        assert_eq!(event.session_id, "sid-1");
        assert_eq!(event.app_id, "app-1");
        assert_eq!(event.tenant_id, "tenant-1");
        assert_eq!(event.max_risk_score, 0);
        assert!(!event.intercepted_at.is_empty());
    }

    #[test]
    fn test_audit_event_tool_call_no_rule_no_reason() {
        let session = make_session();
        let event = AuditEvent::tool_call(&session, "shell", "block", None, None);
        assert_eq!(event.effective_action, "block");
        assert!(event.rule_id.is_none());
        assert!(event.reason_code.is_none());
    }

    #[test]
    fn test_audit_event_memory_write() {
        let session = make_session();
        let event = AuditEvent::memory_write(
            &session,
            "sql_query",
            "block",
            None,
            Some("pii"),
            true,
            Some(-1),
        );
        assert_eq!(event.event_type, "memory_write");
        assert_eq!(event.tool_name, "sql_query");
        assert_eq!(event.effective_action, "block");
        assert_eq!(event.reason_code.as_deref(), Some("pii"));
    }

    #[test]
    fn test_audit_event_memory_write_with_rule() {
        let session = make_session();
        let event = AuditEvent::memory_write(
            &session,
            "write_file",
            "allow",
            Some("kee-1"),
            None,
            false,
            None,
        );
        assert_eq!(event.event_type, "memory_write");
        assert_eq!(event.effective_action, "allow");
        assert_eq!(event.rule_id.as_deref(), Some("kee-1"));
    }

    #[test]
    fn test_rand_sample_range() {
        let s = rand_sample();
        assert!(s >= 0.0);
        assert!(s < 1.0);
    }

    #[test]
    fn test_audit_sink_disabled_enabled() {
        let sink = AuditSink::new(AuditBackend::Disabled, 0.5);
        assert!(!sink.enabled());
    }

    #[tokio::test]
    async fn test_audit_sink_report_disabled() {
        let sink = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0));
        let session = make_session();
        let event = AuditEvent::tool_call(&session, "test", "allow", None, None);
        // Should not panic or block
        sink.report(event).await;
    }

    #[test]
    fn test_audit_event_json_serialization() {
        let session = make_session();
        let event = AuditEvent::tool_call(&session, "rm", "block", None, Some("not allowed"));
        let json = serde_json::to_value(&event).unwrap();
        assert_eq!(json["tool_name"], "rm");
        assert_eq!(json["effective_action"], "block");
        assert_eq!(json["reason_code"], "not allowed");
        assert_eq!(json["layer"], "edge");
        assert_eq!(json["event_type"], "tool_call");
        assert!(json["event_id"].as_str().is_some_and(|s| !s.is_empty()));
        assert!(json["intercepted_at"]
            .as_str()
            .is_some_and(|s| !s.is_empty()));
    }
}
