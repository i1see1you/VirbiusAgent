use crate::manifest::EdgeRule;
use crate::matcher::RuleHit;

#[derive(Debug, Clone)]
pub struct EnforceResult {
    pub effective_action: String,
    pub max_risk_score: i32,
    pub primary: Option<EdgeRule>,
}

pub fn in_canary_bucket(session_id: Option<&str>, percent: i32) -> bool {
    if percent >= 100 {
        return true;
    }
    if percent <= 0 {
        return false;
    }
    let key = session_id.filter(|s| !s.is_empty()).unwrap_or("default");
    let crc = crc32fast::hash(key.as_bytes());
    ((crc % 100) as i32) < percent
}

fn intent_priority(intent: &str) -> i32 {
    match intent.trim().to_ascii_lowercase().as_str() {
        "deny" => 100,
        "captcha" => 50,
        "review" => 30,
        _ => 0,
    }
}

fn is_allow_intent(intent: &str) -> bool {
    matches!(intent.trim().to_ascii_lowercase().as_str(), "allow")
}

fn is_full(mode: &str) -> bool {
    mode.trim().eq_ignore_ascii_case("full")
}

fn is_canary_effective(rule: &EdgeRule, session_id: Option<&str>) -> bool {
    if !rule.enforce_mode.eq_ignore_ascii_case("canary") {
        return false;
    }
    let pct = rule.canary_percent.unwrap_or(0);
    in_canary_bucket(session_id, pct)
}

fn effective_enforce(rules: &[&EdgeRule], session_id: Option<&str>) -> bool {
    rules.iter().any(|r| is_full(&r.enforce_mode))
        || rules.iter().any(|r| is_canary_effective(r, session_id))
}

pub fn merge(hits: &[RuleHit], session_id: Option<&str>) -> EnforceResult {
    if hits.is_empty() {
        return EnforceResult {
            effective_action: "allow".into(),
            max_risk_score: 0,
            primary: None,
        };
    }
    let rules: Vec<&EdgeRule> = hits.iter().map(|h| &h.rule).collect();
    if rules.iter().any(|r| is_allow_intent(&r.intent_action)) {
        return EnforceResult {
            effective_action: "allow".into(),
            max_risk_score: 0,
            primary: None,
        };
    }
    let max_priority = rules
        .iter()
        .map(|r| intent_priority(&r.intent_action))
        .max()
        .unwrap_or(0);
    if max_priority <= 0 {
        return EnforceResult {
            effective_action: "allow".into(),
            max_risk_score: 0,
            primary: None,
        };
    }
    let top: Vec<&EdgeRule> = rules
        .iter()
        .copied()
        .filter(|r| intent_priority(&r.intent_action) == max_priority)
        .collect();
    let max_risk_score = top.iter().map(|r| r.risk_score).max().unwrap_or(0);
    let primary = top
        .iter()
        .max_by(|a, b| {
            a.risk_score
                .cmp(&b.risk_score)
                .then_with(|| a.rule_revision.cmp(&b.rule_revision))
                .then_with(|| a.rule_id.cmp(&b.rule_id))
        })
        .copied()
        .cloned();
    let intent = primary
        .as_ref()
        .map(|p| p.intent_action.trim().to_ascii_lowercase())
        .unwrap_or_else(|| "allow".into());
    let effective = effective_enforce(&top, session_id);
    let effective_action = match intent.as_str() {
        "deny" => {
            if effective {
                "block"
            } else {
                "review"
            }
        }
        "captcha" => {
            if effective {
                "captcha"
            } else {
                "review"
            }
        }
        "review" => "review",
        _ => "allow",
    };
    EnforceResult {
        effective_action: effective_action.to_string(),
        max_risk_score,
        primary,
    }
}

pub fn is_enforced_block(action: &str) -> bool {
    action == "block"
}
