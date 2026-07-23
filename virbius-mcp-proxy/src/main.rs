/// Entry point + CLI argument parsing for virbius-mcp-proxy.
use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use tracing::{error, info, warn};
use virbius_core::EdgeInitConfig;

use virbius_mcp_proxy::audit::{AuditBackend, AuditSink};
use virbius_mcp_proxy::config::ProxyConfig;
use virbius_mcp_proxy::egress::EgressClient;
use virbius_mcp_proxy::pipeline::SecurityPipeline;
use virbius_mcp_proxy::router;
use virbius_mcp_proxy::session::SessionManager;
use virbius_mcp_proxy::trace_collector::{TraceBackend, TraceCollector};
use virbius_mcp_proxy::transport::{self, AppState};
use virbius_mcp_proxy::upstream::UpstreamManager;

#[tokio::main]
async fn main() {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "virbius_mcp_proxy=info".into()),
        )
        .init();

    let cfg = ProxyConfig::load();
    info!(
        "virbius-mcp-proxy starting (transport={})",
        cfg.proxy.listen
    );

    // Load License public key
    let pubkey_pem = load_public_key(&cfg.security.license_public_key);

    // Load fallback License JWT from file (if configured)
    let fallback_license_jwt = load_license_jwt(&cfg.security.license_file);

    // Initialize virbius-core (bootstrap manifest sync)
    if let Err(e) = virbius_core::bootstrap::bootstrap(&EdgeInitConfig::resolve()) {
        info!("virbius-core bootstrap (non-fatal): {e}");
    }

    // Create session manager with TTL from config
    let session_mgr = Arc::new(SessionManager::with_ttl(Duration::from_secs(
        cfg.proxy.session_ttl_secs,
    )));

    // Create audit sink (Redis or Kafka)
    let audit_backend = if cfg.audit_use_kafka() {
        info!("audit backend: kafka (topic={})", cfg.audit.kafka_topic);
        AuditBackend::Kafka {
            brokers: cfg.audit.kafka_brokers.clone(),
            topic: cfg.audit.kafka_topic.clone(),
        }
    } else if !cfg.audit.redis_url.is_empty() {
        info!("audit backend: redis ({})", cfg.audit.redis_url);
        AuditBackend::Redis {
            url: cfg.audit.redis_url.clone(),
        }
    } else {
        AuditBackend::Disabled
    };
    let audit = Arc::new(AuditSink::new(audit_backend, cfg.audit.sample_rate));

    // Create trace collector (Redis or Kafka)
    let trace_backend = if cfg.trace_use_kafka() {
        info!("trace backend: kafka (topic={})", cfg.trace.kafka_topic);
        TraceBackend::Kafka {
            brokers: cfg.trace.kafka_brokers.clone(),
            topic: cfg.trace.kafka_topic.clone(),
        }
    } else {
        let trace_redis = cfg.trace_redis_url().to_string();
        if !trace_redis.is_empty() {
            info!("trace backend: redis ({})", trace_redis);
            TraceBackend::Redis { url: trace_redis }
        } else {
            TraceBackend::Disabled
        }
    };
    let trace_collector = Arc::new(TraceCollector::new(trace_backend));
    if trace_collector.enabled() {
        info!("trace collector enabled");
    } else {
        info!("trace collector disabled");
    }

    // Create security pipeline
    let pipeline = Arc::new(SecurityPipeline::new(
        pubkey_pem.clone(),
        &cfg.security.engine_url,
        cfg.security.fast_path.clone(),
        cfg.security.failover.clone(),
        cfg.fallback_policy(),
        audit.clone(),
        cfg.security.output_review.clone(),
    ));

    // Create upstream manager from normalized config (single or multi-upstream)
    let upstream_mgr = Arc::new(UpstreamManager::new(cfg.proxy.upstreams.clone(), 30));
    if upstream_mgr.is_single_upstream() {
        info!("single-upstream mode");
    } else {
        info!(
            "multi-upstream mode: {} upstreams",
            upstream_mgr.upstream_names().len()
        );
    }

    // Create egress client for proxying tool calls (curl/http_request)
    // to external APIs with streaming response support
    let egress_client = EgressClient::new(30, 50);

    // Egress allowed hosts (from config or empty — populated from License
    // allowed_hosts when that field is added)
    let egress_hosts: Vec<String> = Vec::new();

    // Transport connection ID -> logical session ID mapping
    let conn_to_session: Arc<DashMap<String, String>> = Arc::new(DashMap::new());

    // Start transport
    let listen = cfg.proxy.listen.clone();
    if listen == "stdio" {
        run_stdio(
            session_mgr,
            pipeline,
            upstream_mgr,
            egress_client,
            egress_hosts,
            pubkey_pem,
            fallback_license_jwt,
            trace_collector,
            conn_to_session,
        )
        .await;
    } else if listen.starts_with("tcp://") || listen.starts_with("http://") {
        let addr = listen
            .strip_prefix("tcp://")
            .or_else(|| listen.strip_prefix("http://"))
            .unwrap_or("0.0.0.0:9090");
        run_sse(
            addr,
            session_mgr,
            pipeline,
            upstream_mgr,
            egress_client,
            egress_hosts,
            pubkey_pem,
            fallback_license_jwt,
            trace_collector,
            conn_to_session,
        )
        .await;
    } else {
        error!(
            "unknown transport: {}, use 'stdio' or 'tcp://0.0.0.0:9090'",
            listen
        );
        std::process::exit(1);
    }
}

/// Run in stdio transport mode.
///
/// Reads newline-delimited JSON-RPC from stdin, writes responses to stdout.
/// Uses a fixed session_id for the single stdio connection.
async fn run_stdio(
    session_mgr: Arc<SessionManager>,
    pipeline: Arc<SecurityPipeline>,
    upstream_mgr: Arc<UpstreamManager>,
    egress_client: EgressClient,
    egress_hosts: Vec<String>,
    pubkey_pem: String,
    fallback_license_jwt: String,
    trace_collector: Arc<TraceCollector>,
    conn_to_session: Arc<DashMap<String, String>>,
) {
    info!("stdio transport mode");
    let (transport, _writer_handle) = transport::StdioTransport::new();
    let mut stdin_rx = transport;

    // stdio uses a fixed session_id
    let session_id = "stdio-session".to_string();

    loop {
        match stdin_rx.recv().await {
            Some(request) => {
                let session_mgr = session_mgr.clone();
                let pipeline = pipeline.clone();
                let upstream_mgr = upstream_mgr.clone();
                let egress_client = egress_client.clone();
                let egress_hosts = egress_hosts.clone();
                let pubkey_pem = pubkey_pem.clone();
                let fallback_license_jwt = fallback_license_jwt.clone();
                let session_id = session_id.clone();
                let trace_collector = trace_collector.clone();
                let conn_to_session = conn_to_session.clone();

                tokio::spawn(async move {
                    if let Some(response) = router::route_request(
                        &request,
                        &session_id,
                        &session_mgr,
                        &upstream_mgr,
                        &pipeline,
                        &egress_client,
                        &egress_hosts,
                        &pubkey_pem,
                        &fallback_license_jwt,
                        &trace_collector,
                        &conn_to_session,
                    )
                    .await
                    {
                        // Write response to stdout
                        let json = serde_json::to_string(&response).unwrap_or_default();
                        use tokio::io::AsyncWriteExt;
                        let mut stdout = tokio::io::stdout();
                        let _ = stdout.write_all(json.as_bytes()).await;
                        let _ = stdout.write_all(b"\n").await;
                        let _ = stdout.flush().await;
                    }
                });
            }
            None => {
                info!("stdin closed, shutting down");
                break;
            }
        }
    }
}

/// Run in SSE/HTTP transport mode (axum-based).
///
/// Exposes three routes:
/// - `GET /sse` — MCP SSE server endpoint
/// - `POST /messages/?session_id=xxx` — MCP SSE message endpoint
/// - `POST /` — Simple HTTP JSON-RPC endpoint
///
/// Also starts a background task that cleans up expired sessions every 60 seconds.
#[allow(clippy::too_many_arguments)]
async fn run_sse(
    addr: &str,
    session_mgr: Arc<SessionManager>,
    pipeline: Arc<SecurityPipeline>,
    upstream_mgr: Arc<UpstreamManager>,
    egress_client: EgressClient,
    egress_hosts: Vec<String>,
    pubkey_pem: String,
    fallback_license_jwt: String,
    trace_collector: Arc<TraceCollector>,
    conn_to_session: Arc<DashMap<String, String>>,
) {
    info!("SSE/HTTP transport mode on {}", addr);

    // Start background cleanup task
    let cleanup_mgr = session_mgr.clone();
    let cleanup_upstream = upstream_mgr.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        loop {
            interval.tick().await;
            // Clean up expired sessions and their upstream connections
            let expired = cleanup_mgr.cleanup_expired();
            for sid in &expired {
                cleanup_upstream.remove(sid);
            }
            // Also clean up upstream connections that have been disconnected
            // (SSE stream lost) but whose sessions are still within TTL
            cleanup_upstream.cleanup_disconnected();
            if !expired.is_empty() {
                info!("cleaned up {} expired sessions", expired.len());
            }
        }
    });

    let state = AppState {
        session_mgr,
        pipeline,
        upstream_mgr,
        egress_client,
        egress_hosts: Arc::new(egress_hosts),
        public_key_pem: Arc::new(pubkey_pem),
        fallback_license_jwt: Arc::new(fallback_license_jwt),
        trace_collector,
        sse_sessions: Arc::new(DashMap::new()),
        conn_to_session,
    };

    let app = transport::create_router(state);

    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            error!("failed to bind {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    info!("listening on {}", addr);
    if let Err(e) = axum::serve(listener, app).await {
        error!("server error: {}", e);
        std::process::exit(1);
    }
}

/// Load the Ed25519 public key PEM for License verification.
fn load_public_key(path: &str) -> String {
    if path.is_empty() {
        info!("no license_public_key configured, License verification will use empty key");
        return String::new();
    }
    match std::fs::read_to_string(path) {
        Ok(pem) => {
            info!("loaded license public key from {}", path);
            pem
        }
        Err(e) => {
            error!("failed to load license public key from {}: {}", path, e);
            String::new()
        }
    }
}

/// Load a fallback License JWT from a file.
///
/// The file should contain a single line: the Ed25519-signed JWT string.
/// This JWT is used when the Agent does not pass `_meta.license_jwt` in
/// the MCP `initialize` request.
fn load_license_jwt(path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }
    match std::fs::read_to_string(path) {
        Ok(content) => {
            let jwt = content.trim().to_string();
            if jwt.is_empty() {
                warn!("license_file {} is empty, no fallback license loaded", path);
                return String::new();
            }
            info!(
                "loaded fallback license JWT from {} ({} bytes)",
                path,
                jwt.len()
            );
            jwt
        }
        Err(e) => {
            warn!("failed to load license_file from {}: {}", path, e);
            String::new()
        }
    }
}
