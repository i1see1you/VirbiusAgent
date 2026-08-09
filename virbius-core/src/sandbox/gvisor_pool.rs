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

use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
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
#[derive(Debug, Clone)]
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
/// Thread-safe via internal `Mutex`.  The pool is lazily initialized on
/// first `execute` call.
pub struct GvisorPool {
    config: GvisorPoolConfig,
    warm: Arc<Mutex<HashMap<Language, Vec<WarmContainer>>>>,
    /// Whether `runsc` is available on this host.
    pub(crate) runsc_available: bool,
}

/// Process-wide shared gVisor pool, lazily initialized on first use.
/// Every call to `execute` reuses the same warm-container pool, avoiding
/// per-call cold-start latency and checking `runsc` availability once.
static GLOBAL_GVISOR_POOL: OnceLock<GvisorPool> = OnceLock::new();

impl GvisorPool {
    /// Get the process-wide shared gVisor pool, initializing it on first access.
    ///
    /// Configuration is taken from defaults; set `VIRBIUS_RUNSC_PATH`,
    /// `VIRBIUS_GVISOR_ROOTFS`, and `VIRBIUS_GVISOR_MIN_WARM` to override.
    pub fn global() -> &'static GvisorPool {
        GLOBAL_GVISOR_POOL.get_or_init(|| {
            let mut config = GvisorPoolConfig::default();
            if let Ok(p) = std::env::var("VIRBIUS_RUNSC_PATH") {
                config.runsc_path = p;
            }
            if let Ok(r) = std::env::var("VIRBIUS_GVISOR_ROOTFS") {
                config.rootfs_path = r;
            }
            if let Ok(m) = std::env::var("VIRBIUS_GVISOR_MIN_WARM") {
                if let Ok(n) = m.parse() {
                    config.min_warm = n;
                }
            }
            if let Ok(s) = std::env::var("VIRBIUS_GVISOR_STATE_ROOT") {
                if !s.is_empty() {
                    config.state_root = s;
                }
            }
            GvisorPool::new(config)
        })
    }

    /// Create a new pool with the given configuration.
    pub fn new(config: GvisorPoolConfig) -> Self {
        let runsc_available = Path::new(&config.runsc_path).exists();
        if !runsc_available {
            eprintln!(
                "virbius-gvisor: runsc not found at {}, pool will degrade to Landlock sandbox",
                config.runsc_path
            );
        }
        Self {
            config,
            warm: Arc::new(Mutex::new(HashMap::new())),
            runsc_available,
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
        if !self.runsc_available {
            return Err("runsc binary not available".to_string());
        }

        let mut container = self.acquire_warm(language)?;
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
            // The warm container was dead (EPIPE). Destroy it and retry once
            // with a fresh cold container before giving up.
            eprintln!("virbius-gvisor: warm container write failed ({e}), cold retry");
            let _ = container.child.kill();
            let _ = container.child.wait();
            self.delete_container(&container.id);
            container = self.create_container(language).map(|c| AcquiredContainer {
                id: c.id,
                language: c.language,
                stdin: c.stdin,
                child: c.child,
                warm_hit: false,
            })?;
            let mut stdin = container.stdin.take().ok_or("stdin unavailable")?;
            stdin
                .write_all(code.as_bytes())
                .map_err(|e| format!("write stdin failed: {e}"))?;
            drop(stdin); // EOF
        }

        // Drain stdout/stderr on separate threads: a child emitting more than
        // the OS pipe buffer on one stream must not deadlock the other.
        let mut child = container.child;
        let stdout = child.stdout.take().ok_or("stdout unavailable")?;
        let stderr = child.stderr.take().ok_or("stderr unavailable")?;
        let out_handle = std::thread::spawn(move || {
            let mut buf = Vec::new();
            let mut out = stdout;
            out.read_to_end(&mut buf).ok();
            buf
        });
        let err_handle = std::thread::spawn(move || {
            let mut buf = Vec::new();
            let mut err = stderr;
            err.read_to_end(&mut buf).ok();
            buf
        });

        // Wait for exit with a hard deadline. Unlike a blocking read, this
        // loop always observes the timeout: on expiry the runsc process is
        // killed, which closes the pipes and unblocks the reader threads.
        let timeout = self.config.exec_timeout;
        let status = loop {
            match child.try_wait() {
                Ok(Some(s)) => break Ok(s),
                Ok(None) => {
                    if start.elapsed() > timeout {
                        let _ = child.kill();
                        let _ = child.wait();
                        break Err(format!(
                            "gVisor exec timeout after {}ms",
                            timeout.as_millis()
                        ));
                    }
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(e) => break Err(format!("wait failed: {e}")),
            }
        };

        // Pipes hit EOF once runsc is gone → reader threads finish.
        let stdout_buf = out_handle.join().unwrap_or_default();
        let stderr_buf = err_handle.join().unwrap_or_default();

        // One-shot model: the container is spent. `runsc run` leaves state
        // behind after exit, so explicitly delete it and drop the bundle.
        self.delete_container(&container.id);
        let _ = std::fs::remove_dir_all(PathBuf::from(&self.config.bundle_root).join(&container.id));

        let elapsed = start.elapsed();
        let warm_hit = container.warm_hit;

        // Spawn a replacement in the background.
        self.spawn_warm_async(language);

        let status = status?;
        Ok(GvisorExecResult {
            stdout: String::from_utf8_lossy(&stdout_buf).into_owned(),
            stderr: String::from_utf8_lossy(&stderr_buf).into_owned(),
            exit_code: status.code().unwrap_or(-1),
            elapsed,
            warm_hit,
        })
    }

    /// Acquire a warm container from the pool, or create a new one.
    fn acquire_warm(&self, language: Language) -> Result<AcquiredContainer, String> {
        let deadline = Instant::now() + self.config.acquire_timeout;

        loop {
            {
                let mut pool = self.warm.lock().map_err(|e| format!("pool lock: {e}"))?;
                let containers = pool.entry(language).or_default();
                // Remove dead containers.
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
                // No warm container available; create a cold one.
                return self.create_container(language).map(|c| AcquiredContainer {
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
    fn create_container(&self, language: Language) -> Result<WarmContainer, String> {
        let container_id = format!("virbius-{}-{}", language.as_str(), uuid_v4_short());
        let bundle_dir = PathBuf::from(&self.config.bundle_root).join(&container_id);
        std::fs::create_dir_all(&bundle_dir).map_err(|e| format!("create bundle dir: {e}"))?;

        // Write config.json (OCI runtime spec).
        let config_json = self.build_oci_config(language);
        let config_path = bundle_dir.join("config.json");
        std::fs::write(&config_path, &config_json)
            .map_err(|e| format!("write config.json: {e}"))?;

        // Spawn the container process.
        // runsc runs the process and keeps it alive reading from stdin.
        let mut child = Command::new(&self.config.runsc_path)
            .arg("--root")
            .arg(&self.config.state_root)
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
        let mem_limit = self.config.memory_limit_bytes;
        let cpu_quota = self.config.cpu_quota;
        // Network namespace type: "none" (isolated) or "bridge" (default).
        // Currently always emitted as a network namespace; the value is kept
        // for future conditional logic (e.g. omit network namespace entirely).
        let _network_str = if self.config.network_disabled {
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
                "path": self.config.rootfs_path,
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
    fn spawn_warm_async(&self, language: Language) {
        let config = self.config.clone();
        let warm = Arc::clone(&self.warm);
        std::thread::spawn(move || {
            let pool_inner = GvisorPool {
                config,
                warm,
                runsc_available: true,
            };
            if let Ok(container) = pool_inner.create_container(language) {
                let mut pool = pool_inner.warm.lock().unwrap();
                let containers = pool.entry(language).or_default();
                if containers.len() < pool_inner.config.max_idle {
                    containers.push(container);
                }
            }
        });
    }

    /// Ensure the pool has at least `min_warm` containers per language.
    /// Call this on startup or periodically.
    pub fn ensure_warm(&self, languages: &[Language]) {
        for &lang in languages {
            let pool = self.warm.lock().unwrap();
            let current = pool.get(&lang).map(|v| v.len()).unwrap_or(0);
            drop(pool);
            for _ in current..self.config.min_warm {
                if let Ok(container) = self.create_container(lang) {
                    let mut pool = self.warm.lock().unwrap();
                    pool.entry(lang).or_default().push(container);
                }
            }
        }
    }

    /// Shutdown all containers (cleanup on exit).
    pub fn shutdown(&self) {
        let mut pool = self.warm.lock().unwrap();
        for containers in pool.values_mut() {
            for c in containers {
                let _ = c.child.kill();
                let _ = c.child.wait();
                self.delete_container(&c.id);
                let _ = std::fs::remove_dir_all(
                    PathBuf::from(&self.config.bundle_root).join(&c.id),
                );
            }
        }
        pool.clear();
    }

    /// Best-effort removal of runsc's runtime state for a container.
    /// `runsc run` leaves container state behind after exit; without this the
    /// state directory grows unboundedly with stale `.state`/`.sock` files.
    fn delete_container(&self, id: &str) {
        let out = Command::new(&self.config.runsc_path)
            .arg("--root")
            .arg(&self.config.state_root)
            .arg("--ignore-cgroups")
            .arg("delete")
            .arg("--force")
            .arg(id)
            .output();
        if let Ok(o) = out {
            if !o.status.success() {
                eprintln!(
                    "virbius-gvisor: delete container {id} failed: {}",
                    String::from_utf8_lossy(&o.stderr)
                );
            }
        }
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
}
