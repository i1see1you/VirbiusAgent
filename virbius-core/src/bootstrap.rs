use crate::api::VirbiusError;
use crate::manifest;
use crate::runtime;
use crate::sync::EdgeInitConfig;

pub(crate) fn bootstrap(cfg: &EdgeInitConfig) -> Result<(), VirbiusError> {
    cfg.validate().map_err(VirbiusError::InvalidInitConfig)?;
    cfg.install();
    if let Err(e) = crate::sync::sync_if_configured() {
        eprintln!("virbius-core: edge sync: {e}");
    }
    manifest::reload();
    runtime::ensure_flush_loop();
    Ok(())
}

pub(crate) fn reload_synced() {
    if let Err(e) = crate::sync::sync_if_configured() {
        eprintln!("virbius-core: edge sync: {e}");
    }
    manifest::reload();
}
