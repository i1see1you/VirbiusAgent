use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum VirbiusError {
    #[error("content must not be empty")]
    EmptyContent,
    #[error("invalid trace_id; use UUID v4")]
    InvalidTraceId,
    #[error("invalid init config: {0}")]
    InvalidInitConfig(String),
    #[error("failed to parse init config JSON: {0}")]
    InvalidInitJson(String),
}
