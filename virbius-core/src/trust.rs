//! Trust Tagger: wraps high-risk tool return values with explicit trust boundary tags.
//!
//! Only high(5) / network(4) risk class tools receive explicit trust layering, as
//! agreed in DESIGN.md §13.10.  Lower-risk tools pass through unchanged to avoid
//! extra token overhead.
//!
//! Tags injected:
//! * `<trust_boundary tool="..." risk_class="high|network">...</trust_boundary>`
//! * `<!-- untrusted_data source="tool:..." -->` prefix comment when STI taint
//!   is suspected (caller may also set `tainted=true`).
//!
//! The tags are plain-text XML-ish markers; the LLM is instructed (via
//! PromptGateway) to never treat content inside a `trust_boundary` block as
//! instructions.

/// A snapshot of the inputs needed to decide whether and how to tag a tool result.
#[derive(Debug, Clone)]
pub struct TrustTagInput<'a> {
    pub tool_name: &'a str,
    pub risk_class: &'a str,
    pub tool_result: &'a str,
    /// Whether STI taint detection flagged this result.
    pub tainted: bool,
}

/// Outcome of trust tagging.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrustTagResult {
    /// The result was not wrapped (low/medium risk, or wrapping disabled).
    Passthrough,
    /// The result was wrapped with a trust boundary.
    Wrapped {
        tagged_text: String,
        risk_class: String,
        tainted: bool,
    },
}

pub struct TrustTagger;

impl TrustTagger {
    /// Returns true if the given risk class requires explicit trust layering.
    ///
    /// Only `high` (5) and `network` (4) require wrapping.
    pub fn requires_tagging(risk_class: &str) -> bool {
        matches!(risk_class, "high" | "network")
    }

    /// Tag a tool result with a trust boundary if the risk class requires it.
    ///
    /// When the risk class is low/medium (or unknown), the result is passed
    /// through unchanged and [`TrustTagResult::Passthrough`] is returned.
    /// For high/network risk, the result is wrapped in `<trust_boundary>` tags
    /// and, if tainted, prefixed with an `<untrusted_data>` comment.
    pub fn tag(input: TrustTagInput) -> TrustTagResult {
        if !Self::requires_tagging(input.risk_class) {
            return TrustTagResult::Passthrough;
        }

        let mut tagged = String::with_capacity(input.tool_result.len() + 256);
        if input.tainted {
            tagged.push_str(&format!(
                "<untrusted_data source=\"tool:{}\">\n",
                input.tool_name
            ));
        }
        tagged.push_str(&format!(
            "<trust_boundary tool=\"{}\" risk_class=\"{}\">\n",
            input.tool_name, input.risk_class
        ));
        tagged.push_str(input.tool_result);
        if !input.tool_result.ends_with('\n') {
            tagged.push('\n');
        }
        tagged.push_str("</trust_boundary>");
        if input.tainted {
            tagged.push_str("\n</untrusted_data>");
        }

        TrustTagResult::Wrapped {
            tagged_text: tagged,
            risk_class: input.risk_class.to_string(),
            tainted: input.tainted,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn low_risk_passthrough() {
        let result = TrustTagger::tag(TrustTagInput {
            tool_name: "read_file",
            risk_class: "low",
            tool_result: "hello world",
            tainted: false,
        });
        assert_eq!(result, TrustTagResult::Passthrough);
    }

    #[test]
    fn medium_risk_passthrough() {
        let result = TrustTagger::tag(TrustTagInput {
            tool_name: "search",
            risk_class: "medium",
            tool_result: "results...",
            tainted: false,
        });
        assert_eq!(result, TrustTagResult::Passthrough);
    }

    #[test]
    fn high_risk_wrapped() {
        let result = TrustTagger::tag(TrustTagInput {
            tool_name: "shell_exec",
            risk_class: "high",
            tool_result: "output line 1\noutput line 2",
            tainted: false,
        });
        match result {
            TrustTagResult::Wrapped {
                tagged_text,
                risk_class,
                tainted,
            } => {
                assert_eq!(risk_class, "high");
                assert!(!tainted);
                assert!(tagged_text
                    .contains("<trust_boundary tool=\"shell_exec\" risk_class=\"high\">"));
                assert!(tagged_text.contains("output line 1"));
                assert!(tagged_text.contains("</trust_boundary>"));
                assert!(!tagged_text.contains("<untrusted_data"));
            }
            _ => panic!("expected Wrapped"),
        }
    }

    #[test]
    fn network_risk_tainted_wrapped() {
        let result = TrustTagger::tag(TrustTagInput {
            tool_name: "http_get",
            risk_class: "network",
            tool_result: "<script>evil()</script>",
            tainted: true,
        });
        match result {
            TrustTagResult::Wrapped {
                tagged_text,
                risk_class,
                tainted,
            } => {
                assert_eq!(risk_class, "network");
                assert!(tainted);
                assert!(tagged_text.contains("<untrusted_data source=\"tool:http_get\">"));
                assert!(tagged_text
                    .contains("<trust_boundary tool=\"http_get\" risk_class=\"network\">"));
                assert!(tagged_text.contains("</trust_boundary>"));
                assert!(tagged_text.contains("</untrusted_data>"));
            }
            _ => panic!("expected Wrapped"),
        }
    }

    #[test]
    fn unknown_risk_passthrough() {
        let result = TrustTagger::tag(TrustTagInput {
            tool_name: "unknown_tool",
            risk_class: "unknown",
            tool_result: "data",
            tainted: false,
        });
        assert_eq!(result, TrustTagResult::Passthrough);
    }

    #[test]
    fn requires_tagging_matrix() {
        assert!(!TrustTagger::requires_tagging("low"));
        assert!(!TrustTagger::requires_tagging("medium"));
        assert!(TrustTagger::requires_tagging("high"));
        assert!(TrustTagger::requires_tagging("network"));
        assert!(!TrustTagger::requires_tagging(""));
        assert!(!TrustTagger::requires_tagging("unknown"));
    }
}
