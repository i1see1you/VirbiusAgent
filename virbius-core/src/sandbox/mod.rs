//! Sandbox module: Landlock file-path isolation + gVisor untrusted-code sandbox.
//!
//! The [`execute`] function is the main entry point.  It selects the
//! appropriate sandbox type based on the tool policy and falls back
//! gracefully when features are unavailable.

#[cfg(target_os = "linux")]
pub mod gvisor_pool;
#[cfg(target_os = "linux")]
pub mod landlock;
pub mod wait;

#[cfg(target_os = "linux")]
pub use gvisor_pool::{GvisorExecResult, GvisorPool, GvisorPoolConfig, Language};
#[cfg(target_os = "linux")]
pub use landlock::{
    check_landlock_availability, detect_abi_version, execute_sandboxed, LandlockAbi, LandlockRules,
    LandlockSandbox, SandboxConfig, SandboxResult,
};
pub use wait::{mcp_wall, wait_until, REAP_BUDGET, REAP_STEP, WALL_SLACK};

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
    /// Whether stdout/stderr was truncated at the output cap.
    pub truncated: bool,
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
#[derive(Debug, Clone, Default)]
pub struct LandlockRules {}

/// Non-Linux stub for GvisorPoolConfig.
#[cfg(not(target_os = "linux"))]
#[derive(Debug, Clone, Default)]
pub struct GvisorPoolConfig {}

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
///    If the pool is not delivered or `runsc` is missing, returns
///    `sandbox_unavailable` (no Landlock fallback).
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
                truncated: false,
            })
        }

        SandboxType::Gvisor => {
            GvisorPool::apply_from_manifest_if_needed();
            let pool = GvisorPool::global();

            if pool.is_available() {
                let language = req.gvisor_language.unwrap_or(Language::Shell);
                let result =
                    pool.execute_with_timeout(language, &req.program, Some(req.timeout))?;
                return Ok(ExecutionResult {
                    stdout: result.stdout,
                    stderr: result.stderr,
                    exit_code: result.exit_code,
                    sandbox_used: SandboxType::Gvisor,
                    degraded: false,
                    degrade_note: None,
                    landlock_applied: false,
                    truncated: result.truncated,
                });
            }

            Err("sandbox_unavailable".to_string())
        }
    }
}

/// Supported sandboxed languages (non-Linux variant).
///
/// Mirrors the enum defined in the Linux-gated [`gvisor_pool`] module so the
/// shared request/result types stay platform-independent. Local execution
/// itself is Linux-only (gVisor).
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
            truncated: false,
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
            truncated: false,
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
