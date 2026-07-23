/// Audit event reporting to Redis Stream or Kafka (best-effort, non-blocking).
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, warn};

const AUDIT_REDIS_STREAM_KEY: &str = "virbius:audit:stream";
const AUDIT_QUEUE_SIZE: usize = 512;
const KAFKA_TIMEOUT_SECS: u64 = 5;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEvent {
    pub trace_id: String,
    pub layer: String,
    pub event_type: String,
    pub tool_name: String,
    pub action: String,
    pub rule_id: Option<String>,
    pub reason: Option<String>,
    pub session_id: String,
    pub app_id: String,
    pub tenant_id: String,
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    pub session_risk_score: u32,
    pub timestamp: String,
}

impl AuditEvent {
    pub fn tool_call(
        session: &crate::session::Session,
        tool_name: &str,
        action: &str,
        rule_id: Option<&str>,
        reason: Option<&str>,
    ) -> Self {
        let timestamp = chrono::Utc::now().to_rfc3339();
        Self {
            trace_id: session.trace_id.clone(),
            layer: "edge".to_string(),
            event_type: "tool_call".to_string(),
            tool_name: tool_name.to_string(),
            action: action.to_string(),
            rule_id: rule_id.map(String::from),
            reason: reason.map(String::from),
            session_id: session.session_id.clone(),
            app_id: session.app_id.clone(),
            tenant_id: session.tenant_id.clone(),
            user_id: session.user_id.clone(),
            device_id: session.device_id.clone(),
            session_risk_score: session.session_risk_score,
            timestamp,
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
        let timestamp = chrono::Utc::now().to_rfc3339();
        Self {
            trace_id: session.trace_id.clone(),
            layer: "edge".to_string(),
            event_type: "memory_write".to_string(),
            tool_name: tool_name.to_string(),
            action: action.to_string(),
            rule_id: rule_id.map(String::from),
            reason: reason.map(String::from),
            session_id: session.session_id.clone(),
            app_id: session.app_id.clone(),
            tenant_id: session.tenant_id.clone(),
            user_id: session.user_id.clone(),
            device_id: session.device_id.clone(),
            session_risk_score: session.session_risk_score,
            timestamp,
        }
    }
}

/// Backend type for audit event delivery.
#[derive(Debug, Clone)]
pub enum AuditBackend {
    Disabled,
    Redis { url: String },
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
            AuditBackend::Redis { url } => {
                let (tx, rx) = mpsc::channel::<AuditEvent>(AUDIT_QUEUE_SIZE);
                tokio::spawn(async move {
                    redis_audit_worker(url, rx).await;
                });
                Some(tx)
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
        if event.action == "allow" && rand_sample() > self.sample_rate {
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

// ─── Redis worker ───────────────────────────────────────────────────────

async fn redis_audit_worker(url: String, mut rx: mpsc::Receiver<AuditEvent>) {
    debug!("audit redis worker started");
    let addr = parse_redis_addr(&url);
    loop {
        match tokio::net::TcpStream::connect(addr).await {
            Ok(stream) => {
                use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
                let (reader, mut writer) = stream.into_split();
                let mut reader = BufReader::new(reader);
                let mut line = String::new();

                while let Some(event) = rx.recv().await {
                    let json = match serde_json::to_string(&event) {
                        Ok(j) => j,
                        Err(e) => {
                            warn!("audit serialize error: {e}");
                            continue;
                        }
                    };
                    let cmd = format!(
                        "XADD {} * data '{}'\r\n",
                        AUDIT_REDIS_STREAM_KEY,
                        json.replace('\'', "\\'")
                    );
                    if writer.write_all(cmd.as_bytes()).await.is_err() {
                        warn!("audit redis write failed, reconnecting...");
                        break;
                    }
                    line.clear();
                    let _ = reader.read_line(&mut line).await;
                }
            }
            Err(e) => {
                warn!("audit redis connect failed: {e}, retrying in 5s...");
                while rx.try_recv().is_ok() {}
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        }
    }
}

// ─── Kafka worker ──────────────────────────────────────────────────────

async fn kafka_audit_worker(brokers: String, topic: String, mut rx: mpsc::Receiver<AuditEvent>) {
    debug!("audit kafka worker started, brokers={}", brokers);

    let producer: rdkafka::producer::FutureProducer = match rdkafka::config::ClientConfig::new()
        .set("bootstrap.servers", &brokers)
        .set("message.timeout.ms", "5000")
        .set("queue.buffering.max.ms", "10")
        .create()
    {
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

/// Parse a Redis address string into a `SocketAddr`.
///
/// Accepts both `host:port` and `redis://host:port` formats.
/// Parsing as `SocketAddr` avoids going through the system DNS resolver
/// (which can fail spuriously on macOS for literal IPs).
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
        let event = AuditEvent::tool_call(&session, "read_file", "allow", Some("rule-1"), Some("ok"));
        assert_eq!(event.trace_id, session.trace_id);
        assert_eq!(event.layer, "edge");
        assert_eq!(event.event_type, "tool_call");
        assert_eq!(event.tool_name, "read_file");
        assert_eq!(event.action, "allow");
        assert_eq!(event.rule_id.as_deref(), Some("rule-1"));
        assert_eq!(event.reason.as_deref(), Some("ok"));
        assert_eq!(event.session_id, "sid-1");
        assert_eq!(event.app_id, "app-1");
        assert_eq!(event.tenant_id, "tenant-1");
        assert_eq!(event.session_risk_score, 0);
        assert!(!event.timestamp.is_empty());
    }

    #[test]
    fn test_audit_event_tool_call_no_rule_no_reason() {
        let session = make_session();
        let event = AuditEvent::tool_call(&session, "shell", "block", None, None);
        assert_eq!(event.action, "block");
        assert!(event.rule_id.is_none());
        assert!(event.reason.is_none());
    }

    #[test]
    fn test_audit_event_memory_write() {
        let session = make_session();
        let event = AuditEvent::memory_write(&session, "sql_query", "block", None, Some("pii"), true, Some(-1));
        assert_eq!(event.event_type, "memory_write");
        assert_eq!(event.tool_name, "sql_query");
        assert_eq!(event.action, "block");
        assert_eq!(event.reason.as_deref(), Some("pii"));
    }

    #[test]
    fn test_audit_event_memory_write_with_rule() {
        let session = make_session();
        let event = AuditEvent::memory_write(&session, "write_file", "allow", Some("kee-1"), None, false, None);
        assert_eq!(event.event_type, "memory_write");
        assert_eq!(event.action, "allow");
        assert_eq!(event.rule_id.as_deref(), Some("kee-1"));
    }

    #[test]
    fn test_rand_sample_range() {
        let s = rand_sample();
        assert!(s >= 0.0);
        assert!(s < 1.0);
    }

    #[test]
    fn test_parse_redis_addr_host_port() {
        let addr = parse_redis_addr("127.0.0.1:6379");
        assert_eq!(addr.to_string(), "127.0.0.1:6379");
    }

    #[test]
    fn test_parse_redis_addr_redis_protocol() {
        let addr = parse_redis_addr("redis://10.0.0.1:6380");
        assert_eq!(addr.to_string(), "10.0.0.1:6380");
    }

    #[test]
    #[should_panic(expected = "invalid Redis address")]
    fn test_parse_redis_addr_invalid() {
        parse_redis_addr("not-a-valid-address");
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
        assert_eq!(json["action"], "block");
        assert_eq!(json["reason"], "not allowed");
        assert_eq!(json["layer"], "edge");
        assert_eq!(json["event_type"], "tool_call");
    }
}

fn parse_redis_addr(url: &str) -> std::net::SocketAddr {
    let raw = url.strip_prefix("redis://").unwrap_or(url);
    raw.parse::<std::net::SocketAddr>()
        .unwrap_or_else(|e| {
            panic!("invalid Redis address '{url}': {e}. Expected 'host:port' or 'redis://host:port'")
        })
}
