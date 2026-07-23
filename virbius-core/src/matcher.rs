use crate::manifest::EdgeRule;

#[derive(Debug, Clone)]
pub struct RuleHit {
    pub rule: EdgeRule,
}

pub fn match_rules(content: &str, rules: &[EdgeRule]) -> Vec<RuleHit> {
    let mut hits = Vec::new();
    for rule in rules {
        if rule.body.list_type == "allow" {
            if keyword_hit(content, &rule.body.keywords, &rule.body.keywords_lower) {
                return Vec::new();
            }
            continue;
        }
        if rule.body.list_type == "deny" && keyword_hit(content, &rule.body.keywords, &rule.body.keywords_lower) {
            hits.push(RuleHit { rule: rule.clone() });
        }
    }
    hits
}

fn keyword_hit(content: &str, keywords: &[String], keywords_lower: &[String]) -> bool {
    if content.is_empty() {
        return false;
    }
    let lower = content.to_ascii_lowercase();
    keywords.iter().zip(keywords_lower).any(|(kw, kw_lower)| {
        if kw.is_empty() {
            return false;
        }
        if kw.chars().any(|c| c > '\u{007f}') {
            content.contains(kw.as_str())
        } else {
            lower.contains(kw_lower)
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manifest::RuleBody;

    fn deny_rule(keywords: &[&str]) -> EdgeRule {
        let kws: Vec<String> = keywords.iter().map(|s| s.to_string()).collect();
        EdgeRule {
            rule_id: "r1".into(),
            rule_revision: 1,
            reason_code: "X".into(),
            risk_score: 100,
            intent_action: "deny".into(),
            enforce_mode: "dry_run".into(),
            rollout_state: "dry_run".into(),
            canary_percent: None,
            body: RuleBody {
                keywords_lower: kws.iter().map(|s| s.to_ascii_lowercase()).collect(),
                keywords: kws,
                list_type: "deny".into(),
            },
        }
    }

    #[test]
    fn deny_keyword_matches() {
        let hits = match_rules("hello jailbreak world", &[deny_rule(&["jailbreak"])]);
        assert_eq!(hits.len(), 1);
    }
}
