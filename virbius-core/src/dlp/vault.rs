use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct TokenEntry {
    pub entity_type: String,
    pub plaintext: String,
    pub rule_id: String,
}

#[derive(Debug)]
struct VaultSession {
    tokens: HashMap<String, TokenEntry>,
    expires_at: Instant,
}

static VAULT: OnceLock<Mutex<HashMap<String, VaultSession>>> = OnceLock::new();

fn vault() -> &'static Mutex<HashMap<String, VaultSession>> {
    VAULT.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn store(trace_id: &str, token: String, entry: TokenEntry, ttl: Duration) {
    if trace_id.is_empty() {
        return;
    }
    let mut guard = vault().lock().expect("vault lock");
    purge_expired(&mut guard);
    let session = guard
        .entry(trace_id.to_string())
        .or_insert_with(|| VaultSession {
            tokens: HashMap::new(),
            expires_at: Instant::now() + ttl,
        });
    session.expires_at = Instant::now() + ttl;
    session.tokens.insert(token, entry);
}

pub fn get(trace_id: &str, token: &str) -> Option<TokenEntry> {
    if trace_id.is_empty() {
        return None;
    }
    let mut guard = vault().lock().expect("vault lock");
    purge_expired(&mut guard);
    guard
        .get(trace_id)
        .and_then(|s| s.tokens.get(token).cloned())
}

pub fn session_tokens(trace_id: &str) -> HashMap<String, TokenEntry> {
    if trace_id.is_empty() {
        return HashMap::new();
    }
    let mut guard = vault().lock().expect("vault lock");
    purge_expired(&mut guard);
    guard
        .get(trace_id)
        .map(|s| s.tokens.clone())
        .unwrap_or_default()
}

fn purge_expired(guard: &mut HashMap<String, VaultSession>) {
    let now = Instant::now();
    guard.retain(|_, s| s.expires_at > now);
}
