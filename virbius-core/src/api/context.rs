use crate::api::error::VirbiusError;
use crate::trace::{self, TraceIdSource};

#[derive(Debug, Clone, Default)]
pub struct ScanContext {
    pub user_id: Option<String>,
    pub device_id: Option<String>,
    pub scene: Option<String>,
    pub trace_id: Option<String>,
}

impl ScanContext {
    pub fn resolve_trace_id(&self) -> Result<(String, TraceIdSource), VirbiusError> {
        trace::resolve_trace_id(self.trace_id.as_deref()).map_err(|_| VirbiusError::InvalidTraceId)
    }

    pub fn scene_or_default(&self) -> String {
        self.scene
            .as_deref()
            .filter(|s| !s.is_empty())
            .unwrap_or("default")
            .to_string()
    }
}
