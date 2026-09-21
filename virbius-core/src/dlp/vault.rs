use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct TokenEntry {
    #[allow(dead_code)]
    pub entity_type: String,
    pub plaintext: String,
    #[allow(dead_code)]
    pub rule_id: String,
    pub session: Option<String>,
}

#[derive(Debug)]
struct VaultSession {
    tokens: HashMap<String, TokenEntry>,
    expires_at: Instant,
}

static VAULT: OnceLock<Mutex<HashMap<String, VaultSession>>> = OnceLock::new();
static TRACE_SEQ: OnceLock<Mutex<HashMap<String, usize>>> = OnceLock::new();

fn vault() -> &'static Mutex<HashMap<String, VaultSession>> {
    VAULT.get_or_init(|| Mutex::new(HashMap::new()))
}

fn trace_seq() -> &'static Mutex<HashMap<String, usize>> {
    TRACE_SEQ.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Allocate the next token sequence number for a trace.
///
/// Monotonic per trace and never reset, so repeated `desensitize_in` calls
/// within one trace can never produce colliding tokens (a stale masked text
/// referencing `_0` must not be silently re-bound to a newer plaintext).
pub fn next_seq(trace_id: &str) -> usize {
    let mut guard = trace_seq().lock().expect("trace seq lock");
    let counter = guard.entry(trace_id.to_string()).or_insert(0);
    let seq = *counter;
    *counter += 1;
    seq
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
    session.tokens.insert(token, entry);
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
