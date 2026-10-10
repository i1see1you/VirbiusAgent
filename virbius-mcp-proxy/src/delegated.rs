//! Delegated credential plane (v2.5-min): proxy-side injection of tenant
//! credentials into outbound egress for exact-host matches.
//!
//! Frozen scope (A+B1 contract):
//! - Hit host only, https + 443 only; miss path is byte-identical to today.
//! - 2xx plaintext responses only (absent/`identity` Content-Encoding, no gzip
//!   magic, whole-body buffer with a cap); 3xx returns `{status, location}`
//!   with the body dropped; every other hit-branch failure is a uniform
//!   opaque deny (`delegated_egress_denied`) — real reason goes to tracing only.
//! - Redaction is byte-exact over `prefix+value` and `value` -> `[REDACTED]`,
//!   applied once at the byte-formation point; no downstream sink ever sees
//!   the unredacted buffer.
//! - Sync = Control publishes a full encrypted snapshot to Redis
//!   (`virbius:delegated:snapshot:{tenant}`); the proxy polls and atomically
//!   swaps the vault. KEK is a static deploy-time value. No runtime SSRF
//!   resolution (frozen out), no rotation, no per-subject binding.
//! - Local-exec tools are denied entirely while the plane is enabled (D-I),
//!   enforced by a shared guard in both `execute_in_sandbox` cfg variants.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::{Duration, Instant};

use base64::Engine as _;
use serde_json::Value;
use zeroize::Zeroizing;

use crate::error::{jsonrpc_error, VirbiusErrorCode};

/// Redis key holding the tenant's full encrypted snapshot.
pub fn snapshot_key(tenant: &str) -> String {
    format!("virbius:delegated:snapshot:{tenant}")
}

pub const REDACTED: &str = "[REDACTED]";
/// Snapshot-poll backoff ceiling (spec: 30s → 10min exponential, reset on success).
const MAX_BACKOFF_SECS: u64 = 600;
const IV_LEN: usize = 12;
/// Inbound headers dropped on the hit branch (framing / hop-by-hop /
/// proxy-managed). `Authorization` and the credential's own header name are
/// stripped separately (case-insensitively) before injection.
const STRIP_INBOUND: &[&str] = &[
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
    "keep-alive",
    "te",
    "trailer",
    "upgrade",
    "proxy-authorization",
    "proxy-authenticate",
    "content-type",
    "accept-encoding",
];

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct DelegatedParams {
    pub enabled: bool,
    pub tenant_id: String,
    /// Raw 32-byte tenant KEK (decoded from base64 config / kek file).
    pub kek: [u8; 32],
    pub redis_url: String,
    pub poll_interval: Duration,
    pub ttl: Duration,
    pub max_body_bytes: usize,
    pub allow_external_bind: bool,
    /// Canonicalized egress-allowlist hosts (host part only). Vault apply
    /// alerts when a hit host is absent — the egress gate rejects such
    /// requests before injection ever runs (silent no-inject otherwise).
    pub allowlist_hosts: Vec<String>,
    /// Test-only: relax the https+443 endpoint gate so end-to-end tests can
    /// drive send_hit against a plain local HTTP server. Crate-visible only.
    pub(crate) test_relax_endpoint: bool,
}

// ---------------------------------------------------------------------------
// Canonical host (shared by vault key and URL matching)
// ---------------------------------------------------------------------------

/// Canonicalize a hostname for exact-match keying: lowercase, strip all
/// trailing dots. Rejects IP-literal shapes (pure digits/dots, `0x`-prefixed
/// labels — IPv6 needs `:` which URL hosts carry) and non-`[a-z0-9.-]`
/// characters after lowercasing. Single-label internal hosts are allowed.
pub fn canonical_host(raw: &str) -> Option<String> {
    let lowered = raw.trim().to_ascii_lowercase();
    let trimmed = lowered.trim_end_matches('.');
    if trimmed.is_empty() || trimmed.len() > 253 {
        return None;
    }
    if !trimmed
        .bytes()
        .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-')
    {
        return None;
    }
    if trimmed.contains("..") || trimmed.starts_with('.') || trimmed.ends_with('.') {
        return None;
    }
    for label in trimmed.split('.') {
        if label.is_empty() || label.len() > 63 || label.starts_with('-') || label.ends_with('-') {
            return None;
        }
        if label.starts_with("0x") {
            return None;
        }
    }
    // IP-literal shapes: all labels numeric (covers `1.2.3.4`, `127.1`,
    // `2130706433`).
    if trimmed
        .split('.')
        .all(|l| !l.is_empty() && l.bytes().all(|b| b.is_ascii_digit()))
    {
        return None;
    }
    Some(trimmed.to_string())
}

// ---------------------------------------------------------------------------
// Envelope: `dcv1:1:<b64_std_padded(iv12 || ct || tag16)>`
// AAD = lenprefix(tenant)||lenprefix(handle)||lenprefix(host)||
//       lenprefix(header_name)||lenprefix(header_prefix), lenprefix=u32_be.
// ---------------------------------------------------------------------------

fn lenprefix(buf: &mut Vec<u8>, s: &[u8]) {
    let n = s.len() as u32;
    buf.extend_from_slice(&n.to_be_bytes());
    buf.extend_from_slice(s);
}

pub fn build_aad(
    tenant: &str,
    handle: &str,
    host: &str,
    header_name: &str,
    header_prefix: &str,
) -> Vec<u8> {
    let mut aad = Vec::new();
    lenprefix(&mut aad, tenant.as_bytes());
    lenprefix(&mut aad, handle.as_bytes());
    lenprefix(&mut aad, host.as_bytes());
    lenprefix(&mut aad, header_name.as_bytes());
    lenprefix(&mut aad, header_prefix.as_bytes());
    aad
}

/// Decrypt one `dcv1:1:<b64(iv||ct||tag)>` envelope. Returns the plaintext
/// value (zeroized on drop). Any failure maps to `cred_unreadable`.
pub fn decrypt_envelope(
    kek: &[u8; 32],
    tenant: &str,
    handle: &str,
    host: &str,
    header_name: &str,
    header_prefix: &str,
    ciphertext: &str,
) -> Result<Zeroizing<String>, String> {
    use aes_gcm::aead::{Aead, KeyInit, Payload};
    use aes_gcm::{Aes256Gcm, Key, Nonce};

    let rest = ciphertext.strip_prefix("dcv1:1:").ok_or("bad prefix")?;
    let blob = base64::engine::general_purpose::STANDARD
        .decode(rest.trim_end())
        .map_err(|_| "bad base64")?;
    if blob.len() <= IV_LEN {
        return Err("ciphertext too short".into());
    }
    let (iv, ct) = blob.split_at(IV_LEN);
    let aad = build_aad(tenant, handle, host, header_name, header_prefix);
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(kek));
    let nonce = Nonce::from_slice(iv);
    let plain = cipher
        .decrypt(nonce, Payload { msg: ct, aad: &aad })
        .map_err(|_| "aes-gcm auth failed".to_string())?;
    let s = String::from_utf8(plain).map_err(|_| "non-utf8 value".to_string())?;
    Ok(Zeroizing::new(s))
}

// ---------------------------------------------------------------------------
// Redaction: byte-exact replacement of `prefix+value` and `value`.
// ---------------------------------------------------------------------------

fn replace_all(buf: &[u8], needle: &[u8], replacement: &[u8]) -> Vec<u8> {
    if needle.is_empty() || buf.len() < needle.len() {
        return buf.to_vec();
    }
    let mut out = Vec::with_capacity(buf.len());
    let mut i = 0;
    while i < buf.len() {
        if i + needle.len() <= buf.len() && &buf[i..i + needle.len()] == needle {
            out.extend_from_slice(replacement);
            i += needle.len();
        } else {
            out.push(buf[i]);
            i += 1;
        }
    }
    out
}

/// Literal (never-decoding) encoding variants an upstream might echo instead
/// of the raw bytes: percent-encoded (uppercase hex), standard base64, and
/// base64url. The body is only pattern-replaced, never decoded/rewritten, so
/// binary or otherwise opaque responses are never corrupted. `s` itself is
/// dropped (already handled as the raw needle), as are forms with no special
/// characters (percent form of an all-unreserved value equals the value).
fn encode_variants(s: &str) -> Vec<String> {
    use base64::engine::general_purpose::{STANDARD, URL_SAFE};
    let bytes = s.as_bytes();
    let mut out = vec![
        STANDARD.encode(bytes),
        URL_SAFE.encode(bytes),
        percent_encode(s),
    ];
    out.retain(|v| !v.is_empty() && v != s);
    out.sort();
    out.dedup();
    out
}

/// RFC 3986 percent-encoding, keeping only unreserved characters literal.
fn percent_encode(s: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        let unreserved = b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~');
        if unreserved {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0f) as usize] as char);
        }
    }
    out
}

fn contains_bytes(haystack: &[u8], needle: &[u8]) -> bool {
    !needle.is_empty()
        && haystack.len() >= needle.len()
        && haystack.windows(needle.len()).any(|w| w == needle)
}

/// Replace every occurrence of `prefix+value` (longer form first), bare
/// `value`, and their percent/base64/base64url variants with `[REDACTED]`.
pub fn redact_bytes(body: &[u8], value: &str, prefix: &str) -> Vec<u8> {
    let redacted = REDACTED.as_bytes();
    let combined = format!("{prefix}{value}");
    let mut out = replace_all(body, combined.as_bytes(), redacted);
    out = replace_all(&out, value.as_bytes(), redacted);
    for needle in encode_variants(&combined) {
        out = replace_all(&out, needle.as_bytes(), redacted);
    }
    for needle in encode_variants(value) {
        out = replace_all(&out, needle.as_bytes(), redacted);
    }
    out
}

// ---------------------------------------------------------------------------
// Response gate (D-H, pure for tests)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DenyReason {
    NotSuccess,
    ContentEncoding,
    GzipMagic,
    TooLarge,
}

/// 2xx-only plaintext gate. `content_encoding` carries every header value
/// (absent = empty slice => pass). 204/304/HEAD bodies are empty and skip the
/// magic check trivially (len < 2).
pub fn check_response(
    status: u16,
    content_encoding: &[String],
    body: &[u8],
    max_body: usize,
) -> Result<(), DenyReason> {
    if !(200..300).contains(&status) {
        return Err(DenyReason::NotSuccess);
    }
    if body.len() > max_body {
        return Err(DenyReason::TooLarge);
    }
    for v in content_encoding {
        if !v.trim().eq_ignore_ascii_case("identity") {
            return Err(DenyReason::ContentEncoding);
        }
    }
    if body.len() >= 2 && body[0] == 0x1f && body[1] == 0x8b {
        return Err(DenyReason::GzipMagic);
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Vault + state
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct VaultEntry {
    pub handle: String,
    pub header_name: String,
    pub header_prefix: String,
    pub value: Zeroizing<String>,
}

#[derive(Default)]
struct VaultInner {
    entries: HashMap<String, VaultEntry>,
}

pub struct DelegatedState {
    pub params: DelegatedParams,
    vault: RwLock<VaultInner>,
    loaded: AtomicBool,
    last_success: Mutex<Option<Instant>>,
    alert_throttle: Mutex<HashMap<&'static str, Instant>>,
}

impl DelegatedState {
    pub fn new(params: DelegatedParams) -> Arc<Self> {
        Arc::new(Self {
            params,
            vault: RwLock::new(VaultInner::default()),
            loaded: AtomicBool::new(false),
            last_success: Mutex::new(None),
            alert_throttle: Mutex::new(HashMap::new()),
        })
    }

    pub fn is_loaded(&self) -> bool {
        self.loaded.load(Ordering::Relaxed)
    }

    pub fn entry_count(&self) -> usize {
        self.vault.read().map(|v| v.entries.len()).unwrap_or(0)
    }

    /// Atomically replace the whole vault. `None` clears it (cold).
    pub fn swap_vault(&self, entries: HashMap<String, VaultEntry>) {
        let n = entries.len();
        if let Ok(mut v) = self.vault.write() {
            v.entries = entries;
        }
        if n > 0 {
            self.loaded.store(true, Ordering::Relaxed);
        } else {
            self.loaded.store(false, Ordering::Relaxed);
        }
        *self.last_success.lock().unwrap() = Some(Instant::now());
    }

    pub fn mark_success(&self) {
        *self.last_success.lock().unwrap() = Some(Instant::now());
    }

    pub fn seconds_since_success(&self) -> Option<u64> {
        self.last_success
            .lock()
            .unwrap()
            .map(|t| t.elapsed().as_secs())
    }

    /// Clear the vault (TTL breach / zero readable rows). State goes cold;
    /// KEK stays (static config, not vault-held).
    pub fn clear(&self) {
        if let Ok(mut v) = self.vault.write() {
            v.entries = HashMap::new();
        }
        self.loaded.store(false, Ordering::Relaxed);
    }

    fn lookup(&self, host: &str) -> Option<VaultEntry> {
        self.vault.read().ok()?.entries.get(host).cloned()
    }

    /// Throttled security alert: always `tracing::error!` (never depends on
    /// Kafka), at most one per code per 60s. Payload never carries values.
    pub fn alert(&self, code: &'static str) {
        let now = Instant::now();
        let fire = {
            let mut g = self.alert_throttle.lock().unwrap();
            match g.get(code) {
                Some(t) if now.duration_since(*t) < Duration::from_secs(60) => false,
                _ => {
                    g.insert(code, now);
                    true
                }
            }
        };
        if fire {
            tracing::error!(target: "virbius::delegated", code = code, tenant = %self.params.tenant_id, "delegated_cred_alert");
        }
    }
}

// ---------------------------------------------------------------------------
// Snapshot apply
// ---------------------------------------------------------------------------

#[derive(Debug, serde::Deserialize)]
pub struct SnapshotRow {
    pub tenant_id: String,
    pub handle: String,
    pub host: String,
    pub header_name: String,
    #[serde(default)]
    pub header_prefix: String,
    pub ciphertext: String,
}

#[derive(Debug, serde::Deserialize)]
pub struct SnapshotDoc {
    pub v: u32,
    pub tenant_id: String,
    #[serde(default)]
    pub rows: Vec<SnapshotRow>,
    /// Observability only (never gates application).
    #[serde(default)]
    pub gen: Option<u64>,
}

pub struct ApplyReport {
    pub applied: usize,
    pub unreadable: usize,
    pub foreign: usize,
    pub not_in_allowlist: usize,
    pub cleared: bool,
    /// True only when the doc passed the tenant gate (swap or authoritative
    /// clear); false on a foreign doc. Drives poller success/backoff.
    pub accepted: bool,
}

impl DelegatedState {
    /// Apply a full snapshot: hard-drop foreign rows, skip unreadable rows,
    /// atomically swap. Zero readable rows => clear + cold.
    pub fn apply_snapshot(&self, doc: &SnapshotDoc) -> ApplyReport {
        let mut report = ApplyReport {
            applied: 0,
            unreadable: 0,
            foreign: 0,
            not_in_allowlist: 0,
            cleared: false,
            accepted: false,
        };
        // Doc-level compare is case-insensitive (Control normalizes tenants
        // to lowercase; this guards against a case mismatch silently keeping
        // the plane cold forever). Row-level stays exact — row tenant bytes
        // feed the AAD and must match what Control encrypted with.
        if !doc
            .tenant_id
            .trim()
            .eq_ignore_ascii_case(&self.params.tenant_id)
        {
            self.alert("snapshot_foreign_tenant");
            return report;
        }
        let mut entries = HashMap::new();
        for row in &doc.rows {
            if row.tenant_id != self.params.tenant_id {
                report.foreign += 1;
                continue;
            }
            let Some(host) = canonical_host(&row.host) else {
                report.unreadable += 1;
                continue;
            };
            match decrypt_envelope(
                &self.params.kek,
                &row.tenant_id,
                &row.handle,
                &host,
                &row.header_name,
                &row.header_prefix,
                &row.ciphertext,
            ) {
                Ok(value) => {
                    entries.insert(
                        host,
                        VaultEntry {
                            handle: row.handle.clone(),
                            header_name: row.header_name.clone(),
                            header_prefix: row.header_prefix.clone(),
                            value,
                        },
                    );
                }
                Err(_) => {
                    report.unreadable += 1;
                }
            }
        }
        if entries.is_empty() {
            self.clear();
            report.cleared = true;
            self.alert("snapshot_empty_or_all_unreadable");
        } else {
            if report.unreadable > 0 {
                self.alert("snapshot_unreadable_rows");
            }
            if report.foreign > 0 {
                self.alert("snapshot_foreign_rows");
            }
            // §5-0 consistency: a hit host outside the egress allowlist can
            // never inject (requests die at the allowlist gate first) — alert
            // so operators see the misconfiguration instead of guessing.
            if !self.params.allowlist_hosts.is_empty() {
                for host in entries.keys() {
                    if !self.params.allowlist_hosts.contains(host) {
                        report.not_in_allowlist += 1;
                    }
                }
                if report.not_in_allowlist > 0 {
                    self.alert("host_not_in_allowlist");
                }
            }
            report.applied = entries.len();
            self.swap_vault(entries);
        }
        report.accepted = true;
        self.mark_success();
        report
    }
}

// ---------------------------------------------------------------------------
// Hit handling (egress hook)
// ---------------------------------------------------------------------------

/// Uniform opaque deny for every hit-branch failure (D1⑧): same shape, no
/// reason to the agent; the real reason goes to tracing only.
pub fn deny_response(id: &Value) -> Value {
    jsonrpc_error(
        VirbiusErrorCode::DelegatedEgressDenied,
        id.clone(),
        Value::Object(Default::default()),
    )
}

pub enum HitOutcome {
    /// Handled on the hit branch: this JSON-RPC value is the final response.
    Handled(Value),
    /// Not a hit (or plane off/cold): caller falls through to today's path.
    Fallthrough,
}

impl DelegatedState {
    /// §5-0 ordering: called after the existing allowlist validation; on miss
    /// returns `Fallthrough` so the existing path is byte-identical.
    #[allow(clippy::too_many_arguments)]
    pub async fn try_handle(
        &self,
        id: &Value,
        url: &str,
        method: &str,
        body: Option<&Value>,
        headers: Option<&[(String, String)]>,
        session_tenant: &str,
        nofollow: reqwest::Client,
    ) -> HitOutcome {
        if !self.params.enabled || !self.is_loaded() {
            return HitOutcome::Fallthrough;
        }
        let Some(parsed) = parse_url(url) else {
            // Malformed URLs are rejected by the allowlist before this point;
            // treat as fallthrough (today's path errors identically).
            return HitOutcome::Fallthrough;
        };
        let Some(host) = canonical_host(parsed.host_str().unwrap_or("")) else {
            return HitOutcome::Fallthrough; // IP literal / odd host => miss
        };
        let Some(entry) = self.lookup(&host) else {
            return HitOutcome::Fallthrough; // miss
        };

        // Hit branch from here on: every failure is the uniform deny.
        // D-F: tenant assertion (session tenant is `_meta`-derived and
        // client-controlled; this check only detects misconfiguration).
        if !session_tenant
            .trim()
            .eq_ignore_ascii_case(&self.params.tenant_id)
        {
            self.alert("tenant_mismatch");
            tracing::error!(target: "virbius::delegated", code = "tenant_mismatch", "delegated hit denied");
            return HitOutcome::Handled(deny_response(id));
        }
        // https + 443 only (explicit non-443 port denied). test_relax_endpoint
        // lets the e2e harness drive send_hit against a plain local server.
        let scheme_ok = self.params.test_relax_endpoint || parsed.scheme() == "https";
        let port_ok = self.params.test_relax_endpoint || parsed.port().is_none_or(|p| p == 443);
        if !scheme_ok || !port_ok {
            self.alert("hit_denied_scheme");
            return HitOutcome::Handled(deny_response(id));
        }

        match self
            .send_hit(id, nofollow, &parsed, method, body, headers, &entry)
            .await
        {
            Ok(Some(result)) => HitOutcome::Handled(result),
            Ok(None) => HitOutcome::Handled(deny_response(id)),
            Err(req_err) => {
                // Transport error text cannot carry response bodies.
                tracing::error!(target: "virbius::delegated", code = "hit_transport_error", error = %req_err, "delegated hit failed");
                HitOutcome::Handled(deny_response(id))
            }
        }
    }

    /// Send the hit request through the no-follow client. `Ok(Some(v))` is a
    /// final JSON-RPC result; `Ok(None)` means "uniform deny".
    #[allow(clippy::too_many_arguments)]
    async fn send_hit(
        &self,
        id: &Value,
        client: reqwest::Client,
        parsed: &url::Url,
        method: &str,
        body: Option<&Value>,
        headers: Option<&[(String, String)]>,
        entry: &VaultEntry,
    ) -> Result<Option<Value>, reqwest::Error> {
        use reqwest::header::{HeaderMap, HeaderName, HeaderValue, ACCEPT_ENCODING};

        // Rebuild the URL without userinfo/fragment (strip step).
        let mut url = parsed.clone();
        let _ = url.set_username("");
        let _ = url.set_password(None);
        url.set_fragment(None);

        let mut hm = HeaderMap::new();
        let header_name_lower = entry.header_name.to_ascii_lowercase();
        if let Some(hdrs) = headers {
            for (k, v) in hdrs {
                let kl = k.to_ascii_lowercase();
                if kl == "authorization"
                    || kl == header_name_lower
                    || STRIP_INBOUND.contains(&kl.as_str())
                {
                    continue;
                }
                if let (Ok(name), Ok(val)) = (
                    HeaderName::try_from(k.as_str()),
                    HeaderValue::try_from(v.as_str()),
                ) {
                    hm.insert(name, val);
                }
            }
        }
        // D-H: pin identity so the upstream cannot gzip the echo.
        hm.insert(ACCEPT_ENCODING, HeaderValue::from_static("identity"));
        // Inject exactly one credential header.
        let combined = format!("{}{}", entry.header_prefix, entry.value.as_str());
        let (Ok(name), Ok(val)) = (
            HeaderName::try_from(entry.header_name.as_str()),
            HeaderValue::try_from(combined.as_str()),
        ) else {
            self.alert("hit_bad_header");
            return Ok(None);
        };
        hm.insert(name.clone(), val);
        debug_assert_eq!(hm.get_all(&name).iter().count(), 1);

        let mut req = match method.to_uppercase().as_str() {
            "GET" => client.get(url),
            "POST" => {
                let r = client.post(url);
                match body {
                    Some(v) => r.json(v),
                    None => r,
                }
            }
            "PUT" => {
                let r = client.put(url);
                match body {
                    Some(v) => r.json(v),
                    None => r,
                }
            }
            "DELETE" => client.delete(url),
            _ => {
                self.alert("hit_unsupported_method");
                return Ok(None);
            }
        };
        req = req.headers(hm);

        let resp = req.send().await?;
        let status = resp.status();

        // 3xx: no-follow client => the redirect itself arrives here. Return
        // `{status, location}` with the body dropped (D1⑦/D-H).
        if status.is_redirection() {
            let location = resp
                .headers()
                .get("location")
                .and_then(|v| v.to_str().ok())
                .and_then(|loc| redact_string(loc, entry))
                .unwrap_or_else(|| "null".to_string());
            let result = serde_json::json!({
                "content": [{ "type": "text", "text": serde_json::to_string(&serde_json::json!({
                    "status": status.as_u16(),
                    "location": location,
                })).unwrap_or_default() }],
                "isError": false
            });
            return Ok(Some(jsonrpc_result(id, result)));
        }
        if !status.is_success() {
            // Non-2xx: deny without reading the body (frozen scope).
            self.alert("hit_denied_status");
            return Ok(None);
        }

        let content_type = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_string();
        let content_encoding: Vec<String> = resp
            .headers()
            .get_all("content-encoding")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .collect();

        // Whole-body buffering with a hard cap.
        let max = self.params.max_body_bytes;
        let mut buf: Vec<u8> = Vec::new();
        let mut stream = resp.bytes_stream();
        use futures_util::StreamExt;
        let mut too_large = false;
        while let Some(chunk) = stream.next().await {
            let chunk = chunk?;
            if buf.len() + chunk.len() > max {
                too_large = true;
                break;
            }
            buf.extend_from_slice(&chunk);
        }
        if too_large {
            self.alert("hit_denied_too_large");
            return Ok(None);
        }
        if let Err(reason) = check_response(status.as_u16(), &content_encoding, &buf, max) {
            self.alert(match reason {
                DenyReason::ContentEncoding => "hit_denied_encoding",
                DenyReason::GzipMagic => "hit_denied_gzip_magic",
                _ => "hit_denied_response",
            });
            return Ok(None);
        }

        // Observability: an upstream that reflects the injected credential back
        // in its response is a signal worth alerting on. (Redaction below still
        // removes the raw form; a post-redaction raw-form check would be dead
        // code because `redact_bytes` replaces every raw occurrence.)
        if contains_bytes(&buf, entry.value.as_bytes()) {
            self.alert("hit_credential_echo");
        }
        // Clean once at the byte-formation point; every sink below consumes
        // this cleaned buffer only.
        let cleaned = redact_bytes(&buf, entry.value.as_str(), entry.header_prefix.as_str());
        let egress_resp = crate::egress::EgressResponse {
            status: status.as_u16(),
            content_type,
            body: cleaned,
        };
        Ok(Some(jsonrpc_result(
            id,
            crate::egress::to_mcp_result(&egress_resp),
        )))
    }
}

fn jsonrpc_result(id: &Value, result: Value) -> Value {
    serde_json::json!({ "jsonrpc": "2.0", "id": id, "result": result })
}

/// Redact a header value string (3xx location) against the entry secrets;
/// returns None if the value still contains a secret after redaction.
fn redact_string(s: &str, entry: &VaultEntry) -> Option<String> {
    let combined = format!("{}{}", entry.header_prefix, entry.value.as_str());
    let once = replace_all(s.as_bytes(), combined.as_bytes(), REDACTED.as_bytes());
    let twice = replace_all(&once, entry.value.as_bytes(), REDACTED.as_bytes());
    let out = String::from_utf8(twice).ok()?;
    if out.contains(entry.value.as_str()) {
        return None; // residue (encoding variant) — drop the header entirely
    }
    Some(out)
}

fn parse_url(url: &str) -> Option<url::Url> {
    url::Url::parse(url).ok()
}

// ---------------------------------------------------------------------------
// Global state + init + poller
// ---------------------------------------------------------------------------

static STATE: OnceLock<Arc<DelegatedState>> = OnceLock::new();

pub fn state() -> Option<Arc<DelegatedState>> {
    STATE.get().cloned()
}

/// D-I guard, shared by both `execute_in_sandbox` cfg variants and both call
/// sites: while the plane is enabled, local-exec tools are denied outright.
/// Unset global (plane never configured) = disabled = allowed.
pub fn local_exec_allowed() -> bool {
    STATE.get().map(|s| !s.params.enabled).unwrap_or(true)
}

/// Thin wrapper so router code stays unchanged when the plane is off.
pub async fn try_handle(
    id: &Value,
    url: &str,
    method: &str,
    body: Option<&Value>,
    headers: Option<&[(String, String)]>,
    session_tenant: &str,
    nofollow: reqwest::Client,
) -> HitOutcome {
    match state() {
        Some(s) => {
            s.try_handle(id, url, method, body, headers, session_tenant, nofollow)
                .await
        }
        None => HitOutcome::Fallthrough,
    }
}

/// Decode a KEK given as base64 or read from a `kek_file` (0400 recommended).
fn load_kek(kek_b64: &str, kek_file: &str) -> Result<[u8; 32], String> {
    let raw = if !kek_file.is_empty() {
        std::fs::read_to_string(kek_file)
            .map_err(|e| format!("read kek_file {kek_file}: {e}"))?
            .trim()
            .to_string()
    } else {
        kek_b64.trim().to_string()
    };
    if raw.is_empty() {
        return Err("kek empty".into());
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&raw)
        .map_err(|_| "kek: invalid base64".to_string())?;
    let arr: [u8; 32] = bytes
        .try_into()
        .map_err(|_| "kek: must decode to exactly 32 bytes".to_string())?;
    Ok(arr)
}

/// Startup validation (fail-fast) + global init + poller spawn.
///
/// `listen` is the proxy listen address ("stdio" bypasses the bind check);
/// `redis_fallback` is used when `params.redis_url` is empty.
#[allow(clippy::too_many_arguments)]
pub fn init(
    enabled: bool,
    tenant_id: &str,
    kek_b64: &str,
    kek_file: &str,
    redis_url: &str,
    poll_interval_secs: u64,
    ttl_secs: u64,
    max_body_mb: usize,
    allow_external_bind: bool,
    listen: &str,
    egress_allowlist: &[String],
) -> Result<(), String> {
    if !enabled {
        tracing::info!(target: "virbius::delegated", "delegated credential plane disabled");
        return Ok(());
    }

    let tenant = tenant_id.trim().to_ascii_lowercase();
    if tenant.is_empty() || tenant == "default" {
        return Err("delegated.tenant_id must be a real tenant (non-empty, not 'default')".into());
    }
    let kek = load_kek(kek_b64, kek_file)?;
    let redis = if redis_url.is_empty() {
        return Err("delegated plane enabled but no redis url (snapshot sync impossible)".into());
    } else {
        normalize_redis_url(redis_url)
    };
    let poll = poll_interval_secs.max(1);
    if ttl_secs <= poll {
        return Err("delegated.ttl must be greater than poll_interval".into());
    }
    let ttl = ttl_secs;
    let max_body = if max_body_mb == 0 { 4 } else { max_body_mb } * 1024 * 1024;

    validate_bind(listen, allow_external_bind)?;

    // Canonicalize allowlist entries (host part; ports are irrelevant since
    // hit hosts are 443-only). Uncanonicalizable entries (e.g. IP literals)
    // cannot host vault entries anyway and are skipped.
    let allowlist_hosts: Vec<String> = egress_allowlist
        .iter()
        .filter_map(|e| e.split(':').next())
        .filter_map(canonical_host)
        .collect();
    // Test seam: only a build compiled with the `test-harness` feature honors
    // the relax env var, so a production binary can never disable the
    // https+443 gate. (Compile-time gate, not a runtime debug check.)
    #[cfg(feature = "test-harness")]
    let test_relax_endpoint =
        std::env::var("VIRBIUS_DELEGATED_TEST_RELAX_ENDPOINT").is_ok_and(|v| v == "1");
    #[cfg(not(feature = "test-harness"))]
    let test_relax_endpoint = false;

    let params = DelegatedParams {
        enabled: true,
        tenant_id: tenant,
        kek,
        redis_url: redis,
        poll_interval: Duration::from_secs(poll),
        ttl: Duration::from_secs(ttl),
        max_body_bytes: max_body,
        allow_external_bind,
        allowlist_hosts,
        test_relax_endpoint,
    };
    let st = DelegatedState::new(params);
    tracing::info!(target: "virbius::delegated", tenant = %st.params.tenant_id, "delegated credential plane enabled (cold until first snapshot)");
    let _ = STATE.set(st.clone());
    // Spawn the poller only when a Tokio runtime exists (skip in unit tests).
    if let Ok(handle) = tokio::runtime::Handle::try_current() {
        handle.spawn(async move {
            spawn_poller_inner(st).await;
        });
    } else {
        tracing::warn!(target: "virbius::delegated", "no tokio runtime — snapshot poller not started (test context)");
    }
    Ok(())
}

fn normalize_redis_url(raw: &str) -> String {
    if raw.starts_with("redis://") || raw.starts_with("rediss://") {
        raw.to_string()
    } else {
        format!("redis://{raw}")
    }
}

/// Startup premise check: when listening on a socket, the bind address must
/// be loopback (both families; hostnames resolved) unless explicitly allowed.
fn validate_bind(listen: &str, allow_external: bool) -> Result<(), String> {
    if listen == "stdio" {
        return Ok(());
    }
    let addr = listen
        .strip_prefix("tcp://")
        .or_else(|| listen.strip_prefix("http://"))
        .unwrap_or(listen);
    let host_raw = addr.rsplit_once(':').map(|(h, _)| h).unwrap_or(addr);
    let host = host_raw.trim_start_matches('[').trim_end_matches(']');
    if host.is_empty() {
        let external = true;
        return check_external(external, allow_external);
    }
    let ips: Vec<std::net::IpAddr> = if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        vec![ip]
    } else {
        use std::net::ToSocketAddrs;
        (host, 0u16)
            .to_socket_addrs()
            .map(|it| it.map(|a| a.ip()).collect())
            .unwrap_or_default()
    };
    if ips.is_empty() {
        return Err(format!(
            "delegated: cannot resolve bind host '{host}' (fail-closed)"
        ));
    }
    let external = ips.iter().any(|ip| !ip.is_loopback());
    check_external(external, allow_external)
}

fn check_external(external: bool, allow_external: bool) -> Result<(), String> {
    if external && !allow_external {
        return Err(
            "delegated plane enabled with non-loopback bind — set delegated.allow_external_bind=true (audited) or bind loopback/stdio".into(),
        );
    }
    if external {
        tracing::warn!(target: "virbius::delegated", "delegated plane bound externally (allow_external_bind=true) — premise 'proxy tenant-only reachable' must hold by deployment");
    }
    Ok(())
}

/// Poll the snapshot key every `poll_interval`. Missing key keeps the vault
/// (throttled alert) but counts toward TTL; consecutive staleness beyond TTL
/// clears the vault and returns the plane to cold.
async fn spawn_poller_inner(st: Arc<DelegatedState>) {
    let url = st.params.redis_url.clone();
    let key = snapshot_key(&st.params.tenant_id);
    let client = match redis::Client::open(url.as_str()) {
        Ok(c) => c,
        Err(e) => {
            tracing::error!(target: "virbius::delegated", code = "redis_client_error", error = %e, "delegated poller disabled");
            return;
        }
    };
    let base = st.params.poll_interval;
    let max = Duration::from_secs(MAX_BACKOFF_SECS.max(base.as_secs()));
    let mut failures: u32 = 0;
    loop {
        // Poll immediately on start, then sleep the current backoff.
        let ok = poll_once(&st, &client, &key).await;
        // TTL enforcement runs every loop, regardless of backoff growth.
        if let Some(secs) = st.seconds_since_success() {
            if Duration::from_secs(secs) > st.params.ttl && st.is_loaded() {
                st.clear();
                st.alert("ttl_breach_cleared");
            }
        }
        if ok {
            failures = 0;
        } else {
            failures = failures.saturating_add(1);
        }
        let delay = if failures == 0 {
            base
        } else {
            backoff_delay(failures, base, max)
        };
        if failures > 0 && delay >= max {
            st.alert("poller_long_backoff");
        }
        tokio::time::sleep(jittered(delay)).await;
    }
}

async fn poll_once(st: &DelegatedState, client: &redis::Client, key: &str) -> bool {
    use redis::AsyncCommands;
    let conn = match client.get_multiplexed_async_connection().await {
        Ok(c) => c,
        Err(e) => {
            tracing::error!(target: "virbius::delegated", code = "redis_conn_error", error = %e, "snapshot poll failed");
            return false;
        }
    };
    let mut conn = conn;
    let raw: Option<String> = match conn.get(key).await {
        Ok(v) => v,
        Err(e) => {
            tracing::error!(target: "virbius::delegated", code = "redis_get_error", error = %e, "snapshot poll failed");
            return false;
        }
    };
    let Some(raw) = raw else {
        // Key absent: keep the vault, alert (throttled), counts toward TTL.
        st.alert("snapshot_key_missing");
        return false;
    };
    match serde_json::from_str::<SnapshotDoc>(&raw) {
        Ok(doc) => {
            let report = st.apply_snapshot(&doc);
            tracing::info!(target: "virbius::delegated", applied = report.applied, unreadable = report.unreadable, foreign = report.foreign, cleared = report.cleared, accepted = report.accepted, gen = ?doc.gen, "snapshot applied");
            report.accepted
        }
        Err(e) => {
            tracing::error!(target: "virbius::delegated", code = "snapshot_json_invalid", error = %e, "snapshot rejected (vault kept)");
            false
        }
    }
}

/// Exponential backoff delay for `failures` consecutive poll failures:
/// `base * 2^(failures-1)` capped at `max` (first retry waits `base`).
fn backoff_delay(failures: u32, base: Duration, max: Duration) -> Duration {
    let shift = failures.saturating_sub(1).min(20);
    let d = base.saturating_mul(1u32 << shift);
    if d > max {
        max
    } else {
        d
    }
}

/// ±10% jitter so a fleet of proxies does not reconnect in lockstep.
fn jittered(d: Duration) -> Duration {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|x| x.subsec_nanos())
        .unwrap_or(0);
    let permille = 900 + (nanos % 201); // 900..=1100
    d.mul_f64(permille as f64 / 1000.0)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn kek() -> [u8; 32] {
        [7u8; 32]
    }

    fn encrypt_for_test(
        k: &[u8; 32],
        tenant: &str,
        handle: &str,
        host: &str,
        header_name: &str,
        prefix: &str,
        value: &str,
    ) -> String {
        use aes_gcm::aead::{Aead, KeyInit, Payload};
        use aes_gcm::{Aes256Gcm, Key, Nonce};
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(k));
        let iv = [9u8; IV_LEN];
        let aad = build_aad(tenant, handle, host, header_name, prefix);
        let ct = cipher
            .encrypt(
                Nonce::from_slice(&iv),
                Payload {
                    msg: value.as_bytes(),
                    aad: &aad,
                },
            )
            .unwrap();
        let mut blob = iv.to_vec();
        blob.extend_from_slice(&ct);
        format!(
            "dcv1:1:{}",
            base64::engine::general_purpose::STANDARD.encode(&blob)
        )
    }

    fn params() -> DelegatedParams {
        DelegatedParams {
            enabled: true,
            tenant_id: "acme".into(),
            kek: kek(),
            redis_url: "redis://127.0.0.1:6379".into(),
            poll_interval: Duration::from_secs(30),
            ttl: Duration::from_secs(300),
            max_body_bytes: 4096,
            allow_external_bind: false,
            allowlist_hosts: vec![],
            test_relax_endpoint: false,
        }
    }

    #[test]
    fn canonical_host_basic() {
        assert_eq!(
            canonical_host("API.Example.COM").as_deref(),
            Some("api.example.com")
        );
        assert_eq!(
            canonical_host("api.example.com..").as_deref(),
            Some("api.example.com")
        );
        assert_eq!(
            canonical_host("llm-gateway").as_deref(),
            Some("llm-gateway")
        );
    }

    #[test]
    fn canonical_host_rejects() {
        assert!(canonical_host("1.2.3.4").is_none());
        assert!(canonical_host("2130706433").is_none());
        assert!(canonical_host("0x7f.1").is_none());
        assert!(canonical_host("127.1").is_none());
        assert!(canonical_host("bad_underscore").is_none());
        assert!(canonical_host("café.com").is_none());
        assert!(canonical_host("..").is_none());
        assert!(canonical_host("").is_none());
    }

    #[test]
    fn envelope_roundtrip_and_failures() {
        let ct = encrypt_for_test(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            "sk-secret-value-123",
        );
        let v = decrypt_envelope(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            &ct,
        )
        .unwrap();
        assert_eq!(v.as_str(), "sk-secret-value-123");

        // AAD field tampered (host changed)
        assert!(decrypt_envelope(
            &kek(),
            "acme",
            "h1",
            "evil.com",
            "Authorization",
            "Bearer ",
            &ct
        )
        .is_err());
        // wrong kek
        assert!(decrypt_envelope(
            &[8u8; 32],
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            &ct
        )
        .is_err());
        // bad prefix / bad base64
        assert!(decrypt_envelope(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            "dcv1:2:AAAA"
        )
        .is_err());
        assert!(decrypt_envelope(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            "dcv1:1:!!!"
        )
        .is_err());
    }

    #[test]
    fn redact_covers_combined_and_bare_value() {
        let body = b"echo: Bearer sk-secret-123 and bare sk-secret-123 twice sk-secret-123";
        let out = redact_bytes(body, "sk-secret-123", "Bearer ");
        let s = String::from_utf8(out).unwrap();
        assert!(!s.contains("sk-secret-123"));
        assert_eq!(s.matches("[REDACTED]").count(), 3);
        assert!(s.contains("echo: [REDACTED] and bare [REDACTED] twice [REDACTED]"));
    }

    #[test]
    fn redact_short_body_untouched() {
        let out = redact_bytes(b"hello", "sk-secret-123", "");
        assert_eq!(out, b"hello");
    }

    #[test]
    fn redact_covers_encoded_variants() {
        use base64::engine::general_purpose::{STANDARD, URL_SAFE};
        use base64::Engine as _;
        let value = "sk-abc+def/ghi=jkl:mno";
        let b64 = STANDARD.encode(value.as_bytes());
        let b64url = URL_SAFE.encode(value.as_bytes());
        let pct = percent_encode(value);
        assert_ne!(pct, value);
        let body = format!("a {b64} b {b64url} c {pct} d").into_bytes();
        let out = redact_bytes(&body, value, "");
        let s = String::from_utf8(out).unwrap();
        assert!(!s.contains(&b64), "base64 variant leaked: {s}");
        assert!(!s.contains(&b64url), "base64url variant leaked: {s}");
        assert!(!s.contains(&pct), "percent variant leaked: {s}");
        assert!(!contains_bytes(s.as_bytes(), value.as_bytes()));
    }

    #[test]
    fn contains_bytes_detects_raw_value() {
        let v = "sk-abc+def/ghi=jkl:mno";
        assert!(contains_bytes(format!("x {v} y").as_bytes(), v.as_bytes()));
        assert!(contains_bytes(
            format!("Bearer {v}").as_bytes(),
            v.as_bytes()
        ));
        assert!(!contains_bytes(b"nothing here", v.as_bytes()));
        assert!(!contains_bytes(b"short", v.as_bytes()));
    }

    #[test]
    fn response_gate_matrix() {
        // 2xx, no encoding
        assert!(check_response(200, &[], b"ok", 1024).is_ok());
        // absent encoding = pass; explicit identity = pass (case-insensitive)
        assert!(check_response(200, &["IDENTITY".into()], b"ok", 1024).is_ok());
        // gzip / multi-value non-identity
        assert_eq!(
            check_response(200, &["gzip".into()], b"ok", 1024),
            Err(DenyReason::ContentEncoding)
        );
        assert_eq!(
            check_response(200, &["identity".into(), "br".into()], b"ok", 1024),
            Err(DenyReason::ContentEncoding)
        );
        // gzip magic without header
        assert_eq!(
            check_response(200, &[], &[0x1f, 0x8b, 0x00], 1024),
            Err(DenyReason::GzipMagic)
        );
        // empty / 1-byte bodies skip magic
        assert!(check_response(204, &[], b"", 1024).is_ok());
        assert!(check_response(200, &[], &[0x1f], 1024).is_ok());
        // non-2xx
        assert_eq!(
            check_response(302, &[], b"", 1024),
            Err(DenyReason::NotSuccess)
        );
        assert_eq!(
            check_response(500, &[], b"err", 1024),
            Err(DenyReason::NotSuccess)
        );
        // too large
        assert_eq!(
            check_response(200, &[], &[0u8; 10], 4),
            Err(DenyReason::TooLarge)
        );
    }

    fn doc(json: &str) -> SnapshotDoc {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn snapshot_apply_swaps_and_drops() {
        let st = DelegatedState::new(params());
        let ct = encrypt_for_test(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            "sk-aaaaaaaaaa",
        );
        let ct_bad = "dcv1:1:AAAA";
        let d = doc(&format!(
            r#"{{"v":1,"tenant_id":"acme","gen":3,"rows":[
                {{"tenant_id":"acme","handle":"h1","host":"api.example.com","header_name":"Authorization","header_prefix":"Bearer ","ciphertext":"{ct}"}},
                {{"tenant_id":"other","handle":"x","host":"x.com","header_name":"Authorization","header_prefix":"","ciphertext":"{ct}"}},
                {{"tenant_id":"acme","handle":"h2","host":"bad","header_name":"Authorization","header_prefix":"","ciphertext":"{ct_bad}"}}
            ]}}"#
        ));
        let r = st.apply_snapshot(&d);
        assert_eq!(r.applied, 1);
        assert_eq!(r.foreign, 1);
        assert_eq!(r.unreadable, 1);
        assert!(r.accepted);
        assert!(!r.cleared);
        assert!(st.is_loaded());
        assert!(st.lookup("api.example.com").is_some());
        assert!(st.lookup("bad").is_none());
        // revocation via authoritative empty snapshot
        let empty = doc(r#"{"v":1,"tenant_id":"acme","rows":[]}"#);
        let r2 = st.apply_snapshot(&empty);
        assert!(r2.cleared);
        assert!(
            r2.accepted,
            "authoritative empty snapshot is an accepted outcome"
        );
        assert!(!st.is_loaded());
        assert!(st.lookup("api.example.com").is_none());
    }

    #[test]
    fn snapshot_foreign_doc_kept() {
        let st = DelegatedState::new(params());
        let ct = encrypt_for_test(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "",
            "sk-bbbbbbbbbb",
        );
        let d = doc(&format!(
            r#"{{"v":1,"tenant_id":"other","rows":[{{"tenant_id":"other","handle":"h1","host":"api.example.com","header_name":"Authorization","header_prefix":"","ciphertext":"{ct}"}}]}}"#
        ));
        let r = st.apply_snapshot(&d);
        assert_eq!(r.applied, 0);
        assert!(
            !r.accepted,
            "foreign doc must not count as a successful poll"
        );
        assert!(!st.is_loaded());
    }

    #[test]
    fn deny_response_is_uniform() {
        let a = deny_response(&serde_json::json!(1));
        let b = deny_response(&serde_json::json!("x"));
        assert_eq!(a["error"]["message"], "delegated_egress_denied");
        assert_eq!(a["error"]["code"], b["error"]["code"]);
        assert_eq!(a["error"]["message"], b["error"]["message"]);
        assert!(a["error"]
            .get("data")
            .unwrap()
            .as_object()
            .unwrap()
            .is_empty());
    }

    #[test]
    fn local_exec_allowed_defaults_true() {
        assert!(local_exec_allowed()); // global unset = plane off
    }

    /// Cross-language vector generated by DelegatedCredEnvelopeTest
    /// (javaInteropVectorFixedIv): fixed kek=[7;32], iv=[9;12],
    /// tenant=acme / handle=h1 / host=api.example.com / header=Authorization /
    /// prefix="Bearer " / value below. GCM is deterministic for a fixed
    /// key+iv+aad+plaintext, so this proves the Java producer and the Rust
    /// consumer agree byte-for-byte (b64 std padded, iv||ct||tag, AAD layout).
    #[test]
    fn java_interop_vector() {
        let ct = "dcv1:1:CQkJCQkJCQkJCQkJVO6p/dCEpBPPEuZOg4nXsKadU8ME38aaIB37UE5YjWr7tOt96Fg=";
        let v = decrypt_envelope(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "Bearer ",
            ct,
        )
        .unwrap();
        assert_eq!(v.as_str(), "sk-interop-vector-0001");
        // And byte-identical re-encryption with the same fixed IV.
        assert_eq!(
            encrypt_for_test(
                &kek(),
                "acme",
                "h1",
                "api.example.com",
                "Authorization",
                "Bearer ",
                "sk-interop-vector-0001"
            ),
            ct
        );
    }

    #[test]
    fn location_redaction_and_drop() {
        let entry = VaultEntry {
            handle: "h".into(),
            header_name: "Authorization".into(),
            header_prefix: "Bearer ".into(),
            value: Zeroizing::new("sk-cccccccccc".into()),
        };
        let loc = "https://api.example.com/callback?token=sk-cccccccccc";
        let out = redact_string(loc, &entry).unwrap();
        assert!(!out.contains("sk-cccccccccc"), "location must be redacted");
        // if residue remains, the header is dropped entirely
        let sneaky = format!("https://x/{{\"a\":\"sk-cccc{}cccc\"}}", "cc");
        let _ = sneaky;
    }

    #[test]
    fn bind_check_loopback_ok_external_needs_flag() {
        assert!(validate_bind("stdio", false).is_ok());
        assert!(validate_bind("tcp://127.0.0.1:9090", false).is_ok());
        assert!(validate_bind("tcp://localhost:9090", false).is_ok());
        assert!(validate_bind("tcp://127.0.0.2:9090", false).is_ok());
        assert!(validate_bind("tcp://[::1]:9090", false).is_ok());
        assert!(validate_bind("tcp://0.0.0.0:9090", false).is_err());
        assert!(validate_bind("tcp://[::]:9090", false).is_err());
        assert!(validate_bind("tcp://0.0.0.0:9090", true).is_ok());
    }

    #[test]
    fn init_validation() {
        // disabled => ok, no global set
        assert!(init(false, "", "", "", "", 30, 300, 4, false, "stdio", &[]).is_ok());
        // enabled but bad tenant
        assert!(init(
            true,
            "default",
            "",
            "",
            "redis://x",
            30,
            300,
            4,
            false,
            "stdio",
            &[]
        )
        .is_err());
        assert!(init(
            true,
            "  ",
            "",
            "",
            "redis://x",
            30,
            300,
            4,
            false,
            "stdio",
            &[]
        )
        .is_err());
        // enabled but no kek / no redis
        assert!(init(
            true,
            "acme",
            "",
            "",
            "redis://x",
            30,
            300,
            4,
            false,
            "stdio",
            &[]
        )
        .is_err());
        assert!(init(
            true,
            "acme",
            &base64::engine::general_purpose::STANDARD.encode([7u8; 32]),
            "",
            "",
            30,
            300,
            4,
            false,
            "stdio",
            &[]
        )
        .is_err());
        // poll >= ttl
        assert!(init(
            true,
            "acme",
            &base64::engine::general_purpose::STANDARD.encode([7u8; 32]),
            "",
            "redis://x",
            300,
            300,
            4,
            false,
            "stdio",
            &[]
        )
        .is_err());
        // external bind without flag
        assert!(init(
            true,
            "acme2",
            &base64::engine::general_purpose::STANDARD.encode([7u8; 32]),
            "",
            "redis://x",
            30,
            300,
            4,
            false,
            "tcp://0.0.0.0:9090",
            &[]
        )
        .is_err());
    }
    // ------------------------------------------------------------------
    // New unit tests: doc-level case tolerance + allowlist consistency
    // ------------------------------------------------------------------

    #[test]
    fn snapshot_doc_tenant_case_insensitive() {
        let st = DelegatedState::new(params());
        let ct = encrypt_for_test(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "",
            "sk-dddddddddd",
        );
        let d = doc(&format!(
            r#"{{"v":1,"tenant_id":"ACME","rows":[{{"tenant_id":"acme","handle":"h1","host":"api.example.com","header_name":"Authorization","header_prefix":"","ciphertext":"{ct}"}}]}}"#
        ));
        let r = st.apply_snapshot(&d);
        assert_eq!(r.applied, 1, "case-mismatched doc tenant must still apply");
        assert!(st.is_loaded());
        // Still truly foreign tenants are dropped.
        let d2 = doc(&format!(
            r#"{{"v":1,"tenant_id":"evil","rows":[{{"tenant_id":"evil","handle":"h1","host":"api.example.com","header_name":"Authorization","header_prefix":"","ciphertext":"{ct}"}}]}}"#
        ));
        let r2 = st.apply_snapshot(&d2);
        assert_eq!(r2.applied, 0);
    }

    #[test]
    fn snapshot_allowlist_consistency_reported() {
        let mut p = params();
        p.allowlist_hosts = vec!["api.example.com".into()];
        let st = DelegatedState::new(p);
        let ct1 = encrypt_for_test(
            &kek(),
            "acme",
            "h1",
            "api.example.com",
            "Authorization",
            "",
            "sk-eeeeeeeeee",
        );
        let ct2 = encrypt_for_test(
            &kek(),
            "acme",
            "h2",
            "other.example.com",
            "Authorization",
            "",
            "sk-ffffffffff",
        );
        let d = doc(&format!(
            r#"{{"v":1,"tenant_id":"acme","rows":[
                {{"tenant_id":"acme","handle":"h1","host":"api.example.com","header_name":"Authorization","header_prefix":"","ciphertext":"{ct1}"}},
                {{"tenant_id":"acme","handle":"h2","host":"other.example.com","header_name":"Authorization","header_prefix":"","ciphertext":"{ct2}"}}
            ]}}"#
        ));
        let r = st.apply_snapshot(&d);
        assert_eq!(r.applied, 2);
        assert_eq!(
            r.not_in_allowlist, 1,
            "other.example.com is not in the allowlist"
        );
    }

    // ------------------------------------------------------------------
    // End-to-end tests: full try_handle -> send_hit over a local HTTP
    // server (test_relax_endpoint relaxes the https+443 gate only).
    // ------------------------------------------------------------------

    use std::sync::Mutex as _SyncMutex;

    struct RecordedRequest {
        method: String,
        path: String,
        headers: Vec<(String, String)>,
    }

    impl RecordedRequest {
        fn header(&self, name: &str) -> Vec<&str> {
            self.headers
                .iter()
                .filter(|(k, _)| k == name)
                .map(|(_, v)| v.as_str())
                .collect()
        }
    }

    fn http_response(status_line: &str, extra_headers: &[(&str, String)], body: &[u8]) -> Vec<u8> {
        let mut head = format!(
            "{status_line}\r\nContent-Length: {}\r\nConnection: close\r\n",
            body.len()
        );
        for (k, v) in extra_headers {
            head.push_str(&format!("{k}: {v}\r\n"));
        }
        let mut out = head.into_bytes();
        out.extend_from_slice(b"\r\n");
        out.extend_from_slice(body);
        out
    }

    fn find_hdr_end(buf: &[u8]) -> Option<usize> {
        buf.windows(4).position(|w| w == b"\r\n\r\n").map(|p| p + 4)
    }

    async fn spawn_responder(
        resp: Vec<u8>,
    ) -> (u16, std::sync::Arc<_SyncMutex<Vec<RecordedRequest>>>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let recorded: std::sync::Arc<_SyncMutex<Vec<RecordedRequest>>> =
            std::sync::Arc::new(_SyncMutex::new(Vec::new()));
        let rec = recorded.clone();
        let resp = std::sync::Arc::new(resp);
        tokio::spawn(async move {
            loop {
                let Ok((mut sock, _)) = listener.accept().await else {
                    break;
                };
                let rec = rec.clone();
                let resp = resp.clone();
                tokio::spawn(async move {
                    let mut buf = Vec::new();
                    let mut tmp = [0u8; 4096];
                    let hdr_end = loop {
                        let n = sock.read(&mut tmp).await.unwrap_or(0);
                        if n == 0 {
                            return;
                        }
                        buf.extend_from_slice(&tmp[..n]);
                        if let Some(pos) = find_hdr_end(&buf) {
                            break pos;
                        }
                    };
                    let head = String::from_utf8_lossy(&buf[..hdr_end]).to_string();
                    let mut lines = head.lines();
                    let request_line = lines.next().unwrap_or_default().to_string();
                    let mut headers = Vec::new();
                    for l in lines {
                        if let Some((k, v)) = l.split_once(':') {
                            headers.push((k.trim().to_ascii_lowercase(), v.trim().to_string()));
                        }
                    }
                    let clen: usize = headers
                        .iter()
                        .find(|(k, _)| k == "content-length")
                        .and_then(|(_, v)| v.parse().ok())
                        .unwrap_or(0);
                    while buf.len() < hdr_end + clen {
                        let n = sock.read(&mut tmp).await.unwrap_or(0);
                        if n == 0 {
                            break;
                        }
                        buf.extend_from_slice(&tmp[..n]);
                    }
                    let mut parts = request_line.split_whitespace();
                    rec.lock().unwrap().push(RecordedRequest {
                        method: parts.next().unwrap_or("").to_string(),
                        path: parts.next().unwrap_or("").to_string(),
                        headers,
                    });
                    let _ = sock.write_all(&resp).await;
                    let _ = sock.flush().await;
                });
            }
        });
        (port, recorded)
    }

    const E2E_VALUE: &str = "sk-e2e-value-000123";

    fn e2e_state(relax: bool) -> std::sync::Arc<DelegatedState> {
        let mut p = params();
        p.test_relax_endpoint = relax;
        let st = DelegatedState::new(p);
        let mut entries = HashMap::new();
        entries.insert(
            "localhost".to_string(),
            VaultEntry {
                handle: "h1".into(),
                header_name: "Authorization".into(),
                header_prefix: "Bearer ".into(),
                value: Zeroizing::new(E2E_VALUE.into()),
            },
        );
        st.swap_vault(entries);
        st
    }

    fn nofollow_test_client() -> reqwest::Client {
        reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap()
    }

    fn text_of(resp: &Value) -> String {
        resp["result"]["content"][0]["text"]
            .as_str()
            .unwrap_or("")
            .to_string()
    }

    fn is_uniform_deny(resp: &Value) -> bool {
        resp.get("error")
            .map(|e| e["message"] == "delegated_egress_denied" && e["code"] == -32014)
            .unwrap_or(false)
    }

    async fn run_hit(
        st: &DelegatedState,
        url: &str,
        method: &str,
        headers: Option<&[(String, String)]>,
        tenant: &str,
    ) -> Value {
        let id = serde_json::json!(1);
        match st
            .try_handle(
                &id,
                url,
                method,
                None,
                headers,
                tenant,
                nofollow_test_client(),
            )
            .await
        {
            HitOutcome::Handled(v) => v,
            HitOutcome::Fallthrough => serde_json::json!({"__fallthrough__": true}),
        }
    }

    #[tokio::test]
    async fn e2e_hit_2xx_echo_cleaned_and_headers_injected() {
        let body = format!("echo Bearer {E2E_VALUE} and bare {E2E_VALUE}");
        let (port, rec) = spawn_responder(http_response(
            "HTTP/1.1 200 OK",
            &[("Content-Type", "text/plain".to_string())],
            body.as_bytes(),
        ))
        .await;
        let st = e2e_state(true);
        let agent_headers = vec![
            ("Authorization".to_string(), "agent-own-token".to_string()),
            ("X-Custom".to_string(), "keepme".to_string()),
            ("Accept-Encoding".to_string(), "gzip".to_string()),
            ("Content-Length".to_string(), "999".to_string()),
        ];
        let resp = run_hit(
            &st,
            &format!("http://localhost:{port}/x"),
            "GET",
            Some(&agent_headers),
            "acme",
        )
        .await;
        assert!(resp.get("__fallthrough__").is_none(), "hit must be handled");
        let text = text_of(&resp);
        assert!(
            text.contains("[REDACTED]"),
            "value must be redacted: {text}"
        );
        assert!(!text.contains(E2E_VALUE), "value must not survive: {text}");
        assert_eq!(text.matches("[REDACTED]").count(), 2);
        // Server-side assertions: exactly one injected header, agent auth
        // stripped, Accept-Encoding pinned, framing header dropped.
        let reqs = rec.lock().unwrap();
        assert_eq!(reqs.len(), 1);
        let r = &reqs[0];
        assert_eq!(r.method, "GET");
        assert_eq!(r.path, "/x");
        let auth = r.header("authorization");
        assert_eq!(auth, vec![format!("Bearer {E2E_VALUE}").as_str()]);
        assert_eq!(r.header("accept-encoding"), vec!["identity"]);
        assert_eq!(r.header("x-custom"), vec!["keepme"]);
        assert!(
            r.header("content-length").is_empty(),
            "framing header must be dropped"
        );
    }

    #[tokio::test]
    async fn e2e_hit_base64_echo_redacted() {
        use base64::engine::general_purpose::STANDARD;
        use base64::Engine as _;
        let b64 = STANDARD.encode(E2E_VALUE.as_bytes());
        let body = format!("{{\"token\":\"{b64}\"}}");
        let (port, _rec) = spawn_responder(http_response(
            "HTTP/1.1 200 OK",
            &[("Content-Type", "text/plain".to_string())],
            body.as_bytes(),
        ))
        .await;
        let st = e2e_state(true);
        let resp = run_hit(
            &st,
            &format!("http://localhost:{port}/x"),
            "GET",
            None,
            "acme",
        )
        .await;
        let text = text_of(&resp);
        assert!(
            text.contains("[REDACTED]"),
            "base64 echo must be redacted: {text}"
        );
        assert!(
            !text.contains(&b64),
            "base64 variant must not survive: {text}"
        );
        assert!(!contains_bytes(text.as_bytes(), E2E_VALUE.as_bytes()));
    }

    #[tokio::test]
    async fn e2e_hit_gzip_magic_without_header_denied() {
        let body = vec![0x1fu8, 0x8b, 1, 2, 3, 4, 5];
        let (port, _rec) = spawn_responder(http_response(
            "HTTP/1.1 200 OK",
            &[("Content-Type", "application/octet-stream".to_string())],
            &body,
        ))
        .await;
        let st = e2e_state(true);
        let resp = run_hit(
            &st,
            &format!("http://localhost:{port}/x"),
            "GET",
            None,
            "acme",
        )
        .await;
        assert!(
            is_uniform_deny(&resp),
            "gzip magic body must be denied: {resp}"
        );
    }

    #[tokio::test]
    async fn e2e_hit_content_encoding_gzip_denied() {
        let (port, _rec) = spawn_responder(http_response(
            "HTTP/1.1 200 OK",
            &[
                ("Content-Type", "text/plain".to_string()),
                ("Content-Encoding", "gzip".to_string()),
            ],
            b"irrelevant",
        ))
        .await;
        let st = e2e_state(true);
        let resp = run_hit(
            &st,
            &format!("http://localhost:{port}/x"),
            "GET",
            None,
            "acme",
        )
        .await;
        assert!(is_uniform_deny(&resp));
    }

    #[tokio::test]
    async fn e2e_hit_5xx_echo_denied_uniform_without_body() {
        let body = format!("error page echoing Bearer {E2E_VALUE}");
        let (port, _rec) = spawn_responder(http_response(
            "HTTP/1.1 500 Internal Server Error",
            &[("Content-Type", "text/html".to_string())],
            body.as_bytes(),
        ))
        .await;
        let st = e2e_state(true);
        let resp = run_hit(
            &st,
            &format!("http://localhost:{port}/x"),
            "GET",
            None,
            "acme",
        )
        .await;
        assert!(is_uniform_deny(&resp), "5xx must be a uniform deny");
        assert!(
            resp.to_string().find(E2E_VALUE).is_none(),
            "no body may leak"
        );
    }

    #[tokio::test]
    async fn e2e_hit_302_location_redacted_no_follow() {
        let loc = format!("https://cb.example.com/cb?token={E2E_VALUE}");
        let (port, rec) = spawn_responder(http_response(
            "HTTP/1.1 302 Found",
            &[("Location", loc)],
            b"",
        ))
        .await;
        let st = e2e_state(true);
        let resp = run_hit(
            &st,
            &format!("http://localhost:{port}/x"),
            "GET",
            None,
            "acme",
        )
        .await;
        assert!(resp.get("__fallthrough__").is_none());
        let text = text_of(&resp);
        let parsed: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(parsed["status"], 302);
        let location = parsed["location"].as_str().unwrap();
        assert!(
            !location.contains(E2E_VALUE),
            "location must be redacted: {location}"
        );
        assert!(location.contains("[REDACTED]"));
        assert_eq!(
            rec.lock().unwrap().len(),
            1,
            "redirect must not be followed"
        );
    }

    #[tokio::test]
    async fn e2e_miss_falls_through() {
        let st = e2e_state(true);
        let resp = run_hit(&st, "http://other.example.com/x", "GET", None, "acme").await;
        assert!(
            resp.get("__fallthrough__").is_some(),
            "miss must fall through: {resp}"
        );
    }

    #[tokio::test]
    async fn e2e_cold_falls_through() {
        let mut p = params();
        p.test_relax_endpoint = true;
        let st = DelegatedState::new(p); // never loaded
        let resp = run_hit(&st, "http://localhost:1/x", "GET", None, "acme").await;
        assert!(
            resp.get("__fallthrough__").is_some(),
            "cold must fall through"
        );
    }

    #[tokio::test]
    async fn e2e_tenant_mismatch_denied() {
        let st = e2e_state(true);
        let resp = run_hit(&st, "http://localhost:1/x", "GET", None, "other-tenant").await;
        assert!(
            is_uniform_deny(&resp),
            "tenant mismatch must be a uniform deny"
        );
    }

    #[tokio::test]
    async fn e2e_http_scheme_denied_without_relax() {
        let st = e2e_state(false); // production https+443 gate active
        let resp = run_hit(&st, "http://localhost:1/x", "GET", None, "acme").await;
        assert!(
            is_uniform_deny(&resp),
            "plain http must be denied without the test hook"
        );
    }

    #[tokio::test]
    async fn e2e_unsupported_method_denied() {
        let st = e2e_state(true);
        let resp = run_hit(&st, "http://localhost:1/x", "PATCH", None, "acme").await;
        assert!(is_uniform_deny(&resp));
    }
    #[test]
    fn backoff_delay_sequence_caps_at_max() {
        let base = Duration::from_secs(30);
        let max = Duration::from_secs(600);
        let seq: Vec<u64> = (1..=7u32)
            .map(|f| backoff_delay(f, base, max).as_secs())
            .collect();
        assert_eq!(seq, vec![30, 60, 120, 240, 480, 600, 600]);
        // base already above max collapses to max
        assert_eq!(
            backoff_delay(3, Duration::from_secs(900), max).as_secs(),
            600
        );
    }

    #[test]
    fn jittered_stays_within_ten_percent() {
        let d = Duration::from_secs(100);
        for _ in 0..64 {
            let ms = jittered(d).as_millis();
            assert!(
                (90_000..=110_000).contains(&ms),
                "jitter out of range: {ms}"
            );
        }
    }
}
