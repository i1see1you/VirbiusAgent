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
        pii_found: bool,
        risk_score: Option<i32>,
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

        Self { sender, sample_rate }
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
    loop {
        match tokio::net::TcpStream::connect(&url).await {
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

async fn kafka_audit_worker(
    brokers: String,
    topic: String,
    mut rx: mpsc::Receiver<AuditEvent>,
) {
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
