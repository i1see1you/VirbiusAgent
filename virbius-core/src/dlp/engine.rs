use crate::dlp::entity::{self, luhn_valid, normalize_bank_card};
use crate::dlp::vault::{self, TokenEntry};
use crate::enforce;
use crate::manifest::DlpRule;
use regex::Regex;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct DlpHit {
    pub rule_id: String,
    pub entity_type: String,
    pub token: String,
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone)]
pub struct DesensitizeInResult {
    pub text: String,
    pub masked: bool,
    pub hits: Vec<DlpHit>,
}

#[derive(Debug, Clone)]
pub struct DesensitizeOutResult {
    pub text: String,
    pub unresolved_tokens: Vec<String>,
}

struct CompiledRule {
    rule: DlpRule,
    regex: Regex,
    priority: i32,
    mask_template: String,
}

struct SpanMatch {
    start: usize,
    end: usize,
    rule: CompiledRule,
    plaintext: String,
}

pub fn desensitize_in(
    content: &str,
    trace_id: &str,
    rules: &[DlpRule],
    vault_ttl: Duration,
    session_id: Option<&str>,
) -> DesensitizeInResult {
    let compiled = compile_rules(rules);
    let spans = find_spans(content, &compiled, session_id);
    let hits_meta: Vec<(String, String, String, String)> = spans
        .iter()
        .enumerate()
        .map(|(i, s)| {
            let token = render_token(&s.rule.mask_template, &s.rule.rule.body.entity_type, i);
            (
                token,
                s.plaintext.clone(),
                s.rule.rule.rule_id.clone(),
                s.rule.rule.body.entity_type.clone(),
            )
        })
        .collect();

    let any_effective = spans
        .iter()
        .any(|s| dlp_effective(&s.rule.rule, session_id));
    if !any_effective {
        let hits = hits_meta
            .into_iter()
            .enumerate()
            .map(|(i, (_, _, rule_id, entity_type))| DlpHit {
                rule_id,
                entity_type,
                token: String::new(),
                start: spans[i].start,
                end: spans[i].end,
            })
            .collect();
        return DesensitizeInResult {
            text: content.to_string(),
            masked: false,
            hits,
        };
    }

    let mut out = String::new();
    let mut last = 0usize;
    let mut hits = Vec::new();
    for (i, span) in spans.iter().enumerate() {
        out.push_str(&content[last..span.start]);
        let (token, plaintext, rule_id, entity_type) = hits_meta[i].clone();
        vault::store(
            trace_id,
            token.clone(),
            TokenEntry {
                entity_type: entity_type.clone(),
                plaintext,
                rule_id: rule_id.clone(),
            },
            vault_ttl,
        );
        out.push_str(&token);
        hits.push(DlpHit {
            rule_id,
            entity_type,
            token,
            start: span.start,
            end: span.end,
        });
        last = span.end;
    }
    out.push_str(&content[last..]);
    DesensitizeInResult {
        text: out,
        masked: true,
        hits,
    }
}

pub fn desensitize_out(
    content: &str,
    trace_id: &str,
    session_id: Option<&str>,
) -> DesensitizeOutResult {
    let tokens = vault::session_tokens(trace_id);
    if tokens.is_empty() {
        return DesensitizeOutResult {
            text: content.to_string(),
            unresolved_tokens: Vec::new(),
        };
    }
    let mut out = content.to_string();
    let mut unresolved = Vec::new();
    for (token, entry) in &tokens {
        if out.contains(token) {
            out = out.replace(token, &entry.plaintext);
        } else {
            unresolved.push(token.clone());
        }
        let _ = session_id;
    }
    DesensitizeOutResult {
        text: out,
        unresolved_tokens: unresolved,
    }
}

fn compile_rules(rules: &[DlpRule]) -> Vec<CompiledRule> {
    let mut out = Vec::new();
    for rule in rules {
        let body = &rule.body;
        let pattern = if body.entity_type == "custom_regex" {
            body.pattern.as_deref()
        } else {
            None
        };
        let Some(regex) = entity::compile_entity_regex(&body.entity_type, pattern) else {
            continue;
        };
        let priority = body.priority.unwrap_or(0);
        let mask_template =
            entity::mask_template_for(&body.entity_type, body.mask_template.as_deref());
        out.push(CompiledRule {
            rule: rule.clone(),
            regex,
            priority,
            mask_template,
        });
    }
    out.sort_by_key(|b| std::cmp::Reverse(b.priority));
    out
}

fn find_spans(content: &str, rules: &[CompiledRule], session_id: Option<&str>) -> Vec<SpanMatch> {
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
            if !entity_match_valid(&compiled.rule.body.entity_type, &plaintext) {
                continue;
            }
            raw.push(SpanMatch {
                start: m.start(),
                end: m.end(),
                rule: CompiledRule {
                    rule: compiled.rule.clone(),
                    regex: compiled.regex.clone(),
                    priority: compiled.priority,
                    mask_template: compiled.mask_template.clone(),
                },
                plaintext,
            });
        }
    }
    resolve_overlaps(raw, session_id)
}

fn entity_match_valid(entity_type: &str, plaintext: &str) -> bool {
    if entity_type == "bank_card_cn" {
        let digits = normalize_bank_card(plaintext);
        return luhn_valid(&digits);
    }
    if entity_type == "idcard_cn" {
        return idcard_checksum_valid(plaintext);
    }
    true
}

fn idcard_checksum_valid(id: &str) -> bool {
    if id.len() != 18 {
        return false;
    }
    let upper: Vec<char> = id.chars().collect();
    if !upper[..17].iter().all(|c| c.is_ascii_digit()) {
        return false;
    }
    let weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
    let mut sum = 0u32;
    for (i, w) in weights.iter().enumerate() {
        let d = upper[i].to_digit(10).unwrap_or(0);
        sum += d * (*w as u32);
    }
    let check_map = ['1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'];
    let expected = check_map[(sum % 11) as usize];
    upper[17].to_ascii_uppercase() == expected
}

fn resolve_overlaps(mut spans: Vec<SpanMatch>, session_id: Option<&str>) -> Vec<SpanMatch> {
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
    let _ = session_id;
    chosen
}

fn overlap(a0: usize, a1: usize, b0: usize, b1: usize) -> bool {
    a0 < b1 && b0 < a1
}

fn render_token(template: &str, entity_type: &str, seq: usize) -> String {
    if template.contains("{seq}") {
        return template.replace("{seq}", &seq.to_string());
    }
    format!(
        "{{{{{}_{}}}}}",
        entity::default_mask_prefix(entity_type),
        seq
    )
}

fn dlp_effective(rule: &DlpRule, session_id: Option<&str>) -> bool {
    if rule.enforce_mode.eq_ignore_ascii_case("full") {
        return true;
    }
    if rule.enforce_mode.eq_ignore_ascii_case("canary") {
        let pct = rule.canary_percent.unwrap_or(0);
        return enforce::in_canary_bucket(session_id, pct);
    }
    false
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

    #[test]
    fn dry_run_detects_without_masking() {
        let rules = vec![phone_rule("dry_run")];
        let out = desensitize_in(
            "call 13800138000 please",
            "trace-dry",
            &rules,
            Duration::from_secs(60),
            None,
        );
        assert!(!out.masked);
        assert_eq!(out.text, "call 13800138000 please");
        assert_eq!(out.hits.len(), 1);
    }

    #[test]
    fn full_mode_masks_and_backfills() {
        let rules = vec![phone_rule("full")];
        let trace = "trace-full";
        let in_result = desensitize_in(
            "call 13800138000 please",
            trace,
            &rules,
            Duration::from_secs(60),
            None,
        );
        assert!(in_result.masked);
        assert!(in_result.text.contains("{{VIRBIUS_PHONE_CN_0}}"));
        assert!(!in_result.text.contains("13800138000"));

        let out_result = desensitize_out(&format!("ok {}", in_result.text), trace, None);
        assert!(out_result.text.contains("13800138000"));
        assert!(out_result.unresolved_tokens.is_empty());
    }
}
