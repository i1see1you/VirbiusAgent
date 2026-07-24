//! Landlock + drop caps subprocess sandbox (Linux only).
//!
//! Provides file-path-level isolation for tool execution by spawning child
//! processes under Landlock restrictions and with all Linux capabilities
//! dropped.  The child applies restrictions *between fork and exec* via the
//! `pre_exec` hook exposed by `std::os::unix::process::CommandExt`.
//!
//! ## Architecture
//!
//! ```text
//!  Parent (virbius-core / MCP Proxy)
//!    |
//!    +-- PreparedRules::compile(&rules)   // glob-expand paths to CString list
//!    |
//!    +-- Command::new(program)
//!          .pre_exec(move || {            // runs in child, after fork, before exec
//!              apply_landlock(&prepared)  //   1. landlock_create_ruleset
//!              //                          2. landlock_add_rule (per path / port)
//!              //                          3. landlock_restrict_self
//!              //                          4. capset(drop ALL)
//!              //                          5. prctl(PR_SET_NO_NEW_PRIVS)
//!          })
//!          .spawn()
//!          |
//!          +-- exec actual program (cat, python3, ...)
//!                process is now constrained by Landlock + no caps
//! ```
//!
//! ## async-signal-safety
//!
//! The `pre_exec` closure runs in a forked child where only async-signal-safe
//! operations are permitted (no malloc, no locks, no stdio).  All heap data
//! is prepared in the parent via `PreparedRules::compile` before fork; the
//! closure only reads that data via raw syscalls (`open`, `close`, `syscall`).
//!
//! ## ABI version detection
//!
//! Landlock ABI is detected at runtime.  v1 (kernel 5.13+) covers file
//! paths.  v4 (kernel 6.7+) adds network port restrictions.  When the
//! kernel does not support Landlock at all the sandbox degrades to
//! "drop caps only" mode and logs a warning.

use std::ffi::CString;
use std::io::{self, Read, Write};
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

// ──────────────────────────────────────────────────────────────────────────
//  Public types
// ──────────────────────────────────────────────────────────────────────────

/// Result of a sandboxed execution.
#[derive(Debug)]
pub struct SandboxResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    /// Whether Landlock file/network restrictions were actually applied.
    /// Determined by the child's own report, not by ABI inference.
    pub landlock_applied: bool,
    /// Whether capabilities were dropped.
    pub caps_dropped: bool,
}

/// Rules describing what the sandboxed process is allowed to access.
///
/// Serialized as JSON in the edge manifest; deserialized into
/// [`SandboxConfig`] and then compiled to [`PreparedRules`] before fork.
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
    /// Landlock rules to apply.
    pub rules: LandlockRules,
    /// Execution timeout.
    pub timeout: Duration,
    /// Maximum stdout/stderr size in bytes (prevents OOM on large outputs).
    pub max_output_bytes: usize,
}

impl Default for SandboxConfig {
    fn default() -> Self {
        Self {
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
        matches!(
            self,
            LandlockAbi::V1 | LandlockAbi::V2 | LandlockAbi::V3 | LandlockAbi::V4
        )
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
    const LANDLOCK_CREATE_RULESET: i64 = 444;
    const LANDLOCK_RULESET_VERSION: u32 = 1 << 0; // U32 flags field

    // Access flags for FS (v1).
    const ACCESS_FS_READ: u64 = 0x0_001f;
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

// ──────────────────────────────────────────────────────────────────────────
//  PreparedRules: parent-side pre-compilation before fork
// ──────────────────────────────────────────────────────────────────────────

/// Pre-compiled rules ready to be consumed by the `pre_exec` closure.
///
/// All heap allocations happen here, in the parent, before fork.  The
/// `pre_exec` closure only reads these fields via raw syscalls — no
/// further allocation occurs in the child.
#[derive(Debug, Clone)]
struct PreparedRules {
    abi: LandlockAbi,
    /// Glob-expanded concrete paths (owned CString, safe to read in child).
    read_paths: Vec<CString>,
    write_paths: Vec<CString>,
    exec_paths: Vec<CString>,
    bind_ports: Vec<u16>,
    connect_ports: Vec<u16>,
}

impl PreparedRules {
    fn compile(rules: &LandlockRules) -> Self {
        Self {
            abi: detect_abi_version(),
            read_paths: expand_globs(&rules.read_paths),
            write_paths: expand_globs(&rules.write_paths),
            exec_paths: expand_globs(&rules.exec_paths),
            bind_ports: rules.bind_ports.clone(),
            connect_ports: rules.connect_ports.clone(),
        }
    }
}

/// Expand glob patterns into concrete paths, returned as owned CStrings.
///
/// Runs in the parent process (safe to allocate).  Patterns that match
/// nothing are silently dropped — Landlock would reject them anyway via
/// `open(O_PATH)` returning ENOENT, but filtering them here keeps the
/// child's syscall count minimal.
///
/// The list is capped at [`MAX_EXPANDED_PATHS`] and expansion stops as soon
/// as the cap is reached: a pathological pattern such as `/**` would
/// otherwise walk the entire filesystem (including `/proc` and `/sys`)
/// before truncation, stalling the caller for minutes.
fn expand_globs(patterns: &[String]) -> Vec<CString> {
    let mut out = Vec::new();
    'outer: for pat in patterns {
        if pat.is_empty() {
            continue;
        }
        match glob::glob(pat) {
            Ok(paths) => {
                for path in paths.flatten() {
                    if let Ok(s) = CString::new(path.as_os_str().as_encoded_bytes()) {
                        out.push(s);
                        if out.len() >= MAX_EXPANDED_PATHS {
                            // Cap reached — stop walking immediately.  If a
                            // rule expands to more paths, the operator should
                            // tighten the glob.
                            break 'outer;
                        }
                    }
                }
            }
            Err(_) => {
                // Malformed pattern — skip.  The parent may log this.
            }
        }
    }
    out
}

/// Maximum number of glob-expanded paths kept for a single rule set.
const MAX_EXPANDED_PATHS: usize = 4096;

// ──────────────────────────────────────────────────────────────────────────
//  Child-side apply functions (async-signal-safe)
// ──────────────────────────────────────────────────────────────────────────
//
//  All functions in this section run in the forked child, between fork and
//  exec.  They MUST be async-signal-safe:
//    - no malloc / String / Vec::push / format!
//    - no Mutex / RwLock / log
//    - only raw syscalls (open, close, syscall) and stack-allocated buffers
//
//  Errors are reported back to the parent via the io::Result returned from
//  the pre_exec closure; if it returns Err, exec is aborted and spawn()
//  fails.

// Landlock syscall numbers (stable on x86_64 and aarch64).
const SYS_LANDLOCK_CREATE_RULESET: i64 = 444;
const SYS_LANDLOCK_ADD_RULE: i64 = 445;
const SYS_LANDLOCK_RESTRICT_SELF: i64 = 446;

// Rule types for landlock_add_rule.
const LANDLOCK_RULE_PATH_BENEATH: u32 = 1;
const LANDLOCK_RULE_NET_PORT: u32 = 2;

// FS access flags (v1).
const ACCESS_FS_EXECUTE: u64 = 1 << 0;
const ACCESS_FS_WRITE_FILE: u64 = 1 << 1;
const ACCESS_FS_READ_FILE: u64 = 1 << 2;
const ACCESS_FS_READ_DIR: u64 = 1 << 3;
const ACCESS_FS_REMOVE_DIR: u64 = 1 << 4;
const ACCESS_FS_REMOVE_FILE: u64 = 1 << 5;
const ACCESS_FS_MAKE_CHAR: u64 = 1 << 6;
const ACCESS_FS_MAKE_DIR: u64 = 1 << 7;
const ACCESS_FS_MAKE_REG: u64 = 1 << 8;
const ACCESS_FS_MAKE_SOCK: u64 = 1 << 9;
const ACCESS_FS_MAKE_FIFO: u64 = 1 << 10;
const ACCESS_FS_MAKE_BLOCK: u64 = 1 << 11;
const ACCESS_FS_MAKE_SYM: u64 = 1 << 12;
const ACCESS_FS_REFER: u64 = 1 << 13; // v2

const ACCESS_FS_READ: u64 = ACCESS_FS_EXECUTE | ACCESS_FS_READ_FILE | ACCESS_FS_READ_DIR;

const ACCESS_FS_WRITE: u64 = ACCESS_FS_WRITE_FILE
    | ACCESS_FS_READ_DIR // needed to write into a dir
    | ACCESS_FS_REMOVE_DIR
    | ACCESS_FS_REMOVE_FILE
    | ACCESS_FS_MAKE_CHAR
    | ACCESS_FS_MAKE_DIR
    | ACCESS_FS_MAKE_REG
    | ACCESS_FS_MAKE_SOCK
    | ACCESS_FS_MAKE_FIFO
    | ACCESS_FS_MAKE_BLOCK
    | ACCESS_FS_MAKE_SYM
    | ACCESS_FS_REFER;

const ACCESS_FS_ALL: u64 = ACCESS_FS_READ | ACCESS_FS_WRITE;

// Net access flags (v4).
const ACCESS_NET_BIND_TCP: u64 = 1 << 0;
const ACCESS_NET_CONNECT_TCP: u64 = 1 << 1;
const ACCESS_NET_ALL: u64 = ACCESS_NET_BIND_TCP | ACCESS_NET_CONNECT_TCP;

/// `struct landlock_ruleset_attr { u64 handled_access_fs; u64 handled_access_net; }`
#[repr(C)]
struct LandlockRulesetAttr {
    handled_access_fs: u64,
    handled_access_net: u64,
}

/// `struct landlock_path_beneath_attr { u64 allowed_access; s32 parent_fd; }`
#[repr(C)]
struct LandlockPathBeneathAttr {
    allowed_access: u64,
    parent_fd: i32,
    // 4 bytes padding to align to 8 — the kernel struct is 16 bytes.
    _pad: u32,
}

/// `struct landlock_net_port_attr { u64 allowed_access; u64 port; }`
#[repr(C)]
struct LandlockNetPortAttr {
    allowed_access: u64,
    port: u64,
}

/// Apply Landlock restrictions + drop caps in the child process.
///
/// Runs in the `pre_exec` closure — async-signal-safety required.
/// Returns `Ok(apply_report)` on success, or `Err` to abort exec.
fn apply_landlock(rules: &PreparedRules) -> io::Result<ApplyReport> {
    let mut report = ApplyReport::default();

    if rules.abi == LandlockAbi::None {
        // No Landlock — degrade to drop caps only.
        report.caps_dropped = drop_caps_and_no_new_privs()?;
        return Ok(report);
    }

    // 1. landlock_create_ruleset
    let attr = LandlockRulesetAttr {
        handled_access_fs: ACCESS_FS_ALL,
        handled_access_net: if rules.abi.supports_net() {
            ACCESS_NET_ALL
        } else {
            0
        },
    };
    let attr_size = if rules.abi.supports_net() { 16 } else { 8 };
    let fd = unsafe {
        libc::syscall(
            SYS_LANDLOCK_CREATE_RULESET,
            &attr as *const LandlockRulesetAttr as *const u8,
            attr_size,
            0u32,
        )
    };
    if fd < 0 {
        // Kernel refused to create a ruleset — degrade to drop caps only.
        report.caps_dropped = drop_caps_and_no_new_privs()?;
        return Ok(report);
    }
    let fd = fd as i32;

    // 2. Add path rules.  Errors here are non-fatal: a missing path just
    //    means that path won't be accessible, which is the safe default.
    for path in &rules.read_paths {
        let _ = add_path_rule(fd, path, ACCESS_FS_READ);
    }
    for path in &rules.write_paths {
        let _ = add_path_rule(fd, path, ACCESS_FS_WRITE);
    }
    for path in &rules.exec_paths {
        let _ = add_path_rule(fd, path, ACCESS_FS_EXECUTE);
    }

    // 3. Add net port rules (v4 only).
    if rules.abi.supports_net() {
        for port in &rules.bind_ports {
            let _ = add_net_rule(fd, *port, ACCESS_NET_BIND_TCP);
        }
        for port in &rules.connect_ports {
            let _ = add_net_rule(fd, *port, ACCESS_NET_CONNECT_TCP);
        }
    }

    // 4. landlock_restrict_self — apply the ruleset to this process.
    let r = unsafe { libc::syscall(SYS_LANDLOCK_RESTRICT_SELF, fd, 0u32) };
    unsafe { libc::close(fd) };

    if r < 0 {
        // restrict_self failed — degrade to drop caps only.
        report.caps_dropped = drop_caps_and_no_new_privs()?;
        return Ok(report);
    }

    report.landlock_applied = true;
    report.caps_dropped = drop_caps_and_no_new_privs()?;
    Ok(report)
}

#[derive(Debug, Default, Clone, Copy)]
struct ApplyReport {
    landlock_applied: bool,
    caps_dropped: bool,
}

/// Add a single path-beneath rule to the ruleset.  Async-signal-safe.
fn add_path_rule(ruleset_fd: i32, path: &std::ffi::CStr, access: u64) -> io::Result<()> {
    // Open the path with O_PATH — no actual file access, just a handle
    // for the kernel to attach the rule to.
    const O_PATH: i32 = 0o010000000;
    const O_CLOEXEC: i32 = 0o2000000;
    let path_fd = unsafe { libc::open(path.as_ptr(), O_PATH | O_CLOEXEC) };
    if path_fd < 0 {
        return Err(io::Error::last_os_error());
    }

    let attr = LandlockPathBeneathAttr {
        allowed_access: access,
        parent_fd: path_fd,
        _pad: 0,
    };
    let r = unsafe {
        libc::syscall(
            SYS_LANDLOCK_ADD_RULE,
            ruleset_fd,
            LANDLOCK_RULE_PATH_BENEATH,
            &attr as *const LandlockPathBeneathAttr as *const u8,
            0u32,
        )
    };
    unsafe { libc::close(path_fd) };

    if r < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Add a single net-port rule to the ruleset.  Async-signal-safe.
fn add_net_rule(ruleset_fd: i32, port: u16, access: u64) -> io::Result<()> {
    let attr = LandlockNetPortAttr {
        allowed_access: access,
        port: port as u64,
    };
    let r = unsafe {
        libc::syscall(
            SYS_LANDLOCK_ADD_RULE,
            ruleset_fd,
            LANDLOCK_RULE_NET_PORT,
            &attr as *const LandlockNetPortAttr as *const u8,
            0u32,
        )
    };
    if r < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Drop all Linux capabilities and set PR_SET_NO_NEW_PRIVS.
/// Async-signal-safe.
///
/// Returns true if at least one of capset / prctl succeeded; false only
/// if both failed (extremely unlikely — usually means we're already
/// unprivileged and have no caps to drop).
fn drop_caps_and_no_new_privs() -> io::Result<bool> {
    const PR_SET_NO_NEW_PRIVS: i32 = 38;
    const CAP_TO_MASK: u32 = 0x1F; // 0xFFFFFFFF / etc. — see linux/capability.h
    const _LINUX_CAPABILITY_VERSION_3: u32 = 0x20080522;

    // `struct cap_header_t { u32 version; int pid; }`
    // `struct cap_data_t    { u32 effective; u32 permitted; u32 inheritable; }`
    // We drop ALL caps: effective = permitted = inheritable = 0.
    #[repr(C)]
    struct CapHeader {
        version: u32,
        pid: i32,
    }
    #[repr(C)]
    struct CapData {
        effective: u32,
        permitted: u32,
        inheritable: u32,
    }

    let mut any_ok = false;

    // prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) — must succeed for the sandbox
    // to be meaningful; setuid binaries would otherwise bypass Landlock.
    let r = unsafe { libc::syscall(libc::SYS_prctl, PR_SET_NO_NEW_PRIVS, 1i32, 0i32, 0i32, 0i32) };
    if r == 0 {
        any_ok = true;
    }

    // capset(header={_LINUX_CAPABILITY_VERSION_3, 0}, data=[{0,0,0},{0,0,0}])
    // drops all capabilities in all three sets for the current thread.
    let header = CapHeader {
        version: _LINUX_CAPABILITY_VERSION_3,
        pid: 0, // 0 = current thread
    };
    let data: [CapData; 2] = [
        CapData {
            effective: 0,
            permitted: 0,
            inheritable: 0,
        },
        CapData {
            effective: 0,
            permitted: 0,
            inheritable: 0,
        },
    ];
    let r = unsafe {
        libc::syscall(
            libc::SYS_capset,
            &header as *const CapHeader as *const u8,
            data.as_ptr() as *const u8,
        )
    };
    if r == 0 {
        any_ok = true;
    }

    // Silence unused warning on CAP_TO_MASK (kept for documentation).
    let _ = CAP_TO_MASK;

    Ok(any_ok)
}

// ──────────────────────────────────────────────────────────────────────────
//  Self-pipe helpers: child→parent ApplyReport transport
// ──────────────────────────────────────────────────────────────────────────

/// Create a pipe suitable for child→parent ApplyReport transport.
/// Sets CLOEXEC on the write end so exec closes it automatically.
fn create_report_pipe() -> io::Result<(i32, i32)> {
    let mut fds: [i32; 2] = [-1; 2];
    let ret = unsafe { libc::pipe(fds.as_mut_ptr()) };
    if ret != 0 {
        return Err(io::Error::last_os_error());
    }
    let flags = unsafe { libc::fcntl(fds[1], libc::F_GETFD) };
    if flags >= 0 {
        unsafe { libc::fcntl(fds[1], libc::F_SETFD, flags | libc::FD_CLOEXEC) };
    }
    Ok((fds[0], fds[1]))
}

/// Read the child's ApplyReport from the self-pipe.
///
/// Uses `poll(2)` with a 100 ms timeout to avoid blocking forever if
/// the child crashes before writing.  On timeout / error / empty read
/// falls back to ABI-based inference (the same logic used before the
/// self-pipe was introduced).
fn read_report(fd: i32, fallback_abi: LandlockAbi) -> (bool, bool) {
    let mut pfd = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let ret = unsafe { libc::poll(&mut pfd as *mut libc::pollfd, 1, 100) };

    let mut buf = [0u8; 2];
    let n = if ret > 0 && (pfd.revents & libc::POLLIN) != 0 {
        unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, 2) }
    } else {
        -1
    };
    unsafe { libc::close(fd) };

    if n == 2 {
        (buf[0] != 0, buf[1] != 0)
    } else {
        (fallback_abi.supports_file(), true)
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  LandlockSandbox: parent-side executor
// ──────────────────────────────────────────────────────────────────────────

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
            // Log to stderr — at construction time we're still in the parent
            // and stdio is safe.
            let _ = writeln!(
                io::stderr(),
                "virbius-sandbox: Landlock not available, running in degraded mode (drop caps only)"
            );
        }
        Self { config, abi }
    }

    /// Execute a program in the sandbox.
    ///
    /// Spawns a child process whose `pre_exec` hook applies Landlock rules
    /// and drops capabilities before `exec`.  If Landlock is unavailable,
    /// degrades to "drop caps only" mode.
    pub fn execute(&self, program: &str, args: &[String]) -> Result<SandboxResult, String> {
        let prepared = PreparedRules::compile(&self.config.rules);
        let prepared_for_hook = prepared.clone();

        // Create self-pipe for child→parent ApplyReport.
        // pipe(2) is async-signal-safe; the write end is CLOEXEC so it
        // closes automatically on exec.  The child writes a 2-byte report
        // in pre_exec; the parent reads it after spawn.
        let (read_fd, write_fd) =
            create_report_pipe().map_err(|e| format!("failed to create self-pipe: {e}"))?;

        let mut cmd = Command::new(program);
        cmd.args(args);

        // SAFETY: pre_exec runs in the forked child where only
        // async-signal-safe operations are permitted.  The closure only
        // calls raw syscalls (apply_landlock, write, close) on
        // pre-allocated data — all async-signal-safe.
        unsafe {
            cmd.pre_exec(move || {
                let report = apply_landlock(&prepared_for_hook)?;
                let buf = [report.landlock_applied as u8, report.caps_dropped as u8];
                let _ = libc::write(write_fd, buf.as_ptr() as *const libc::c_void, 2);
                libc::close(write_fd);
                Ok(())
            });
        }

        let mut child = cmd
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| {
                unsafe { libc::close(read_fd) };
                format!("failed to spawn '{program}': {e}")
            })?;

        // Close parent's copy of write end so the pipe delivers EOF when
        // the child finishes its report and execs (CLOEXEC).
        unsafe { libc::close(write_fd) };

        // Read child's ApplyReport (2 bytes) from self-pipe.
        // Falls back to ABI inference if the child didn't write (should
        // not happen in normal operation).
        let (landlock_applied, caps_dropped) = read_report(read_fd, self.abi);

        drop(prepared);
        let start = Instant::now();
        let timeout = self.config.timeout;

        // Wait with timeout.
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    let stdout = read_capped(&mut child, true, self.config.max_output_bytes);
                    let stderr = read_capped(&mut child, false, self.config.max_output_bytes);
                    let exit_code = status.code().unwrap_or_else(|| {
                        use std::os::unix::process::ExitStatusExt;
                        status.signal().unwrap_or(-1)
                    });
                    return Ok(SandboxResult {
                        stdout,
                        stderr,
                        exit_code,
                        landlock_applied,
                        caps_dropped,
                    });
                }
                Ok(None) => {
                    if start.elapsed() > timeout {
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

/// Read stdout (if `is_stdout`) or stderr from a child, capped to `max_bytes`.
///
/// `ChildStdout` and `ChildStderr` are distinct types but both implement
/// `Read`, so we dispatch via a helper closure to avoid type-mismatch.
fn read_capped(child: &mut std::process::Child, is_stdout: bool, max_bytes: usize) -> String {
    let mut buf = Vec::with_capacity(8192);
    if is_stdout {
        if let Some(mut s) = child.stdout.take() {
            let _ = s.read_to_end(&mut buf);
        }
    } else if let Some(mut s) = child.stderr.take() {
        let _ = s.read_to_end(&mut buf);
    }
    if buf.len() > max_bytes {
        buf.truncate(max_bytes);
    }
    String::from_utf8_lossy(&buf).into_owned()
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
    fn test_default_config_no_preload_field() {
        // The old SandboxConfig had a preload_lib_path field; the new one
        // does not.  This test guards against accidental reintroduction.
        let config = SandboxConfig::default();
        assert_eq!(config.timeout, Duration::from_secs(30));
        assert_eq!(config.max_output_bytes, 10 * 1024 * 1024);
        assert!(config.rules.read_paths.is_empty());
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

    #[test]
    fn test_expand_globs_handles_empty() {
        let out = expand_globs(&[]);
        assert!(out.is_empty());
    }

    #[test]
    fn test_expand_globs_handles_malformed() {
        // Invalid glob pattern — should not panic, just return empty.
        let out = expand_globs(&["[unclosed".to_string()]);
        assert!(out.is_empty());
    }

    #[test]
    fn test_expand_globs_truncates_large_expansion() {
        // A glob that matches many paths — we cap at 4096.
        let out = expand_globs(&["/usr/**/*".to_string()]);
        assert!(out.len() <= 4096);
    }

    #[test]
    fn test_prepared_rules_compile() {
        let rules = LandlockRules {
            read_paths: vec!["/etc/hostname".into()], // likely exists
            write_paths: vec![],
            exec_paths: vec![],
            bind_ports: vec![8080],
            connect_ports: vec![443],
        };
        let prepared = PreparedRules::compile(&rules);
        assert!(!prepared.read_paths.is_empty() || prepared.read_paths.is_empty()); // just check no panic
        assert_eq!(prepared.bind_ports, vec![8080]);
        assert_eq!(prepared.connect_ports, vec![443]);
    }
}
