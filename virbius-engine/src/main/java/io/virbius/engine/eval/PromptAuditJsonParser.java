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

        // 尝试 JSON 格式（通用模型 follow system prompt 时）
        PromptAuditResult json = tryParseJson(trimmed);
        if (json != null) {
            return json;
        }

        // 回退 Qwen3Guard 原生格式（Safety:/Categories:）
        return tryParseQwen3Guard(trimmed);
    }

    private PromptAuditResult tryParseJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            if (!node.has("hit_rule")) {
                return null;
            }
            boolean hit = node.path("hit_rule").asBoolean(false);
            String triggeredId = node.path("triggered_id").asText(null);
            if (triggeredId != null && (triggeredId.isBlank() || "null".equalsIgnoreCase(triggeredId))) {
                triggeredId = null;
            }
            String reason = node.path("reason").asText(null);
            return new PromptAuditResult(hit, triggeredId, reason);
        } catch (Exception e) {
            return null;
        }
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
