package io.virbius.control.service.deploy;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.RegistryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FalcoConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(FalcoConfigBuilder.class);

    private final RegistryRepository ruleRepo;

    public FalcoConfigBuilder(RegistryRepository ruleRepo) {
        this.ruleRepo = ruleRepo;
    }

    public String buildRulesYaml(String tenantId) {
        List<RuleRevision> rules = ruleRepo.listCurrentRules(tenantId, "falco");
        StringBuilder sb = new StringBuilder();
        sb.append("# Virbius Falco rules (auto-generated)\n\n");
        sb.append("- list: virbius_falco_tools\n");
        sb.append("  items: [curl, wget, scp, sqlite3, mysql, bash, python3]\n\n");

        for (RuleRevision rule : rules) {
            if (!"falco".equals(rule.layer())) continue;
            String body = rule.body() instanceof String s ? s : String.valueOf(rule.body());
            String condition = extractField(body, "condition");
            if (condition == null || condition.isBlank()) {
                condition = "evt.num > 0";
            }
            String output = extractField(body, "output");
            if (output == null || output.isBlank()) {
                output = "Falco rule triggered (rule=" + rule.ruleId() + ")";
            }
            String tags = extractField(body, "tags");
            String priority = rule.reasonCode() != null && !rule.reasonCode().isBlank()
                    ? rule.reasonCode() : "WARNING";

            sb.append("- rule: ").append(rule.ruleId()).append("\n");
            sb.append("  desc: ").append(safeDesc(rule)).append("\n");
            sb.append("  condition: ").append(condition).append("\n");
            sb.append("  output: ").append(output).append("\n");
            sb.append("  priority: ").append(priority).append("\n");
            if (tags != null && !tags.isBlank()) {
                sb.append("  tags: [").append(tags).append("]\n");
            }
            sb.append("\n");
        }
        log.info("built falco rules yaml tenant={} rules={}", tenantId, rules.size());
        return sb.toString();
    }

    private String safeDesc(RuleRevision rule) {
        if (rule.scope() != null && rule.scope().containsKey("description")) {
            return String.valueOf(rule.scope().get("description"));
        }
        return "Virbius custom Falco rule " + rule.ruleId();
    }

    private String extractField(String body, String field) {
        if (body == null || body.isBlank()) return null;
        String marker = "\"" + field + "\":";
        int idx = body.indexOf(marker);
        if (idx < 0) {
            marker = "\"" + field + "\" : ";
            idx = body.indexOf(marker);
        }
        if (idx < 0) return null;
        int start = idx + marker.length();
        while (start < body.length() && body.charAt(start) == ' ') start++;
        if (start >= body.length()) return null;
        if (body.charAt(start) == '"') {
            int end = body.indexOf('"', start + 1);
            if (end > start) return body.substring(start + 1, end);
        } else {
            int end = start;
            while (end < body.length() && body.charAt(end) != ',' && body.charAt(end) != '}') end++;
            return body.substring(start, end).trim();
        }
        return null;
    }
}
