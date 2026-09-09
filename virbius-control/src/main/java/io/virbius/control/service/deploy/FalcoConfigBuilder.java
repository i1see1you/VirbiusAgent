package io.virbius.control.service.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.RegistryRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FalcoConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(FalcoConfigBuilder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Falco rule priority levels, see https://falco.org/docs/rules/basic-elements/#priority.
     * Anything outside this whitelist is rejected by the falco engine at load time, which would
     * silently keep the whole ruleset on the previous revision.
     */
    private static final Set<String> VALID_PRIORITIES = Set.of(
            "EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFORMATIONAL", "DEBUG");
    private static final String DEFAULT_PRIORITY = "WARNING";

    private final RegistryRepository ruleRepo;

    public FalcoConfigBuilder(RegistryRepository ruleRepo) {
        this.ruleRepo = ruleRepo;
    }

    public String buildRulesYaml(String tenantId) {
        // Execution-plane filter, aligned with the other layer builders: draft/disabled rules
        // must never reach the device.
        List<RuleRevision> rules = ruleRepo.listCurrentRules(tenantId, "falco").stream()
                .filter(RolloutStateHelper::inExecutionPlane)
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("# Virbius Falco rules (auto-generated)\n\n");

        // ── Phase 1: emit falco_list entries first (lists must precede rules) ──
        for (RuleRevision rule : rules) {
            if (!"falco".equals(rule.layer())) continue;
            if (!"falco_list".equals(rule.runtime())) continue;
            JsonNode body = parseBody(rule);
            String items = renderListField(body, "items");
            sb.append("- list: ").append(rule.ruleId()).append("\n");
            sb.append("  items: [").append(items).append("]\n\n");
        }

        // ── Phase 2: emit falco rules ──
        int emitted = 0;
        int skipped = 0;
        for (RuleRevision rule : rules) {
            if (!"falco".equals(rule.layer())) continue;
            if ("falco_list".equals(rule.runtime())) continue;
            JsonNode body = parseBody(rule);
            String condition = textField(body, "condition");
            if (condition == null || condition.isBlank()) {
                // Defense in depth: never fall back to a match-all condition like `evt.num > 0`.
                // The API rejects empty conditions at upsert time; this guard covers rows that
                // bypass the API (direct DB writes, legacy data).
                log.error("skipping falco rule with empty condition tenant={} rule={}", tenantId, rule.ruleId());
                skipped++;
                continue;
            }
            String output = textField(body, "output");
            if (output == null || output.isBlank()) {
                output = "Falco rule triggered (rule=" + rule.ruleId() + ")";
            }
            String tags = renderListField(body, "tags");
            // Carry the rollout state inside the rule itself: falco alerts echo the rule's
            // tags, so the engine can tell dry_run alerts (observe-only, no scoring) apart
            // without needing a second state channel.
            String stateTag = "virbius_state:" + RolloutStateHelper.stateOf(rule);
            tags = tags.isBlank() ? stateTag : tags + ", " + stateTag;
            String priority = resolvePriority(tenantId, rule, body);

            sb.append("- rule: ").append(rule.ruleId()).append("\n");
            sb.append("  desc: ").append(safeDesc(rule)).append("\n");
            sb.append("  condition: ").append(singleLine(condition)).append("\n");
            sb.append("  output: ").append(singleLine(output)).append("\n");
            sb.append("  priority: ").append(priority).append("\n");
            sb.append("  tags: [").append(tags).append("]\n");
            sb.append("\n");
            emitted++;
        }
        log.info("built falco rules yaml tenant={} rules={} emitted={} skipped={}",
                tenantId, rules.size(), emitted, skipped);
        return sb.toString();
    }

    private String safeDesc(RuleRevision rule) {
        if (rule.scope() != null && rule.scope().containsKey("description")) {
            return String.valueOf(rule.scope().get("description"));
        }
        return "Virbius custom Falco rule " + rule.ruleId();
    }

    /** Parses the rule body as JSON. Returns null when the body is absent or not valid JSON. */
    private JsonNode parseBody(RuleRevision rule) {
        Object body = rule.body();
        if (body == null) return null;
        try {
            if (body instanceof String s) {
                if (s.isBlank()) return null;
                return JSON.readTree(s);
            }
            return JSON.valueToTree(body);
        } catch (Exception e) {
            log.error("failed to parse falco rule body as JSON rule={}: {}", rule.ruleId(), e.getMessage());
            return null;
        }
    }

    private static String textField(JsonNode body, String field) {
        if (body == null) return null;
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }

    /**
     * Renders a list-ish field ({@code items} / {@code tags}) as the inner text of a YAML
     * flow sequence. Accepts either a JSON array (strings are double-quoted and escaped) or a
     * plain comma-separated string (used as-is, preserving historical behavior).
     */
    private static String renderListField(JsonNode body, String field) {
        if (body == null) return "";
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return "";
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (sb.length() > 0) sb.append(", ");
                if (item.isTextual()) {
                    sb.append('"').append(escapeYamlDoubleQuoted(item.asText())).append('"');
                } else {
                    sb.append(item.asText());
                }
            }
            return sb.toString();
        }
        return node.asText().trim();
    }

    /**
     * Resolves the falco priority from the rule body's {@code priority} field, validated against
     * the falco priority whitelist. Invalid or missing values fall back to WARNING. The internal
     * {@code reason_code} is deliberately NOT used here: it is a business code (e.g.
     * SENSITIVE_FILE_WRITE), not a falco severity.
     */
    private String resolvePriority(String tenantId, RuleRevision rule, JsonNode body) {
        String raw = textField(body, "priority");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PRIORITY;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (VALID_PRIORITIES.contains(normalized)) {
            return normalized;
        }
        log.warn("invalid falco priority '{}' in rule body, falling back to {} tenant={} rule={}",
                raw, DEFAULT_PRIORITY, tenantId, rule.ruleId());
        return DEFAULT_PRIORITY;
    }

    private static String singleLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String escapeYamlDoubleQuoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
