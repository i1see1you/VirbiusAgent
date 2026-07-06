use crate::manifest::EdgeRule;
use crate::trace::TraceIdSource;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EffectiveAction {
    Allow,
    Block,
    Review,
    Captcha,
}

impl EffectiveAction {
    pub fn from_effective(s: &str) -> Self {
        match s.trim().to_ascii_lowercase().as_str() {
            "block" => Self::Block,
            "review" => Self::Review,
            "captcha" => Self::Captcha,
            _ => Self::Allow,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleHit {
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub layer: &'static str,
}

impl From<&EdgeRule> for RuleHit {
    fn from(rule: &EdgeRule) -> Self {
        Self {
            rule_id: rule.rule_id.clone(),
            rule_revision: rule.rule_revision,
            reason_code: rule.reason_code.clone(),
            layer: "edge",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScanOutcome {
    pub action: EffectiveAction,
    pub trace_id: String,
    pub trace_id_source: TraceIdSource,
    pub max_risk_score: i32,
    pub primary: Option<RuleHit>,
}
