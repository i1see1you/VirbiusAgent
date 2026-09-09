//! gVisor runsc container pool for untrusted code execution (Linux only).
//!
//! gVisor provides a userspace kernel that intercepts syscalls, offering
//! much stronger isolation than Landlock for arbitrary code execution
//! (e.g. `execute_python`, `shell`).  The trade-off is higher cold-start
//! latency (1–5 s), which is mitigated by a warm container pool.
//!
//! ## Architecture
//!
//! ```text
//!  GvisorPool (inside MCP Proxy / virbius-core)
//!    |
//!    +-- Background task: maintain min_warm containers per language
//!    |     For each warm container:
//!    |       runsc run --bundle /tmp/virbius-gvisor/{id} {id}
//!    |       (interpreter blocked on stdin, waiting for a script)
//!    |
//!    +-- execute(language, code):
//!    |     1. Acquire warm container from pool (or wait up to `acquire_timeout`)
//!    |     2. Write code to container stdin, then close it (EOF)
//!    |     3. Interpreter runs the script and exits; read stdout/stderr to EOF
//!    |        with a hard deadline (kill on timeout)
//!    |     4. `runsc delete --force` the spent container, spawn replacement
//!    |     5. Return result
//!    |
//!    +-- Degradation: if runsc binary not found, fall back to LandlockSandbox
//!        with timeout=5s + memory cgroup limit=128MB
//! ```

use crate::sandbox::wait::{
    abort_child, detach_reap, kill_and_wait_brief, recv_buf_timeout, run_command_timed, wait_until,
    REAP_BUDGET, REAP_STEP,
};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

/// Supported sandboxed languages.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Language {
    Python,
    Shell,
    Node,
}

impl Language {
    pub fn as_str(&self) -> &'static str {
        match self {
            Language::Python => "python",
            Language::Shell => "shell",
            Language::Node => "node",
        }
    }

    /// The interpreter binary to run inside the container.
    pub fn interpreter(&self) -> &str {
        match self {
            Language::Python => "python3",
            Language::Shell => "sh",
            Language::Node => "node",
        }
    }

    /// Parse from string.
    pub fn parse(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "python" | "python3" => Some(Language::Python),
            "shell" | "sh" | "bash" => Some(Language::Shell),
            "node" | "nodejs" | "javascript" => Some(Language::Node),
            _ => None,
        }
    }

    /// OCI process args for the stdin-EOF protocol: the interpreter reads
    /// the payload from stdin and exits at EOF. Deliberately non-interactive
    /// (no `-i`, no PTY) so stdout/stderr stay clean and the container
    /// terminates naturally once stdin is closed.
    pub fn sandbox_args(&self) -> Vec<String> {
        match self {
            Language::Python => vec!["python3".into(), "-".into()],
            Language::Shell => vec!["sh".into(), "-s".into()],
            Language::Node => vec!["node".into(), "-".into()],
        }
    }
}

/// Configuration for the gVisor pool.
#[derive(Debug, Clone, PartialEq)]
pub struct GvisorPoolConfig {
    /// Path to the `runsc` binary.
    pub runsc_path: String,
    /// Base directory for container bundles.
    pub bundle_root: String,
    /// Minimum number of warm containers per language.
    pub min_warm: usize,
    /// Maximum number of idle containers per language.
    pub max_idle: usize,
    /// Timeout for acquiring a warm container.
    pub acquire_timeout: Duration,
    /// Execution timeout per command.
    pub exec_timeout: Duration,
    /// Memory limit for each container (bytes).
    pub memory_limit_bytes: u64,
    /// CPU quota (number of CPUs, e.g. 1.0 = one full CPU).
    pub cpu_quota: f64,
    /// Network isolation: if true, container has no network access.
    pub network_disabled: bool,
    /// Root filesystem path for the container (e.g. an Alpine rootfs).
    pub rootfs_path: String,
    /// Directory for runsc's own runtime state (containers/sock). Must be
    /// writable by the proxy user (e.g. /tmp, not /var/run).
    pub state_root: String,
}

impl Default for GvisorPoolConfig {
    fn default() -> Self {
        Self {
            runsc_path: "/usr/local/bin/runsc".to_string(),
            bundle_root: "/tmp/virbius-gvisor".to_string(),
            min_warm: 2,
            max_idle: 5,
            acquire_timeout: Duration::from_secs(10),
            exec_timeout: Duration::from_secs(30),
            memory_limit_bytes: 256 * 1024 * 1024, // 256 MB
            cpu_quota: 1.0,
            network_disabled: true,
            rootfs_path: "/opt/virbius/rootfs".to_string(),
            state_root: "/tmp/virbius-gvisor-state".to_string(),
        }
    }
}

impl GvisorPoolConfig {
    /// Build pool config from a delivered Edge-manifest `gvisor_config`.
    ///
    /// Isolation limits come from the rule. Host paths (`runsc_path`,
    /// `rootfs_path`, `state_root`) are overlaid by `VIRBIUS_*` env vars when set.
    pub fn from_delivered_manifest(cfg: &crate::manifest::GvisorConfig) -> Self {
        let exec_timeout_ms = if cfg.exec_timeout_ms == 0 {
            30_000
        } else {
            cfg.exec_timeout_ms
        };
        let mut out = Self {
            runsc_path: cfg.runsc_path.clone(),
            bundle_root: Self::default().bundle_root,
            min_warm: cfg.min_warm,
            max_idle: cfg.max_idle,
            acquire_timeout: Self::default().acquire_timeout,
            exec_timeout: Duration::from_millis(exec_timeout_ms),
            memory_limit_bytes: cfg.memory_limit_bytes,
            cpu_quota: cfg.cpu_quota,
            network_disabled: cfg.network_disabled,
            rootfs_path: cfg.rootfs_path.clone(),
            state_root: Self::default().state_root,
        };
        overlay_host_paths(&mut out);
        out
    }
}

fn overlay_host_paths(config: &mut GvisorPoolConfig) {
    if let Ok(p) = std::env::var("VIRBIUS_RUNSC_PATH") {
        if !p.is_empty() {
            config.runsc_path = p;
        }
    }
    if let Ok(r) = std::env::var("VIRBIUS_GVISOR_ROOTFS") {
        if !r.is_empty() {
            config.rootfs_path = r;
        }
    }
    if let Ok(s) = std::env::var("VIRBIUS_GVISOR_STATE_ROOT") {
        if !s.is_empty() {
            config.state_root = s;
        }
    }
}

/// A pre-warmed container waiting for a command.
#[allow(dead_code)]
struct WarmContainer {
    id: String,
    language: Language,
    /// The child process's stdin (we write commands to it).
    stdin: Option<std::process::ChildStdin>,
    /// The child process (we read stdout/stderr from it).
    child: std::process::Child,
    created_at: Instant,
}

impl WarmContainer {
    fn is_alive(&mut self) -> bool {
        match self.child.try_wait() {
            Ok(Some(_)) => false,
            Ok(None) => true,
            Err(_) => false,
        }
    }
}

/// Result of a gVisor execution.
#[derive(Debug)]
pub struct GvisorExecResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    /// Wall-clock execution time.
    pub elapsed: Duration,
    /// Whether a warm container was reused (true) or a cold start occurred (false).
    pub warm_hit: bool,
}

/// The gVisor container pool.
///
/// Process-wide use goes through [`GvisorPool::global`], which is reconfigured
/// from the Edge manifest via [`GvisorPool::apply`]. Tests construct isolated
/// pools with [`GvisorPool::new`].
pub struct GvisorPool {
    inner: Arc<Mutex<GvisorPoolState>>,
}

struct GvisorPoolState {
    config: GvisorPoolConfig,
    warm: HashMap<Language, Vec<WarmContainer>>,
    runsc_available: bool,
    /// False when no canary/full gVisor rule is in the current manifest.
    delivered: bool,
    generation: u64,
}

/// Process-wide shared gVisor pool. Inner config is reconfigurable; do not
/// replace the `OnceLock` with a per-call `GvisorPool::new`.
static GLOBAL_GVISOR_POOL: OnceLock<GvisorPool> = OnceLock::new();
static MANIFEST_APPLIED: AtomicBool = AtomicBool::new(false);

impl GvisorPool {
    /// Get the process-wide shared gVisor pool.
    ///
    /// Starts undelivered until [`apply_from_manifest`] (or first execute)
    /// loads Edge `gvisor_config`.
    pub fn global() -> &'static GvisorPool {
        GLOBAL_GVISOR_POOL.get_or_init(GvisorPool::undelivered)
    }

    fn undelivered() -> Self {
        Self {
            inner: Arc::new(Mutex::new(GvisorPoolState {
                config: GvisorPoolConfig::default(),
                warm: HashMap::new(),
                runsc_available: false,
                delivered: false,
                generation: 0,
            })),
        }
    }

    /// Apply the current Edge-manifest gVisor config to the process-wide pool.
    pub fn apply_from_manifest() {
        let delivered = crate::manifest::gvisor_config();
        Self::global().apply(delivered);
        MANIFEST_APPLIED.store(true, Ordering::SeqCst);
    }

    /// Apply manifest config if bootstrap has not done so yet.
    pub fn apply_from_manifest_if_needed() {
        if !MANIFEST_APPLIED.load(Ordering::SeqCst) {
            Self::apply_from_manifest();
        }
    }

    /// Reconfigure this pool from a delivered manifest object.
    ///
    /// `None` drains idle containers and marks the pool unavailable.
    /// Unchanged config is a no-op (in-flight executes keep their containers).
    pub fn apply(&self, delivered: Option<crate::manifest::GvisorConfig>) {
        let mut state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        match delivered {
            None => {
                if !state.delivered {
                    return;
                }
                Self::drain_locked(&mut state);
                state.delivered = false;
                state.runsc_available = false;
                state.generation = state.generation.wrapping_add(1);
                eprintln!("virbius-gvisor: no delivered gvisor_config; pool drained");
            }
            Some(cfg) => {
                let new_config = GvisorPoolConfig::from_delivered_manifest(&cfg);
                if state.delivered && state.config == new_config {
                    state.runsc_available = Path::new(&new_config.runsc_path).exists();
                    return;
                }
                Self::drain_locked(&mut state);
                let runsc_available = Path::new(&new_config.runsc_path).exists();
                if !runsc_available {
                    eprintln!(
                        "virbius-gvisor: runsc not found at {}, pool unavailable",
                        new_config.runsc_path
                    );
                }
                state.config = new_config;
                state.delivered = true;
                state.runsc_available = runsc_available;
                state.generation = state.generation.wrapping_add(1);
            }
        }
    }

    /// Isolation limits from the currently delivered config, if any.
    pub fn delivered_limits(&self) -> Option<GvisorPoolConfig> {
        let state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if state.delivered {
            Some(state.config.clone())
        } else {
            None
        }
    }

    /// True when a gVisor rule is delivered and `runsc` exists on this host.
    pub fn is_available(&self) -> bool {
        let state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        state.delivered && state.runsc_available
    }

    fn drain_locked(state: &mut GvisorPoolState) {
        let config = state.config.clone();
        for containers in state.warm.values_mut() {
            for mut c in containers.drain(..) {
                if kill_and_wait_brief(&mut c.child).is_none() {
                    Self::delete_container_with(&config, &c.id);
                    if wait_until(&mut c.child, Instant::now() + REAP_STEP).is_none() {
                        detach_reap(c.child);
                    }
                } else {
                    Self::delete_container_with(&config, &c.id);
                }
                let _ = std::fs::remove_dir_all(PathBuf::from(&config.bundle_root).join(&c.id));
            }
        }
    }

    /// Create a new pool with the given configuration (tests / explicit hosts).
    pub fn new(config: GvisorPoolConfig) -> Self {
        let runsc_available = Path::new(&config.runsc_path).exists();
        if !runsc_available {
            eprintln!(
                "virbius-gvisor: runsc not found at {}, pool unavailable",
                config.runsc_path
            );
        }
        Self {
            inner: Arc::new(Mutex::new(GvisorPoolState {
                config,
                warm: HashMap::new(),
                runsc_available,
                delivered: true,
                generation: 0,
            })),
        }
    }

    /// Execute code in a gVisor container.
    ///
    /// stdin-EOF protocol: the payload is written to the container's stdin,
    /// which is then closed (EOF). The interpreter (python3 -/sh -s/node -)
    /// reads the script, executes it and exits; stdout/stderr reach EOF
    /// naturally. No interactive/PTY/marker protocol is involved.
    ///
    /// If a warm container is available, reuses it (hot path). Otherwise,
    /// creates a new container (cold path, 1-5s).
    /// If gVisor is not available, returns `Err` so the caller can
    /// fall back to [`super::landlock::LandlockSandbox`].
    pub fn execute(&self, language: Language, code: &str) -> Result<GvisorExecResult, String> {
        self.execute_with_timeout(language, code, None)
    }

    /// Like [`execute`](Self::execute), with an optional per-call timeout cap
    /// (from the tool registry). The pool `exec_timeout` is always an upper bound.
    pub fn execute_with_timeout(
        &self,
        language: Language,
        code: &str,
        timeout: Option<Duration>,
    ) -> Result<GvisorExecResult, String> {
        let (config, generation) = {
            let state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            if !state.delivered || !state.runsc_available {
                return Err("runsc binary not available".to_string());
            }
            (state.config.clone(), state.generation)
        };

        let timeout = timeout
            .filter(|t| *t > Duration::ZERO)
            .map(|t| t.min(config.exec_timeout))
            .unwrap_or(config.exec_timeout);

        let mut container = self.acquire_warm(language, &config)?;
        let start = Instant::now();

        // Write the payload to stdin, then close (EOF).
        let write_res = match container.stdin.take() {
            Some(mut stdin) => {
                let r = stdin.write_all(code.as_bytes());
                drop(stdin); // close even on error → EOF
                r
            }
            None => Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "stdin unavailable",
            )),
        };
        if let Err(e) = write_res {
            eprintln!("virbius-gvisor: warm container write failed ({e}), cold retry");
            let reaped = kill_and_wait_brief(&mut container.child);
            Self::delete_container_with(&config, &container.id);
            if reaped.is_none()
                && wait_until(&mut container.child, Instant::now() + REAP_STEP).is_none()
            {
                detach_reap(container.child);
                return Err(
                    "sandbox_exec_timeout: runsc did not exit after stdin write failure".into(),
                );
            }
            container = Self::create_container(&config, language).map(|c| AcquiredContainer {
                id: c.id,
                language: c.language,
                stdin: c.stdin,
                child: c.child,
                warm_hit: false,
            })?;
            let mut stdin = match container.stdin.take() {
                Some(s) => s,
                None => {
                    Self::delete_container_with(&config, &container.id);
                    abort_child(container.child);
                    return Err("stdin unavailable".into());
                }
            };
            if let Err(e) = stdin.write_all(code.as_bytes()) {
                drop(stdin);
                Self::delete_container_with(&config, &container.id);
                abort_child(container.child);
                return Err(format!("write stdin failed: {e}"));
            }
            drop(stdin); // EOF
        }

        // Drain stdout/stderr on separate threads: a child emitting more than
        // the OS pipe buffer on one stream must not deadlock the other.
        let mut child = container.child;
        let stdout = match child.stdout.take() {
            Some(s) => s,
            None => {
                Self::delete_container_with(&config, &container.id);
                abort_child(child);
                return Err("stdout unavailable".into());
            }
        };
        let stderr = match child.stderr.take() {
            Some(s) => s,
            None => {
                Self::delete_container_with(&config, &container.id);
                abort_child(child);
                return Err("stderr unavailable".into());
            }
        };
        let (out_tx, out_rx) = std::sync::mpsc::channel();
        let (err_tx, err_rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let mut buf = Vec::new();
            let mut out = stdout;
            out.read_to_end(&mut buf).ok();
            let _ = out_tx.send(buf);
        });
        std::thread::spawn(move || {
            let mut buf = Vec::new();
            let mut err = stderr;
            err.read_to_end(&mut buf).ok();
            let _ = err_tx.send(buf);
        });

        let timed_out = loop {
            match child.try_wait() {
                Ok(Some(s)) => break Ok(s),
                Ok(None) => {
                    if start.elapsed() > timeout {
                        break Err(());
                    }
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(e) => {
                    return Err(format!("wait failed: {e}"));
                }
            }
        };

        if let Err(()) = timed_out {
            let msg = Self::reap_after_exec_timeout(child, &config, &container.id, timeout);
            let _ = recv_buf_timeout(out_rx, REAP_STEP);
            let _ = recv_buf_timeout(err_rx, REAP_STEP);
            let _ = std::fs::remove_dir_all(PathBuf::from(&config.bundle_root).join(&container.id));
            self.spawn_warm_async(language, config, generation);
            return Err(msg);
        }
        let status = timed_out.unwrap();

        let stdout_buf = recv_buf_timeout(out_rx, REAP_BUDGET);
        let stderr_buf = recv_buf_timeout(err_rx, REAP_BUDGET);

        Self::delete_container_with(&config, &container.id);
        let _ = std::fs::remove_dir_all(PathBuf::from(&config.bundle_root).join(&container.id));

        let elapsed = start.elapsed();
        let warm_hit = container.warm_hit;

        self.spawn_warm_async(language, config, generation);

        Ok(GvisorExecResult {
            stdout: String::from_utf8_lossy(&stdout_buf).into_owned(),
            stderr: String::from_utf8_lossy(&stderr_buf).into_owned(),
            exit_code: status.code().unwrap_or(-1),
            elapsed,
            warm_hit,
        })
    }

    fn reap_after_exec_timeout(
        mut child: std::process::Child,
        config: &GvisorPoolConfig,
        id: &str,
        timeout: Duration,
    ) -> String {
        let msg = format!("sandbox_exec_timeout after {}ms", timeout.as_millis());
        if kill_and_wait_brief(&mut child).is_some() {
            Self::delete_container_with(config, id);
            return msg;
        }
        Self::delete_container_with(config, id);
        if wait_until(&mut child, Instant::now() + REAP_STEP).is_some() {
            return msg;
        }
        detach_reap(child);
        msg
    }

    /// Acquire a warm container from the pool, or create a new one.
    fn acquire_warm(
        &self,
        language: Language,
        config: &GvisorPoolConfig,
    ) -> Result<AcquiredContainer, String> {
        let deadline = Instant::now() + config.acquire_timeout;

        loop {
            {
                let mut state = self.inner.lock().map_err(|e| format!("pool lock: {e}"))?;
                let containers = state.warm.entry(language).or_default();
                containers.retain_mut(|c| c.is_alive());
                if let Some(mut container) = containers.pop() {
                    return Ok(AcquiredContainer {
                        id: container.id,
                        language: container.language,
                        stdin: container.stdin.take(),
                        child: container.child,
                        warm_hit: true,
                    });
                }
            }

            if Instant::now() >= deadline {
                return Self::create_container(config, language).map(|c| AcquiredContainer {
                    id: c.id,
                    language: c.language,
                    stdin: c.stdin,
                    child: c.child,
                    warm_hit: false,
                });
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    /// Create a new gVisor container.
    fn create_container(
        config: &GvisorPoolConfig,
        language: Language,
    ) -> Result<WarmContainer, String> {
        let container_id = format!("virbius-{}-{}", language.as_str(), uuid_v4_short());
        let bundle_dir = PathBuf::from(&config.bundle_root).join(&container_id);
        std::fs::create_dir_all(&bundle_dir).map_err(|e| format!("create bundle dir: {e}"))?;

        let config_json = Self::oci_config_json(config, language);
        let config_path = bundle_dir.join("config.json");
        std::fs::write(&config_path, &config_json)
            .map_err(|e| format!("write config.json: {e}"))?;

        let mut child = Command::new(&config.runsc_path)
            .arg("--root")
            .arg(&config.state_root)
            .arg("--ignore-cgroups")
            .arg("run")
            .arg("--bundle")
            .arg(&bundle_dir)
            .arg(&container_id)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| {
                let _ = std::fs::remove_dir_all(&bundle_dir);
                format!("runsc spawn failed: {e}")
            })?;

        let stdin = child.stdin.take();
        Ok(WarmContainer {
            id: container_id,
            language,
            stdin,
            child,
            created_at: Instant::now(),
        })
    }

    /// Build the OCI runtime configuration JSON for a container.
    pub fn build_oci_config(&self, language: Language) -> String {
        let config = self
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .config
            .clone();
        Self::oci_config_json(&config, language)
    }

    fn oci_config_json(config: &GvisorPoolConfig, language: Language) -> String {
        let mem_limit = config.memory_limit_bytes;
        let cpu_quota = config.cpu_quota;
        let _network_str = if config.network_disabled {
            "none"
        } else {
            "bridge"
        };

        serde_json::json!({
            "ociVersion": "1.0.2",
            "process": {
                "terminal": false,
                "user": { "uid": 1000, "gid": 1000 },
                "args": language.sandbox_args(),
                "env": [
                    "PATH=/usr/local/bin:/usr/bin:/bin",
                    "HOME=/tmp",
                    "LANG=en_US.UTF-8"
                ],
                "cwd": "/tmp",
                "capabilities": {
                    "bounding": [],
                    "effective": [],
                    "inheritable": [],
                    "permitted": [],
                    "ambient": []
                },
                "noNewPrivileges": true
            },
            "root": {
                "path": config.rootfs_path,
                "readonly": false
            },
            "hostname": "sandbox",
            "linux": {
                "resources": {
                    "memory": { "limit": mem_limit, "swap": mem_limit },
                    "cpu": { "quota": (cpu_quota * 100000.0) as i64, "period": 100000 }
                },
                "namespaces": [
                    { "type": "pid" },
                    { "type": "ipc" },
                    { "type": "uts" },
                    { "type": "mount" },
                    { "type": "network" }
                ]
            }
        })
        .to_string()
    }

    /// Spawn a warm container in the background to replenish the pool.
    fn spawn_warm_async(&self, language: Language, config: GvisorPoolConfig, generation: u64) {
        let inner = Arc::clone(&self.inner);
        std::thread::spawn(move || {
            let created = match Self::create_container(&config, language) {
                Ok(c) => c,
                Err(_) => return,
            };
            let mut state = inner.lock().unwrap_or_else(|e| e.into_inner());
            if state.generation != generation || !state.delivered {
                let mut c = created;
                if kill_and_wait_brief(&mut c.child).is_none() {
                    Self::delete_container_with(&config, &c.id);
                    if wait_until(&mut c.child, Instant::now() + REAP_STEP).is_none() {
                        detach_reap(c.child);
                    }
                } else {
                    Self::delete_container_with(&config, &c.id);
                }
                let _ = std::fs::remove_dir_all(PathBuf::from(&config.bundle_root).join(&c.id));
                return;
            }
            let max_idle = state.config.max_idle;
            let containers = state.warm.entry(language).or_default();
            if containers.len() < max_idle {
                containers.push(created);
            } else {
                drop(state);
                let mut c = created;
                if kill_and_wait_brief(&mut c.child).is_none() {
                    Self::delete_container_with(&config, &c.id);
                    if wait_until(&mut c.child, Instant::now() + REAP_STEP).is_none() {
                        detach_reap(c.child);
                    }
                } else {
                    Self::delete_container_with(&config, &c.id);
                }
                let _ = std::fs::remove_dir_all(PathBuf::from(&config.bundle_root).join(&c.id));
            }
        });
    }

    /// Ensure the pool has at least `min_warm` containers per language.
    /// Call this on startup or periodically.
    pub fn ensure_warm(&self, languages: &[Language]) {
        let (config, generation) = {
            let state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            if !state.delivered {
                return;
            }
            (state.config.clone(), state.generation)
        };
        for &lang in languages {
            let current = {
                let state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
                state.warm.get(&lang).map(|v| v.len()).unwrap_or(0)
            };
            for _ in current..config.min_warm {
                if let Ok(container) = Self::create_container(&config, lang) {
                    let mut state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
                    if state.generation != generation || !state.delivered {
                        drop(state);
                        let mut c = container;
                        if kill_and_wait_brief(&mut c.child).is_none() {
                            Self::delete_container_with(&config, &c.id);
                            if wait_until(&mut c.child, Instant::now() + REAP_STEP).is_none() {
                                detach_reap(c.child);
                            }
                        } else {
                            Self::delete_container_with(&config, &c.id);
                        }
                        break;
                    }
                    state.warm.entry(lang).or_default().push(container);
                }
            }
        }
    }

    /// Shutdown all containers (cleanup on exit).
    pub fn shutdown(&self) {
        let mut state = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        Self::drain_locked(&mut state);
        state.delivered = false;
        state.runsc_available = false;
        state.generation = state.generation.wrapping_add(1);
    }

    /// Best-effort removal of runsc's runtime state for a container.
    fn delete_container_with(config: &GvisorPoolConfig, id: &str) {
        let mut delete = Command::new(&config.runsc_path);
        delete
            .arg("--root")
            .arg(&config.state_root)
            .arg("--ignore-cgroups")
            .arg("delete")
            .arg("--force")
            .arg(id);
        if let Err(e) = run_command_timed(delete, REAP_STEP) {
            eprintln!("virbius-gvisor: delete container {id} failed: {e}");
        }
    }

    #[cfg(test)]
    fn generation(&self) -> u64 {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .generation
    }
}

impl Drop for GvisorPool {
    fn drop(&mut self) {
        self.shutdown();
    }
}

/// Internal: a container acquired from the pool (or freshly created).
#[allow(dead_code)]
struct AcquiredContainer {
    id: String,
    language: Language,
    stdin: Option<std::process::ChildStdin>,
    child: std::process::Child,
    warm_hit: bool,
}

/// Generate a short UUID-like string for container IDs.
fn uuid_v4_short() -> String {
    use std::time::SystemTime;
    let now = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("{:x}", now)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_language_from_str() {
        assert_eq!(Language::parse("python"), Some(Language::Python));
        assert_eq!(Language::parse("PYTHON3"), Some(Language::Python));
        assert_eq!(Language::parse("bash"), Some(Language::Shell));
        assert_eq!(Language::parse("nodejs"), Some(Language::Node));
        assert_eq!(Language::parse("ruby"), None);
    }

    #[test]
    fn test_default_config() {
        let config = GvisorPoolConfig::default();
        assert_eq!(config.min_warm, 2);
        assert_eq!(config.max_idle, 5);
        assert!(config.network_disabled);
        assert_eq!(config.memory_limit_bytes, 256 * 1024 * 1024);
    }

    #[test]
    fn test_oci_config_contains_security_settings() {
        let pool = GvisorPool::new(GvisorPoolConfig::default());
        let config = pool.build_oci_config(Language::Python);
        let parsed: serde_json::Value = serde_json::from_str(&config).unwrap();

        // stdin-EOF protocol: interpreter reads the script from stdin.
        assert_eq!(
            parsed["process"]["args"],
            serde_json::json!(["python3", "-"]),
            "args should use stdin mode"
        );
        // Non-interactive: no PTY.
        assert_eq!(parsed["process"]["terminal"].as_bool(), Some(false));
        // noNewPrivileges should be true.
        assert_eq!(parsed["process"]["noNewPrivileges"].as_bool(), Some(true));
        // Capabilities should be empty.
        assert_eq!(
            parsed["process"]["capabilities"]["bounding"]
                .as_array()
                .unwrap()
                .len(),
            0
        );
        // Memory limit should be set.
        assert!(
            parsed["linux"]["resources"]["memory"]["limit"]
                .as_u64()
                .unwrap()
                > 0
        );
    }

    #[test]
    fn test_sandbox_args_per_language() {
        assert_eq!(Language::Python.sandbox_args(), vec!["python3", "-"]);
        assert_eq!(Language::Shell.sandbox_args(), vec!["sh", "-s"]);
        assert_eq!(Language::Node.sandbox_args(), vec!["node", "-"]);
    }

    #[test]
    fn test_pool_creation_does_not_crash() {
        let _pool = GvisorPool::new(GvisorPoolConfig::default());
        // runsc likely not present in test environment — should not crash.
    }

    fn sample_manifest_config() -> crate::manifest::GvisorConfig {
        crate::manifest::GvisorConfig {
            runsc_path: "/nonexistent/runsc".into(),
            rootfs_path: "/opt/virbius/rootfs".into(),
            min_warm: 3,
            max_idle: 7,
            memory_limit_bytes: 64 * 1024 * 1024,
            cpu_quota: 0.5,
            network_disabled: false,
            exec_timeout_ms: 5_000,
        }
    }

    #[test]
    fn apply_none_marks_pool_unavailable() {
        let pool = GvisorPool::new(GvisorPoolConfig::default());
        pool.apply(None);
        assert!(!pool.is_available());
        assert!(pool.delivered_limits().is_none());
    }

    #[test]
    fn apply_same_config_does_not_bump_generation() {
        let pool = GvisorPool::undelivered();
        let cfg = sample_manifest_config();
        pool.apply(Some(cfg.clone()));
        let gen = pool.generation();
        pool.apply(Some(cfg));
        assert_eq!(pool.generation(), gen);
        let limits = pool.delivered_limits().expect("delivered");
        assert_eq!(limits.memory_limit_bytes, 64 * 1024 * 1024);
        assert!(!limits.network_disabled);
        assert_eq!(limits.min_warm, 3);
        assert_eq!(limits.exec_timeout, Duration::from_millis(5_000));
        assert!(!pool.is_available(), "runsc path does not exist");
    }

    #[test]
    fn apply_changed_limits_bumps_generation() {
        let pool = GvisorPool::undelivered();
        let mut cfg = sample_manifest_config();
        pool.apply(Some(cfg.clone()));
        let gen = pool.generation();
        cfg.memory_limit_bytes = 32 * 1024 * 1024;
        pool.apply(Some(cfg));
        assert_eq!(pool.generation(), gen + 1);
        assert_eq!(
            pool.delivered_limits().unwrap().memory_limit_bytes,
            32 * 1024 * 1024
        );
    }

    #[test]
    fn from_delivered_manifest_overlays_host_paths() {
        let cfg = sample_manifest_config();
        let built = GvisorPoolConfig::from_delivered_manifest(&cfg);
        assert_eq!(built.memory_limit_bytes, 64 * 1024 * 1024);
        assert_eq!(built.cpu_quota, 0.5);
        assert!(!built.network_disabled);
        // Without env overlay the rule path is used.
        if std::env::var("VIRBIUS_RUNSC_PATH")
            .ok()
            .filter(|s| !s.is_empty())
            .is_none()
        {
            assert_eq!(built.runsc_path, "/nonexistent/runsc");
        }
    }

    #[test]
    fn oci_config_uses_applied_memory_limit() {
        let mut config = GvisorPoolConfig::default();
        config.memory_limit_bytes = 42 * 1024 * 1024;
        let pool = GvisorPool::new(config);
        let parsed: serde_json::Value =
            serde_json::from_str(&pool.build_oci_config(Language::Python)).unwrap();
        assert_eq!(
            parsed["linux"]["resources"]["memory"]["limit"].as_u64(),
            Some(42 * 1024 * 1024)
        );
    }
}
