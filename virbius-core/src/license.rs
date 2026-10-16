/// Runtime License: JWT bound to app_id, signed by virbius-control with Ed25519.
use base64::Engine;
use ed25519_dalek::pkcs8::DecodePublicKey;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use std::sync::{OnceLock, RwLock};

static REVOKED_APPS: OnceLock<RwLock<Vec<String>>> = OnceLock::new();

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LicenseClaims {
    pub app_id: String,
    pub tenant_id: String,
    #[serde(default)]
    pub agent_name: String,
    #[serde(default)]
    pub agent_aid: String,
    #[serde(default)]
    pub allowed_tools: Vec<String>,
    #[serde(default)]
    pub risk_quota: u32,
    #[serde(default)]
    pub tool_rate_limit: u32,
    pub exp: i64,
    pub iat: i64,
}

#[derive(Debug, Clone)]
pub struct License {
    pub claims: LicenseClaims,
    pub raw_jwt: String,
}

/// Verification failure taxonomy. These used to collapse into a single
/// `InvalidSignature`, which made an app_id mismatch, an unparseable public
/// key and a genuinely bad signature indistinguishable — operators had no way
/// to self-diagnose ("接不上时完全摸黑"). Each variant now says exactly what
/// differs.
#[derive(Debug)]
pub enum LicenseError {
    InvalidFormat,
    /// The JWT's `app_id` does not match the app_id the session presented.
    AppIdMismatch { jwt: String, session: String },
    /// The public key PEM could not be parsed (e.g. a stray trailing blank
    /// line in the file). Carries the underlying parse error.
    KeyParseFailed(String),
    /// Ed25519 signature verification failed.
    SignatureInvalid,
    Expired,
    Revoked(String),
    NotConfigured,
}

impl std::fmt::Display for LicenseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidFormat => write!(f, "license JWT malformed"),
            Self::AppIdMismatch { jwt, session } => write!(
                f,
                "license app_id mismatch: jwt='{jwt}', session='{session}'"
            ),
            Self::KeyParseFailed(why) => {
                write!(f, "license public key PEM unparseable: {why}")
            }
            Self::SignatureInvalid => write!(f, "license signature invalid"),
            Self::Expired => write!(f, "license expired"),
            Self::Revoked(app) => write!(f, "license revoked for app '{app}'"),
            Self::NotConfigured => write!(f, "license public key not configured"),
        }
    }
}

impl std::error::Error for LicenseError {}

impl License {
    pub fn verify(jwt: &str, public_key_pem: &str, app_id: &str) -> Result<Self, LicenseError> {
        let parts: Vec<&str> = jwt.split('.').collect();
        if parts.len() != 3 {
            return Err(LicenseError::InvalidFormat);
        }
        let (header_b64, payload_b64, sig_b64) = (parts[0], parts[1], parts[2]);

        let payload_json = decode_b64url(payload_b64).map_err(|_| LicenseError::InvalidFormat)?;
        let claims: LicenseClaims =
            serde_json::from_slice(&payload_json).map_err(|_| LicenseError::InvalidFormat)?;

        if claims.app_id != app_id {
            return Err(LicenseError::AppIdMismatch {
                jwt: claims.app_id.clone(),
                session: app_id.to_string(),
            });
        }

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64;
        if now > claims.exp {
            return Err(LicenseError::Expired);
        }

        // Trim before parsing: a PEM file ending in a blank line (an easy,
        // invisible copy error) must not fail verification — and when it did,
        // it used to surface as the misleading `InvalidSignature`.
        let pub_key = VerifyingKey::from_public_key_pem(public_key_pem.trim())
            .map_err(|e| LicenseError::KeyParseFailed(e.to_string()))?;
        let message = format!("{}.{}", header_b64, payload_b64);
        let sig_bytes = decode_b64url(sig_b64).map_err(|_| LicenseError::InvalidFormat)?;
        let sig =
            Signature::from_slice(&sig_bytes).map_err(|_| LicenseError::InvalidFormat)?;
        pub_key
            .verify(message.as_bytes(), &sig)
            .map_err(|_| LicenseError::SignatureInvalid)?;

        if is_revoked(app_id) {
            return Err(LicenseError::Revoked(app_id.to_string()));
        }

        Ok(License {
            claims,
            raw_jwt: jwt.to_string(),
        })
    }

    pub fn is_tool_allowed(&self, tool_name: &str) -> bool {
        self.claims.allowed_tools.is_empty()
            || self.claims.allowed_tools.iter().any(|t| t == tool_name)
    }

    pub fn remaining_quota(&self, current_risk: u32) -> u32 {
        self.claims.risk_quota.saturating_sub(current_risk)
    }
}

fn decode_b64url(input: &str) -> Result<Vec<u8>, ()> {
    let mut s = input.to_string();
    match s.len() % 4 {
        2 => s.push_str("=="),
        3 => s.push('='),
        _ => {}
    }
    s = s.replace('-', "+").replace('_', "/");
    base64::engine::general_purpose::STANDARD
        .decode(&s)
        .map_err(|_| ())
}

pub fn revoke(app_id: &str) {
    let store = REVOKED_APPS.get_or_init(|| RwLock::new(Vec::new()));
    let mut guard = store.write().unwrap();
    if !guard.iter().any(|id| id == app_id) {
        guard.push(app_id.to_string());
    }
}

pub fn is_revoked(app_id: &str) -> bool {
    let store = REVOKED_APPS.get_or_init(|| RwLock::new(Vec::new()));
    let guard = store.read().unwrap();
    guard.iter().any(|id| id == app_id)
}

/// Validate that a PEM string parses as an Ed25519 public key (trims first).
/// Used by pre-flight tooling (`virbius-mcp-proxy doctor`) to separate
/// "the key file is bad" from "the signature is bad".
pub fn validate_public_key_pem(pem: &str) -> Result<(), LicenseError> {
    VerifyingKey::from_public_key_pem(pem.trim())
        .map(|_| ())
        .map_err(|e| LicenseError::KeyParseFailed(e.to_string()))
}

pub fn unrevoke(app_id: &str) {
    let store = REVOKED_APPS.get_or_init(|| RwLock::new(Vec::new()));
    let mut guard = store.write().unwrap();
    guard.retain(|id| id != app_id);
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::pkcs8::EncodePublicKey;
    use ed25519_dalek::Signer;
    use ed25519_dalek::SigningKey;
    use rand::rngs::OsRng;

    #[test]
    fn test_license_verify_valid() {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let verifying_key = signing_key.verifying_key();
        let pub_pem = verifying_key.to_public_key_pem(Default::default()).unwrap();

        let claims = LicenseClaims {
            app_id: "test-agent".into(),
            tenant_id: "tenant-1".into(),
            agent_name: "Test Agent".into(),
            agent_aid: "aid:cn:org:tenant-1:agent:test-agent-abc123".into(),
            allowed_tools: vec!["read_file".into(), "search".into()],
            risk_quota: 60,
            tool_rate_limit: 50,
            exp: 9999999999,
            iat: 1700000000,
        };

        let header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&claims).unwrap());
        let message = format!("{}.{}", header, payload);
        let sig = signing_key.sign(message.as_bytes());
        let sig_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes());
        let jwt = format!("{}.{}.{}", header, payload, sig_b64);

        let license = License::verify(&jwt, &pub_pem, "test-agent").unwrap();
        assert!(license.is_tool_allowed("read_file"));
        assert!(!license.is_tool_allowed("curl"));
    }

    #[test]
    fn test_license_expired() {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let verifying_key = signing_key.verifying_key();
        let pub_pem = verifying_key.to_public_key_pem(Default::default()).unwrap();

        let claims = LicenseClaims {
            app_id: "test-agent".into(),
            tenant_id: "tenant-1".into(),
            agent_name: "Test Agent".into(),
            agent_aid: "aid:cn:org:tenant-1:agent:test-agent-abc123".into(),
            allowed_tools: vec![],
            risk_quota: 60,
            tool_rate_limit: 50,
            exp: 1,
            iat: 1,
        };

        let header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&claims).unwrap());
        let message = format!("{}.{}", header, payload);
        let sig = signing_key.sign(message.as_bytes());
        let sig_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes());
        let jwt = format!("{}.{}.{}", header, payload, sig_b64);

        assert!(matches!(
            License::verify(&jwt, &pub_pem, "test-agent").unwrap_err(),
            LicenseError::Expired
        ));
    }

    #[test]
    fn test_license_error_taxonomy() {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let pub_pem = signing_key
            .verifying_key()
            .to_public_key_pem(Default::default())
            .unwrap();
        let claims = LicenseClaims {
            app_id: "test-agent".into(),
            tenant_id: "tenant-1".into(),
            agent_name: "Test Agent".into(),
            agent_aid: String::new(),
            allowed_tools: vec![],
            risk_quota: 60,
            tool_rate_limit: 50,
            exp: 9999999999,
            iat: 1700000000,
        };
        let header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&claims).unwrap());
        let message = format!("{}.{}", header, payload);
        let sig = signing_key.sign(message.as_bytes());
        let sig_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes());
        let jwt = format!("{}.{}.{}", header, payload, sig_b64);

        // app_id mismatch names both sides — the operator can see WHAT differs.
        match License::verify(&jwt, &pub_pem, "other-agent").unwrap_err() {
            LicenseError::AppIdMismatch { jwt, session } => {
                assert_eq!(jwt, "test-agent");
                assert_eq!(session, "other-agent");
            }
            e => panic!("expected AppIdMismatch, got {e:?}"),
        }

        // Wrong key verifies to SignatureInvalid (not the old catch-all).
        let mut other = OsRng;
        let other_pem = SigningKey::generate(&mut other)
            .verifying_key()
            .to_public_key_pem(Default::default())
            .unwrap();
        assert!(matches!(
            License::verify(&jwt, &other_pem, "test-agent").unwrap_err(),
            LicenseError::SignatureInvalid
        ));

        // Garbage PEM reports the parse failure itself.
        assert!(matches!(
            License::verify(&jwt, "not a pem", "test-agent").unwrap_err(),
            LicenseError::KeyParseFailed(_)
        ));
    }

    #[test]
    fn test_license_pem_trailing_blank_line() {
        // A PEM whose file ends with a blank line used to fail verification with
        // the misleading `InvalidSignature`; trim() makes it verify.
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let pub_pem = signing_key
            .verifying_key()
            .to_public_key_pem(Default::default())
            .unwrap();
        let claims = LicenseClaims {
            app_id: "test-agent".into(),
            tenant_id: "tenant-1".into(),
            agent_name: "Test Agent".into(),
            agent_aid: String::new(),
            allowed_tools: vec![],
            risk_quota: 60,
            tool_rate_limit: 50,
            exp: 9999999999,
            iat: 1700000000,
        };
        let header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&claims).unwrap());
        let message = format!("{}.{}", header, payload);
        let sig = signing_key.sign(message.as_bytes());
        let sig_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes());
        let jwt = format!("{}.{}.{}", header, payload, sig_b64);

        let sloppy = format!("{}\n\n  \n", pub_pem);
        assert!(License::verify(&jwt, &sloppy, "test-agent").is_ok());
    }
}
