/// Decision chain trace collector: records tool_call and tool_result events
/// to Redis Stream or Kafka for async ingestion by the Control plane.
///
/// Events are sent via a background tokio task. The collector is best-effort
/// and non-blocking.
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, warn};

const TRACE_REDIS_STREAM_KEY: &str = "virbius:trace:stream";
const TRACE_QUEUE_SIZE: usize = 1024;
const KAFKA_TIMEOUT_SECS: u64 = 5;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StepType {
    Input,
    Reasoning,
    ToolCall,
    ToolResult,
    Output,
    MemoryWrite,
}

impl StepType {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Input => "input",
            Self::Reasoning => "reasoning",
            Self::ToolCall => "tool_call",
            Self::ToolResult => "tool_result",
            Self::Output => "output",
            Self::MemoryWrite => "memory_write",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TraceEvent {
    pub trace_id: String,
    pub session_id: String,
    pub tenant_id: String,
    pub step_id: String,
    pub parent_step_id: Option<String>,
    pub step_seq: u32,
    pub step_type: String,
    pub layer: String,
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    pub input_role: Option<String>,
    pub input_content_hash: Option<String>,
    pub tool_name: Option<String>,
    pub tool_args_hash: Option<String>,
    pub tool_args: Option<Value>,
    pub tool_decision: Option<String>,
    pub rule_id: Option<String>,
    pub reason_code: Option<String>,
    pub risk_score: Option<u32>,
    pub tool_status: Option<String>,
    pub tool_duration_ms: Option<u64>,
    pub tool_result_preview: Option<String>,
    pub content_size: Option<usize>,
    pub content_sampled: bool,
    pub dlp_masked: bool,
    pub occurred_at: String,
}

impl TraceEvent {
    pub fn tool_call(
        session: &crate::session::Session,
        step_id: &str,
        parent_step_id: Option<&str>,
        step_seq: u32,
        tool_name: &str,
        args: &Value,
    ) -> Self {
        let args_json = serde_json::to_string(args).unwrap_or_default();
        let args_hash = sha256_hex(&format!("{}:{}", tool_name, args_json));
        let args_size = args_json.len();
        Self {
            trace_id: session.trace_id.clone(),
            session_id: session.session_id.clone(),
            tenant_id: session.tenant_id.clone(),
            step_id: step_id.to_string(),
            parent_step_id: parent_step_id.map(String::from),
            step_seq,
            step_type: StepType::ToolCall.as_str().to_string(),
            layer: "edge".to_string(),
            user_id: None,
            device_id: None,
            input_role: None,
            input_content_hash: None,
            tool_name: Some(tool_name.to_string()),
            tool_args_hash: Some(args_hash),
            tool_args: Some(args.clone()),
            tool_decision: None,
            rule_id: None,
            reason_code: None,
            risk_score: None,
            tool_status: None,
            tool_duration_ms: None,
            tool_result_preview: None,
            content_size: Some(args_size),
            content_sampled: true,
            dlp_masked: false,
            occurred_at: chrono::Utc::now().to_rfc3339(),
        }
    }

    pub fn tool_result(
        session: &crate::session::Session,
        step_id: &str,
        parent_step_id: &str,
        step_seq: u32,
        status: &str,
        duration_ms: u64,
        result: &Value,
    ) -> Self {
        let result_str = serde_json::to_string(result).unwrap_or_default();
        let result_size = result_str.len();
        let preview = if result_str.len() > 2048 {
            result_str[..2048].to_string()
        } else {
            result_str
        };
        Self {
            trace_id: session.trace_id.clone(),
            session_id: session.session_id.clone(),
            tenant_id: session.tenant_id.clone(),
            step_id: step_id.to_string(),
            parent_step_id: Some(parent_step_id.to_string()),
            step_seq,
            step_type: StepType::ToolResult.as_str().to_string(),
            layer: "edge".to_string(),
            user_id: None,
            device_id: None,
            input_role: None,
            input_content_hash: None,
            tool_name: None,
            tool_args_hash: None,
            tool_args: None,
            tool_decision: None,
            rule_id: None,
            reason_code: None,
            risk_score: None,
            tool_status: Some(status.to_string()),
            tool_duration_ms: Some(duration_ms),
            tool_result_preview: Some(preview),
            content_size: Some(result_size),
            content_sampled: true,
            dlp_masked: false,
            occurred_at: chrono::Utc::now().to_rfc3339(),
        }
    }

    pub fn with_decision(
        mut self,
        decision: &str,
        rule_id: Option<&str>,
        reason_code: Option<&str>,
        risk_score: Option<u32>,
    ) -> Self {
        self.tool_decision = Some(decision.to_string());
        self.rule_id = rule_id.map(String::from);
        self.reason_code = reason_code.map(String::from);
        self.risk_score = risk_score;
        self
    }
}

/// Backend type for trace event delivery.
#[derive(Debug, Clone)]
pub enum TraceBackend {
    Disabled,
    Redis { url: String },
    Kafka { brokers: String, topic: String },
}

pub struct TraceCollector {
    sender: Option<mpsc::Sender<TraceEvent>>,
}

impl TraceCollector {
    pub fn new(backend: TraceBackend) -> Self {
        let sender = match backend {
            TraceBackend::Disabled => {
                debug!("trace collector disabled");
                None
            }
            TraceBackend::Redis { url } => {
                let (tx, rx) = mpsc::channel::<TraceEvent>(TRACE_QUEUE_SIZE);
                tokio::spawn(async move {
                    redis_trace_worker(url, rx).await;
                });
                Some(tx)
            }
            TraceBackend::Kafka { brokers, topic } => {
                let (tx, rx) = mpsc::channel::<TraceEvent>(TRACE_QUEUE_SIZE);
                tokio::spawn(async move {
                    kafka_trace_worker(brokers, topic, rx).await;
                });
                Some(tx)
            }
        };

        Self { sender }
    }

    pub async fn record(&self, event: TraceEvent) {
        if let Some(ref tx) = self.sender {
            if tx.try_send(event).is_err() {
                warn!("trace queue full, dropping event");
            }
        }
    }

    pub fn enabled(&self) -> bool {
        self.sender.is_some()
    }
}

pub type SharedTraceCollector = Arc<TraceCollector>;

// ─── Redis worker ───────────────────────────────────────────────────────

async fn redis_trace_worker(url: String, mut rx: mpsc::Receiver<TraceEvent>) {
    debug!("trace redis worker started");
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
                            warn!("trace serialize error: {e}");
                            continue;
                        }
                    };
                    let cmd = format!(
                        "XADD {} * data '{}'\r\n",
                        TRACE_REDIS_STREAM_KEY,
                        json.replace('\'', "\\'")
                    );
                    if writer.write_all(cmd.as_bytes()).await.is_err() {
                        warn!("trace redis write failed, reconnecting...");
                        break;
                    }
                    line.clear();
                    let _ = reader.read_line(&mut line).await;
                }
            }
            Err(e) => {
                warn!("trace redis connect failed: {e}, retrying in 5s...");
                while rx.try_recv().is_ok() {}
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        }
    }
}

// ─── Kafka worker ──────────────────────────────────────────────────────

async fn kafka_trace_worker(brokers: String, topic: String, mut rx: mpsc::Receiver<TraceEvent>) {
    debug!("trace kafka worker started, brokers={}", brokers);

    let producer: rdkafka::producer::FutureProducer = match rdkafka::config::ClientConfig::new()
        .set("bootstrap.servers", &brokers)
        .set("message.timeout.ms", "5000")
        .set("queue.buffering.max.ms", "10")
        .create()
    {
        Ok(p) => p,
        Err(e) => {
            warn!("trace kafka producer creation failed: {e}, disabling trace");
            while rx.recv().await.is_some() {}
            return;
        }
    };

    while let Some(event) = rx.recv().await {
        let json = match serde_json::to_string(&event) {
            Ok(j) => j,
            Err(e) => {
                warn!("trace serialize error: {e}");
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
            warn!("trace kafka send failed: {e}");
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let result = hasher.finalize();
    format!("sha256:{}", hex_encode(&result))
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}
