/// Audit event reporting to Redis Stream (best-effort, non-blocking).

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, warn};

const AUDIT_STREAM_KEY: &str = "virbius:audit:stream";
const AUDIT_QUEUE_SIZE: usize = 512;

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
}

/// Async audit sink: writes events to a Redis Stream via a background task.
pub struct AuditSink {
    sender: Option<mpsc::Sender<AuditEvent>>,
    sample_rate: f64,
}

impl AuditSink {
    pub fn new(redis_url: &str, sample_rate: f64) -> Self {
        if redis_url.is_empty() {
            debug!("audit sink disabled: no redis_url configured");
            return Self {
                sender: None,
                sample_rate,
            };
        }

        let (tx, rx) = mpsc::channel::<AuditEvent>(AUDIT_QUEUE_SIZE);
        let url = redis_url.to_string();
        tokio::spawn(async move {
            audit_worker(url, rx).await;
        });

        Self {
            sender: Some(tx),
            sample_rate,
        }
    }

    /// Send an audit event (best-effort, non-blocking).
    pub async fn report(&self, event: AuditEvent) {
        // Sampling: always sample blocks, sample allows at sample_rate
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

async fn audit_worker(redis_url: String, mut rx: mpsc::Receiver<AuditEvent>) {
    debug!("audit worker started, redis={}", redis_url);

    // We use a simple TCP Redis client for minimal dependencies.
    // For production, replace with a proper Redis client crate.
    loop {
        match tokio::net::TcpStream::connect(&redis_url).await {
            Ok(stream) => {
                use tokio::io::{AsyncWriteExt, AsyncBufReadExt, BufReader};
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
                    // XADD virbius:audit:stream * data '<json>'
                    let cmd = format!(
                        "XADD {} * data '{}'\r\n",
                        AUDIT_STREAM_KEY,
                        json.replace('\'', "\\'")
                    );
                    if writer.write_all(cmd.as_bytes()).await.is_err() {
                        warn!("audit redis write failed, reconnecting...");
                        break;
                    }
                    // Read response (best-effort)
                    line.clear();
                    let _ = reader.read_line(&mut line).await;
                }
            }
            Err(e) => {
                warn!("audit redis connect failed: {e}, retrying in 5s...");
                // Drain pending events to avoid infinite backlog
                while rx.try_recv().is_ok() {}
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        }
    }
}

fn rand_sample() -> f64 {
    // Simple pseudo-random without external dependency
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

pub type SharedAuditSink = Arc<AuditSink>;
