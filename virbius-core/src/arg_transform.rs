//! Tool-catalog arg transforms: restrict / redact / truncate.
//! Applied after policy allow (or fast-path): replace with a definite value.
//! List restrict may become []; a scalar that does not match fails the call.

use crate::dlp::mask_pii;
use crate::manifest::{self, DlpRule, DlpRuleBody};
use regex::Regex;
use serde::Deserialize;
use serde_json::Value;

pub const MAX_MUTATIONS: usize = 64;
const MAX_MATCH: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TransformError {
    pub reason: String,
    pub detail: String,
}

impl TransformError {
    fn new(reason: &str, detail: impl Into<String>) -> Self {
        Self {
            reason: reason.to_string(),
            detail: detail.into(),
        }
    }
}

#[derive(Debug, Clone)]
enum PathSpec {
    Explicit(Vec<PathSeg>),
    ScanStrings,
}

#[derive(Debug, Clone)]
enum PathSeg {
    Key(String),
    Index(usize),
}

#[derive(Debug, Clone)]
enum To {
    Values(Vec<Value>),
    Prefixes(Vec<String>),
    Match(Regex),
    Range { min: Option<f64>, max: Option<f64> },
}

#[derive(Debug, Clone)]
enum Op {
    Restrict { to: To },
    Redact { rules: Vec<DlpRule> },
    Truncate { max_len: usize },
}

#[derive(Debug, Clone)]
struct Mutation {
    path_raw: String,
    path: PathSpec,
    op: Op,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct WireMutation {
    path: String,
    op: String,
    #[serde(default)]
    to: Option<Value>,
    #[serde(default)]
    on_violation: Option<String>,
    #[serde(default)]
    detector: Option<String>,
    #[serde(default)]
    detectors: Option<Vec<String>>,
    #[serde(default)]
    max_len: Option<usize>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AppliedOp {
    pub path: String,
    pub op: &'static str,
    pub count: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Applied {
    pub args: Value,
    pub applied: Vec<AppliedOp>,
    pub skipped: Vec<AppliedOp>,
}

pub fn apply_config(args: &Value, config: &Value) -> Result<Applied, TransformError> {
    let mutations = parse_config(config)?;
    apply_mutations(args, &mutations)
}

fn parse_config(config: &Value) -> Result<Vec<Mutation>, TransformError> {
    let arr = if config.is_array() {
        config
    } else if let Some(m) = config.get("mutations") {
        let phase = config
            .get("phase")
            .and_then(Value::as_str)
            .unwrap_or("pre_tool_call");
        if phase != "pre_tool_call" {
            return Err(TransformError::new(
                "arg_transform_invalid",
                format!("phase must be pre_tool_call, got {phase}"),
            ));
        }
        m
    } else {
        return Err(TransformError::new(
            "arg_transform_invalid",
            "expected mutations array",
        ));
    };
    let raw: Vec<WireMutation> = serde_json::from_value(arr.clone())
        .map_err(|e| TransformError::new("arg_transform_invalid", e.to_string()))?;
    if raw.is_empty() || raw.len() > MAX_MUTATIONS {
        return Err(TransformError::new(
            "arg_transform_invalid",
            format!("mutations count must be 1..{MAX_MUTATIONS}"),
        ));
    }
    let mut out = Vec::with_capacity(raw.len());
    let mut seen = std::collections::HashSet::new();
    for m in raw {
        let mutation = parse_one(m)?;
        if !seen.insert(canonical_path(&mutation.path)) {
            return Err(TransformError::new(
                "arg_transform_conflict",
                format!("duplicate path '{}'", mutation.path_raw),
            ));
        }
        out.push(mutation);
    }
    out.sort_by_key(|m| op_order(&m.op));
    Ok(out)
}

/// Canonical form of a parsed path so duplicates are detected by resolved
/// segments, not by raw spelling (which future grammar forms could vary).
fn canonical_path(path: &PathSpec) -> String {
    match path {
        PathSpec::ScanStrings => "$..*string".to_string(),
        PathSpec::Explicit(segs) => segs
            .iter()
            .map(|s| match s {
                PathSeg::Key(k) => format!(".{k}"),
                PathSeg::Index(i) => format!("[{i}]"),
            })
            .collect(),
    }
}

fn op_order(op: &Op) -> u8 {
    match op {
        Op::Restrict { .. } => 0,
        Op::Redact { .. } => 1,
        Op::Truncate { .. } => 2,
    }
}

fn parse_one(m: WireMutation) -> Result<Mutation, TransformError> {
    let path = parse_path(&m.path)?;
    let op = match m.op.as_str() {
        "restrict" => {
            let to = parse_to(m.to.as_ref().ok_or_else(|| bad(&m, "to"))?)?;
            match m.on_violation.as_deref().unwrap_or("clamp") {
                "clamp" => {}
                _ => return Err(bad(&m, "on_violation")),
            }
            if matches!(path, PathSpec::ScanStrings) {
                return Err(TransformError::new(
                    "arg_transform_invalid",
                    "scan path is only valid for redact/truncate",
                ));
            }
            Op::Restrict { to }
        }
        "truncate" => Op::Truncate {
            max_len: m
                .max_len
                .filter(|n| *n > 0)
                .ok_or_else(|| bad(&m, "max_len"))?,
        },
        "redact" => {
            let mut detectors = m.detectors.clone().unwrap_or_default();
            if let Some(d) = &m.detector {
                if !d.is_empty() {
                    detectors.push(d.clone());
                }
            }
            detectors.sort();
            detectors.dedup();
            if detectors.is_empty()
                || detectors
                    .iter()
                    .any(|d| d == "custom_regex" || d.is_empty())
            {
                return Err(TransformError::new(
                    "arg_transform_invalid",
                    "redact requires built-in detectors",
                ));
            }
            for d in &detectors {
                if crate::dlp::entity::built_in_pattern(d).is_none() {
                    return Err(TransformError::new(
                        "arg_transform_invalid",
                        format!("unknown detector '{d}'"),
                    ));
                }
            }
            Op::Redact {
                rules: build_synth_rules(&detectors, &manifest::load().dlp_rules),
            }
        }
        other => {
            return Err(TransformError::new(
                "arg_transform_invalid",
                format!("unsupported op '{other}'"),
            ))
        }
    };
    Ok(Mutation {
        path_raw: m.path,
        path,
        op,
    })
}

fn bad(m: &WireMutation, field: &str) -> TransformError {
    TransformError::new(
        "arg_transform_invalid",
        format!("{}: missing/invalid '{field}'", m.path),
    )
}

fn parse_to(v: &Value) -> Result<To, TransformError> {
    match v {
        Value::Array(items) => Ok(To::Values(items.clone())),
        Value::Object(map) => {
            if let Some(vals) = map.get("values").and_then(Value::as_array) {
                return Ok(To::Values(vals.clone()));
            }
            if let Some(pre) = map.get("prefixes").and_then(Value::as_array) {
                return Ok(To::Prefixes(string_list(pre)?));
            }
            if let Some(pat) = map.get("match").and_then(Value::as_str) {
                return parse_match(pat);
            }
            if map.get("domains").is_some() {
                return Err(TransformError::new(
                    "arg_transform_invalid",
                    "restrict.to.domains is removed; use to.match",
                ));
            }
            if map.contains_key("min") || map.contains_key("max") {
                let min = map.get("min").and_then(Value::as_f64);
                let max = map.get("max").and_then(Value::as_f64);
                if min.is_none() && max.is_none() {
                    return Err(TransformError::new(
                        "arg_transform_invalid",
                        "restrict.to range needs a numeric min or max",
                    ));
                }
                if let (Some(lo), Some(hi)) = (min, max) {
                    if lo > hi {
                        return Err(TransformError::new(
                            "arg_transform_invalid",
                            "restrict.to.min must be <= max",
                        ));
                    }
                }
                return Ok(To::Range { min, max });
            }
            Err(TransformError::new(
                "arg_transform_invalid",
                "restrict.to shape unknown",
            ))
        }
        _ => Err(TransformError::new(
            "arg_transform_invalid",
            "restrict.to must be array or object",
        )),
    }
}

fn parse_match(pat: &str) -> Result<To, TransformError> {
    if pat.is_empty() || pat.len() > MAX_MATCH {
        return Err(TransformError::new(
            "arg_transform_invalid",
            format!("restrict.to.match length must be 1..{MAX_MATCH}"),
        ));
    }
    Regex::new(pat)
        .map(To::Match)
        .map_err(|e| TransformError::new("arg_transform_invalid", format!("invalid match: {e}")))
}

fn string_list(items: &[Value]) -> Result<Vec<String>, TransformError> {
    items
        .iter()
        .map(|i| {
            i.as_str().map(str::to_string).ok_or_else(|| {
                TransformError::new(
                    "arg_transform_invalid",
                    "restrict.to entries must be strings",
                )
            })
        })
        .collect()
}

fn parse_path(s: &str) -> Result<PathSpec, TransformError> {
    let bad =
        |why: &str| TransformError::new("arg_transform_invalid", format!("path '{s}': {why}"));
    if s == "$..*string" {
        return Ok(PathSpec::ScanStrings);
    }
    let rest = s
        .strip_prefix('$')
        .ok_or_else(|| bad("must start with $"))?;
    if rest.is_empty() {
        return Err(bad("root path not allowed"));
    }
    if rest.starts_with('.') && rest[1..].starts_with('.') || rest.contains('*') {
        return Err(bad("filter/wildcard expressions are not supported"));
    }
    let mut segs = Vec::new();
    let bytes = rest.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == b'.' {
            i += 1;
            let start = i;
            while i < bytes.len() && bytes[i] != b'.' && bytes[i] != b'[' {
                i += 1;
            }
            let key = &rest[start..i];
            if key.is_empty()
                || !key
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '@' | ' '))
            {
                return Err(bad("invalid key segment"));
            }
            segs.push(PathSeg::Key(key.to_string()));
        } else if bytes[i] == b'[' {
            i += 1;
            let start = i;
            while i < bytes.len() && bytes[i] != b']' {
                i += 1;
            }
            if i >= bytes.len() || start == i {
                return Err(bad("unterminated or empty index"));
            }
            let num = &rest[start..i];
            if num.len() > 1 && num.starts_with('0') {
                // A lenient parse here would make `$.a[01]` alias `$.a[1]` and
                // slip past duplicate detection.
                return Err(bad("index must not have leading zeros"));
            }
            let idx: usize = num.parse().map_err(|_| bad("index must be a number"))?;
            i += 1;
            segs.push(PathSeg::Index(idx));
        } else {
            return Err(bad("expected '.' or '['"));
        }
    }
    if segs.is_empty() {
        return Err(bad("no segments"));
    }
    Ok(PathSpec::Explicit(segs))
}

fn find_mut<'a>(root: &'a mut Value, segs: &[PathSeg]) -> Option<&'a mut Value> {
    let mut cur = root;
    for seg in segs {
        cur = match seg {
            PathSeg::Key(k) => cur.get_mut(k)?,
            PathSeg::Index(i) => {
                let len = cur.as_array()?.len();
                if *i < len {
                    cur.get_mut(*i)?
                } else {
                    return None;
                }
            }
        };
    }
    Some(cur)
}

fn map_strings_mut(
    v: &mut Value,
    f: &mut dyn FnMut(&mut String) -> Result<u32, TransformError>,
) -> Result<u32, TransformError> {
    match v {
        Value::String(s) => f(s),
        Value::Array(items) => {
            let mut n = 0u32;
            for item in items {
                n += map_strings_mut(item, f)?;
            }
            Ok(n)
        }
        Value::Object(map) => {
            let keys: Vec<String> = map.keys().cloned().collect();
            let mut n = 0u32;
            for k in keys {
                if let Some(child) = map.get_mut(&k) {
                    n += map_strings_mut(child, f)?;
                }
            }
            Ok(n)
        }
        _ => Ok(0),
    }
}

fn apply_mutations(args: &Value, mutations: &[Mutation]) -> Result<Applied, TransformError> {
    let mut root = args.clone();
    let mut applied = Vec::new();
    let mut skipped = Vec::new();
    for m in mutations {
        let op_name = op_label(&m.op);
        match &m.path {
            PathSpec::ScanStrings => {
                let mut hits = 0u32;
                map_strings_mut(&mut root, &mut |s| {
                    let n = apply_on_string(s, &m.op)?;
                    hits += n;
                    Ok(n)
                })?;
                if hits > 0 {
                    applied.push(AppliedOp {
                        path: "$..*string".into(),
                        op: op_name,
                        count: hits,
                    });
                }
            }
            PathSpec::Explicit(segs) => {
                let Some(target) = find_mut(&mut root, segs) else {
                    skipped.push(AppliedOp {
                        path: m.path_raw.clone(),
                        op: op_name,
                        count: 0,
                    });
                    continue;
                };
                let count = apply_on_value(target, &m.op, &m.path_raw)?;
                if count > 0 {
                    applied.push(AppliedOp {
                        path: m.path_raw.clone(),
                        op: op_name,
                        count,
                    });
                }
            }
        }
    }
    Ok(Applied {
        args: root,
        applied,
        skipped,
    })
}

fn op_label(op: &Op) -> &'static str {
    match op {
        Op::Restrict { .. } => "restrict",
        Op::Redact { .. } => "redact",
        Op::Truncate { .. } => "truncate",
    }
}

fn apply_on_value(target: &mut Value, op: &Op, path: &str) -> Result<u32, TransformError> {
    let mismatch = |want: &str| {
        TransformError::new(
            "arg_transform_type_mismatch",
            format!("{path}: expected {want}"),
        )
    };
    match op {
        Op::Truncate { max_len } => match target {
            Value::String(s) => {
                let n = s.chars().count();
                if n > *max_len {
                    *s = s.chars().take(*max_len).collect();
                    Ok(1)
                } else {
                    Ok(0)
                }
            }
            Value::Array(items) => {
                if items.len() > *max_len {
                    items.truncate(*max_len);
                    Ok(1)
                } else {
                    Ok(0)
                }
            }
            _ => Err(mismatch("string or array")),
        },
        Op::Redact { rules } => map_strings_mut(target, &mut |s| {
            let (new, n) = redact_string(s, rules);
            if n > 0 {
                *s = new;
            }
            Ok(n)
        }),
        Op::Restrict { to } => apply_restrict(target, to, path),
    }
}

fn apply_on_string(s: &mut String, op: &Op) -> Result<u32, TransformError> {
    match op {
        Op::Redact { rules } => {
            let (new, n) = redact_string(s, rules);
            if n > 0 {
                *s = new;
            }
            Ok(n)
        }
        Op::Truncate { max_len } => {
            let n = s.chars().count();
            if n > *max_len {
                *s = s.chars().take(*max_len).collect();
                Ok(1)
            } else {
                Ok(0)
            }
        }
        Op::Restrict { .. } => Err(TransformError::new(
            "arg_transform_invalid",
            "restrict cannot target $..*string",
        )),
    }
}

/// Single deterministic pass over `s` with all detector rules: span
/// overlaps are resolved by priority inside `mask_pii`, so e.g. an 18-digit
/// id card can never be swallowed by the 13–19-digit bank-card pattern.
fn redact_string(s: &str, rules: &[DlpRule]) -> (String, u32) {
    let r = mask_pii(s, rules, None);
    if r.masked {
        (r.text, r.hits.len() as u32)
    } else {
        (s.to_string(), 0)
    }
}

/// A detector name is a *selector* into the catalog DLP rules, not a
/// standalone rule: `priority` and `mask_template` are inherited so edge
/// redaction competes and renders exactly like engine/output masking for
/// the same entity. Enforcement posture is deliberately NOT inherited —
/// configuring a redact is itself the decision to enforce, so the rule
/// always applies in full (catalog dry_run/canary rollout does not leak
/// into this boundary). Patterns stay built-in per entity (mask_pii only
/// honors `body.pattern` for `custom_regex`).
fn build_synth_rules(detectors: &[String], catalog: &[DlpRule]) -> Vec<DlpRule> {
    detectors
        .iter()
        .map(|d| {
            let inherited = catalog
                .iter()
                .filter(|r| r.body.entity_type == *d)
                .max_by_key(|r| r.body.priority.unwrap_or(0));
            DlpRule {
                rule_id: "arg_transform".into(),
                rule_revision: 1,
                reason_code: "ARG_TRANSFORM".into(),
                risk_score: 0,
                intent_action: "allow".into(),
                enforce_mode: "full".into(),
                rollout_state: "full".into(),
                canary_percent: None,
                body: DlpRuleBody {
                    entity_type: d.clone(),
                    pattern: None,
                    mask_template: inherited.and_then(|r| r.body.mask_template.clone()),
                    priority: Some(match inherited {
                        Some(r) => r.body.priority.unwrap_or(0),
                        None => default_priority(d),
                    }),
                },
            }
        })
        .collect()
}

/// Fallback specificity order when the catalog has no rule for an entity:
/// id card outranks bank card (whose digit-run pattern overlaps it), and
/// everything else stays neutral.
fn default_priority(entity_type: &str) -> i32 {
    match entity_type {
        "idcard_cn" => 100,
        "bank_card_cn" => 50,
        _ => 10,
    }
}

fn apply_restrict(target: &mut Value, to: &To, path: &str) -> Result<u32, TransformError> {
    let outside = || TransformError::new("arg_transform_value_outside_set", path.to_string());
    match to {
        To::Range { min, max } => {
            let x = target.as_f64().ok_or_else(|| {
                TransformError::new(
                    "arg_transform_type_mismatch",
                    format!("{path}: expected number"),
                )
            })?;
            let lo = min.unwrap_or(f64::MIN);
            let hi = max.unwrap_or(f64::MAX);
            if x < lo || x > hi {
                *target = to_number(x.clamp(lo, hi));
                return Ok(1);
            }
            Ok(0)
        }
        To::Prefixes(list) => restrict_strings(target, path, |s| {
            !s.contains("..") && list.iter().any(|p| s.starts_with(p.as_str()))
        }),
        To::Match(re) => restrict_strings(target, path, |s| re.is_match(s)),
        To::Values(list) => {
            let member = |v: &Value| list.iter().any(|x| x == v);
            match target {
                Value::Array(items) => {
                    let n = items.len();
                    let kept: Vec<Value> = items.iter().filter(|i| member(i)).cloned().collect();
                    let changed = (n - kept.len()) as u32;
                    *items = kept;
                    Ok(changed)
                }
                v => {
                    if member(v) {
                        return Ok(0);
                    }
                    if list.len() == 1 {
                        *v = list[0].clone();
                        Ok(1)
                    } else {
                        Err(outside())
                    }
                }
            }
        }
    }
}

fn restrict_strings(
    target: &mut Value,
    path: &str,
    keep: impl Fn(&str) -> bool,
) -> Result<u32, TransformError> {
    let outside = || TransformError::new("arg_transform_value_outside_set", path.to_string());
    match target {
        Value::String(s) => {
            if keep(s) {
                Ok(0)
            } else {
                Err(outside())
            }
        }
        Value::Array(items) => {
            let n = items.len();
            let mut kept = Vec::new();
            for it in items.iter() {
                let s = it.as_str().ok_or_else(|| {
                    TransformError::new(
                        "arg_transform_type_mismatch",
                        format!("{path}[]: expected strings"),
                    )
                })?;
                if keep(s) {
                    kept.push(it.clone());
                }
            }
            let changed = (n - kept.len()) as u32;
            *items = kept;
            Ok(changed)
        }
        _ => Err(TransformError::new(
            "arg_transform_type_mismatch",
            format!("{path}: expected string or string array"),
        )),
    }
}

fn to_number(x: f64) -> Value {
    if x.fract() == 0.0 && x.abs() < (i64::MAX as f64) {
        Value::from(x as i64)
    } else {
        serde_json::Number::from_f64(x)
            .map(Value::Number)
            .unwrap_or(Value::Null)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn cap_via_restrict_max() {
        let cfg = json!({"phase":"pre_tool_call","mutations":[
            {"path":"$.amount","op":"restrict","to":{"max":500},"on_violation":"clamp"}
        ]});
        let r = apply_config(&json!({"amount": 800}), &cfg).unwrap();
        assert_eq!(r.args["amount"], json!(500));
        let r2 = apply_config(&json!({"amount": 100}), &cfg).unwrap();
        assert!(r2.applied.is_empty());
    }

    #[test]
    fn duplicate_path_rejected() {
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"truncate","max_len":2},
            {"path":"$.a","op":"redact","detector":"phone_cn"}
        ]});
        assert_eq!(
            apply_config(&json!({"a":"x"}), &cfg).unwrap_err().reason,
            "arg_transform_conflict"
        );
    }

    #[test]
    fn redact_phone() {
        let cfg = json!({"mutations":[
            {"path":"$.note","op":"redact","detector":"phone_cn"}
        ]});
        let r = apply_config(&json!({"note":"call 13800138000"}), &cfg).unwrap();
        assert!(r.args["note"]
            .as_str()
            .unwrap()
            .contains("[REDACTED:PHONE_CN]"));
    }

    #[test]
    fn singleton_restrict_clamp() {
        let cfg = json!({"mutations":[
            {"path":"$.bcc","op":"restrict","to":["audit@corp.com"],"on_violation":"clamp"}
        ]});
        let r = apply_config(&json!({"bcc":"x@evil"}), &cfg).unwrap();
        assert_eq!(r.args["bcc"], json!("audit@corp.com"));
    }

    #[test]
    fn absent_path_skipped() {
        let cfg = json!({"mutations":[{"path":"$.missing","op":"truncate","max_len":1}]});
        let r = apply_config(&json!({"a":1}), &cfg).unwrap();
        assert_eq!(r.skipped.len(), 1);
    }

    #[test]
    fn unknown_op_rejected() {
        let cfg = json!({"mutations":[{"path":"$.a","op":"cap","value":1}]});
        assert_eq!(
            apply_config(&json!({"a":1}), &cfg).unwrap_err().reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn deny_rejected() {
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"restrict","to":["x"],"on_violation":"deny"}
        ]});
        assert_eq!(
            apply_config(&json!({"a":"y"}), &cfg).unwrap_err().reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn prefix_and_multi_enum_scalar_fail() {
        let prefix = json!({"mutations":[
            {"path":"$.url","op":"restrict","to":{"prefixes":["https://"]},"on_violation":"clamp"}
        ]});
        assert_eq!(
            apply_config(&json!({"url":"http://x"}), &prefix)
                .unwrap_err()
                .reason,
            "arg_transform_value_outside_set"
        );

        let multi = json!({"mutations":[
            {"path":"$.m","op":"restrict","to":["a","b"],"on_violation":"clamp"}
        ]});
        assert_eq!(
            apply_config(&json!({"m":"z"}), &multi).unwrap_err().reason,
            "arg_transform_value_outside_set"
        );
    }

    #[test]
    fn enum_array_filters_to_empty() {
        let cfg = json!({"mutations":[
            {"path":"$.bcc","op":"restrict","to":["a@corp.com"],"on_violation":"clamp"}
        ]});
        let r = apply_config(&json!({"bcc":["x@evil.com"]}), &cfg).unwrap();
        assert_eq!(r.args["bcc"], json!([]));
        assert!(r.args.as_object().unwrap().contains_key("bcc"));
    }

    #[test]
    fn match_filters_and_rejects_scalar() {
        let cfg = json!({"mutations":[
            {"path":"$.url","op":"restrict","to":{"match":"^https://files\\.corp\\.com"},"on_violation":"clamp"}
        ]});
        let r = apply_config(&json!({"url":"https://files.corp.com/a"}), &cfg).unwrap();
        assert!(r.applied.is_empty());
        assert_eq!(
            apply_config(&json!({"url":"https://evil.com"}), &cfg)
                .unwrap_err()
                .reason,
            "arg_transform_value_outside_set"
        );

        let arr = json!({"mutations":[
            {"path":"$.urls","op":"restrict","to":{"match":"^https://ok"},"on_violation":"clamp"}
        ]});
        let r = apply_config(&json!({"urls":["https://ok/a","http://no"]}), &arr).unwrap();
        assert_eq!(r.args["urls"], json!(["https://ok/a"]));
        let r = apply_config(&json!({"urls":["http://no"]}), &arr).unwrap();
        assert_eq!(r.args["urls"], json!([]));
    }

    #[test]
    fn match_invalid_rejected() {
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"restrict","to":{"match":"("},"on_violation":"clamp"}
        ]});
        assert_eq!(
            apply_config(&json!({"a":"x"}), &cfg).unwrap_err().reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn domains_shape_rejected() {
        let cfg = json!({"mutations":[
            {"path":"$.to","op":"restrict","to":{"domains":["corp.com"]},"on_violation":"clamp"}
        ]});
        assert_eq!(
            apply_config(&json!({"to":"a@corp.com"}), &cfg)
                .unwrap_err()
                .reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn redact_explicit_array_leaves_other_fields() {
        let cfg = json!({"mutations":[
            {"path":"$.recipients","op":"redact","detector":"email"}
        ]});
        let r = apply_config(
            &json!({"recipients":["a@x.com","ok"],"note":"keep 13800138000"}),
            &cfg,
        )
        .unwrap();
        assert!(r.args["recipients"][0]
            .as_str()
            .unwrap()
            .contains("[REDACTED:EMAIL]"));
        assert_eq!(r.args["recipients"][1], json!("ok"));
        assert_eq!(r.args["note"], json!("keep 13800138000"));
    }

    #[test]
    fn redact_number_is_noop() {
        let cfg = json!({"mutations":[{"path":"$.amount","op":"redact","detector":"email"}]});
        let r = apply_config(&json!({"amount": 9}), &cfg).unwrap();
        assert_eq!(r.args["amount"], json!(9));
        assert!(r.applied.is_empty());
    }

    #[test]
    fn truncate_array_still_caps_len() {
        let cfg = json!({"mutations":[{"path":"$.recipients","op":"truncate","max_len":2}]});
        let r = apply_config(&json!({"recipients":["a","b","c"]}), &cfg).unwrap();
        assert_eq!(r.args["recipients"], json!(["a", "b"]));
    }

    #[test]
    fn redact_overlap_resolved_by_priority() {
        // id card (priority 100) must win the span-overlap race against the
        // bank-card digit-run pattern (50) in a single pass.
        let cfg = json!({"mutations":[
            {"path":"$.note","op":"redact","detectors":["idcard_cn","bank_card_cn"]}
        ]});
        let r = apply_config(&json!({"note":"id 110101199003077934 ok"}), &cfg).unwrap();
        let s = r.args["note"].as_str().unwrap();
        assert!(s.contains("[REDACTED:IDCARD_CN]"), "{s}");
        assert!(!s.contains("BANK_CARD"), "{s}");
        assert_eq!(r.applied[0].count, 1);
    }

    #[test]
    fn synth_rules_inherit_catalog_priority_and_template() {
        let catalog = vec![DlpRule {
            rule_id: "dlp1".into(),
            rule_revision: 1,
            reason_code: "R".into(),
            risk_score: 0,
            intent_action: "allow".into(),
            enforce_mode: "dry_run".into(),
            rollout_state: "dry_run".into(),
            canary_percent: None,
            body: DlpRuleBody {
                entity_type: "phone_cn".into(),
                pattern: None,
                mask_template: Some("***".into()),
                priority: Some(7),
            },
        }];
        let rules = build_synth_rules(&["bank_card_cn".into(), "phone_cn".into()], &catalog);
        let phone = rules
            .iter()
            .find(|r| r.body.entity_type == "phone_cn")
            .unwrap();
        assert_eq!(phone.body.mask_template.as_deref(), Some("***"));
        assert_eq!(phone.body.priority, Some(7));
        assert_eq!(phone.enforce_mode, "full"); // posture is never inherited
        let bank = rules
            .iter()
            .find(|r| r.body.entity_type == "bank_card_cn")
            .unwrap();
        assert_eq!(bank.body.priority, Some(50)); // built-in fallback when absent from catalog
    }

    #[test]
    fn range_inverted_bounds_rejected() {
        // Without a parse-time guard this reaches f64::clamp with lo>hi and
        // panics on the first numeric argument that hits the path.
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"restrict","to":{"min":10,"max":5},"on_violation":"clamp"}
        ]});
        let e = apply_config(&json!({"a": 7}), &cfg).unwrap_err();
        assert_eq!(e.reason, "arg_transform_invalid");
    }

    #[test]
    fn range_null_bounds_rejected() {
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"restrict","to":{"min":null,"max":null},"on_violation":"clamp"}
        ]});
        assert_eq!(
            apply_config(&json!({"a": 7}), &cfg).unwrap_err().reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn range_single_real_bound_ok() {
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"restrict","to":{"min":5,"max":null},"on_violation":"clamp"}
        ]});
        let r = apply_config(&json!({"a": 1}), &cfg).unwrap();
        assert_eq!(r.args["a"], json!(5));
    }

    #[test]
    fn leading_zero_index_rejected() {
        // "01" parsing to 1 lets `$.a[01]` slip past the raw-string duplicate
        // check while targeting the same element as `$.a[1]`.
        let cfg = json!({"mutations":[
            {"path":"$.a[01]","op":"truncate","max_len":1}
        ]});
        assert_eq!(
            apply_config(&json!({"a": ["xyz"]}), &cfg)
                .unwrap_err()
                .reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn value_field_rejected() {
        // `value` was silently accepted dead weight on both ends.
        let cfg = json!({"mutations":[
            {"path":"$.a","op":"truncate","max_len":1,"value":9}
        ]});
        assert_eq!(
            apply_config(&json!({"a": "xyz"}), &cfg).unwrap_err().reason,
            "arg_transform_invalid"
        );
    }

    #[test]
    fn multi_index_and_leading_bracket_paths_apply() {
        // `.key` with stacked indices, and a leading `[n]`.
        let cfg = json!({"mutations":[{"path":"$.matrix[0][1]","op":"truncate","max_len":2}]});
        let r = apply_config(&json!({"matrix":[["ab","abcd"]]}), &cfg).unwrap();
        assert_eq!(r.args["matrix"][0][1], json!("ab"));
        assert_eq!(r.applied[0].count, 1);

        let cfg2 = json!({"mutations":[
            {"path":"$[0].a","op":"restrict","to":["ok"],"on_violation":"clamp"}
        ]});
        let r2 = apply_config(&json!([{"a":"bad"}]), &cfg2).unwrap();
        assert_eq!(r2.args[0]["a"], json!("ok"));
    }

    #[test]
    fn scan_may_mix_with_restrict() {
        let cfg = json!({"mutations":[
            {"path":"$.amount","op":"restrict","to":{"max":10},"on_violation":"clamp"},
            {"path":"$..*string","op":"redact","detector":"email"}
        ]});
        let r = apply_config(&json!({"amount": 99, "note":"a@x.com"}), &cfg).unwrap();
        assert_eq!(r.args["amount"], json!(10));
        assert!(r.args["note"]
            .as_str()
            .unwrap()
            .contains("[REDACTED:EMAIL]"));
    }
}
