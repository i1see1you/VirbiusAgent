package io.virbius.engine.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * B4: output content rules. NeMo-style injection/leak patterns (code injection,
 * SQL injection, template injection, XSS, credentials) implemented as regex so
 * no extra dependency is needed. Runs cheaply and synchronously on final output
 * (and on each stream window) alongside the LLM content review.
 */
@Component
public class OutputContentRules {

    public record Hit(String reasonCode, String patternName) {}

    private record Rule(String reasonCode, String name, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("OUTPUT_CODE_INJECTION", "py-os-exec",
                    Pattern.compile("(__import__\\s*\\(\\s*['\"]os|os\\.system\\s*\\(|subprocess\\.(Popen|call|run)|"
                            + "Runtime\\.getRuntime\\(\\)\\.exec|child_process\\.exec)", Pattern.CASE_INSENSITIVE)),
            new Rule("OUTPUT_SQL_INJECTION", "sqli",
                    Pattern.compile("(\\bUNION\\s+SELECT\\b|;\\s*DROP\\s+TABLE\\b|\\bOR\\s+1\\s*=\\s*1\\b|"
                            + "xp_cmdshell)", Pattern.CASE_INSENSITIVE)),
            new Rule("OUTPUT_TEMPLATE_INJECTION", "template",
                    Pattern.compile("(\\{\\{.*?(config|self|__class__|__globals__).*?\\}\\}|"
                            + "\\$\\{.*?(Runtime|getClass|scriptEngine).*?\\})", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)),
            new Rule("OUTPUT_XSS", "xss",
                    Pattern.compile("(<script[^>]*>|</script>|onerror\\s*=|onload\\s*=|javascript\\s*:)", Pattern.CASE_INSENSITIVE)),
            new Rule("OUTPUT_CREDENTIAL_LEAK", "credential",
                    Pattern.compile("(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|"
                            + "\\b(Bearer|Authorization)\\s+[A-Za-z0-9._-]{20,})")));

    /** Scan text for output-rule hits (empty when clean). */
    public List<Hit> scan(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Rule r : RULES) {
            if (r.pattern().matcher(text).find()) {
                hits.add(new Hit(r.reasonCode(), r.name()));
            }
        }
        return hits;
    }
}
