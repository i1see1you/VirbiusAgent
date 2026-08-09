//! Sandbox module: Landlock file-path isolation + gVisor untrusted-code sandbox.
//!
//! The [`execute`] function is the main entry point.  It selects the
//! appropriate sandbox type based on the tool policy and falls back
//! gracefully when features are unavailable.

#[cfg(target_os = "linux")]
pub mod gvisor_pool;
#[cfg(target_os = "linux")]
pub mod landlock;

#[cfg(target_os = "linux")]
pub use gvisor_pool::{GvisorExecResult, GvisorPool, GvisorPoolConfig, Language};
#[cfg(target_os = "linux")]
pub use landlock::{
    check_landlock_availability, detect_abi_version, execute_sandboxed, LandlockAbi, LandlockRules,
    LandlockSandbox, SandboxConfig, SandboxResult,
};

/// Type of sandbox to use for a tool execution.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SandboxType {
    /// No sandboxing — same-process execution (P0 default).
    None,
    /// Landlock + drop caps subprocess (P2, for file-based tools).
    Landlock,
    /// gVisor container (P2, for untrusted code execution).
    Gvisor,
}

impl SandboxType {
    pub fn parse(s: &str) -> Self {
        match s.to_lowercase().as_str() {
            "landlock" | "subprocess" => SandboxType::Landlock,
            "gvisor" => SandboxType::Gvisor,
            _ => SandboxType::None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            SandboxType::None => "none",
            SandboxType::Landlock => "landlock",
            SandboxType::Gvisor => "gvisor",
        }
    }
}

/// Result of a sandboxed execution (shared between Landlock and gVisor paths).
#[derive(Debug)]
pub struct ExecutionResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    /// Which sandbox was actually used.
    pub sandbox_used: SandboxType,
    /// Whether a degraded path was taken (e.g. Landlock unavailable, gVisor unavailable).
    pub degraded: bool,
    /// Human-readable note about degradation (if any).
    pub degrade_note: Option<String>,
    /// Whether Landlock file/network restrictions were actually applied
    /// (reported by the child process via self-pipe, not inferred from ABI).
    pub landlock_applied: bool,
}

impl ExecutionResult {
    pub fn is_success(&self) -> bool {
        self.exit_code == 0
    }
}

/// Non-Linux stub for LandlockRules.
#[cfg(not(target_os = "linux"))]
#[derive(Debug, Clone)]
pub struct LandlockRules {}

#[cfg(not(target_os = "linux"))]
impl Default for LandlockRules {
    fn default() -> Self {
        Self {}
    }
}

/// Non-Linux stub for GvisorPoolConfig.
#[cfg(not(target_os = "linux"))]
#[derive(Debug, Clone)]
pub struct GvisorPoolConfig {}

#[cfg(not(target_os = "linux"))]
impl Default for GvisorPoolConfig {
    fn default() -> Self {
        Self {}
    }
}

/// Configuration for executing a tool in a sandbox.
#[derive(Debug, Clone)]
pub struct ExecutionRequest {
    /// Sandbox type to use.
    pub sandbox_type: SandboxType,
    /// Program to execute (e.g. "cat", "python3", "sh").
    pub program: String,
    /// Arguments to pass to the program.
    pub args: Vec<String>,
    /// Landlock rules (used when sandbox_type = Landlock).
    pub landlock_rules: LandlockRules,
    /// Execution timeout.
    pub timeout: std::time::Duration,
    /// gVisor pool config (used when sandbox_type = Gvisor).
    pub gvisor_config: Option<GvisorPoolConfig>,
    /// Language for gVisor execution (used when sandbox_type = Gvisor).
    pub gvisor_language: Option<Language>,
}

impl Default for ExecutionRequest {
    fn default() -> Self {
        Self {
            sandbox_type: SandboxType::None,
            program: String::new(),
            args: Vec::new(),
            landlock_rules: LandlockRules::default(),
            timeout: std::time::Duration::from_secs(30),
            gvisor_config: None,
            gvisor_language: None,
        }
    }
}

/// Execute a tool in the appropriate sandbox.
///
/// ## Selection logic
///
/// 1. **SandboxType::None** — returns `Err` (caller should execute in-process).
/// 2. **SandboxType::Landlock** — spawns subprocess with Landlock + drop caps.
///    If Landlock is unavailable, degrades to "drop caps only" and sets `degraded=true`.
/// 3. **SandboxType::Gvisor** — executes in a gVisor container.
///    If `runsc` is not available, **automatically degrades to Landlock** sandbox
///    with timeout=5s.  If Landlock is also unavailable, returns `Err`.
#[cfg(target_os = "linux")]
pub fn execute(req: ExecutionRequest) -> Result<ExecutionResult, String> {
    match req.sandbox_type {
        SandboxType::None => Err("no sandbox requested, execute in-process".to_string()),

        SandboxType::Landlock => {
            let config = SandboxConfig {
                rules: req.landlock_rules,
                timeout: req.timeout,
                ..SandboxConfig::default()
            };
            let sandbox = LandlockSandbox::new(config);
            let abi = detect_abi_version();
            let result = sandbox.execute(&req.program, &req.args)?;

            let degraded = !abi.supports_file();
            let degrade_note = if degraded {
                Some(format!(
                    "Landlock not available (ABI={:?}), executed with drop-caps only",
                    abi
                ))
            } else {
                None
            };

            Ok(ExecutionResult {
                stdout: result.stdout,
                stderr: result.stderr,
                exit_code: result.exit_code,
                sandbox_used: SandboxType::Landlock,
                degraded,
                degrade_note,
                landlock_applied: result.landlock_applied,
            })
        }

        SandboxType::Gvisor => {
            // Try gVisor first using the shared global pool.
            let pool = GvisorPool::global();

            if pool.is_available() {
                let language = req.gvisor_language.unwrap_or(Language::Shell);
                let result = pool.execute(language, &req.program)?;
                return Ok(ExecutionResult {
                    stdout: result.stdout,
                    stderr: result.stderr,
                    exit_code: result.exit_code,
                    sandbox_used: SandboxType::Gvisor,
                    degraded: false,
                    degrade_note: None,
                    landlock_applied: false,
                });
            }

            // gVisor not available → degrade to Landlock with stricter limits.
            let degraded_config = SandboxConfig {
                rules: req.landlock_rules,
                timeout: std::time::Duration::min(req.timeout, std::time::Duration::from_secs(5)),
                ..SandboxConfig::default()
            };
            let sandbox = LandlockSandbox::new(degraded_config);
            let result = sandbox.execute(&req.program, &req.args)?;

            Ok(ExecutionResult {
                stdout: result.stdout,
                stderr: result.stderr,
                exit_code: result.exit_code,
                sandbox_used: SandboxType::Landlock,
                degraded: true,
                degrade_note: Some(
                    "gVisor unavailable, degraded to Landlock sandbox (timeout=5s)".to_string(),
                ),
                landlock_applied: result.landlock_applied,
            })
        }
    }
}

/// Unsandboxed subprocess execution (P0, sandbox_type="none").
///
/// Spawns the interpreter without any file/network/capability isolation.
/// Applies hard-wall-clock timeout (SIGKILL) and RLIMIT_AS/RLIMIT_CPU
/// to mitigate memory exhaustion / fork bombs.
///
/// Only local code-execution tools with `sandbox_type=none` and the
/// `VIRBIUS_ALLOW_UNSANDBOXED` gate open will reach this path.
#[cfg(target_os = "linux")]
pub fn run_unsandboxed(
    language: Language,
    code: &str,
    timeout_ms: u64,
) -> Result<ExecutionResult, String> {
    use std::io::Read;
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};

    let interpreter = language.interpreter();
    let flag = match language {
        Language::Node => "-e",
        _ => "-c",
    };

    let mem_limit: u64 = match language {
        Language::Node => 512 * 1024 * 1024, // V8 needs larger CodeRange reservation
        _               => 256 * 1024 * 1024,
    };
    let cpu_limit = (timeout_ms / 1000).max(2); // RLIMIT_CPU seconds

    let mut cmd = Command::new(interpreter);
    cmd.arg(flag)
        .arg(code)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    unsafe {
        cmd.pre_exec(move || {
            let rlim = libc::rlimit {
                rlim_cur: mem_limit,
                rlim_max: mem_limit,
            };
            libc::setrlimit(libc::RLIMIT_AS, &rlim);
            let cpu = libc::rlimit {
                rlim_cur: cpu_limit,
                rlim_max: cpu_limit,
            };
            libc::setrlimit(libc::RLIMIT_CPU, &cpu);
            Ok(())
        });
    }

    let mut child = cmd.spawn().map_err(|e| format!("spawn failed: {e}"))?;
    let timeout = std::time::Duration::from_millis(timeout_ms);
    let start = std::time::Instant::now();

    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let mut stdout = String::new();
                let mut stderr = String::new();
                if let Some(mut s) = child.stdout.take() {
                    s.read_to_string(&mut stdout).ok();
                }
                if let Some(mut s) = child.stderr.take() {
                    s.read_to_string(&mut stderr).ok();
                }
                return Ok(ExecutionResult {
                    stdout,
                    stderr,
                    exit_code: status.code().unwrap_or(-1),
                    sandbox_used: SandboxType::None,
                    degraded: false,
                    degrade_note: None,
                    landlock_applied: false,
                });
            }
            Ok(None) => {
                if start.elapsed() > timeout {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!(
                        "unsandboxed exec timed out after {}ms",
                        timeout_ms
                    ));
                }
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            Err(e) => return Err(format!("wait failed: {e}")),
        }
    }
}

/// Supported sandboxed languages (non-Linux variant).
///
/// Mirrors the enum defined in the Linux-gated [`gvisor_pool`] module so that
/// `run_unsandboxed` works on macOS/Windows for local development.
#[cfg(not(target_os = "linux"))]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Language {
    Python,
    Shell,
    Node,
}

#[cfg(not(target_os = "linux"))]
impl Language {
    pub fn as_str(&self) -> &'static str {
        match self {
            Language::Python => "python",
            Language::Shell => "shell",
            Language::Node => "node",
        }
    }

    /// The interpreter binary to spawn.
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
}

/// Non-Linux stub: full sandbox features (landlock/gvisor) are unavailable.
#[cfg(not(target_os = "linux"))]
pub fn execute(_req: ExecutionRequest) -> Result<ExecutionResult, String> {
    Err("sandbox not available on this platform (Linux only)".to_string())
}

/// Non-Linux implementation of unsandboxed execution: spawns the interpreter
/// with wall-clock timeout. No rlimit — per-process memory/CPU caps are
/// Linux-only; the caller (proxy) already gates this with
/// `VIRBIUS_ALLOW_UNSANDBOXED`.
#[cfg(not(target_os = "linux"))]
pub fn run_unsandboxed(
    language: Language,
    code: &str,
    timeout_ms: u64,
) -> Result<ExecutionResult, String> {
    use std::io::Read;
    use std::process::{Command, Stdio};

    let interpreter = language.interpreter();
    let flag = match language {
        Language::Node => "-e",
        _ => "-c",
    };

    let mut cmd = Command::new(interpreter);
    cmd.arg(flag)
        .arg(code)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("spawn failed: {e}"))?;
    let timeout = std::time::Duration::from_millis(timeout_ms);
    let start = std::time::Instant::now();

    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let mut stdout = String::new();
                let mut stderr = String::new();
                if let Some(mut s) = child.stdout.take() {
                    s.read_to_string(&mut stdout).ok();
                }
                if let Some(mut s) = child.stderr.take() {
                    s.read_to_string(&mut stderr).ok();
                }
                return Ok(ExecutionResult {
                    stdout,
                    stderr,
                    exit_code: status.code().unwrap_or(-1),
                    sandbox_used: SandboxType::None,
                    degraded: false,
                    degrade_note: None,
                    landlock_applied: false,
                });
            }
            Ok(None) => {
                if start.elapsed() > timeout {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!(
                        "unsandboxed exec timed out after {}ms",
                        timeout_ms
                    ));
                }
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            Err(e) => return Err(format!("wait failed: {e}")),
        }
    }
}

#[cfg(target_os = "linux")]
impl GvisorPool {
    /// Check if the pool is functional (runsc binary exists).
    pub fn is_available(&self) -> bool {
        self.runsc_available
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sandbox_type_from_str() {
        assert_eq!(SandboxType::parse("none"), SandboxType::None);
        assert_eq!(SandboxType::parse("landlock"), SandboxType::Landlock);
        assert_eq!(SandboxType::parse("gvisor"), SandboxType::Gvisor);
        assert_eq!(SandboxType::parse("subprocess"), SandboxType::Landlock);
        assert_eq!(SandboxType::parse(""), SandboxType::None);
    }

    #[test]
    fn test_sandbox_type_as_str() {
        assert_eq!(SandboxType::None.as_str(), "none");
        assert_eq!(SandboxType::Landlock.as_str(), "landlock");
        assert_eq!(SandboxType::Gvisor.as_str(), "gvisor");
    }

    #[test]
    fn test_execution_result_success() {
        let r = ExecutionResult {
            stdout: "ok".into(),
            stderr: "".into(),
            exit_code: 0,
            sandbox_used: SandboxType::None,
            degraded: false,
            degrade_note: None,
            landlock_applied: false,
        };
        assert!(r.is_success());
    }

    #[test]
    fn test_execution_result_failure() {
        let r = ExecutionResult {
            stdout: "".into(),
            stderr: "error".into(),
            exit_code: 1,
            sandbox_used: SandboxType::None,
            degraded: false,
            degrade_note: None,
            landlock_applied: false,
        };
        assert!(!r.is_success());
    }

    #[test]
    fn test_execution_request_default() {
        let req = ExecutionRequest::default();
        assert_eq!(req.sandbox_type, SandboxType::None);
        assert_eq!(req.timeout, std::time::Duration::from_secs(30));
    }
}
