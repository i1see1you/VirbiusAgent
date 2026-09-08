//! Standalone falco config subscriber with node-level gray resolution.
//!
//! Env:
//!   VIRBIUS_REDIS_URL       (default redis://127.0.0.1:6379)
//!   VIRBIUS_TENANT_ID       (default "default")
//!   VIRBIUS_NODE_ID         (default $HOSTNAME → /etc/hostname; must be stable per node)
//!   VIRBIUS_FALCO_RULES_DIR (default /etc/falco/falco_rules.d)
fn main() {
    virbius_kernel::run_config_subscriber();
}
