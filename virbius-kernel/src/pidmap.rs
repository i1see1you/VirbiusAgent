use std::collections::HashMap;
use std::io::Write;
use std::net::TcpStream;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

/// In-memory PID mapping: <1μs lookup, no network dependency.
///
/// # Host PID vs Namespace PID
///
/// In container environments, `getpid()` returns the **Namespace PID** (visible
/// inside the container), while Falco on the host observes the **Host
/// PID** (visible in the initial PID namespace). These are different numbers.
///
/// Solution: `register_agent` auto-detects the Host PID from `/proc/self/status`
/// (NSpid field) and the cgroup ID from `/proc/self/cgroup` + `stat()`. The
/// primary HashMap is keyed by **Host PID** — the same number Falco emits.
/// A secondary index by **cgroup_id** provides container-level correlation
/// that survives fork/exec within the same cgroup.
static PID_MAP: OnceLock<Mutex<PidMapStore>> = OnceLock::new();
const TTL: Duration = Duration::from_secs(3600);

#[derive(Debug, Clone)]
pub struct PidMapEntry {
    /// Host PID — what Falco/eBPF sees on the host.
    pub host_pid: u32,
    /// Namespace PID — what the process sees inside its container.
    /// Equal to host_pid when not in a PID namespace.
    pub ns_pid: u32,
    /// Cgroup v2 inode ID — container-level identifier.
    /// Survives fork/exec within the same container. 0 if unavailable.
    pub cgroup_id: u64,
    pub trace_id: String,
    pub session_id: String,
    pub app_id: String,
    pub tenant_id: String,
    pub start_time: Instant,
}

/// Internal store with dual indexes.
struct PidMapStore {
    /// Primary index: Host PID → entry (for Falco event lookup).
    by_host_pid: HashMap<u32, PidMapEntry>,
    /// Secondary index: cgroup_id → entry (for container-level correlation).
    /// Allows looking up the Agent session when only the cgroup is known
    /// (e.g., eBPF `bpf_get_current_cgroup_id()`).
    by_cgroup: HashMap<u64, PidMapEntry>,
}

impl PidMapStore {
    fn new() -> Self {
        Self {
            by_host_pid: HashMap::new(),
            by_cgroup: HashMap::new(),
        }
    }

    fn insert(&mut self, entry: PidMapEntry) {
        self.by_host_pid.insert(entry.host_pid, entry.clone());
        if entry.cgroup_id != 0 {
            self.by_cgroup.insert(entry.cgroup_id, entry);
        }
    }

    fn remove(&mut self, host_pid: u32) {
        if let Some(entry) = self.by_host_pid.remove(&host_pid) {
            if entry.cgroup_id != 0 {
                self.by_cgroup.remove(&entry.cgroup_id);
            }
        }
    }

    fn lookup_by_host_pid(&self, host_pid: u32) -> Option<&PidMapEntry> {
        self.by_host_pid.get(&host_pid)
    }

    fn lookup_by_cgroup(&self, cgroup_id: u64) -> Option<&PidMapEntry> {
        self.by_cgroup.get(&cgroup_id)
    }

    fn evict_expired(&mut self) {
        self.by_host_pid.retain(|_, e| e.start_time.elapsed() < TTL);
        self.by_cgroup.retain(|_, e| e.start_time.elapsed() < TTL);
    }
}

/// Register the calling process for Falco/eBPF event correlation.
///
/// Automatically detects Host PID and cgroup ID — the caller does not need
/// to know whether it's running inside a container.
///
/// # Arguments
/// - `ns_pid`: The PID as seen by the calling process (`getpid()`).
///   If 0, auto-detected via `libc::getpid()`.
/// - `trace_id`, `session_id`, `app_id`, `tenant_id`: Agent identity.
pub fn register_agent(
    ns_pid: u32,
    trace_id: &str,
    session_id: &str,
    app_id: &str,
    tenant_id: &str,
) {
    let ns_pid = if ns_pid == 0 {
        std::process::id()
    } else {
        ns_pid
    };

    let host_pid = read_host_pid().unwrap_or(ns_pid);
    let cgroup_id = read_cgroup_id().unwrap_or(0);

    let store = PID_MAP.get_or_init(|| Mutex::new(PidMapStore::new()));
    let mut guard = store.lock().unwrap();
    guard.insert(PidMapEntry {
        host_pid,
        ns_pid,
        cgroup_id,
        trace_id: trace_id.to_string(),
        session_id: session_id.to_string(),
        app_id: app_id.to_string(),
        tenant_id: tenant_id.to_string(),
        start_time: Instant::now(),
    });
    guard.evict_expired();

    // Async Redis backup (best-effort, non-blocking)
    redis_backup_async(host_pid, cgroup_id, trace_id, session_id, app_id, tenant_id);
}

pub fn unregister_agent(host_pid: u32) {
    if let Some(store) = PID_MAP.get() {
        let mut guard = store.lock().unwrap();
        guard.remove(host_pid);
    }
}

/// Lookup by Host PID — the PRIMARY path for Falco event enrichment.
///
/// Falco emits Host PID in its events. This lookup is <1μs.
pub fn lookup_agent(host_pid: u32) -> Option<PidMapEntry> {
    let store = PID_MAP.get()?;
    let guard = store.lock().ok()?;
    let entry = guard.lookup_by_host_pid(host_pid)?;
    if entry.start_time.elapsed() < TTL {
        Some(entry.clone())
    } else {
        None
    }
}

/// Lookup by cgroup ID — for eBPF programs that use
/// `bpf_get_current_cgroup_id()` instead of PID.
pub fn lookup_by_cgroup(cgroup_id: u64) -> Option<PidMapEntry> {
    let store = PID_MAP.get()?;
    let guard = store.lock().ok()?;
    let entry = guard.lookup_by_cgroup(cgroup_id)?;
    if entry.start_time.elapsed() < TTL {
        Some(entry.clone())
    } else {
        None
    }
}

/// Read the Host PID from `/proc/self/status` (NSpid field).
///
/// Example NSpid line: `NSpid:\t42\t12345`
/// - First number: namespace PID (what the process sees)
/// - Last number: Host PID (what Falco/eBPF sees)
///
/// Returns `ns_pid` unchanged when not in a PID namespace (NSpid has one entry).
fn read_host_pid() -> Option<u32> {
    #[cfg(target_os = "linux")]
    {
        let status = fs::read_to_string("/proc/self/status").ok()?;
        for line in status.lines() {
            if let Some(rest) = line.strip_prefix("NSpid:") {
                let pids: Vec<&str> = rest.split_whitespace().collect();
                // Last element is the Host PID (initial namespace).
                // If only one element, we're not in a namespace — it == getpid().
                return pids.last().and_then(|s| s.parse().ok());
            }
        }
        None
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// Read the cgroup v2 inode ID for the calling process.
///
/// On cgroup v2: `/proc/self/cgroup` → `0::/kubepods/...` → stat() the path
/// relative to `/sys/fs/cgroup` to get `st_ino` (the cgroup ID that
/// `bpf_get_current_cgroup_id()` returns).
///
/// Returns 0 if cgroup v2 is unavailable (cgroup v1, non-Linux).
fn read_cgroup_id() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        let cgroup = fs::read_to_string("/proc/self/cgroup").ok()?;
        for line in cgroup.lines() {
            // cgroup v2 line: "0::/path/to/cgroup"
            if let Some(path) = line.strip_prefix("0::") {
                let full_path = if path.starts_with('/') {
                    format!("/sys/fs/cgroup{}", path)
                } else {
                    format!("/sys/fs/cgroup/{}", path)
                };
                return fs::metadata(&full_path).ok().and_then(|meta| {
                    use std::os::unix::fs::MetadataExt;
                    Some(meta.ino())
                });
            }
        }
        None
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// Asynchronous Redis backup via simple TCP (fire-and-forget).
///
/// Writes two keys pointing to the same JSON value:
/// <ul>
///   <li><b>{@code pid_trace:{host_pid}}</b> — primary index, looked up by
///       Falco's {@code proc.pid} (Host PID).</li>
///   <li><b>{@code cgroup_trace:{cgroup_id}}</b> — reverse index, looked up
///       by Falco's {@code proc.cgroup.id}. Survives fork/exec/detach within
///       the same cgroup, covering grandchild processes where ppid fallback
///       breaks. Only written when {@code cgroup_id != 0} (cgroup v2).</li>
/// </ul>
///
/// Both keys share the same TTL (3600s) and are written in a single Redis
/// pipeline to keep the fire-and-forget overhead minimal.
fn redis_backup_async(
    host_pid: u32,
    cgroup_id: u64,
    trace_id: &str,
    session_id: &str,
    app_id: &str,
    tenant_id: &str,
) {
    let redis_url = match std::env::var("VIRBIUS_REDIS_URL") {
        Ok(url) if !url.is_empty() => url,
        _ => return,
    };
    let pid_key = format!("pid_trace:{}", host_pid);
    let value = serde_json::json!({
        "host_pid": host_pid,
        "cgroup_id": cgroup_id,
        "trace_id": trace_id,
        "session_id": session_id,
        "app_id": app_id,
        "tenant_id": tenant_id,
        "start_ts": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
    });
    let value_str = value.to_string().replace('\'', "\\'");

    // Build a pipelined command: always SET pid_trace, additionally SET
    // cgroup_trace when cgroup_id is available (non-zero, i.e. cgroup v2).
    let cmd = if cgroup_id != 0 {
        let cgroup_key = format!("cgroup_trace:{}", cgroup_id);
        format!(
            "SET {} '{}' EX 3600\r\nSET {} '{}' EX 3600\r\n",
            pid_key, value_str, cgroup_key, value_str
        )
    } else {
        format!("SET {} '{}' EX 3600\r\n", pid_key, value_str)
    };

    // Fire-and-forget: no connection reuse, no read response
    std::thread::spawn(move || {
        if let Ok(mut stream) = TcpStream::connect(&redis_url) {
            let _ = stream.write_all(cmd.as_bytes());
            let _ = stream.flush();
        }
    });
}

pub fn entry_to_audit_json(entry: &PidMapEntry) -> String {
    serde_json::json!({
        "host_pid": entry.host_pid,
        "ns_pid": entry.ns_pid,
        "cgroup_id": entry.cgroup_id,
        "trace_id": entry.trace_id,
        "session_id": entry.session_id,
        "app_id": entry.app_id,
        "tenant_id": entry.tenant_id,
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_register_lookup_by_host_pid() {
        // Use a synthetic host_pid that won't collide with real processes.
        // register_agent auto-detects host_pid, so we test lookup with the
        // real detected value.
        register_agent(0, "trace-1", "sess-1", "agent-1", "tenant-1");
        let host_pid = read_host_pid().unwrap_or_else(std::process::id);
        let entry = lookup_agent(host_pid).expect("should find entry by host_pid");
        assert_eq!(entry.trace_id, "trace-1");
        assert_eq!(entry.session_id, "sess-1");
        assert_eq!(entry.ns_pid, std::process::id());
        unregister_agent(host_pid);
        assert!(lookup_agent(host_pid).is_none());
    }

    #[test]
    fn test_lookup_by_cgroup() {
        register_agent(0, "trace-cg", "sess-cg", "agent-cg", "tenant-cg");
        let cgroup_id = read_cgroup_id().unwrap_or(0);
        if cgroup_id != 0 {
            let entry = lookup_by_cgroup(cgroup_id).expect("should find entry by cgroup_id");
            assert_eq!(entry.trace_id, "trace-cg");
        }
        // Cleanup
        let host_pid = read_host_pid().unwrap_or_else(std::process::id);
        unregister_agent(host_pid);
    }

    #[test]
    fn test_read_host_pid_matches_getpid_when_no_namespace() {
        // On non-container Linux, host_pid == ns_pid == getpid()
        let host_pid = read_host_pid();
        let ns_pid = std::process::id();
        if let Some(hp) = host_pid {
            assert_eq!(
                hp, ns_pid,
                "host_pid should equal getpid() when not in a PID namespace"
            );
        }
    }
}
