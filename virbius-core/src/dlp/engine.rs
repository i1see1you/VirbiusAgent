use crate::dlp::entity;
use crate::dlp::vault::{self, TokenEntry};
use crate::enforce::EnforceMode;
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
    pub warnings: Vec<String>,
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
    mode: EnforceMode,
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
    let (compiled, warnings) = compile_rules(rules);
    let spans = find_spans(content, &compiled);

    let any_effective = spans.iter().any(|s| s.rule.mode.is_effective(session_id));
    if !any_effective {
        let hits = spans
            .iter()
            .map(|s| DlpHit {
                rule_id: s.rule.rule.rule_id.clone(),
                entity_type: s.rule.rule.body.entity_type.clone(),
                token: String::new(),
                start: s.start,
                end: s.end,
            })
            .collect();
        return DesensitizeInResult {
            text: content.to_string(),
            masked: false,
            hits,
            warnings,
        };
    }

    let mut out = String::new();
    let mut last = 0usize;
    let mut hits = Vec::new();
    for span in &spans {
        let seq = vault::next_seq(trace_id);
        let token = render_token(&span.rule.mask_template, seq);
        out.push_str(&content[last..span.start]);
        vault::store(
            trace_id,
            token.clone(),
            TokenEntry {
                entity_type: span.rule.rule.body.entity_type.clone(),
                plaintext: span.plaintext.clone(),
                rule_id: span.rule.rule.rule_id.clone(),
                session: session_id.map(|s| s.to_string()),
            },
            vault_ttl,
        );
        out.push_str(&token);
        hits.push(DlpHit {
            rule_id: span.rule.rule.rule_id.clone(),
            entity_type: span.rule.rule.body.entity_type.clone(),
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
        warnings,
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
        if let Some(stored_session) = &entry.session {
            if Some(stored_session.as_str()) != session_id {
                unresolved.push(token.clone());
                continue;
            }
        }
        if out.contains(token) {
            out = out.replace(token, &entry.plaintext);
        } else {
            unresolved.push(token.clone());
        }
    }
    DesensitizeOutResult {
        text: out,
        unresolved_tokens: unresolved,
    }
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
        let mask_template =
            entity::mask_template_for(&body.entity_type, body.mask_template.as_deref());
        let mode = EnforceMode::parse(&rule.enforce_mode, rule.canary_percent);
        out.push(CompiledRule {
            rule: rule.clone(),
            regex,
            priority,
            mask_template,
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
                    mask_template: compiled.mask_template.clone(),
                    mode: compiled.mode.clone(),
                },
                plaintext,
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

fn render_token(template: &str, seq: usize) -> String {
    if template.contains("{seq}") {
        return template.replace("{seq}", &seq.to_string());
    }
    format!("{template}_{seq}")
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

    fn email_rule_with_template(template: &str) -> DlpRule {
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
                mask_template: Some(template.into()),
                priority: None,
            },
        }
    }

    #[test]
    fn repeated_calls_same_trace_restore_respective_plaintexts() {
        let rules = vec![phone_rule("full")];
        let trace = "trace-bug2-repeat";
        let first = desensitize_in(
            "call 13912345678",
            trace,
            &rules,
            Duration::from_secs(60),
            None,
        );
        let second = desensitize_in(
            "call 13800138000",
            trace,
            &rules,
            Duration::from_secs(60),
            None,
        );
        assert!(first.masked);
        assert!(second.masked);

        let out_first = desensitize_out(&first.text, trace, None);
        assert!(
            out_first.text.contains("13912345678"),
            "first message must restore its own number, got: {}",
            out_first.text
        );
        assert!(
            !out_first.text.contains("13800138000"),
            "second call's number must not leak into first message, got: {}",
            out_first.text
        );

        let out_second = desensitize_out(&second.text, trace, None);
        assert!(out_second.text.contains("13800138000"));
    }

    #[test]
    fn mask_template_without_seq_is_honored() {
        let rules = vec![email_rule_with_template("[EMAIL]")];
        let trace = "trace-bug3-template";
        let out = desensitize_in(
            "alice@example.com and bob@example.org",
            trace,
            &rules,
            Duration::from_secs(60),
            None,
        );
        assert!(out.masked);
        assert_eq!(out.hits.len(), 2);
        assert!(
            out.hits.iter().all(|h| h.token.starts_with("[EMAIL]")),
            "configured mask_template must be used, got tokens: {:?}",
            out.hits.iter().map(|h| h.token.clone()).collect::<Vec<_>>()
        );
        assert_ne!(
            out.hits[0].token, out.hits[1].token,
            "distinct entities must map to distinct tokens"
        );

        let restored = desensitize_out(&out.text, trace, None);
        assert!(
            restored.text.contains("alice@example.com")
                && restored.text.contains("bob@example.org"),
            "both addresses must be restored, got: {}",
            restored.text
        );
    }

    #[test]
    fn enforce_mode_with_surrounding_whitespace_still_masks() {
        let rules = vec![phone_rule(" full")];
        let out = desensitize_in(
            "call 13800138000",
            "trace-bug5-trim",
            &rules,
            Duration::from_secs(60),
            None,
        );
        assert!(
            out.masked,
            "enforce_mode ' full' should be treated as full (consistent with enforce::is_full)"
        );
    }

    #[test]
    fn desensitize_out_does_not_restore_across_sessions() {
        let rules = vec![phone_rule("full")];
        let trace = "trace-bug6-session";
        let stored = desensitize_in(
            "call 13912345678",
            trace,
            &rules,
            Duration::from_secs(60),
            Some("session-a"),
        );
        assert!(stored.masked);

        let out_other = desensitize_out(&stored.text, trace, Some("session-b"));
        assert!(
            !out_other.text.contains("13912345678"),
            "session-b must not restore session-a's plaintext, got: {}",
            out_other.text
        );

        let out_same = desensitize_out(&stored.text, trace, Some("session-a"));
        assert!(out_same.text.contains("13912345678"));
    }

    #[test]
    fn vault_ttl_expires_from_first_store_without_sliding() {
        let rules = vec![phone_rule("full")];
        let trace = "trace-bug7-ttl";
        let ttl = Duration::from_millis(200);
        let first = desensitize_in("call 13912345678", trace, &rules, ttl, None);
        assert!(first.masked);

        std::thread::sleep(Duration::from_millis(120));
        desensitize_in("call 13800138000", trace, &rules, ttl, None);
        std::thread::sleep(Duration::from_millis(120));

        let out = desensitize_out(&first.text, trace, None);
        assert!(
            !out.text.contains("13912345678") && !out.text.contains("13800138000"),
            "token must be expired 240ms after first store with 200ms ttl, got: {}",
            out.text
        );
    }

    #[test]
    fn invalid_rules_produce_warnings() {
        let mut bad_regex = email_rule_with_template("[X]");
        bad_regex.rule_id = "bad_regex".into();
        bad_regex.body.entity_type = "custom_regex".into();
        bad_regex.body.pattern = Some("[".into());
        let mut unknown_entity = phone_rule("full");
        unknown_entity.rule_id = "unknown_entity".into();
        unknown_entity.body.entity_type = "phone".into();
        let rules = vec![bad_regex, unknown_entity, phone_rule("full")];

        let out = desensitize_in(
            "call 13800138000",
            "trace-warnings",
            &rules,
            Duration::from_secs(60),
            None,
        );
        assert!(out.masked, "valid rule must still mask");
        assert!(
            out.warnings.iter().any(|w| w.contains("bad_regex")),
            "warnings: {:?}",
            out.warnings
        );
        assert!(
            out.warnings
                .iter()
                .any(|w| w.contains("unknown_entity") && w.contains("unknown entity_type")),
            "warnings: {:?}",
            out.warnings
        );
    }
}
