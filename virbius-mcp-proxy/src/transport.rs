/// Transport layer: abstract trait + stdio and SSE implementations.

use async_trait::async_trait;
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tracing::{debug, warn};

/// Trait for MCP transport implementations.
#[async_trait]
pub trait McpTransport: Send + Sync {
    /// Read the next incoming JSON-RPC message.
    /// Returns `None` when the transport is closed.
    async fn read(&self) -> Option<Value>;

    /// Write a JSON-RPC message to the output.
    async fn write(&self, message: &Value);
}

/// stdio transport: reads JSON-RPC messages from stdin (newline-delimited),
/// writes to stdout.
pub struct StdioTransport {
    stdin_rx: tokio::sync::mpsc::Receiver<Value>,
    stdout_tx: tokio::sync::mpsc::Sender<String>,
}

impl StdioTransport {
    pub fn new() -> (Self, tokio::task::JoinHandle<()>) {
        let (stdin_tx, stdin_rx) = tokio::sync::mpsc::channel::<Value>(64);
        let (stdout_tx, stdout_rx) = tokio::sync::mpsc::channel::<String>(64);

        // Spawn stdin reader
        tokio::spawn(async move {
            let stdin = tokio::io::stdin();
            let reader = BufReader::new(stdin);
            let mut lines = reader.lines();

            loop {
                match lines.next_line().await {
                    Ok(Some(line)) => {
                        if line.trim().is_empty() {
                            continue;
                        }
                        match serde_json::from_str::<Value>(&line) {
                            Ok(v) => {
                                if stdin_tx.send(v).await.is_err() {
                                    break;
                                }
                            }
                            Err(e) => {
                                warn!("stdin parse error: {e}");
                            }
                        }
                    }
                    Ok(None) => {
                        debug!("stdin EOF, transport closed");
                        break;
                    }
                    Err(e) => {
                        warn!("stdin read error: {e}");
                        break;
                    }
                }
            }
        });

        // Spawn stdout writer
        let writer_handle = tokio::spawn(async move {
            let mut stdout = tokio::io::stdout();
            let mut rx = stdout_rx;
            while let Some(line) = rx.recv().await {
                if stdout.write_all(line.as_bytes()).await.is_err() {
                    break;
                }
                if stdout.write_all(b"\n").await.is_err() {
                    break;
                }
                let _ = stdout.flush().await;
            }
        });

        let transport = Self {
            stdin_rx,
            stdout_tx,
        };

        (transport, writer_handle)
    }

    pub async fn send(&self, message: &Value) {
        let json = serde_json::to_string(message).unwrap_or_default();
        let _ = self.stdout_tx.send(json).await;
    }

    pub async fn recv(&mut self) -> Option<Value> {
        self.stdin_rx.recv().await
    }
}

/// SSE / HTTP transport: listens on a TCP port for MCP HTTP requests.
pub struct SseTransport {
    listener_rx: tokio::sync::mpsc::Receiver<(Value, tokio::sync::oneshot::Sender<Value>)>,
}

impl SseTransport {
    pub async fn new(addr: &str) -> Result<(Self, tokio::task::JoinHandle<()>), std::io::Error> {
        let listener = tokio::net::TcpListener::bind(addr).await?;
        let (tx, rx) = tokio::sync::mpsc::channel::<(Value, tokio::sync::oneshot::Sender<Value>)>(64);
        let addr_owned = addr.to_string();

        let handle = tokio::spawn(async move {
            debug!("SSE transport listening on {}", addr_owned);
            loop {
                match listener.accept().await {
                    Ok((stream, peer)) => {
                        debug!("SSE connection from {}", peer);
                        let tx = tx.clone();
                        tokio::spawn(async move {
                            handle_sse_connection(stream, tx).await;
                        });
                    }
                    Err(e) => {
                        warn!("accept error: {e}");
                        continue;
                    }
                }
            }
        });

        Ok((Self { listener_rx: rx }, handle))
    }

    pub async fn recv(&mut self) -> Option<(Value, tokio::sync::oneshot::Sender<Value>)> {
        self.listener_rx.recv().await
    }
}

async fn handle_sse_connection(
    stream: tokio::net::TcpStream,
    tx: tokio::sync::mpsc::Sender<(Value, tokio::sync::oneshot::Sender<Value>)>,
) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut buf = vec![0u8; 65536];
    let (mut reader, mut writer) = stream.into_split();

    let n = match reader.read(&mut buf).await {
        Ok(0) => return,
        Ok(n) => n,
        Err(_) => return,
    };

    let raw = String::from_utf8_lossy(&buf[..n]);
    let body = extract_http_body(&raw);

    if body.is_empty() {
        let _ = writer
            .write_all(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
            .await;
        return;
    }

    let request: Value = match serde_json::from_str(&body) {
        Ok(v) => v,
        Err(e) => {
            let msg = format!("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{{\"error\":\"{e}\"}}", e.to_string().len());
            let _ = writer.write_all(msg.as_bytes()).await;
            return;
        }
    };

    let (resp_tx, resp_rx) = tokio::sync::oneshot::channel();
    if tx.send((request, resp_tx)).await.is_err() {
        return;
    }

    match resp_rx.await {
        Ok(resp) => {
            let body = serde_json::to_string(&resp).unwrap_or_default();
            let http_resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = writer.write_all(http_resp.as_bytes()).await;
        }
        Err(_) => {
            let _ = writer
                .write_all(b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n")
                .await;
        }
    }
}

fn extract_http_body(raw: &str) -> String {
    if let Some(pos) = raw.find("\r\n\r\n") {
        raw[pos + 4..].to_string()
    } else if let Some(pos) = raw.find("\n\n") {
        raw[pos + 2..].to_string()
    } else {
        raw.to_string()
    }
}
