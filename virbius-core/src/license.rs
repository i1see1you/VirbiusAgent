/// Runtime License verification.
/// Validates JWT-signed License bound to app_id.

pub struct License {
    pub app_id: String,
    pub tenant_id: String,
    pub allowed_tools: Vec<String>,
    pub risk_quota: u32,
    pub tool_rate_limit: u32,
    pub expiry: i64,
}

impl License {
    pub fn verify(_jwt: &str) -> Result<Self, LicenseError> {
        Err(LicenseError::NotImplemented)
    }
}

#[derive(Debug)]
pub enum LicenseError {
    InvalidSignature,
    Expired,
    Revoked,
    NotImplemented,
}
