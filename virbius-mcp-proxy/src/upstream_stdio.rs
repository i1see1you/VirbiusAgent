//! stdio upstream client: spawns a local MCP server subprocess and speaks
//! newline-delimited JSON-RPC over its stdin/stdout.
//!
//! Process model: the manager keeps one child per (session, upstream) pair,
//! so HTTP-listening proxies get per-client isolation while `listen = "stdio"`
//! naturally uses a single child (one downstream client).
//!
//! Security: argv is passed without a shell, the child environment is built
//! only from the entry's `env` plus a safe subset, stderr is drained (so a
//! chatty server cannot block on a full pipe), and `kill_on_drop` guarantees
//! no orphan survives the proxy.

use std::collections::BTreeMap;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::{mpsc, oneshot, Mutex as TokioMutex};
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};

use crate::upstream::UpstreamError;
use crate::upstream_streamable::CLIENT_PROTOCOL_VERSION;

const STDERR_LINE_CAP: usize = 8 * 1024;
const SHUTDOWN_GRACE: Duration = Duration::from_secs(3);

struct ProcessState {
    child: Option<Child>,
    stdin: Option<mpsc::Sender<String>>,
}

pub struct StdioUpstream {
    name: String,
    command: String,
    args: Vec<String>,
    env: BTreeMap<String, String>,
    cwd: Option<String>,
    timeout: Duration,
    inner: TokioMutex<ProcessState>,
    init_lock: TokioMutex<()>,
    pending: DashMap<Value, oneshot::Sender<Value>>,
    cancels: DashMap<Value, CancellationToken>,
    ready: AtomicBool,
    closed: AtomicBool,
    next_id: AtomicU64,
}

impl StdioUpstream {
    pub fn new(
        name: String,
        command: String,
        args: Vec<String>,
        env: BTreeMap<String, String>,
        cwd: Option<String>,
        timeout: Duration,
    ) -> Self {
        Self {
            name,
            command,
            args,
            env,
            cwd,
            timeout,
            inner: TokioMutex::new(ProcessState {
                child: None,
                stdin: None,
            }),
            init_lock: TokioMutex::new(()),
            pending: DashMap::new(),
            cancels: DashMap::new(),
            ready: AtomicBool::new(false),
            closed: AtomicBool::new(false),
            next_id: AtomicU64::new(1),
        }
    }

    pub fn url(&self) -> &str {
        &self.command
    }

    pub fn is_connected(&self) -> bool {
        self.ready.load(Ordering::Relaxed) && !self.closed.load(Ordering::Relaxed)
    }

    fn next_id(&self) -> u64 {
        self.next_id.fetch_add(1, Ordering::Relaxed)
    }

    /// Child environment: explicit entry vars + a safe subset only. Proxy
    /// secrets (licenses, Redis URLs, tokens) are never inherited.
    fn build_env(env: &BTreeMap<String, String>) -> Vec<(String, String)> {
        let mut out: Vec<(String, String)> = Vec::new();
        for key in ["PATH", "HOME", "LANG", "LC_ALL", "TMPDIR"] {
            if let Ok(value) = std::env::var(key) {
                out.push((key.to_string(), value));
            }
        }
        for (k, v) in env {
            out.retain(|(existing, _)| existing != k);
            out.push((k.clone(), v.clone()));
        }
        out
    }

    fn spawn_command(&self) -> Result<Command, UpstreamError> {
        if let Some(cwd) = &self.cwd {
            if !std::path::Path::new(cwd).is_dir() {
                return Err(UpstreamError::Spawn(format!(
                    "upstream '{}': cwd '{}' does not exist or is not a directory",
                    self.name, cwd
                )));
            }
        }
        let mut cmd = Command::new(&self.command);
        cmd.args(&self.args)
            .env_clear()
            .envs(Self::build_env(&self.env))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        if let Some(cwd) = &self.cwd {
            cmd.current_dir(cwd);
        }
        // Die with the proxy instead of leaking orphans (Unix only).
        #[cfg(unix)]
        cmd.process_group(0);
        Ok(cmd)
    }

    /// Spawn the child and complete the MCP handshake (single-flight).
    pub async fn ensure_started(self: &Arc<Self>) -> Result<(), UpstreamError> {
        if self.ready.load(Ordering::Relaxed) {
            return Ok(());
        }
        let _guard = self.init_lock.lock().await;
        if self.ready.load(Ordering::Relaxed) {
            return Ok(());
        }
        if self.closed.load(Ordering::Relaxed) {
            return Err(UpstreamError::NotConnected);
        }

        let mut cmd = self.spawn_command()?;
        let mut child = cmd.spawn().map_err(|e| {
            UpstreamError::Spawn(format!(
                "upstream '{}': cannot spawn '{}': {e}",
                self.name, self.command
            ))
        })?;

        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| UpstreamError::Spawn("child stdin unavailable".into()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| UpstreamError::Spawn("child stdout unavailable".into()))?;
        let stderr = child.stderr.take();

        let (stdin_tx, mut stdin_rx) = mpsc::channel::<String>(64);
        {
            let name = self.name.clone();
            let mut stdin = stdin;
            tokio::spawn(async move {
                while let Some(line) = stdin_rx.recv().await {
                    if stdin.write_all(line.as_bytes()).await.is_err() {
                        break;
                    }
                    if stdin.write_all(b"\n").await.is_err() || stdin.flush().await.is_err() {
                        break;
                    }
                }
                // Channel closed → dropping stdin closes the child's stdin.
                debug!("stdio upstream '{}': writer stopped", name);
            });
        }

        // stdout reader: correlates responses, forwards notifications.
        {
            let this = self.clone();
            let name = self.name.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stdout).lines();
                loop {
                    match lines.next_line().await {
                        Ok(Some(line)) => {
                            if line.trim().is_empty() {
                                continue;
                            }
                            match serde_json::from_str::<Value>(&line) {
                                Ok(msg) => match msg.get("id").cloned() {
                                    Some(id) => this.deliver(&id, msg),
                                    None => {
                                        debug!("stdio upstream '{}': notification {msg}", this.name)
                                    }
                                },
                                Err(e) => {
                                    warn!("stdio upstream '{}': unparsable stdout line: {e}", name)
                                }
                            }
                        }
                        Ok(None) => break,
                        Err(e) => {
                            warn!("stdio upstream '{}': stdout read error: {e}", name);
                            break;
                        }
                    }
                }
                this.ready.store(false, Ordering::Relaxed);
                this.fail_pending();
                debug!("stdio upstream '{}': reader stopped", name);
            });
        }

        // stderr drain: never inherit into the log verbatim beyond a cap.
        if let Some(stderr) = stderr {
            let name = self.name.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let capped = if line.len() > STDERR_LINE_CAP {
                        &line[..STDERR_LINE_CAP]
                    } else {
                        &line
                    };
                    debug!(upstream = %name, "stdio stderr: {capped}");
                }
            });
        }

        {
            let mut st = self.inner.lock().await;
            st.child = Some(child);
            st.stdin = Some(stdin_tx);
        }

        // MCP handshake.
        let init = json!({
            "jsonrpc": "2.0",
            "id": self.next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": CLIENT_PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {
                    "name": "virbius-mcp-proxy",
                    "version": env!("CARGO_PKG_VERSION")
                }
            }
        });
        self.send_and_wait(&init).await?;
        self.write_line(&json!({
            "jsonrpc": "2.0",
            "method": "notifications/initialized"
        }))
        .await?;

        self.ready.store(true, Ordering::Relaxed);
        debug!("stdio upstream '{}' started ({})", self.name, self.command);
        Ok(())
    }

    async fn write_line(&self, msg: &Value) -> Result<(), UpstreamError> {
        let line = serde_json::to_string(msg)
            .map_err(|e| UpstreamError::Parse(format!("serialize upstream message: {e}")))?;
        let tx = {
            let st = self.inner.lock().await;
            st.stdin.clone().ok_or(UpstreamError::NotConnected)?
        };
        tx.send(line).await.map_err(|_| UpstreamError::NotConnected)
    }

    async fn send_and_wait(&self, request: &Value) -> Result<Value, UpstreamError> {
        let id = request.get("id").cloned().unwrap_or(Value::Null);
        let token = CancellationToken::new();
        self.cancels.insert(id.clone(), token.clone());
        let (tx, rx) = oneshot::channel();
        self.pending.insert(id.clone(), tx);

        let result = async {
            self.write_line(request).await?;
            tokio::select! {
                _ = token.cancelled() => Err(UpstreamError::Cancelled),
                r = tokio::time::timeout(self.timeout, rx) => {
                    match r {
                        Ok(Ok(value)) => Ok(value),
                        Ok(Err(_)) => Err(UpstreamError::NotConnected),
                        Err(_) => Err(UpstreamError::Timeout),
                    }
                }
            }
        }
        .await;

        self.cancels.remove(&id);
        self.pending.remove(&id);
        result
    }

    pub async fn forward(self: &Arc<Self>, request: &Value) -> Result<Value, UpstreamError> {
        self.ensure_started().await?;
        self.send_and_wait(request).await
    }

    pub async fn forward_notification(
        self: &Arc<Self>,
        request: &Value,
    ) -> Result<(), UpstreamError> {
        self.ensure_started().await?;
        self.write_line(request).await
    }

    fn deliver(&self, id: &Value, msg: Value) {
        if let Some((_, tx)) = self.pending.remove(id) {
            let _ = tx.send(msg);
        }
    }

    fn fail_pending(&self) {
        let ids: Vec<Value> = self.pending.iter().map(|e| e.key().clone()).collect();
        for id in ids {
            self.pending.remove(&id);
        }
    }

    /// Abort an in-flight request (`notifications/cancelled`).
    pub fn cancel(&self, id: &Value) {
        if let Some((_, token)) = self.cancels.remove(id) {
            token.cancel();
        }
    }

    /// Graceful shutdown: close stdin, wait, then kill.
    pub fn shutdown(self: &Arc<Self>) {
        if self.closed.swap(true, Ordering::Relaxed) {
            return;
        }
        self.ready.store(false, Ordering::Relaxed);
        let Ok(handle) = tokio::runtime::Handle::try_current() else {
            return;
        };
        let this = self.clone();
        handle.spawn(async move {
            let mut st = this.inner.lock().await;
            st.stdin.take();
            let Some(mut child) = st.child.take() else {
                return;
            };
            drop(st);
            match tokio::time::timeout(SHUTDOWN_GRACE, child.wait()).await {
                Ok(_) => debug!("stdio upstream '{}' exited", this.name),
                Err(_) => {
                    let _ = child.kill().await;
                    let _ = child.wait().await;
                    debug!("stdio upstream '{}' killed after grace period", this.name);
                }
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_does_not_inherit_proxy_secrets() {
        let mut entry_env = BTreeMap::new();
        entry_env.insert("FS_ROOT".to_string(), "/data".to_string());
        entry_env.insert("PATH".to_string(), "/custom/bin".to_string());

        let env = StdioUpstream::build_env(&entry_env);

        assert!(env.iter().any(|(k, v)| k == "FS_ROOT" && v == "/data"));
        // Explicit entry value overrides the inherited one.
        let paths: Vec<&String> = env
            .iter()
            .filter(|(k, _)| k == "PATH")
            .map(|(_, v)| v)
            .collect();
        assert_eq!(paths, vec![&"/custom/bin".to_string()]);
        // Proxy secrets are never passed through.
        for key in [
            "VIRBIUS_LICENSE_FILE",
            "VIRBIUS_LICENSE_PUBLIC_KEY",
            "VIRBIUS_REDIS_URL",
            "VIRBIUS_POLICY_REDIS_URL",
            "VIRBIUS_DELEGATED_KEK",
        ] {
            assert!(!env.iter().any(|(k, _)| k == key), "{key} leaked");
        }
    }
}
