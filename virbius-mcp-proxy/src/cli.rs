//! CLI surface for the proxy binary: `--config <path>`, `doctor` (pre-flight
//! validation) and `enroll` (fetch a license + public key from control).
//!
//! Design notes (integration-friendliness work, P0/P1):
//! * `--config` is explicit — a typo'd path or malformed TOML exits instead of
//!   silently running with defaults.
//! * `doctor` answers "why won't this thing connect" in one command: config
//!   summary, license/key validation with the split error taxonomy, and
//!   connectivity probes. Connectivity problems are WARNINGS (the proxy has
//!   failover); config/license problems are FAILURES.
//! * `enroll` turns "hand-assemble license.jwt + license_pub.pem" into one
//!   command. Security posture: the admin credential is read from the
//!   `VIRBIUS_ADMIN_API_KEY` env var only — never a CLI flag (shell history /
//!   `ps` leakage) — files are written mode 0600, and the JWT itself is never
//!   printed.

use crate::config::ProxyConfig;
use std::path::PathBuf;

/// Parsed command line.
pub struct Cli {
    pub config_path: Option<PathBuf>,
    pub command: Command,
}

pub enum Command {
    /// Run the proxy (default).
    Run,
    Doctor,
    Enroll(EnrollArgs),
}

#[derive(Debug, Default)]
pub struct EnrollArgs {
    pub control: Option<String>,
    pub tenant: Option<String>,
    pub app_id: Option<String>,
    pub agent_name: Option<String>,
    pub tools: Vec<String>,
    pub out_dir: Option<PathBuf>,
    /// Revoke the app's active license before issuing a new one.
    pub revoke: bool,
}

pub fn print_help() {
    println!(
        "virbius-mcp-proxy {} — MCP security proxy for VirbiusAgent

USAGE:
    virbius-mcp-proxy [OPTIONS] [COMMAND]

COMMANDS:
    run                     Run the proxy (default)
    doctor                  Pre-flight check: config, license, connectivity
    enroll                  Fetch a license + public key from control and
                            write them to disk

OPTIONS:
    -c, --config <PATH>     Explicit config file (must parse; no silent
                            fallback to the search paths)
    -h, --help              Print help
    -V, --version           Print version

ENROLL OPTIONS:
    --control <URL>         Control base URL (falls back to VIRBIUS_BASE_URL,
                            VIRBIUS_CONTROL_URL, then the config file)
    --tenant <ID>           Tenant id (required)
    --app-id <ID>           App id (required)
    --agent-name <NAME>     Agent name (defaults to the app id)
    --tools <A,B,C>         Comma-separated tool names: registered in the
                            tenant tool registry AND allowlisted in the license
    --out-dir <DIR>         Where to write agent-license.jwt and
                            license_pub.pem (default: current directory)
    --revoke                Revoke the app's active license first (required
                            when re-enrolling an app that already has one)

ENVIRONMENT:
    VIRBIUS_ADMIN_API_KEY   Control admin credential for `enroll`, sent as
                            X-Virbius-Api-Key. Env-only by design — never a
                            CLI flag.
    VIRBIUS_BASE_URL        Opt-in single base URL: derives control,
                            engine (same host, :8082) and the evaluate-token
                            exchange endpoints. Explicit per-endpoint env vars
                            and config-file values always win.
    See PROXY_CONFIG.md for the full variable list.",
        env!("CARGO_PKG_VERSION")
    );
}

/// Parse the command line. Exits(0) on --help/--version, exits(2) on bad args.
pub fn parse_cli() -> Cli {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut config_path: Option<PathBuf> = None;
    let mut kind: Option<String> = None;
    let mut enroll = EnrollArgs::default();
    let mut saw_enroll_flag = false;

    let mut i = 0;
    while i < args.len() {
        let a = args[i].clone();
        match a.as_str() {
            "--help" | "-h" => {
                print_help();
                std::process::exit(0);
            }
            "--version" | "-V" => {
                println!("virbius-mcp-proxy {}", env!("CARGO_PKG_VERSION"));
                std::process::exit(0);
            }
            "--config" | "-c" => {
                i += 1;
                match args.get(i) {
                    Some(v) => config_path = Some(PathBuf::from(v)),
                    None => {
                        eprintln!("error: --config requires a path");
                        std::process::exit(2);
                    }
                }
            }
            "run" | "doctor" | "enroll" => {
                if kind.is_some() {
                    eprintln!("error: unexpected second command '{a}'");
                    std::process::exit(2);
                }
                kind = Some(a);
            }
            "--control" | "--tenant" | "--app-id" | "--agent-name" | "--tools" | "--out-dir" => {
                saw_enroll_flag = true;
                i += 1;
                let Some(v) = args.get(i).cloned() else {
                    eprintln!("error: {a} requires a value");
                    std::process::exit(2);
                };
                match a.as_str() {
                    "--control" => enroll.control = Some(v),
                    "--tenant" => enroll.tenant = Some(v),
                    "--app-id" => enroll.app_id = Some(v),
                    "--agent-name" => enroll.agent_name = Some(v),
                    "--tools" => {
                        enroll.tools = v
                            .split(',')
                            .map(|s| s.trim().to_string())
                            .filter(|s| !s.is_empty())
                            .collect();
                    }
                    "--out-dir" => enroll.out_dir = Some(PathBuf::from(v)),
                    _ => unreachable!(),
                }
            }
            "--revoke" => {
                saw_enroll_flag = true;
                enroll.revoke = true;
            }
            other => {
                eprintln!("error: unknown argument '{other}'\n");
                print_help();
                std::process::exit(2);
            }
        }
        i += 1;
    }

    let command = match kind.as_deref() {
        None | Some("run") => {
            if saw_enroll_flag {
                eprintln!("error: enroll options require the `enroll` command");
                std::process::exit(2);
            }
            Command::Run
        }
        Some("doctor") => {
            if saw_enroll_flag {
                eprintln!("error: enroll options require the `enroll` command");
                std::process::exit(2);
            }
            Command::Doctor
        }
        Some("enroll") => Command::Enroll(enroll),
        _ => unreachable!(),
    };

    Cli { config_path, command }
}

// ─── doctor ─────────────────────────────────────────────────────────────

/// Pre-flight validation. Returns the process exit code.
pub async fn doctor(cfg: ProxyConfig) -> i32 {
    let mut failures = 0;
    println!("virbius-mcp-proxy {} — doctor", env!("CARGO_PKG_VERSION"));
    println!("  transport       : {}", cfg.proxy.listen);
    for u in &cfg.proxy.upstreams {
        println!("  upstream        : {} ({})", u.name, u.url);
    }
    println!("  engine          : {}", cfg.security.engine_url);
    println!("  control         : {}", cfg.security.control_base_url);
    if cfg.auth.control_base_url.is_empty() {
        println!("  token exchange  : disabled");
    } else {
        println!("  token exchange  : {}", cfg.auth.control_base_url);
    }
    println!(
        "  audit           : {}",
        if cfg.audit_use_kafka() {
            format!("kafka({})", cfg.audit.kafka_topic)
        } else {
            "disabled".to_string()
        }
    );
    println!(
        "  trace           : {}",
        if cfg.trace_use_kafka() {
            format!("kafka({})", cfg.trace.kafka_topic)
        } else {
            "disabled".to_string()
        }
    );
    println!("  fallback policy : {}", cfg.security.fallback_policy);

    // ── license public key ──
    let mut pem = String::new();
    if cfg.security.license_public_key.is_empty() {
        println!("  [WARN] license_public_key not configured — agent-supplied licenses cannot verify");
    } else {
        match std::fs::read_to_string(&cfg.security.license_public_key) {
            Ok(raw) => match virbius_core::license::validate_public_key_pem(&raw) {
                Ok(()) => {
                    println!(
                        "  public key      : ok ({})",
                        cfg.security.license_public_key
                    );
                    pem = raw;
                }
                Err(e) => {
                    println!(
                        "  [FAIL] public key {} : {e}",
                        cfg.security.license_public_key
                    );
                    failures += 1;
                }
            },
            Err(e) => {
                println!(
                    "  [FAIL] cannot read public key {}: {e}",
                    cfg.security.license_public_key
                );
                failures += 1;
            }
        }
    }

    // ── fallback license ──
    if cfg.security.license_file.is_empty() {
        println!("  [WARN] license_file not configured — no fallback license (agents must pass _meta.license_jwt)");
    } else {
        match std::fs::read_to_string(&cfg.security.license_file) {
            Ok(raw) => {
                let jwt = raw.trim();
                match crate::session::extract_claims_from_jwt(jwt) {
                    None => {
                        println!("  [FAIL] license_file {} : malformed JWT", cfg.security.license_file);
                        failures += 1;
                    }
                    Some(claims) => {
                        let app_id = claims
                            .get("app_id")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        println!(
                            "  license         : app_id={app_id} tenant={} tools={}",
                            claims.get("tenant_id").and_then(|v| v.as_str()).unwrap_or("?"),
                            claims
                                .get("allowed_tools")
                                .and_then(|v| v.as_array())
                                .map(|a| a.len())
                                .unwrap_or(0)
                        );
                        if let Some(exp) = claims.get("exp").and_then(|v| v.as_i64()) {
                            let now = std::time::SystemTime::now()
                                .duration_since(std::time::UNIX_EPOCH)
                                .map(|d| d.as_secs() as i64)
                                .unwrap_or(0);
                            let days = (exp - now) / 86_400;
                            if days < 0 {
                                println!("  [FAIL] license expired {} day(s) ago", -days);
                                failures += 1;
                            } else {
                                println!("  license expiry  : in {days} day(s)");
                            }
                        }
                        if !pem.is_empty() {
                            match virbius_core::License::verify(jwt, &pem, &app_id) {
                                Ok(_) => println!("  license verify  : ok"),
                                Err(e) => {
                                    println!("  [FAIL] license verify: {e}");
                                    failures += 1;
                                }
                            }
                        } else {
                            println!("  [WARN] license not verified (no usable public key)");
                        }
                    }
                }
            }
            Err(e) => {
                println!(
                    "  [FAIL] cannot read license_file {}: {e}",
                    cfg.security.license_file
                );
                failures += 1;
            }
        }
    }

    // ── connectivity (warnings: the proxy fail-overs) ──
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build();
    if let Ok(client) = client {
        let engine = format!("{}/admin/health", cfg.security.engine_url.trim_end_matches('/'));
        probe(&client, "engine", &engine).await;
        let control = format!(
            "{}/api/v1/health",
            cfg.security.control_base_url.trim_end_matches('/')
        );
        probe(&client, "control", &control).await;
        if !cfg.auth.control_base_url.is_empty() {
            let auth = format!(
                "{}/api/v1/health",
                cfg.auth.control_base_url.trim_end_matches('/')
            );
            probe(&client, "token exchange", &auth).await;
        }
        for u in &cfg.proxy.upstreams {
            probe(&client, &format!("upstream {}", u.name), &u.url).await;
        }
    }

    if failures == 0 {
        println!("doctor: OK");
        0
    } else {
        println!("doctor: {failures} failure(s)");
        1
    }
}

async fn probe(client: &reqwest::Client, what: &str, url: &str) {
    // Any HTTP response means the endpoint is reachable (404 still proves
    // the server is there); only transport errors count as unreachable.
    match client.get(url).send().await {
        Ok(resp) => println!("  reachable       : {what} (HTTP {})", resp.status().as_u16()),
        Err(e) => println!("  [WARN] unreachable: {what} at {url} ({e})"),
    }
}

// ─── enroll ─────────────────────────────────────────────────────────────

/// Issue a license for the app and write the credential files. Returns the
/// process exit code.
pub async fn enroll(args: &EnrollArgs, cfg: ProxyConfig) -> i32 {
    let Some(tenant) = args.tenant.clone() else {
        eprintln!("error: enroll requires --tenant <id>");
        return 2;
    };
    let Some(app_id) = args.app_id.clone() else {
        eprintln!("error: enroll requires --app-id <id>");
        return 2;
    };
    let agent_name = args.agent_name.clone().unwrap_or_else(|| app_id.clone());

    let control = args
        .control
        .clone()
        .or_else(|| std::env::var("VIRBIUS_BASE_URL").ok())
        .or_else(|| std::env::var("VIRBIUS_CONTROL_URL").ok())
        .unwrap_or_else(|| cfg.security.control_base_url.clone())
        .trim()
        .trim_end_matches('/')
        .to_string();
    if control.is_empty() {
        eprintln!("error: no control URL (use --control, VIRBIUS_BASE_URL or VIRBIUS_CONTROL_URL)");
        return 2;
    }

    // Admin credential: env-only by design (shell history / ps leakage).
    let api_key = std::env::var("VIRBIUS_ADMIN_API_KEY")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());

    let client = match reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("error: http client: {e}");
            return 1;
        }
    };

    let base = format!("{control}/api/v1/admin/tenants/{tenant}");

    // 1) Ensure the tenant exists (409 = already there, fine).
    let (st, body) = http_json(
        &client,
        api_key.as_deref(),
        reqwest::Method::POST,
        &format!("{control}/api/v1/admin/tenants"),
        Some(&serde_json::json!({"tenant_id": tenant, "name": tenant})),
    )
    .await;
    if !(200..300).contains(&st) && st != 409 {
        eprintln!("error: create tenant failed: HTTP {st} {}", brief(&body));
        return 1;
    }

    // 2) Register the tools (allowlist does not depend on the registry; treat
    //    failures as warnings so re-enrolment stays idempotent).
    let mut tools = args.tools.clone();
    tools.sort();
    tools.dedup();
    for tool in &tools {
        let (st, body) = http_json(
            &client,
            api_key.as_deref(),
            reqwest::Method::POST,
            &format!("{base}/tools"),
            Some(&serde_json::json!({
                "tool_name": tool, "risk_class": "low", "sandbox_type": "none",
                "timeout_ms": 30000, "fast_path": true, "approval_mode": "lax",
            })),
        )
        .await;
        if st >= 400 {
            println!("warn: tool registry '{tool}' -> HTTP {st} {} (continuing)", brief(&body));
        }
    }

    // 3) Issue the license; on 409 optionally revoke + retry.
    let issue_url = format!("{base}/licenses/issue");
    let issue_body = serde_json::json!({
        "app_id": app_id, "agent_name": agent_name,
        "allowed_tools": tools, "risk_quota": 100,
    });
    let (mut st, mut body) = http_json(
        &client,
        api_key.as_deref(),
        reqwest::Method::POST,
        &issue_url,
        Some(&issue_body),
    )
    .await;
    if st == 409 {
        if !args.revoke {
            eprintln!(
                "error: HTTP 409 — an active license already exists for app '{app_id}'. \
                 Re-run with --revoke to replace it."
            );
            return 1;
        }
        match revoke_active(&client, api_key.as_deref(), &base, &app_id).await {
            Ok(lid) => println!("revoked previous license {lid} for app '{app_id}'"),
            Err(e) => {
                eprintln!("error: {e}");
                return 1;
            }
        }
        let (st2, body2) = http_json(
            &client,
            api_key.as_deref(),
            reqwest::Method::POST,
            &issue_url,
            Some(&issue_body),
        )
        .await;
        st = st2;
        body = body2;
    }
    if st >= 400 {
        eprintln!("error: license issue failed: HTTP {st} {}", brief(&body));
        return 1;
    }
    let jwt = body
        .get("data")
        .and_then(|d| d.get("jwt"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string();
    if jwt.is_empty() {
        eprintln!("error: license issue returned no jwt");
        return 1;
    }

    // 4) Fetch the tenant's active public key.
    let (kst, kbody) = http_json(
        &client,
        api_key.as_deref(),
        reqwest::Method::GET,
        &format!("{base}/licenses/public-key"),
        None,
    )
    .await;
    if kst >= 400 {
        eprintln!("error: public-key fetch failed: HTTP {kst} {}", brief(&kbody));
        return 1;
    }
    let pem_raw = kbody
        .get("data")
        .and_then(|d| d.get("public_key_pem"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    // Normalise: exactly one trailing newline (the proxy trims anyway, but the
    // file should be clean for every other consumer too).
    let pem = format!("{}\n", pem_raw.trim());
    if !pem.contains("BEGIN PUBLIC KEY") {
        eprintln!("error: public-key response did not contain a PEM block");
        return 1;
    }

    // 5) Write the credential files, mode 0600.
    let out_dir = args
        .out_dir
        .clone()
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    if let Err(e) = std::fs::create_dir_all(&out_dir) {
        eprintln!("error: cannot create {}: {e}", out_dir.display());
        return 1;
    }
    let jwt_path = out_dir.join("agent-license.jwt");
    let pem_path = out_dir.join("license_pub.pem");
    if let Err(e) = write_secret(&jwt_path, &jwt) {
        eprintln!("error: {e}");
        return 1;
    }
    if let Err(e) = write_secret(&pem_path, &pem) {
        eprintln!("error: {e}");
        return 1;
    }

    // 6) Summary — the JWT itself is never printed.
    let exp_days = crate::session::extract_claims_from_jwt(&jwt)
        .and_then(|c| c.get("exp").and_then(|v| v.as_i64()))
        .map(|exp| {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            (exp - now) / 86_400
        })
        .unwrap_or(0);
    let tool_list = if tools.is_empty() {
        "(all tools)".to_string()
    } else {
        format!("{} tool(s): {}", tools.len(), tools.join(","))
    };
    println!("enrolled app '{app_id}' (tenant '{tenant}')");
    println!("  license : {} ({} bytes, mode 0600)", jwt_path.display(), jwt.len());
    println!("  pubkey  : {} (mode 0600)", pem_path.display());
    println!("  window  : {tool_list}");
    println!("  expiry  : in {exp_days} day(s)");
    println!();
    println!("next steps:");
    println!("  export VIRBIUS_LICENSE_FILE={}", jwt_path.display());
    println!("  export VIRBIUS_LICENSE_PUBLIC_KEY={}", pem_path.display());
    if args.control.is_none() && std::env::var("VIRBIUS_BASE_URL").is_err() {
        println!("  export VIRBIUS_BASE_URL={control}   # derives control + engine(:8082) + token exchange");
    }
    0
}

async fn revoke_active(
    client: &reqwest::Client,
    api_key: Option<&str>,
    base: &str,
    app_id: &str,
) -> Result<String, String> {
    let (st, body) = http_json(
        client,
        api_key,
        reqwest::Method::GET,
        &format!("{base}/licenses/list?status=active"),
        None,
    )
    .await;
    if st >= 400 {
        return Err(format!("license list failed: HTTP {st} {}", brief(&body)));
    }
    let rows = body.get("data").and_then(|d| d.as_array()).cloned().unwrap_or_default();
    for row in rows {
        let app = row.get("app_id").and_then(|v| v.as_str()).unwrap_or("");
        let lid = row
            .get("license_id")
            .or_else(|| row.get("id"))
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if app == app_id && !lid.is_empty() {
            let (rst, rbody) = http_json(
                client,
                api_key,
                reqwest::Method::POST,
                &format!("{base}/licenses/{lid}/revoke"),
                Some(&serde_json::json!({"reason": "proxy_enroll_reissue"})),
            )
            .await;
            if rst >= 400 {
                return Err(format!("revoke {lid} failed: HTTP {rst} {}", brief(&rbody)));
            }
            return Ok(lid.to_string());
        }
    }
    Err(format!(
        "issue returned 409 but no active license for app '{app_id}' was found to revoke"
    ))
}

async fn http_json(
    client: &reqwest::Client,
    api_key: Option<&str>,
    method: reqwest::Method,
    url: &str,
    body: Option<&serde_json::Value>,
) -> (u16, serde_json::Value) {
    let mut rb = client.request(method, url);
    if let Some(k) = api_key {
        rb = rb.header("X-Virbius-Api-Key", k);
    }
    if let Some(b) = body {
        rb = rb.json(b);
    }
    match rb.send().await {
        Ok(resp) => {
            let status = resp.status().as_u16();
            let parsed = resp.json().await.unwrap_or(serde_json::Value::Null);
            (status, parsed)
        }
        Err(e) => (0, serde_json::json!({"transport_error": e.to_string()})),
    }
}

/// One-line rendering of a JSON body for error messages.
fn brief(v: &serde_json::Value) -> String {
    let s = v.to_string();
    if s.len() > 160 {
        format!("{}…", &s[..160])
    } else {
        s
    }
}

/// Write a credential file with mode 0600 (unix). Overwrites.
fn write_secret(path: &std::path::Path, content: &str) -> Result<(), String> {
    use std::io::Write;
    #[cfg(unix)]
    let file = {
        use std::os::unix::fs::OpenOptionsExt;
        std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(path)
            .map_err(|e| format!("cannot write {}: {e}", path.display()))?
    };
    #[cfg(not(unix))]
    let file = {
        std::fs::File::create(path).map_err(|e| format!("cannot write {}: {e}", path.display()))?
    };
    let mut file = file;
    file.write_all(content.as_bytes())
        .map_err(|e| format!("cannot write {}: {e}", path.display()))
}

/// Sorted, de-duplicated tool list view used by tests.
#[cfg(test)]
pub(crate) fn normalize_tools(tools: &[String]) -> Vec<String> {
    let set: std::collections::BTreeSet<String> = tools.iter().cloned().collect();
    set.into_iter().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tools_are_sorted_and_deduped() {
        let raw = vec!["b".to_string(), "a".to_string(), "b".to_string(), "".to_string()];
        // parse_cli filters empties at split time; normalize_tools covers the rest
        let cleaned: Vec<String> = raw.into_iter().filter(|s| !s.is_empty()).collect();
        assert_eq!(normalize_tools(&cleaned), vec!["a".to_string(), "b".to_string()]);
    }
}
