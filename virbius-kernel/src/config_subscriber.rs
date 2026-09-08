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

/// One resolve + apply round. `last_applied` dedupes SIGHUPs so the periodic resync is silent
/// when nothing changed.
fn apply_current(
    con: &mut redis::Connection,
    cfg: &NodeConfig,
    last_applied: &mut Option<PoolResolution>,
) -> Result<(), Box<dyn std::error::Error>> {
    let stable_revision = read_stable_revision(con, &cfg.tenant_id)?;
    let deploy_pointer = read_deploy_pointer(con, &cfg.tenant_id)?;
    match resolve_pool(stable_revision, &deploy_pointer, &cfg.node_id) {
        Some(res) => {
            if last_applied.as_ref() == Some(&res) {
                return Ok(());
            }
            let key = artifact_key(&cfg.tenant_id, res.revision);
            let yaml: Option<String> = redis::cmd("GET").arg(&key).query(con)?;
            match yaml {
                Some(y) => {
                    write_active_rules(&cfg.rules_dir, &cfg.tenant_id, &y)?;
                    send_sighup_to_falco();
                    println!(
                        "config_subscriber: applied falco rules tenant={} revision={} pool={}",
                        cfg.tenant_id, res.revision, res.pool
                    );
                    *last_applied = Some(res);
                }
                None => {
                    // Artifact not (yet) written; keep current rules, retry on next tick.
                    eprintln!("config_subscriber: artifact missing: {key}");
                }
            }
        }
        None => {
            // Nothing deployed for this tenant: ensure no stale managed file stays loaded.
            if last_applied.is_some() {
                remove_file_if_exists(&active_file(&cfg.rules_dir, &cfg.tenant_id))?;
                send_sighup_to_falco();
                println!(
                    "config_subscriber: cleared falco rules tenant={} (no active revision)",
                    cfg.tenant_id
                );
                *last_applied = None;
            }
        }
    }
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
    // Dedicated pub/sub connection (a subscribed connection cannot issue other commands).
    let mut pubsub_con = match client.get_connection() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config_subscriber: failed to get pubsub connection: {e}");
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

    let mut pubsub = pubsub_con.as_pubsub();
    if let Err(e) = pubsub.subscribe(&[DEPLOY_CHANGED_CHANNEL, FALCO_CHANGED_CHANNEL]) {
        eprintln!("config_subscriber: subscribe failed: {e}");
        return;
    }
    if let Err(e) = pubsub.set_read_timeout(Some(READ_TIMEOUT)) {
        eprintln!("config_subscriber: set_read_timeout failed: {e}");
        return;
    }

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
                    if let Err(e) = apply_current(&mut con, &cfg, &mut last_applied) {
                        eprintln!("config_subscriber: apply failed: {e}");
                    }
                    last_sync = Instant::now();
                }
            }
            Err(e) if e.is_timeout() => {
                // Read timeout: fall through to the resync check below.
            }
            Err(e) => {
                eprintln!("config_subscriber: pubsub error: {e}");
                std::thread::sleep(Duration::from_secs(1));
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

fn send_sighup_to_falco() {
    #[cfg(target_os = "linux")]
    {
        let output = std::process::Command::new("pgrep").arg("falco").output();
        if let Ok(output) = output {
            let pids = String::from_utf8_lossy(&output.stdout);
            for line in pids.lines() {
                if let Ok(pid) = line.trim().parse::<i32>() {
                    unsafe {
                        libc::kill(pid, libc::SIGHUP);
                    }
                }
            }
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = (); // SIGHUP only supported on Linux
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
