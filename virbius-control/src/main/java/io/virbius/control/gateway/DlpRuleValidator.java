package io.virbius.control.gateway;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.enums.IntentAction;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validates edge-only {@code dlp-dsl} rules. */
public final class DlpRuleValidator {

    private static final Set<String> ENTITY_TYPES =
            Set.of("idcard_cn", "phone_cn", "email", "bank_card_cn", "custom_regex");

    private static final int MAX_CUSTOM_PATTERN_LEN = 512;

    private DlpRuleValidator() {}

    public static void validateUpsert(UpsertRuleRequest req) {
        if (!"dlp-dsl".equals(req.runtime())) {
            return;
        }
        if (!"edge".equalsIgnoreCase(req.layer())) {
            throw new IllegalArgumentException("dlp-dsl rules must use layer=edge (rule " + req.ruleId() + ")");
        }
        if (req.intentAction() != null
                && !IntentAction.ALLOW.value().equalsIgnoreCase(req.intentAction().trim())) {
            throw new IllegalArgumentException(
                    "dlp-dsl intent_action must be allow (rule " + req.ruleId() + ")");
        }
        Map<String, Object> body = asMap(req.body());
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("dlp-dsl body required (rule " + req.ruleId() + ")");
        }
        String entityType = str(body.get("entity_type"));
        if (entityType.isEmpty() || !ENTITY_TYPES.contains(entityType)) {
            throw new IllegalArgumentException("dlp-dsl body.entity_type must be one of "
                    + ENTITY_TYPES
                    + " (rule "
                    + req.ruleId()
                    + ")");
        }
        if ("custom_regex".equals(entityType)) {
            String pattern = str(body.get("pattern"));
            if (pattern.isEmpty()) {
                throw new IllegalArgumentException(
                        "dlp-dsl custom_regex requires body.pattern (rule " + req.ruleId() + ")");
            }
            if (pattern.length() > MAX_CUSTOM_PATTERN_LEN) {
                throw new IllegalArgumentException("dlp-dsl pattern too long (rule " + req.ruleId() + ")");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(
                        "invalid dlp-dsl pattern: " + e.getMessage() + " (rule " + req.ruleId() + ")");
            }
        }
    }

    public static boolean isDlpRuntime(String runtime) {
        return "dlp-dsl".equals(runtime);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object body) {
        if (body instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
