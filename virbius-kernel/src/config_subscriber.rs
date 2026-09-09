//! Falco rule config subscriber with node-level gray (canary) resolution.
//!
//! Each kernel node resolves its own pool from the same facts the control plane maintains:
//!
//! - `virbius:falco:pointer:{tenant}`  → current stable revision
//! - `virbius:deploy:active:{tenant}`  → active node-gray pointer (canary revision + percent)
//!
//! Notifications arrive over two Pub/Sub channels (`virbius:deploy:changed`,
//! `virbius:falco:rule-changed`). They are pool-agnostic "re-resolve now" hints; a periodic
//! resync covers missed messages. The node always applies exactly ONE rule set — its pool's —
//! into `{tenant}-active.yaml` and SIGHUPs falco only when the applied revision changes.

use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

const DEPLOY_CHANGED_CHANNEL: &str = "virbius:deploy:changed";
const FALCO_CHANGED_CHANNEL: &str = "virbius:falco:rule-changed";

const DEFAULT_RULES_DIR: &str = "/etc/falco/falco_rules.d";

/// Pub/Sub is fire-and-forget; re-resolve at least this often to converge after missed
/// messages, restarts mid-gray, or artifact rewrites.
const RESYNC_INTERVAL: Duration = Duration::from_secs(30);

/// How long a blocking pub/sub read waits before we check the resync timer.
const READ_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone)]
pub struct NodeConfig {
    pub redis_url: String,
    pub tenant_id: String,
    pub node_id: String,
    pub rules_dir: PathBuf,
}

impl NodeConfig {
    pub fn from_env() -> Self {
        let redis_url = std::env::var("VIRBIUS_REDIS_URL")
            .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
        let tenant_id =
            std::env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".to_string());
        let node_id = std::env::var("VIRBIUS_NODE_ID")
            .ok()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(hostname_fallback);
        let rules_dir = std::env::var("VIRBIUS_FALCO_RULES_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from(DEFAULT_RULES_DIR));
        Self {
            redis_url,
            tenant_id,
            node_id,
            rules_dir,
        }
    }
}

/// Node identity for bucketing must be stable across restarts, otherwise the node drifts
/// between pools. Prefer explicit VIRBIUS_NODE_ID; fall back to the host name.
fn hostname_fallback() -> String {
    if let Ok(h) = std::env::var("HOSTNAME") {
        if !h.is_empty() {
            return h;
        }
    }
    if let Ok(raw) = fs::read_to_string("/etc/hostname") {
        let h = raw.trim();
        if !h.is_empty() {
            return h.to_string();
        }
    }
    "unknown-node".to_string()
}

/// CRC32C bucket (0-99), matching `BucketCalculator.bucketOf()` on the control plane and
/// `bucket_of()` in virbius-core's edge sync.
fn bucket_of(node_id: &str) -> u64 {
    use std::hash::Hasher;
    let mut hasher = crc32c::Crc32cHasher::new(0);
    hasher.write(node_id.as_bytes());
    hasher.finish() % 100
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PoolResolution {
    pub revision: i64,
    pub pool: &'static str,
}

/// Pure pool resolution, kept separate from Redis I/O for unit testing.
///
/// Deploy-pointer existence == active gray (the pointer is cleared on finalize/rollback), so no
/// state whitelist is needed. Canary revision and percent come from the deploy pointer; the
/// stable revision always comes from the falco pointer.
fn resolve_pool(
    stable_revision: i64,
    deploy_pointer: &HashMap<String, String>,
    node_id: &str,
) -> Option<PoolResolution> {
    if !deploy_pointer.is_empty() {
        let canary_rev = parse_i64(deploy_pointer.get("canary_falco_revision"));
        let percent = parse_i64(deploy_pointer.get("canary_percent"));
        if canary_rev > 0 && (percent >= 100 || (bucket_of(node_id) as i64) < percent) {
            return Some(PoolResolution {
                revision: canary_rev,
                pool: "canary",
            });
        }
    }
    if stable_revision > 0 {
        return Some(PoolResolution {
            revision: stable_revision,
            pool: "stable",
        });
    }
    None
}

fn parse_i64(raw: Option<&String>) -> i64 {
    raw.and_then(|s| s.trim().parse().ok()).unwrap_or(0)
}

/// Delete the managed rules file only when this node previously applied a revision AND
/// there is no active deploy pointer. An in-flight gray with stable_revision=0 must keep
/// last-known-good rules (out-of-bucket nodes on a first deploy).
fn should_clear_on_none(
    deploy_pointer: &HashMap<String, String>,
    last_applied: &Option<PoolResolution>,
) -> bool {
    last_applied.is_some() && deploy_pointer.is_empty()
}

fn falco_pointer_key(tenant_id: &str) -> String {
    format!("virbius:falco:pointer:{tenant_id}")
}

fn deploy_pointer_key(tenant_id: &str) -> String {
    format!("virbius:deploy:active:{tenant_id}")
}

fn artifact_key(tenant_id: &str, revision: i64) -> String {
    format!("virbius:falco:artifact:{tenant_id}:{revision}")
}

fn active_file(rules_dir: &Path, tenant_id: &str) -> PathBuf {
    rules_dir.join(format!("{tenant_id}-active.yaml"))
}

/// Legacy per-target files written by the stream-based subscriber. Both were loaded by falco
/// simultaneously, so they must go or the canary/full rules stay active forever.
fn legacy_files(rules_dir: &Path, tenant_id: &str) -> [PathBuf; 2] {
    [
        rules_dir.join(format!("{tenant_id}-canary.yaml")),
        rules_dir.join(format!("{tenant_id}-full.yaml")),
    ]
}

fn read_stable_revision(con: &mut redis::Connection, tenant_id: &str) -> redis::RedisResult<i64> {
    let raw: Option<String> = redis::cmd("HGET")
        .arg(falco_pointer_key(tenant_id))
        .arg("stable_revision")
        .query(con)?;
    Ok(raw
        .as_deref()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(0))
}

fn read_deploy_pointer(
    con: &mut redis::Connection,
    tenant_id: &str,
) -> redis::RedisResult<HashMap<String, String>> {
    redis::cmd("HGETALL")
        .arg(deploy_pointer_key(tenant_id))
        .query(con)
}

/// Extracts `tenant_id` from a notification payload. Fail-open (None) so malformed payloads
/// still trigger a resync instead of being silently dropped.
fn message_tenant(payload: &str) -> Option<String> {
    serde_json::from_str::<serde_json::Value>(payload)
        .ok()
        .and_then(|v| v.get("tenant_id")?.as_str().map(String::from))
}

fn write_active_rules(rules_dir: &Path, tenant_id: &str, yaml: &str) -> std::io::Result<()> {
    fs::create_dir_all(rules_dir)?;
    let target = active_file(rules_dir, tenant_id);
    let tmp = rules_dir.join(format!("{tenant_id}-active.yaml.tmp"));
    {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(yaml.as_bytes())?;
        file.flush()?;
    }
    fs::rename(&tmp, &target)?;
    Ok(())
}

fn remove_file_if_exists(path: &Path) -> std::io::Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

/// Outcome of one resolve+apply round, reported to the kernel node registry so the control
/// plane can show per-node convergence (advisory only — never blocks a rollout).
struct NodeReport {
    status: &'static str, // "applied" | "cleared" | "error" | "idle"
    revision: i64,        // last successfully applied revision, 0 when nothing is applied
    pool: String,         // "stable" | "canary" | "none"
    sighup_pids: Vec<i32>,
    error: String, // empty unless status == "error"
}

/// Report baseline reflecting what is currently on disk (i.e. `last_applied`).
fn baseline_report(last_applied: &Option<PoolResolution>) -> NodeReport {
    match last_applied {
        Some(res) => NodeReport {
            status: "applied",
            revision: res.revision,
            pool: res.pool.to_string(),
            sighup_pids: Vec::new(),
            error: String::new(),
        },
        None => NodeReport {
            status: "idle",
            revision: 0,
            pool: "none".to_string(),
            sighup_pids: Vec::new(),
            error: String::new(),
        },
    }
}

/// Reports this node's applied state to the kernel node registry
/// (`virbius:nodes:kernel:{tenant}:{node_id}`, 60s TTL), following the same heartbeat pattern
/// as engine/gateway nodes. The control plane reads it for advisory convergence display and
/// for including kernel nodes in canary bucket calculations.
fn report_node_status(con: &mut redis::Connection, cfg: &NodeConfig, report: &NodeReport) {
    let key = format!("virbius:nodes:kernel:{}:{}", cfg.tenant_id, cfg.node_id);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let pids = report
        .sighup_pids
        .iter()
        .map(|p| p.to_string())
        .collect::<Vec<_>>()
        .join(",");
    let fields: Vec<(&str, String)> = vec![
        ("pool", report.pool.clone()),
        ("revision", report.revision.to_string()),
        ("status", report.status.to_string()),
        ("error", report.error.clone()),
        ("sighup_pids", pids),
        ("last_seen", now.to_string()),
    ];
    let result: redis::RedisResult<()> = redis::pipe()
        .atomic()
        .hset_multiple(&key, &fields)
        .expire(&key, 60)
        .query(con);
    if let Err(e) = result {
        eprintln!("config_subscriber: failed to report node status: {e}");
    }
}

/// One resolve + apply round. `last_applied` dedupes SIGHUPs so the periodic resync is silent
/// when nothing changed.
fn apply_current(
    con: &mut redis::Connection,
    cfg: &NodeConfig,
    last_applied: &mut Option<PoolResolution>,
) -> Result<(), Box<dyn std::error::Error>> {
    let stable_revision = read_stable_revision(con, &cfg.tenant_id)?;
    let deploy_pointer = read_deploy_pointer(con, &cfg.tenant_id)?;
    let report = match resolve_pool(stable_revision, &deploy_pointer, &cfg.node_id) {
        Some(res) => {
            if last_applied.as_ref() == Some(&res) {
                // Nothing changed; the heartbeat refresh still happens below.
                baseline_report(last_applied)
            } else {
                let key = artifact_key(&cfg.tenant_id, res.revision);
                let yaml: Option<String> = redis::cmd("GET").arg(&key).query(con)?;
                match yaml {
                    Some(y) => match write_active_rules(&cfg.rules_dir, &cfg.tenant_id, &y) {
                        Ok(()) => {
                            let pids = send_sighup_to_falco();
                            println!(
                                "config_subscriber: applied falco rules tenant={} revision={} pool={} sighup_pids={:?}",
                                cfg.tenant_id, res.revision, res.pool, pids
                            );
                            *last_applied = Some(res);
                            let mut r = baseline_report(last_applied);
                            r.sighup_pids = pids;
                            r
                        }
                        Err(e) => {
                            eprintln!("config_subscriber: failed to write rules: {e}");
                            let mut r = baseline_report(last_applied);
                            r.status = "error";
                            r.error = format!("write rules: {e}");
                            r
                        }
                    },
                    None => {
                        // Artifact not (yet) written; keep current rules, retry on next tick.
                        eprintln!("config_subscriber: artifact missing: {key}");
                        let mut r = baseline_report(last_applied);
                        r.status = "error";
                        r.error = format!("artifact missing: {key}");
                        r
                    }
                }
            }
        }
        None => {
            // Nothing for this node to run. Only delete the managed file when there is also
            // no active gray — otherwise a first-deploy (stable_revision=0) would wipe
            // last-known-good rules on out-of-bucket nodes.
            if should_clear_on_none(&deploy_pointer, last_applied) {
                match remove_file_if_exists(&active_file(&cfg.rules_dir, &cfg.tenant_id)) {
                    Ok(()) => {
                        let pids = send_sighup_to_falco();
                        println!(
                            "config_subscriber: cleared falco rules tenant={} (no active revision)",
                            cfg.tenant_id
                        );
                        *last_applied = None;
                        let mut r = baseline_report(last_applied);
                        r.status = "cleared";
                        r.sighup_pids = pids;
                        r
                    }
                    Err(e) => {
                        eprintln!("config_subscriber: failed to clear rules: {e}");
                        let mut r = baseline_report(last_applied);
                        r.status = "error";
                        r.error = format!("clear rules: {e}");
                        r
                    }
                }
            } else {
                if !deploy_pointer.is_empty() && last_applied.is_none() {
                    eprintln!(
                        "config_subscriber: no stable revision during gray tenant={} node={}; keeping current rules",
                        cfg.tenant_id, cfg.node_id
                    );
                    let mut r = baseline_report(last_applied);
                    r.status = "waiting_stable";
                    r.error = "no stable revision during gray".to_string();
                    r
                } else {
                    baseline_report(last_applied)
                }
            }
        }
    };
    report_node_status(con, cfg, &report);
    Ok(())
}

pub fn run() {
    run_with(NodeConfig::from_env());
}

pub fn run_with(cfg: NodeConfig) {
    println!(
        "config_subscriber: starting tenant={} node_id={} rules_dir={}",
        cfg.tenant_id,
        cfg.node_id,
        cfg.rules_dir.display()
    );

    let client = match redis::Client::open(cfg.redis_url.as_str()) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config_subscriber: failed to connect to Redis: {e}");
            return;
        }
    };
    // Command connection: resolve pointers + fetch artifacts.
    let mut con = match client.get_connection() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config_subscriber: failed to get connection: {e}");
            return;
        }
    };

    // Remove legacy per-target files before applying anything (K5).
    for legacy in legacy_files(&cfg.rules_dir, &cfg.tenant_id) {
        if legacy.exists() {
            if let Err(e) = remove_file_if_exists(&legacy) {
                eprintln!(
                    "config_subscriber: failed to remove legacy file {}: {e}",
                    legacy.display()
                );
            } else {
                println!(
                    "config_subscriber: removed legacy rules file {}",
                    legacy.display()
                );
            }
        }
    }

    // Boot sync: converge to the current pointer state even if we missed notifications.
    let mut last_applied: Option<PoolResolution> = None;
    if let Err(e) = apply_current(&mut con, &cfg, &mut last_applied) {
        eprintln!("config_subscriber: initial sync failed: {e}");
    }
    let mut last_sync = Instant::now();

    // Pub/Sub is an optimization. Subscribe failures must not exit the process — the
    // periodic resync below is what actually keeps the node converged.
    loop {
        match client.get_connection() {
            Ok(mut pubsub_con) => {
                let mut pubsub = pubsub_con.as_pubsub();
                if let Err(e) = pubsub.subscribe(&[DEPLOY_CHANGED_CHANNEL, FALCO_CHANGED_CHANNEL]) {
                    eprintln!("config_subscriber: subscribe failed: {e}; retrying");
                } else if let Err(e) = pubsub.set_read_timeout(Some(READ_TIMEOUT)) {
                    eprintln!("config_subscriber: set_read_timeout failed: {e}; retrying");
                } else {
                    loop {
                        match pubsub.get_message() {
                            Ok(msg) => {
                                let payload: String = match msg.get_payload() {
                                    Ok(p) => p,
                                    Err(e) => {
                                        eprintln!("config_subscriber: bad payload: {e}");
                                        continue;
                                    }
                                };
                                // Ignore notifications for other tenants.
                                if message_tenant(&payload).is_none_or(|t| t == cfg.tenant_id) {
                                    if let Err(e) = apply_current(&mut con, &cfg, &mut last_applied)
                                    {
                                        eprintln!("config_subscriber: apply failed: {e}");
                                    }
                                    last_sync = Instant::now();
                                }
                            }
                            Err(e) if e.is_timeout() => {
                                // Read timeout: fall through to the resync check below.
                            }
                            Err(e) => {
                                eprintln!("config_subscriber: pubsub error: {e}; resubscribing");
                                break;
                            }
                        }

                        if last_sync.elapsed() >= RESYNC_INTERVAL {
                            if let Err(e) = apply_current(&mut con, &cfg, &mut last_applied) {
                                eprintln!("config_subscriber: resync failed: {e}");
                            }
                            last_sync = Instant::now();
                        }
                    }
                }
            }
            Err(e) => {
                eprintln!("config_subscriber: pubsub connection failed: {e}; retrying");
            }
        }

        if last_sync.elapsed() >= RESYNC_INTERVAL {
            if let Err(e) = apply_current(&mut con, &cfg, &mut last_applied) {
                eprintln!("config_subscriber: resync failed: {e}");
            }
            last_sync = Instant::now();
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

fn send_sighup_to_falco() -> Vec<i32> {
    #[cfg(target_os = "linux")]
    {
        // `pgrep -x falco`: exact comm match. A substring match ("pgrep falco") also matches
        // this subscriber when its binary name contains "falco" (e.g. falco-config-subscriber,
        // comm truncated to "falco-config-su"), causing a self-SIGHUP kill (exit 129).
        let output = std::process::Command::new("pgrep")
            .arg("-x")
            .arg("falco")
            .output();
        let mut signaled = Vec::new();
        if let Ok(output) = output {
            let pids = String::from_utf8_lossy(&output.stdout);
            let self_pid = std::process::id() as i32;
            for line in pids.lines() {
                if let Ok(pid) = line.trim().parse::<i32>() {
                    if pid == self_pid {
                        continue; // defense in depth: never signal ourselves
                    }
                    unsafe {
                        libc::kill(pid, libc::SIGHUP);
                    }
                    signaled.push(pid);
                }
            }
        }
        signaled
    }
    #[cfg(not(target_os = "linux"))]
    {
        Vec::new() // SIGHUP only supported on Linux
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dp(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn bucket_matches_control_plane_crc32c() {
        // Golden values computed with java.util.zip.CRC32C (BucketCalculator.bucketOf).
        assert_eq!(bucket_of("node-a"), 53);
        assert_eq!(bucket_of("node-b"), 93);
        assert_eq!(bucket_of("kernel-01.prod"), 15);
        assert_eq!(bucket_of(""), 0);
        assert_eq!(bucket_of("x"), 31);
        assert_eq!(bucket_of("device-123"), 68);
        assert_eq!(bucket_of("i-0abcdef"), 90);
    }

    #[test]
    fn resolves_stable_when_no_gray_active() {
        assert_eq!(
            resolve_pool(5, &HashMap::new(), "node-a"),
            Some(PoolResolution {
                revision: 5,
                pool: "stable"
            })
        );
    }

    #[test]
    fn resolves_none_when_nothing_deployed() {
        assert_eq!(resolve_pool(0, &HashMap::new(), "node-a"), None);
    }

    #[test]
    fn canary_bucket_gets_canary_revision() {
        // bucket_of("kernel-01.prod") == 15 < 50 → canary
        let pointer = dp(&[("canary_falco_revision", "9"), ("canary_percent", "50")]);
        assert_eq!(
            resolve_pool(5, &pointer, "kernel-01.prod"),
            Some(PoolResolution {
                revision: 9,
                pool: "canary"
            })
        );
    }

    #[test]
    fn stable_bucket_keeps_stable_revision() {
        // bucket_of("node-b") == 93 >= 50 → stable
        let pointer = dp(&[("canary_falco_revision", "9"), ("canary_percent", "50")]);
        assert_eq!(
            resolve_pool(5, &pointer, "node-b"),
            Some(PoolResolution {
                revision: 5,
                pool: "stable"
            })
        );
    }

    #[test]
    fn full_percent_moves_everyone_to_canary() {
        let pointer = dp(&[("canary_falco_revision", "9"), ("canary_percent", "100")]);
        assert_eq!(
            resolve_pool(5, &pointer, "node-b"),
            Some(PoolResolution {
                revision: 9,
                pool: "canary"
            })
        );
    }

    #[test]
    fn gray_without_falco_revision_keeps_stable() {
        // A cloud/gateway-only gray must not affect kernel nodes.
        let pointer = dp(&[("canary_falco_revision", "0"), ("canary_percent", "50")]);
        assert_eq!(
            resolve_pool(5, &pointer, "node-a"),
            Some(PoolResolution {
                revision: 5,
                pool: "stable"
            })
        );
    }

    #[test]
    fn gray_without_stable_and_outside_bucket_resolves_none() {
        // bucket_of("node-b") == 93 >= 50, and no stable revision exists yet → nothing to run
        let pointer = dp(&[("canary_falco_revision", "9"), ("canary_percent", "50")]);
        assert_eq!(resolve_pool(0, &pointer, "node-b"), None);
        // apply_current must NOT delete last-known-good files in this case
        let last = Some(PoolResolution {
            revision: 1,
            pool: "canary",
        });
        assert!(!should_clear_on_none(&pointer, &last));
    }

    #[test]
    fn clears_only_when_nothing_is_deployed() {
        let last = Some(PoolResolution {
            revision: 1,
            pool: "stable",
        });
        assert!(should_clear_on_none(&HashMap::new(), &last));
        assert!(!should_clear_on_none(&HashMap::new(), &None));
    }

    #[test]
    fn pending_pointer_with_zero_percent_keeps_stable() {
        let pointer = dp(&[("canary_falco_revision", "9"), ("canary_percent", "0")]);
        assert_eq!(
            resolve_pool(5, &pointer, "node-a"),
            Some(PoolResolution {
                revision: 5,
                pool: "stable"
            })
        );
    }

    #[test]
    fn message_tenant_parses_json_and_fails_open() {
        assert_eq!(
            message_tenant("{\"tenant_id\":\"acme\",\"revision\":9,\"reason\":\"canary\"}"),
            Some("acme".to_string())
        );
        assert_eq!(message_tenant("not json"), None);
        assert_eq!(message_tenant("{\"reason\":\"x\"}"), None);
    }
}
