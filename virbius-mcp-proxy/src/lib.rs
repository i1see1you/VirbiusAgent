/// Library target for virbius-mcp-proxy, enabling integration tests.
///
/// All modules are re-exported as `pub` so that `tests/integration_test.rs`
/// can construct proxy components (SessionManager, UpstreamManager, etc.)
/// and call `router::route_request` directly.

pub mod audit;
pub mod config;
pub mod egress;
pub mod error;
pub mod pipeline;
pub mod router;
pub mod session;
pub mod trace_collector;
pub mod transport;
pub mod upstream;
