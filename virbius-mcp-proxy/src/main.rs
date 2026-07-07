/// Entry point + CLI argument parsing for virbius-mcp-proxy.

mod audit;
mod config;
mod egress;
mod error;
mod pipeline;
mod router;
mod session;
mod transport;
mod upstream;

use std::sync::Arc;

use tracing::{error, info};
use virbius_core::EdgeInitConfig;

use crate::audit::AuditSink;
use crate::config::ProxyConfig;
use crate::egress::EgressClient;
use crate::pipeline::SecurityPipeline;
use crate::session::SessionManager;
use crate::upstream::{UpstreamClient, UpstreamConfig};

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
    info!("virbius-mcp-proxy starting (transport={})", cfg.proxy.listen);

    // Load License public key
    let pubkey_pem = load_public_key(&cfg.security.license_public_key);

    // Initialize virbius-core (bootstrap manifest sync)
    if let Err(e) = virbius_core::bootstrap::bootstrap(&EdgeInitConfig::resolve()) {
        info!("virbius-core bootstrap (non-fatal): {e}");
    }

    // Create session manager
    let session_mgr = Arc::new(SessionManager::new());

    // Create audit sink
    let audit = Arc::new(AuditSink::new(
        &cfg.audit.redis_url,
        cfg.audit.sample_rate,
    ));

    // Create security pipeline
    let pipeline = Arc::new(SecurityPipeline::new(
        pubkey_pem,
        &cfg.security.engine_url,
        cfg.security.fast_path.clone(),
        cfg.security.failover.clone(),
        cfg.fallback_policy(),
        audit.clone(),
    ));

    // Create upstream client
    let upstream = UpstreamClient::new(UpstreamConfig {
        url: cfg.proxy.upstream_url.clone(),
        transport: cfg.proxy.upstream_transport.clone(),
        timeout_secs: 30,
    });

    // Create egress client for proxying tool calls (curl/http_request)
    // to external APIs with streaming response support
    let egress_client = EgressClient::new(30, 50);

    // Egress allowed hosts (from config or empty — populated from License
    // allowed_hosts when that field is added)
    let egress_hosts: Vec<String> = Vec::new();

    // Start transport
    let listen = cfg.proxy.listen.clone();
    if listen == "stdio" {
        run_stdio(session_mgr, pipeline, upstream, egress_client, egress_hosts).await;
    } else if listen.starts_with("tcp://") || listen.starts_with("http://") {
        let addr = listen
            .strip_prefix("tcp://")
            .or_else(|| listen.strip_prefix("http://"))
            .unwrap_or("0.0.0.0:9090");
        run_sse(addr, session_mgr, pipeline, upstream, egress_client, egress_hosts).await;
    } else {
        error!("unknown transport: {}, use 'stdio' or 'tcp://0.0.0.0:9090'", listen);
        std::process::exit(1);
    }
}

/// Run in stdio transport mode.
async fn run_stdio(
    session_mgr: Arc<SessionManager>,
    pipeline: Arc<SecurityPipeline>,
    upstream: UpstreamClient,
    egress_client: EgressClient,
    egress_hosts: Vec<String>,
) {
    info!("stdio transport mode");
    let (transport, _writer_handle) = transport::StdioTransport::new();
    let mut stdin_rx = transport;

    loop {
        match stdin_rx.recv().await {
            Some(request) => {
                let conn_id = 1u64; // stdio has a single connection
                let session_mgr = session_mgr.clone();
                let pipeline = pipeline.clone();
                let upstream = upstream.clone();
                let egress_client = egress_client.clone();
                let egress_hosts = egress_hosts.clone();

                tokio::spawn(async move {
                    if let Some(response) =
                        router::route_request(&request, conn_id, &session_mgr, &pipeline, &upstream, &egress_client, &egress_hosts, "").await
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

/// Run in SSE/HTTP transport mode.
async fn run_sse(
    addr: &str,
    session_mgr: Arc<SessionManager>,
    pipeline: Arc<SecurityPipeline>,
    upstream: UpstreamClient,
    egress_client: EgressClient,
    egress_hosts: Vec<String>,
) {
    info!("SSE transport mode on {}", addr);
    let (mut transport, _handle) = match transport::SseTransport::new(addr).await {
        Ok((t, h)) => (t, h),
        Err(e) => {
            error!("failed to bind {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    loop {
        match transport.recv().await {
            Some((request, resp_tx)) => {
                let conn_id = session::next_connection_id();
                let session_mgr = session_mgr.clone();
                let pipeline = pipeline.clone();
                let upstream = upstream.clone();
                let egress_client = egress_client.clone();
                let egress_hosts = egress_hosts.clone();

                tokio::spawn(async move {
                    let response =
                        router::route_request(&request, conn_id, &session_mgr, &pipeline, &upstream, &egress_client, &egress_hosts, "").await;
                    if response.is_none() {
                        // For notifications, send an empty 200
                        let _ = resp_tx.send(serde_json::json!({}));
                    } else {
                        let _ = resp_tx.send(response.unwrap());
                    }
                });
            }
            None => {
                info!("SSE transport closed");
                break;
            }
        }
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
