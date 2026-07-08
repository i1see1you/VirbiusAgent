/// Decision chain trace collector: records tool_call and tool_result events
/// to a Redis Stream for async ingestion by the Control plane.
///
/// Events are sent via a background tokio task using a simple TCP Redis client
/// (same pattern as audit.rs). The collector is best-effort and non-blocking.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, warn};

const TRACE_STREAM_KEY: &str = "virbius:trace:stream";
const TRACE_QUEUE_SIZE: usize = 1024;

/// Type of trace step in the decision chain.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StepType {
    Input,
    Reasoning,
    ToolCall,
    ToolResult,
    Output,
}

impl StepType {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Input => "input",
            Self::Reasoning => "reasoning",
            Self::ToolCall => "tool_call",
            Self::ToolResult => "tool_result",
            Self::Output => "output",
        }
    }
}

/// A single trace event representing one step in the Agent decision chain.
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
    pub scene: String,
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    // input-specific
    pub input_role: Option<String>,
    pub input_content_hash: Option<String>,
    // tool_call-specific
    pub tool_name: Option<String>,
    pub tool_args_hash: Option<String>,
    pub tool_args: Option<Value>,
    pub tool_decision: Option<String>,
    pub rule_id: Option<String>,
    pub reason_code: Option<String>,
    pub risk_score: Option<u32>,
    // tool_result-specific
    pub tool_status: Option<String>,
    pub tool_duration_ms: Option<u64>,
    pub tool_result_preview: Option<String>,
    // content
    pub content_size: Option<usize>,
    pub content_sampled: bool,
    pub dlp_masked: bool,
    pub occurred_at: String,
}

impl TraceEvent {
    /// Build a tool_call trace event.
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
            scene: String::new(),
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

    /// Build a tool_result trace event.
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
            scene: String::new(),
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

    /// Set the decision info on a tool_call event (called after pipeline evaluation).
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

/// Async trace sink: writes events to a Redis Stream via a background task.
pub struct TraceCollector {
    sender: Option<mpsc::Sender<TraceEvent>>,
}

impl TraceCollector {
    /// Create a new TraceCollector. If `redis_url` is empty, tracing is disabled.
    pub fn new(redis_url: &str) -> Self {
        if redis_url.is_empty() {
            debug!("trace collector disabled: no redis_url configured");
            return Self { sender: None };
        }

        let (tx, rx) = mpsc::channel::<TraceEvent>(TRACE_QUEUE_SIZE);
        let url = redis_url.to_string();
        tokio::spawn(async move {
            trace_worker(url, rx).await;
        });

        Self { sender: Some(tx) }
    }

    /// Send a trace event (best-effort, non-blocking).
    pub async fn record(&self, event: TraceEvent) {
        if let Some(ref tx) = self.sender {
            if tx.try_send(event).is_err() {
                warn!("trace queue full, dropping event");
            }
        }
    }

    /// Check if tracing is enabled.
    pub fn enabled(&self) -> bool {
        self.sender.is_some()
    }
}

/// Background worker that writes trace events to a Redis Stream.
async fn trace_worker(redis_url: String, mut rx: mpsc::Receiver<TraceEvent>) {
    debug!("trace worker started, redis={}", redis_url);

    loop {
        match tokio::net::TcpStream::connect(&redis_url).await {
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
                    // XADD virbius:trace:stream * data '<json>'
                    let cmd = format!(
                        "XADD {} * data '{}'\r\n",
                        TRACE_STREAM_KEY,
                        json.replace('\'', "\\'")
                    );
                    if writer.write_all(cmd.as_bytes()).await.is_err() {
                        warn!("trace redis write failed, reconnecting...");
                        break;
                    }
                    // Read response (best-effort)
                    line.clear();
                    let _ = reader.read_line(&mut line).await;
                }
            }
            Err(e) => {
                warn!("trace redis connect failed: {e}, retrying in 5s...");
                // Drain pending events to avoid infinite backlog
                while rx.try_recv().is_ok() {}
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        }
    }
}

/// Compute SHA-256 hex digest of the input string.
fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let result = hasher.finalize();
    format!("sha256:{}", hex_encode(&result))
}

/// Minimal hex encoding.
fn hex_encode(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}

pub type SharedTraceCollector = Arc<TraceCollector>;
