use crate::enforce::EnforceResult;
use crate::manifest::{self, SdkConfig};
use crate::upload;
use rand::Rng;
use serde::Serialize;
use std::sync::{Mutex, OnceLock};

#[derive(Debug, Clone, Serialize)]
pub struct AuditEvent {
    pub event_id: String,
    pub trace_id: String,
    pub trace_id_source: String,
    pub tenant_id: String,
    pub scene: String,
    pub layer: String,
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub effective_action: String,
    pub max_risk_score: i32,
    pub rollout_state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub canary_percent: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub in_canary_bucket: Option<bool>,
    pub degraded: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sampled_allow: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sample_rate_allow: Option<f64>,
    pub intercepted_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
}

static QUEUE: OnceLock<Mutex<Vec<AuditEvent>>> = OnceLock::new();

fn queue() -> &'static Mutex<Vec<AuditEvent>> {
    QUEUE.get_or_init(|| Mutex::new(Vec::new()))
}

pub fn maybe_record(
    cfg: &SdkConfig,
    trace_id: &str,
    trace_id_source: &str,
    scene: &str,
    result: &EnforceResult,
    session_id: Option<&str>,
    user_id: Option<&str>,
    device_id: Option<&str>,
) {
    let effective = result.effective_action.as_str();
    let sample_hit = cfg.audit_sample_rate_hit.clamp(0.0, 1.0);
    let sample_allow = cfg.audit_sample_rate_allow.clamp(0.0, 1.0);
    let mut sampled_allow = false;
    let should = if effective == "allow" {
        let ok = rand::thread_rng().gen::<f64>() < sample_allow;
        sampled_allow = ok;
        ok
    } else {
        rand::thread_rng().gen::<f64>() < sample_hit
    };
    if !should {
        return;
    }
    let primary = result.primary.as_ref();
    let rollout = primary
        .map(|p| {
            if !p.rollout_state.is_empty() {
                p.rollout_state.clone()
            } else {
                p.enforce_mode.clone()
            }
        })
        .unwrap_or_else(|| "dry_run".into());
    let canary_percent = primary.and_then(|p| p.canary_percent);
    let in_bucket = if rollout == "canary" {
        canary_percent.map(|pct| crate::enforce::in_canary_bucket(session_id, pct))
    } else {
        None
    };
    let event = AuditEvent {
        event_id: uuid::Uuid::new_v4().to_string(),
        trace_id: trace_id.to_string(),
        trace_id_source: trace_id_source.to_string(),
        tenant_id: manifest::tenant_id(),
        scene: scene.to_string(),
        layer: "edge".into(),
        rule_id: primary.map(|p| p.rule_id.clone()).unwrap_or_default(),
        rule_revision: primary.map(|p| p.rule_revision).unwrap_or(0),
        reason_code: primary.map(|p| p.reason_code.clone()).unwrap_or_default(),
        effective_action: effective.to_string(),
        max_risk_score: result.max_risk_score,
        rollout_state: rollout,
        canary_percent,
        in_canary_bucket: in_bucket,
        degraded: false,
        sampled_allow: if sampled_allow { Some(true) } else { None },
        sample_rate_allow: if sampled_allow {
            Some(sample_allow)
        } else {
            None
        },
        intercepted_at: chrono::Utc::now().to_rfc3339(),
        user_id: user_id.map(|s| s.to_string()),
        device_id: device_id.map(|s| s.to_string()),
    };
    enqueue(cfg, event);
}

fn enqueue(cfg: &SdkConfig, event: AuditEvent) {
    let mut guard = queue().lock().expect("audit queue");
    guard.push(event);
    if guard.len() >= cfg.audit_queue_max {
        let batch: Vec<AuditEvent> = guard.drain(..).collect();
        drop(guard);
        upload::flush_batch(cfg, batch);
        return;
    }
    if guard.len() >= 20 {
        let batch: Vec<AuditEvent> = guard.drain(..).collect();
        drop(guard);
        upload::flush_batch(cfg, batch);
    }
}

pub fn flush_pending(cfg: &SdkConfig) {
    let batch = {
        let mut guard = queue().lock().expect("audit queue");
        if guard.is_empty() {
            return;
        }
        guard.drain(..).collect::<Vec<_>>()
    };
    upload::flush_batch(cfg, batch);
}
