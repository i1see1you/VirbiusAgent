//! Landlock + drop caps subprocess sandbox (Linux only).
//!
//! Provides file-path-level isolation for tool execution by spawning child
//! processes under Landlock restrictions and with all Linux capabilities
//! dropped.  The child applies restrictions via a small LD_PRELOAD shim
//! *before* any user code runs.
//!
//! ## Architecture
//!
//! ```text
//!  Parent (virbius-core / MCP Proxy)
//!    |
//!    +-- posix_spawn(child, env={LD_PRELOAD, VIRBIUS_LANDLOCK_RULES_JSON})
//!          |
//!          +-- LD_PRELOAD constructor runs FIRST:
//!          |     1. Parse VIRBIUS_LANDLOCK_RULES_JSON
//!          |     2. landlock_create_ruleset + add rules + restrict_self
//!          |     3. capset(drop ALL)
//!          |     4. prctl(PR_SET_NO_NEW_PRIVS)
//!          |     5. unsetenv sensitive vars
//!          |
//!          +-- exec actual program (cat, python3, ...)
//! ```
//!
//! ## ABI version detection
//!
//! Landlock ABI is detected at runtime.  v1 (kernel 5.13+) covers file
//! paths.  v4 (kernel 6.7+) adds network port restrictions.  When the
//! kernel does not support Landlock at all the sandbox degrades to
//! "drop caps only" mode and logs a warning.

use std::ffi::CString;
use std::io::{self, Read};
use std::os::unix::io::AsRawFd;
use std::os::unix::process::ExitStatusExt;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

/// Result of a sandboxed execution.
#[derive(Debug)]
pub struct SandboxResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    /// Whether Landlock was actually applied (false = degraded mode).
    pub landlock_applied: bool,
    /// Whether capabilities were dropped.
    pub caps_dropped: bool,
}

/// Rules describing what the sandboxed process is allowed to access.
///
/// Serialized as JSON and passed to the child via the
/// `VIRBIUS_LANDLOCK_RULES` environment variable.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LandlockRules {
    /// Glob patterns for paths the process may read (e.g. `["/usr/*", "/tmp/data/*"]`).
    #[serde(default)]
    pub read_paths: Vec<String>,
    /// Glob patterns for paths the process may read and write.
    #[serde(default)]
    pub write_paths: Vec<String>,
    /// Glob patterns for binaries the process may execute.
    #[serde(default)]
    pub exec_paths: Vec<String>,
    /// Allowed TCP bind ports (Landlock v4+, kernel 6.7+).  Empty = no restriction info.
    #[serde(default)]
    pub bind_ports: Vec<u16>,
    /// Allowed TCP connect ports (Landlock v4+).  Empty = no restriction info.
    #[serde(default)]
    pub connect_ports: Vec<u16>,
}

/// Configuration for a single sandboxed execution.
#[derive(Debug, Clone)]
pub struct SandboxConfig {
    /// Path to the LD_PRELOAD shared library (libvirbius_sandbox_preload.so).
    pub preload_lib_path: String,
    /// Landlock rules to apply.
    pub rules: LandlockRules,
    /// Execution timeout.
    pub timeout: Duration,
    /// Maximum stdout size in bytes (prevents OOM on large outputs).
    pub max_output_bytes: usize,
}

impl Default for SandboxConfig {
    fn default() -> Self {
        Self {
            preload_lib_path: "/opt/virbius/lib/libvirbius_sandbox_preload.so".to_string(),
            rules: LandlockRules::default(),
            timeout: Duration::from_secs(30),
            max_output_bytes: 10 * 1024 * 1024, // 10 MB
        }
    }
}

/// Detected Landlock ABI version.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LandlockAbi {
    /// Landlock not available (kernel < 5.13 or compiled out).
    None,
    /// v1 (kernel 5.13+): file path restrictions.
    V1,
    /// v2 (kernel 5.19+): file + refer restrictions.
    V2,
    /// v3 (kernel 6.2+): file + device restrictions.
    V3,
    /// v4 (kernel 6.7+): file + network port restrictions.
    V4,
}

impl LandlockAbi {
    /// Returns true if file-path restrictions are available.
    pub fn supports_file(self) -> bool {
        matches!(self, LandlockAbi::V1 | LandlockAbi::V2 | LandlockAbi::V3 | LandlockAbi::V4)
    }

    /// Returns true if network port restrictions are available.
    pub fn supports_net(self) -> bool {
        matches!(self, LandlockAbi::V4)
    }
}

/// Detect the highest Landlock ABI version supported by the running kernel.
///
/// Uses the `landlock_create_ruleset` syscall with an empty ruleset attr
/// of increasing scope.  Returns `LandlockAbi::None` when the syscall is
/// not available or returns `EOPNOTSUPP`.
pub fn detect_abi_version() -> LandlockAbi {
    // Try v4 (file + net) first.
    if try_create_ruleset(true) {
        return LandlockAbi::V4;
    }
    // Fall back to v1 (file only).
    if try_create_ruleset(false) {
        return LandlockAbi::V1;
    }
    LandlockAbi::None
}

/// Attempt to create a Landlock ruleset to probe ABI support.
fn try_create_ruleset(with_net: bool) -> bool {
    // We use raw syscalls via libc to avoid a hard dependency on the
    // landlock crate.  The syscall numbers are stable on all arches
    // that support Landlock (x86_64, aarch64).
    //
    // landlock_create_ruleset(struct landlock_ruleset_attr *attr, size_t size, u32 flags)
    //   syscall number 444 (x86_64), 444 (aarch64)
    //
    // struct landlock_ruleset_attr {
    //   __u64 handled_access_fs;   // offset 0
    //   __u64 handled_access_net;  // offset 8  (v4+)
    // };

    const LANDLOCK_CREATE_RULESET: i64 = 444;
    const LANDLOCK_RULESET_VERSION: u32 = 1 << 0; // U32 flags field

    // Access flags for FS (v1).
    const ACCESS_FS_READ: u64 = 0x0_001f; // execute, write_file, read_file, read_dir, remove_dir, ...
    const ACCESS_FS_WRITE: u64 = 0x0_1fe0;
    const ACCESS_FS_ALL: u64 = ACCESS_FS_READ | ACCESS_FS_WRITE;
    // Access flags for NET (v4).
    const ACCESS_NET_BIND_TCP: u64 = 1u64 << 0;
    const ACCESS_NET_CONNECT_TCP: u64 = 1u64 << 1;
    const ACCESS_NET_ALL: u64 = ACCESS_NET_BIND_TCP | ACCESS_NET_CONNECT_TCP;

    let attr_size: usize = if with_net { 16 } else { 8 };
    let mut attr_buf = vec![0u8; attr_size];

    // Write handled_access_fs at offset 0.
    attr_buf[0..8].copy_from_slice(&ACCESS_FS_ALL.to_le_bytes());
    if with_net {
        attr_buf[8..16].copy_from_slice(&ACCESS_NET_ALL.to_le_bytes());
    }

    let ret = unsafe {
        libc::syscall(
            LANDLOCK_CREATE_RULESET,
            attr_buf.as_mut_ptr() as *const u8,
            attr_size,
            0u32, // flags=0: create a real ruleset (not just version query)
        )
    };
    if ret >= 0 {
        // Close the fd.
        unsafe {
            libc::close(ret as i32);
        }
        true
    } else {
        // Also try the version-query path (flags = LANDLOCK_RULESET_VERSION)
        // to distinguish "not supported" from "attr too big".
        if with_net {
            return false; // will retry without net
        }
        let ver = unsafe {
            libc::syscall(
                LANDLOCK_CREATE_RULESET,
                std::ptr::null::<u8>(),
                0usize,
                LANDLOCK_RULESET_VERSION,
            )
        };
        ver > 0
    }
}

/// The Landlock sandbox executor.
pub struct LandlockSandbox {
    config: SandboxConfig,
    abi: LandlockAbi,
}

impl LandlockSandbox {
    /// Create a new sandbox with the given configuration.
    pub fn new(config: SandboxConfig) -> Self {
        let abi = detect_abi_version();
        if abi == LandlockAbi::None {
            eprintln!("virbius-sandbox: Landlock not available, running in degraded mode (drop caps only)");
        }
        Self { config, abi }
    }

    /// Execute a program in the sandbox.
    ///
    /// Spawns a child process with `LD_PRELOAD` set to the preload library,
    /// which applies Landlock rules + drops capabilities before `exec`.
    pub fn execute(&self, program: &str, args: &[String]) -> Result<SandboxResult, String> {
        let rules_json = serde_json::to_string(&self.config.rules)
            .map_err(|e| format!("failed to serialize rules: {e}"))?;

        let preload_cstr = CString::new(self.config.preload_lib_path.as_str())
            .map_err(|e| format!("invalid preload path: {e}"))?;
        let rules_cstr = CString::new(rules_json.as_str())
            .map_err(|e| format!("invalid rules json: {e}"))?;

        let mut child = Command::new(program)
            .args(args)
            .env("LD_PRELOAD", preload_cstr.to_str().unwrap())
            .env("VIRBIUS_LANDLOCK_RULES", rules_cstr.to_str().unwrap())
            .env("VIRBIUS_DROP_CAPS", "all")
            .env("VIRBIUS_LANDLOCK_ABI", format!("{}", self.abi as i32))
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("failed to spawn '{program}': {e}"))?;

        let start = Instant::now();
        let timeout = self.config.timeout;

        // Wait with timeout.
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    let mut stdout = String::new();
                    let mut stderr = String::new();
                    if let Some(mut out) = child.stdout.take() {
                        let mut buf = Vec::with_capacity(8192);
                        let _ = out.read_to_end(&mut buf);
                        if buf.len() > self.config.max_output_bytes {
                            buf.truncate(self.config.max_output_bytes);
                        }
                        stdout = String::from_utf8_lossy(&buf).into_owned();
                    }
                    if let Some(mut err) = child.stderr.take() {
                        let mut buf = Vec::with_capacity(8192);
                        let _ = err.read_to_end(&mut buf);
                        if buf.len() > self.config.max_output_bytes {
                            buf.truncate(self.config.max_output_bytes);
                        }
                        stderr = String::from_utf8_lossy(&buf).into_owned();
                    }
                    let exit_code = status.code().unwrap_or_else(|| {
                        status.signal().unwrap_or(-1)
                    });
                    return Ok(SandboxResult {
                        stdout,
                        stderr,
                        exit_code,
                        landlock_applied: self.abi.supports_file(),
                        caps_dropped: true,
                    });
                }
                Ok(None) => {
                    if start.elapsed() > timeout {
                        // Kill the child.
                        let _ = child.kill();
                        let _ = child.wait();
                        return Err(format!(
                            "sandbox timeout after {}s for '{program}'",
                            timeout.as_secs()
                        ));
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(e) => {
                    return Err(format!("wait failed: {e}"));
                }
            }
        }
    }
}

/// Convenience: execute a command and capture stdout.
///
/// Returns `Err` if the sandbox cannot be created or the process fails to
/// spawn.  Returns `Ok(result)` even on non-zero exit codes — check
/// `result.exit_code`.
pub fn execute_sandboxed(
    program: &str,
    args: &[String],
    rules: LandlockRules,
    timeout: Duration,
) -> Result<SandboxResult, String> {
    let config = SandboxConfig {
        rules,
        timeout,
        ..SandboxConfig::default()
    };
    let sandbox = LandlockSandbox::new(config);
    sandbox.execute(program, args)
}

/// Check whether the current process has the ability to use Landlock.
///
/// Returns a human-readable description of what's available.
pub fn check_landlock_availability() -> String {
    let abi = detect_abi_version();
    match abi {
        LandlockAbi::None => "Landlock not available (kernel < 5.13 or compiled out). Sandbox degrades to drop-caps only.".to_string(),
        LandlockAbi::V1 => "Landlock v1 (kernel 5.13+): file path restrictions available.".to_string(),
        LandlockAbi::V2 => "Landlock v2 (kernel 5.19+): file + refer restrictions available.".to_string(),
        LandlockAbi::V3 => "Landlock v3 (kernel 6.2+): file + device restrictions available.".to_string(),
        LandlockAbi::V4 => "Landlock v4 (kernel 6.7+): file + network port restrictions available.".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rules_serialization() {
        let rules = LandlockRules {
            read_paths: vec!["/usr/*".into(), "/tmp/data/*".into()],
            write_paths: vec!["/tmp/workdir/*".into()],
            exec_paths: vec!["/usr/bin/cat".into()],
            bind_ports: vec![],
            connect_ports: vec![443],
        };
        let json = serde_json::to_string(&rules).unwrap();
        let deserialized: LandlockRules = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized.read_paths.len(), 2);
        assert_eq!(deserialized.write_paths.len(), 1);
        assert_eq!(deserialized.connect_ports, vec![443]);
    }

    #[test]
    fn test_default_config() {
        let config = SandboxConfig::default();
        assert_eq!(config.timeout, Duration::from_secs(30));
        assert_eq!(config.max_output_bytes, 10 * 1024 * 1024);
        assert!(!config.preload_lib_path.is_empty());
    }

    #[test]
    fn test_abi_supports() {
        assert!(LandlockAbi::V1.supports_file());
        assert!(!LandlockAbi::V1.supports_net());
        assert!(LandlockAbi::V4.supports_file());
        assert!(LandlockAbi::V4.supports_net());
        assert!(!LandlockAbi::None.supports_file());
    }

    #[test]
    fn test_detect_abi_does_not_crash() {
        // This should not panic even on kernels without Landlock.
        let _abi = detect_abi_version();
    }
}
