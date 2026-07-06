use crate::audit::AuditEvent;
use crate::manifest::SdkConfig;
use serde::Serialize;
use std::thread;

#[derive(Serialize)]
struct BatchBody<'a> {
    events: &'a [AuditEvent],
}

pub fn flush_batch(cfg: &SdkConfig, events: Vec<AuditEvent>) {
    if events.is_empty() || cfg.audit_ingest_url.is_empty() {
        return;
    }
    let url = cfg.audit_ingest_url.clone();
    let token = cfg.audit_ingest_token.clone();
    thread::spawn(move || {
        let body = match serde_json::to_string(&BatchBody { events: &events }) {
            Ok(b) => b,
            Err(_) => return,
        };
        let mut req = ureq::post(&url).set("Content-Type", "application/json");
        if !token.is_empty() {
            req = req.set("Authorization", &format!("Bearer {token}"));
        }
        let _ = req.send_string(&body);
    });
}
