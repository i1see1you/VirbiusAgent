package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class PromptAuditJsonParser {

    private static final Pattern SAFETY_PATTERN =
            Pattern.compile("Safety:\\s*(Safe|Unsafe|Controversial)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORIES_PATTERN =
            Pattern.compile("Categories?\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;

    public PromptAuditJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public PromptAuditResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PromptAuditResult.miss();
        }
        String trimmed = raw.trim();

        // Try JSON format first (generic models that follow the system prompt)
        PromptAuditResult json = tryParseJson(trimmed);
        if (json != null) {
            return json;
        }

        // Fallback to Qwen3Guard native format (Safety:/Categories:)
        return tryParseQwen3Guard(trimmed);
    }

    private PromptAuditResult tryParseJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        String slice = closeTruncatedJson(text.substring(start));
        int end = slice.lastIndexOf('}');
        if (end <= 0) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(slice.substring(0, end + 1));
            if (!node.has("hit_rule")) {
                return null;
            }
            boolean hit = node.path("hit_rule").asBoolean(false);
            String triggeredId = node.path("triggered_id").asText(null);
            if (triggeredId != null
                    && (triggeredId.isBlank()
                            || "null".equalsIgnoreCase(triggeredId)
                            || "none".equalsIgnoreCase(triggeredId))) {
                triggeredId = null;
            }
            String reason = node.path("reason").asText(null);
            // VirbiusGuard (V13) format carries the safety category in triggered_id and omits
            // reason. Normalize so downstream consumers reading reason still get the category.
            if ((reason == null || reason.isBlank()) && triggeredId != null) {
                reason = triggeredId;
            }
            return new PromptAuditResult(hit, triggeredId, reason);
        } catch (Exception e) {
            return null;
        }
    }

    /** Q4 GGUF often EOS before the closing quote/brace; close enough to parse. */
    static String closeTruncatedJson(String json) {
        StringBuilder sb = new StringBuilder(json.stripTrailing());
        int quotes = 0;
        boolean escape = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                quotes++;
            }
        }
        if (quotes % 2 == 1) {
            sb.append('"');
        }
        int depth = 0;
        escape = false;
        boolean inString = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        while (depth > 0) {
            sb.append('}');
            depth--;
        }
        return sb.toString();
    }

    private static PromptAuditResult tryParseQwen3Guard(String text) {
        Matcher sm = SAFETY_PATTERN.matcher(text);
        if (!sm.find()) {
            return PromptAuditResult.miss();
        }
        String safetyLabel = sm.group(1);
        boolean hit = safetyLabel.equalsIgnoreCase("Unsafe")
                || safetyLabel.equalsIgnoreCase("Controversial");

        String category = null;
        Matcher cm = CATEGORIES_PATTERN.matcher(text);
        if (cm.find()) {
            String cats = cm.group(1).trim();
            for (String c : cats.split(",")) {
                String t = c.trim();
                if (!t.isEmpty() && !"None".equalsIgnoreCase(t)) {
                    category = t;
                    break;
                }
            }
        }
        return new PromptAuditResult(hit, "SYSTEM", category != null ? category : safetyLabel);
    }

    public record PromptAuditResult(boolean hitRule, String triggeredId, String reason) {
        static PromptAuditResult miss() {
            return new PromptAuditResult(false, null, null);
        }
    }
}
