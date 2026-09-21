//! Output PII Masker — irreversible masking of PII in tool return values.
//!
//! Unlike `desensitize_in` (which uses vault-backed tokens for reversible
//! round-tripping), this module replaces PII entities with human-readable
//! `[REDACTED:ENTITY_TYPE]` placeholders that cannot be reversed.
//!
//! This is applied to tool *return values* before they reach the Agent (LLM),
//! so that PII from external data sources (files, DBs, HTTP) does not leak
//! into the LLM context.

use crate::dlp::entity;
use crate::enforce::EnforceMode;
use crate::manifest::{DlpRule, DlpRuleBody};
use regex::Regex;

/// Result of output PII masking.
#[derive(Debug, Clone)]
pub struct OutputMaskResult {
    /// Masked text (original if no PII found or masking disabled).
    pub text: String,
    /// Whether any PII was masked.
    pub masked: bool,
    /// Details of each PII hit.
    pub hits: Vec<MaskHit>,
    /// True if this tool was in the exempt list (no masking applied).
    pub exempt: bool,
    /// Non-fatal configuration problems detected while compiling rules.
    pub warnings: Vec<String>,
}

/// A single PII entity detected in the output.
#[derive(Debug, Clone)]
pub struct MaskHit {
    pub rule_id: String,
    pub entity_type: String,
    pub start: usize,
    pub end: usize,
}

struct CompiledRule {
    rule: DlpRule,
    regex: Regex,
    priority: i32,
    mode: EnforceMode,
}

struct SpanMatch {
    start: usize,
    end: usize,
    rule: CompiledRule,
}

/// Mask PII entities in `content` using the provided DLP rules.
///
/// Returns an [`OutputMaskResult`] with masked text and hit details.
/// Rules with `enforce_mode = "dry_run"` detect only (no replacement).
/// Rules with `enforce_mode = "canary"` are applied based on session bucket.
pub fn mask_pii(content: &str, rules: &[DlpRule], session_id: Option<&str>) -> OutputMaskResult {
    let (compiled, warnings) = compile_rules(rules);
    if compiled.is_empty() {
        return OutputMaskResult {
            text: content.to_string(),
            masked: false,
            hits: vec![],
            exempt: false,
            warnings,
        };
    }

    let spans = find_spans(content, &compiled);

    // Check if any rule is effective (not dry_run)
    let any_effective = spans.iter().any(|s| s.rule.mode.is_effective(session_id));

    if !any_effective {
        // dry_run mode: detect only, return original text
        let hits = spans
            .iter()
            .map(|s| MaskHit {
                rule_id: s.rule.rule.rule_id.clone(),
                entity_type: s.rule.rule.body.entity_type.clone(),
                start: s.start,
                end: s.end,
            })
            .collect();
        return OutputMaskResult {
            text: content.to_string(),
            masked: false,
            hits,
            exempt: false,
            warnings,
        };
    }

    // Replace PII with [REDACTED:ENTITY_TYPE]
    let mut out = String::with_capacity(content.len());
    let mut last = 0usize;
    let mut hits = Vec::new();

    for span in &spans {
        if !span.rule.mode.is_effective(session_id) {
            continue;
        }
        out.push_str(&content[last..span.start]);
        let mask = render_mask(&span.rule.rule.body);
        out.push_str(&mask);
        hits.push(MaskHit {
            rule_id: span.rule.rule.rule_id.clone(),
            entity_type: span.rule.rule.body.entity_type.clone(),
            start: span.start,
            end: span.end,
        });
        last = span.end;
    }
    out.push_str(&content[last..]);

    let masked = !hits.is_empty();
    OutputMaskResult {
        text: out,
        masked,
        hits,
        exempt: false,
        warnings,
    }
}

/// Render the mask placeholder for an entity type.
///
/// Default: `[REDACTED:PHONE_CN]`, `[REDACTED:IDCARD_CN]`, etc.
fn render_mask(body: &DlpRuleBody) -> String {
    // A rule-level `mask_template` renders verbatim here (no `{seq}` binding:
    // irreversible masking has no vault to resolve against).  Same entity
    // therefore masks identically at the transform edge and on outputs.
    if let Some(t) = body.mask_template.as_deref().map(str::trim) {
        if !t.is_empty() {
            return t.to_string();
        }
    }
    format!("[REDACTED:{}]", body.entity_type.to_uppercase())
}

fn compile_rules(rules: &[DlpRule]) -> (Vec<CompiledRule>, Vec<String>) {
    let mut out = Vec::new();
    let mut warnings = Vec::new();
    for rule in rules {
        let body = &rule.body;
        let pattern = if body.entity_type == "custom_regex" {
            body.pattern.as_deref()
        } else {
            None
        };
        let regex = match entity::compile_entity_regex(&body.entity_type, pattern) {
            Ok(re) => re,
            Err(reason) => {
                let msg = format!("dlp rule {} ignored: {}", rule.rule_id, reason);
                eprintln!("virbius-core: {msg}");
                warnings.push(msg);
                continue;
            }
        };
        let priority = body.priority.unwrap_or(0);
        let mode = EnforceMode::parse(&rule.enforce_mode, rule.canary_percent);
        out.push(CompiledRule {
            rule: rule.clone(),
            regex,
            priority,
            mode,
        });
    }
    out.sort_by_key(|b| std::cmp::Reverse(b.priority));
    (out, warnings)
}

fn find_spans(content: &str, rules: &[CompiledRule]) -> Vec<SpanMatch> {
    let mut raw = Vec::new();
    for compiled in rules {
        for m in compiled.regex.find_iter(content) {
            let plaintext = m.as_str().to_string();
            if !entity::match_has_valid_boundaries(
                &compiled.rule.body.entity_type,
                content,
                m.start(),
                m.end(),
            ) {
                continue;
            }
            if !entity::entity_match_valid(&compiled.rule.body.entity_type, &plaintext) {
                continue;
            }
            raw.push(SpanMatch {
                start: m.start(),
                end: m.end(),
                rule: CompiledRule {
                    rule: compiled.rule.clone(),
                    regex: compiled.regex.clone(),
                    priority: compiled.priority,
                    mode: compiled.mode.clone(),
                },
            });
        }
    }
    resolve_overlaps(raw)
}

fn resolve_overlaps(mut spans: Vec<SpanMatch>) -> Vec<SpanMatch> {
    spans.sort_by(|a, b| {
        b.rule
            .priority
            .cmp(&a.rule.priority)
            .then_with(|| (b.end - b.start).cmp(&(a.end - a.start)))
            .then_with(|| a.start.cmp(&b.start))
    });
    let mut chosen: Vec<SpanMatch> = Vec::new();
    'outer: for span in spans {
        for existing in &chosen {
            if overlap(span.start, span.end, existing.start, existing.end) {
                continue 'outer;
            }
        }
        chosen.push(span);
    }
    chosen.sort_by_key(|s| s.start);
    chosen
}

fn overlap(a0: usize, a1: usize, b0: usize, b1: usize) -> bool {
    a0 < b1 && b0 < a1
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manifest::{DlpRule, DlpRuleBody};

    fn phone_rule(enforce_mode: &str) -> DlpRule {
        DlpRule {
            rule_id: "dlp_phone".into(),
            rule_revision: 1,
            reason_code: "DLP_PHONE".into(),
            risk_score: 0,
            intent_action: "allow".into(),
            enforce_mode: enforce_mode.into(),
            rollout_state: enforce_mode.into(),
            canary_percent: None,
            body: DlpRuleBody {
                entity_type: "phone_cn".into(),
                pattern: None,
                mask_template: None,
                priority: None,
            },
        }
    }

    fn email_rule() -> DlpRule {
        DlpRule {
            rule_id: "dlp_email".into(),
            rule_revision: 1,
            reason_code: "DLP_EMAIL".into(),
            risk_score: 0,
            intent_action: "allow".into(),
            enforce_mode: "full".into(),
            rollout_state: "full".into(),
            canary_percent: None,
            body: DlpRuleBody {
                entity_type: "email".into(),
                pattern: None,
                mask_template: None,
                priority: None,
            },
        }
    }

    #[test]
    fn full_mode_masks_phone() {
        let rules = vec![phone_rule("full")];
        let result = mask_pii("call 13912345678 please", &rules, None);
        assert!(result.masked);
        assert!(result.text.contains("[REDACTED:PHONE_CN]"));
        assert!(!result.text.contains("13912345678"));
        assert_eq!(result.hits.len(), 1);
        assert!(!result.exempt);
    }

    #[test]
    fn dry_run_detects_without_masking() {
        let rules = vec![phone_rule("dry_run")];
        let result = mask_pii("call 13912345678 please", &rules, None);
        assert!(!result.masked);
        assert_eq!(result.text, "call 13912345678 please");
        assert_eq!(result.hits.len(), 1);
    }

    #[test]
    fn multiple_entities_masked() {
        let rules = vec![phone_rule("full"), email_rule()];
        let result = mask_pii("contact 13912345678 or admin@example.com", &rules, None);
        assert!(result.masked);
        assert!(result.text.contains("[REDACTED:PHONE_CN]"));
        assert!(result.text.contains("[REDACTED:EMAIL]"));
        assert!(!result.text.contains("13912345678"));
        assert!(!result.text.contains("admin@example.com"));
        assert_eq!(result.hits.len(), 2);
    }

    #[test]
    fn no_pii_returns_original() {
        let rules = vec![phone_rule("full")];
        let result = mask_pii("no sensitive data here", &rules, None);
        assert!(!result.masked);
        assert_eq!(result.text, "no sensitive data here");
        assert!(result.hits.is_empty());
    }

    #[test]
    fn empty_rules_returns_original() {
        let result = mask_pii("13912345678", &[], None);
        assert!(!result.masked);
        assert_eq!(result.text, "13912345678");
    }

    #[test]
    fn mask_template_respected() {
        let mut rule = phone_rule("full");
        rule.body.mask_template = Some("***".into());
        let r = mask_pii("call 13912345678 please", &[rule], None);
        assert!(r.text.contains("***"), "{}", r.text);
        assert!(!r.text.contains("13912345678"));
    }

    #[test]
    fn idcard_masked() {
        let rule = DlpRule {
            rule_id: "dlp_idcard".into(),
            rule_revision: 1,
            reason_code: "DLP_IDCARD".into(),
            risk_score: 0,
            intent_action: "allow".into(),
            enforce_mode: "full".into(),
            rollout_state: "full".into(),
            canary_percent: None,
            body: DlpRuleBody {
                entity_type: "idcard_cn".into(),
                pattern: None,
                mask_template: None,
                priority: None,
            },
        };
        let result = mask_pii("ID: 110101199003077934", &[rule], None);
        assert!(result.masked);
        assert!(result.text.contains("[REDACTED:IDCARD_CN]"));
        assert!(!result.text.contains("110101199003077934"));
    }
}
