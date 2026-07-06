use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{OnceLock, RwLock};

static RUNTIME_CONFIG: OnceLock<RwLock<EdgeInitConfig>> = OnceLock::new();

/// Host-app integration config (production). Install once via [`EdgeInitConfig::install`]
/// or [`crate::VirbiusEdge::init`]; do not rely on environment variables in shipped apps.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EdgeInitConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub control_base_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub offline_manifest_path: Option<PathBuf>,
    pub cache_dir: PathBuf,
    #[serde(default = "default_tenant")]
    pub tenant_id: String,
    #[serde(default)]
    pub app_id: String,
    /// Bearer token for Control Edge pull API (tenant-scoped; not in manifest).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub edge_api_key: Option<String>,
    /// Device identifier used for canary pool assignment.
    /// If set, the SDK computes a bucket (0-99) from the device_id and compares it against
    /// the active rollout's canary_percent to decide whether to fetch the canary manifest.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
}

fn default_tenant() -> String {
    "default".into()
}

impl Default for EdgeInitConfig {
    fn default() -> Self {
        Self {
            control_base_url: None,
            offline_manifest_path: None,
            cache_dir: PathBuf::from("./data/edge/default"),
            tenant_id: default_tenant(),
            app_id: String::new(),
            edge_api_key: None,
            device_id: None,
        }
    }
}

impl EdgeInitConfig {
    pub fn resolve() -> Self {
        RUNTIME_CONFIG
            .get()
            .map(|lock| lock.read().expect("edge init lock").clone())
            .unwrap_or_default()
    }

    pub fn is_installed() -> bool {
        RUNTIME_CONFIG.get().is_some()
    }

    pub fn install(&self) {
        let lock = RUNTIME_CONFIG.get_or_init(|| RwLock::new(self.clone()));
        *lock.write().expect("edge init lock") = self.clone();
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.cache_dir.as_os_str().is_empty() {
            return Err("cache_dir must not be empty".into());
        }
        if self
            .control_base_url
            .as_ref()
            .is_some_and(|u| !u.is_empty())
            && self.app_id.is_empty()
        {
            return Err("app_id is required when control_base_url is set".into());
        }
        Ok(())
    }

    pub fn default_cache_dir(tenant_id: &str, app_id: &str) -> PathBuf {
        PathBuf::from("./data")
            .join("edge")
            .join(tenant_id)
            .join(app_id)
    }

    pub fn remote_sync_enabled(&self) -> bool {
        self.offline_manifest_path.is_none()
            && self
                .control_base_url
                .as_ref()
                .is_some_and(|url| !url.is_empty())
            && !self.app_id.is_empty()
    }

    pub fn manifest_path(&self) -> PathBuf {
        if let Some(p) = &self.offline_manifest_path {
            return p.clone();
        }
        manifest_cache_path(&self.cache_dir)
    }

    /// **Examples / CI only.** Production apps should build [`EdgeInitConfig`] from app config.
    #[doc(hidden)]
    pub fn from_env() -> Self {
        use std::env;

        let offline_manifest_path = env::var("VIRBIUS_EDGE_MANIFEST_PATH")
            .ok()
            .filter(|s| !s.is_empty())
            .map(PathBuf::from);
        let control_base_url = env::var("VIRBIUS_CONTROL_BASE_URL")
            .ok()
            .filter(|s| !s.is_empty());
        let tenant_id = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| default_tenant());
        let app_id = env::var("VIRBIUS_APP_ID").unwrap_or_default();
        let edge_api_key = env::var("VIRBIUS_EDGE_API_KEY")
            .ok()
            .filter(|s| !s.is_empty());
        let cache_dir = if let Ok(p) = env::var("VIRBIUS_EDGE_CACHE_DIR") {
            if !p.is_empty() {
                PathBuf::from(p)
            } else {
                Self::default_cache_dir(&tenant_id, &app_id)
            }
        } else if let Ok(data) = env::var("VIRBIUS_DATA_DIR") {
            PathBuf::from(data)
                .join("edge")
                .join(&tenant_id)
                .join(&app_id)
        } else {
            Self::default_cache_dir(&tenant_id, &app_id)
        };
        let device_id = env::var("VIRBIUS_DEVICE_ID").ok().filter(|s| !s.is_empty());
        Self {
            control_base_url,
            offline_manifest_path,
            cache_dir,
            tenant_id,
            app_id,
            edge_api_key,
            device_id,
        }
    }
}

pub fn manifest_cache_path(cache_dir: &Path) -> PathBuf {
    cache_dir.join("edge-manifest.json")
}

fn cache_meta_path(cache_dir: &Path) -> PathBuf {
    cache_dir.join("meta.json")
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct CacheMeta {
    artifact_revision: u64,
    content_sha256: String,
}

#[derive(Debug, Deserialize)]
struct PolicyVersionResponse {
    artifact_revision: u64,
    content_sha256: String,
    #[serde(default)]
    stable_revision: u64,
    #[serde(default)]
    stable_sha256: String,
    #[serde(default)]
    canary_revision: u64,
    #[serde(default)]
    canary_sha256: String,
    #[serde(default)]
    canary_percent: u64,
}

impl PolicyVersionResponse {
    /// Returns the (revision, sha256, pool) for the device. When the control plane provides
    /// canary info and the device falls into the canary bucket, the canary manifest is used.
    fn resolve_pool(&self, device_id: Option<&str>) -> PoolSelection<'_> {
        let has_canary = self.canary_revision > 0 && !self.canary_sha256.is_empty();
        if !has_canary || self.canary_percent == 0 {
            return PoolSelection {
                revision: self.artifact_revision,
                sha256: self.content_sha256.clone(),
                pool: "stable",
            };
        }
        let bucket = device_id
            .filter(|id| !id.is_empty())
            .map(bucket_of)
            .unwrap_or(100);
        if bucket < self.canary_percent || self.canary_percent >= 100 {
            PoolSelection {
                revision: self.canary_revision,
                sha256: self.canary_sha256.clone(),
                pool: "canary",
            }
        } else {
            PoolSelection {
                revision: self.stable_revision,
                sha256: self.stable_sha256.clone(),
                pool: "stable",
            }
        }
    }
}

struct PoolSelection<'a> {
    revision: u64,
    sha256: String,
    pool: &'a str,
}

/// CRC32C bucket computation matching `BucketCalculator.bucketOf()` on the control side.
fn bucket_of(device_id: &str) -> u64 {
    use std::hash::Hasher;
    let mut hasher = crc32c::Crc32cHasher::new(0);
    hasher.write(device_id.as_bytes());
    hasher.finish() % 100
}

pub fn sync_if_configured() -> Result<(), String> {
    let cfg = EdgeInitConfig::resolve();
    if !cfg.remote_sync_enabled() {
        return Ok(());
    }
    sync_from_control(&cfg)
}

fn apply_edge_auth(req: ureq::Request, cfg: &EdgeInitConfig) -> ureq::Request {
    if let Some(key) = cfg.edge_api_key.as_ref().filter(|k| !k.is_empty()) {
        req.set("Authorization", &format!("Bearer {key}"))
    } else {
        req
    }
}

fn sync_from_control(cfg: &EdgeInitConfig) -> Result<(), String> {
    let base = cfg
        .control_base_url
        .as_ref()
        .expect("remote sync requires control base url")
        .trim_end_matches('/');

    // --- resolve pool from device_id ---
    let version_url = format!(
        "{base}/api/v1/edge/tenants/{}/apps/{}/policy-version",
        cfg.tenant_id, cfg.app_id
    );
    let local_meta = read_cache_meta(&cfg.cache_dir);

    let version_resp = apply_edge_auth(ureq::get(&version_url), cfg)
        .call()
        .map_err(|e| format!("policy-version request failed: {e}"))?;
    if version_resp.status() == 401 || version_resp.status() == 403 {
        return Err(format!(
            "policy-version unauthorized (HTTP {}): check edge_api_key and tenant_id",
            version_resp.status()
        ));
    }
    if version_resp.status() >= 400 {
        return Err(format!("policy-version HTTP {}", version_resp.status()));
    }
    let version: PolicyVersionResponse = version_resp
        .into_json()
        .map_err(|e| format!("policy-version parse failed: {e}"))?;

    // --- resolve pool based on device_id ---
    let selection = version.resolve_pool(cfg.device_id.as_deref());

    let manifest_file = manifest_cache_path(&cfg.cache_dir);
    if let Some(ref meta) = local_meta {
        if meta.artifact_revision == selection.revision
            && meta.content_sha256.eq_ignore_ascii_case(&selection.sha256)
            && manifest_file.is_file()
        {
            return Ok(());
        }
    }

    let manifest_url = format!(
        "{base}/api/v1/edge/tenants/{}/apps/{}/manifest?pool={}",
        cfg.tenant_id, cfg.app_id, selection.pool
    );
    let manifest_resp = apply_edge_auth(ureq::get(&manifest_url), cfg)
        .set("If-None-Match", &selection.revision.to_string())
        .call()
        .map_err(|e| format!("manifest request failed: {e}"))?;

    if manifest_resp.status() == 401 || manifest_resp.status() == 403 {
        return Err(format!(
            "manifest unauthorized (HTTP {}): check edge_api_key and tenant_id",
            manifest_resp.status()
        ));
    }
    if manifest_resp.status() >= 400 && manifest_resp.status() != 304 {
        return Err(format!("manifest HTTP {}", manifest_resp.status()));
    }

    if manifest_resp.status() == 304 {
        if let Some(meta) = local_meta {
            if meta.artifact_revision == selection.revision {
                return Ok(());
            }
        }
        return Err("manifest 304 but local cache missing or stale".into());
    }

    let body = manifest_resp
        .into_string()
        .map_err(|e| format!("manifest body read failed: {e}"))?;
    let sha = sha256_hex(body.as_bytes());
    if !sha.eq_ignore_ascii_case(&selection.sha256) {
        return Err(format!(
            "manifest sha256 mismatch: expected {}, got {sha}",
            selection.sha256
        ));
    }

    fs::create_dir_all(&cfg.cache_dir).map_err(|e| e.to_string())?;
    atomic_write(&manifest_file, body.as_bytes())?;
    write_cache_meta(
        &cfg.cache_dir,
        &CacheMeta {
            artifact_revision: selection.revision,
            content_sha256: selection.sha256,
        },
    )?;
    Ok(())
}

fn read_cache_meta(cache_dir: &Path) -> Option<CacheMeta> {
    let raw = fs::read_to_string(cache_meta_path(cache_dir)).ok()?;
    serde_json::from_str(&raw).ok()
}

fn write_cache_meta(cache_dir: &Path, meta: &CacheMeta) -> Result<(), String> {
    let path = cache_meta_path(cache_dir);
    let json = serde_json::to_string(meta).map_err(|e| e.to_string())?;
    atomic_write(&path, json.as_bytes())
}

fn atomic_write(path: &Path, content: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content).map_err(|e| e.to_string())?;
    fs::rename(&tmp, path).map_err(|e| e.to_string())
}

pub fn sha256_hex(data: &[u8]) -> String {
    format!("{:x}", Sha256::digest(data))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn sha256_hex_matches_known_vector() {
        assert_eq!(
            sha256_hex(b"hello"),
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
    }

    #[test]
    fn cache_meta_roundtrip() {
        let dir = TempDir::new().expect("tempdir");
        let meta = CacheMeta {
            artifact_revision: 3,
            content_sha256: "abc".into(),
        };
        write_cache_meta(dir.path(), &meta).expect("write meta");
        assert_eq!(read_cache_meta(dir.path()), Some(meta));
    }

    #[test]
    fn remote_sync_disabled_without_control_url() {
        let cfg = EdgeInitConfig {
            control_base_url: None,
            cache_dir: PathBuf::from("/tmp/unused"),
            tenant_id: "default".into(),
            app_id: "demo".into(),
            ..Default::default()
        };
        assert!(!cfg.remote_sync_enabled());
    }

    #[test]
    fn remote_sync_disabled_with_offline_manifest() {
        let cfg = EdgeInitConfig {
            control_base_url: Some("http://127.0.0.1:8080".into()),
            offline_manifest_path: Some(PathBuf::from("/tmp/manifest.json")),
            cache_dir: PathBuf::from("/tmp/cache"),
            tenant_id: "default".into(),
            app_id: "demo".into(),
            ..Default::default()
        };
        assert!(!cfg.remote_sync_enabled());
    }

    #[test]
    fn validate_requires_app_id_with_control_url() {
        let cfg = EdgeInitConfig {
            control_base_url: Some("http://127.0.0.1:8080".into()),
            app_id: String::new(),
            ..Default::default()
        };
        assert!(cfg.validate().is_err());
    }

    #[test]
    fn edge_api_key_serializes_in_init_json() {
        let cfg = EdgeInitConfig {
            edge_api_key: Some("vrb_edge_test".into()),
            ..Default::default()
        };
        let json = serde_json::to_string(&cfg).expect("serialize");
        assert!(json.contains("edge_api_key"));
        let back: EdgeInitConfig = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(back.edge_api_key.as_deref(), Some("vrb_edge_test"));
    }
}
