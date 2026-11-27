use crate::api::VirbiusError;
use crate::manifest;
use crate::runtime;
use crate::sync::EdgeInitConfig;

pub fn bootstrap(cfg: &EdgeInitConfig) -> Result<(), VirbiusError> {
    cfg.validate().map_err(VirbiusError::InvalidInitConfig)?;
    cfg.install();
    if let Err(e) = crate::sync::sync_if_configured() {
        eprintln!("virbius-core: edge sync: {e}");
    }
    manifest::reload();
    #[cfg(target_os = "linux")]
    crate::sandbox::GvisorPool::apply_from_manifest();
    runtime::ensure_flush_loop();
    Ok(())
}

/// Re-run the edge sync + manifest reload + gVisor apply.
///
/// Public so the standalone proxy can poll it: `GvisorPool::apply` is a no-op
/// for unchanged config, so periodic refreshes only reconfigure on change.
pub fn reload_synced() {
    if let Err(e) = crate::sync::sync_if_configured() {
        eprintln!("virbius-core: edge sync: {e}");
    }
    manifest::reload();
    #[cfg(target_os = "linux")]
    crate::sandbox::GvisorPool::apply_from_manifest();
}
