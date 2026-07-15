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
    detect_abi_version, execute_sandboxed, check_landlock_availability,
    LandlockAbi, LandlockRules, LandlockSandbox, SandboxConfig, SandboxResult,
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
    pub fn from_str(s: &str) -> Self {
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
}

impl ExecutionResult {
    pub fn is_success(&self) -> bool {
        self.exit_code == 0
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
            })
        }

        SandboxType::Gvisor => {
            // Try gVisor first.
            let gvisor_config = req.gvisor_config.clone().unwrap_or_default();
            let pool = GvisorPool::new(gvisor_config);

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
            })
        }
    }
}

/// Non-Linux stub: sandbox features are unavailable on macOS/Windows.
#[cfg(not(target_os = "linux"))]
pub fn execute(_req: ExecutionRequest) -> Result<ExecutionResult, String> {
    Err("sandbox not available on this platform (Linux only)".to_string())
}

#[cfg(target_os = "linux")]
impl GvisorPool {
    /// Check if the pool is functional (runsc binary exists).
    fn is_available(&self) -> bool {
        self.runsc_available
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sandbox_type_from_str() {
        assert_eq!(SandboxType::from_str("none"), SandboxType::None);
        assert_eq!(SandboxType::from_str("landlock"), SandboxType::Landlock);
        assert_eq!(SandboxType::from_str("gvisor"), SandboxType::Gvisor);
        assert_eq!(SandboxType::from_str("subprocess"), SandboxType::Landlock);
        assert_eq!(SandboxType::from_str(""), SandboxType::None);
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
