//! Caller authentication for the proxy (P0-min: control is the single issuer).
//!
//! The proxy holds the agent's long-lived License JWT (presented once at MCP
//! `initialize`). For every engine call it presents a SHORT-LIVED evaluate
//! token instead, obtained by exchanging the license at control's
//! `POST /api/v1/auth/evaluate-token`. Tokens are cached per session and
//! refreshed proactively (refresh_margin_percent before expiry).

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::json;
use tracing::{debug, warn};

/// Configuration for the exchange endpoint.
#[derive(Debug, Clone)]
pub struct AuthConfig {
    /// When empty, auth is disabled (dev / engine auth off).
    pub control_base_url: String,
    pub exchange_path: String,
    pub timeout: Duration,
    /// Refresh a cached token once this percentage of its TTL has elapsed.
    pub refresh_margin_percent: u64,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            control_base_url: String::new(),
            exchange_path: "/api/v1/auth/evaluate-token".to_string(),
            timeout: Duration::from_millis(3000),
            refresh_margin_percent: 50,
        }
    }
}

impl AuthConfig {
    pub fn enabled(&self) -> bool {
        !self.control_base_url.trim().is_empty()
    }
}

struct CachedToken {
    token: String,
    expires_at: Instant,
    ttl: Duration,
}

/// Exchanges + caches short-lived evaluate tokens per session.
pub struct TokenManager {
    cfg: AuthConfig,
    http: reqwest::Client,
    cache: Mutex<HashMap<String, CachedToken>>,
}

impl TokenManager {
    pub fn new(cfg: AuthConfig) -> Self {
        let http = reqwest::Client::builder()
            .timeout(cfg.timeout)
            // Short idle timeout: a pooled control connection goes stale across
            // control restarts (the remote side disappears without FIN under the
            // Docker NAT); reusing it turns every exchange into a permanent
            // transport failure until the proxy restarts. Exchange traffic is
            // tiny (one request per session TTL), so fresh connections cost
            // nothing.
            .pool_idle_timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self {
            cfg,
            http,
            cache: Mutex::new(HashMap::new()),
        }
    }

    pub fn enabled(&self) -> bool {
        self.cfg.enabled()
    }

    /// Return a currently-valid bearer token for the session, exchanging or
    /// refreshing as needed. Returns "" when auth is disabled.
    pub async fn token_for(&self, license_jwt: &str, sid: &str) -> String {
        if !self.cfg.enabled() {
            return String::new();
        }
        if license_jwt.is_empty() {
            return String::new();
        }
        let now = Instant::now();
        if let Some(cached) = self.get_cached(sid, now) {
            return cached;
        }
        // One retry on failure: the first attempt may have consumed a stale
        // pooled connection (control restarted underneath us); the retry must
        // not silently leave every request in fail_open until proxy restart.
        let first = self.exchange(license_jwt, sid).await;
        let outcome = match first {
            Ok(ok) => Ok(ok),
            Err(e) => match self.exchange(license_jwt, sid).await {
                Ok(ok) => Ok(ok),
                Err(e2) => Err(format!("{e}; retry: {e2}")),
            },
        };
        match outcome {
            Ok((token, ttl)) => {
                let mut cache = self.cache.lock().unwrap();
                cache.insert(
                    sid.to_string(),
                    CachedToken {
                        token: token.clone(),
                        expires_at: now + ttl,
                        ttl,
                    },
                );
                token
            }
            Err(e) => {
                warn!("evaluate-token exchange failed (session {sid}): {e}");
                String::new()
            }
        }
    }

    /// Drop a cached token (e.g. after a 401) so the next call re-exchanges.
    pub fn invalidate(&self, sid: &str) {
        self.cache.lock().unwrap().remove(sid);
    }

    fn get_cached(&self, sid: &str, now: Instant) -> Option<String> {
        let cache = self.cache.lock().unwrap();
        let cached = cache.get(sid)?;
        // Refresh once refresh_margin_percent of the TTL has elapsed.
        let margin = cached.ttl.as_secs_f64() * (self.cfg.refresh_margin_percent as f64 / 100.0);
        let refresh_at = cached.expires_at - Duration::from_secs_f64(margin);
        if now >= refresh_at {
            None
        } else {
            Some(cached.token.clone())
        }
    }

    async fn exchange(&self, license_jwt: &str, sid: &str) -> Result<(String, Duration), String> {
        let url = format!(
            "{}{}",
            self.cfg.control_base_url.trim_end_matches('/'),
            self.cfg.exchange_path
        );
        let body = json!({ "license_jwt": license_jwt, "sid": sid });
        let resp = self
            .http
            .post(&url)
            .json(&body)
            .timeout(self.cfg.timeout)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("HTTP {}", resp.status().as_u16()));
        }
        let value: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
        // Control wraps responses in {code,message,data}; accept both shapes.
        let data = value.get("data").unwrap_or(&value);
        let token = data
            .get("access_token")
            .and_then(|v| v.as_str())
            .ok_or_else(|| format!("no access_token in response: {value}"))?;
        let ttl = data
            .get("expires_in")
            .and_then(|v| v.as_u64())
            .unwrap_or(900);
        debug!("exchanged evaluate token for session {sid} (ttl={ttl}s)");
        Ok((token.to_string(), Duration::from_secs(ttl.max(1))))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_returns_empty_token() {
        let mgr = TokenManager::new(AuthConfig::default());
        assert!(!mgr.enabled());
        // No runtime (tokio) needed: disabled short-circuits before any await.
        let token = futures_lite_block_on(mgr.token_for("license", "s1"));
        assert_eq!(token, "");
    }

    #[test]
    fn enabled_but_no_license_returns_empty() {
        let cfg = AuthConfig {
            control_base_url: "http://control:8080".to_string(),
            ..Default::default()
        };
        let mgr = TokenManager::new(cfg);
        assert!(mgr.enabled());
        let token = futures_lite_block_on(mgr.token_for("", "s1"));
        assert_eq!(token, "");
    }

    #[test]
    fn invalidate_is_safe_on_empty_cache() {
        let mgr = TokenManager::new(AuthConfig::default());
        mgr.invalidate("nope"); // must not panic
    }

    /// Minimal block_on without pulling in a runtime dependency: the tested
    /// paths return before any real await, so a trivial poll loop suffices.
    fn futures_lite_block_on<F: std::future::Future>(fut: F) -> F::Output {
        use std::task::{Context, Poll, Waker};
        let waker = Waker::noop();
        let mut cx = Context::from_waker(waker);
        let mut fut = std::pin::pin!(fut);
        match fut.as_mut().poll(&mut cx) {
            Poll::Ready(v) => v,
            Poll::Pending => panic!("future unexpectedly pending in test"),
        }
    }
}
