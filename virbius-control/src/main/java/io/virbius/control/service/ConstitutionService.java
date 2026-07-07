package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.ConstitutionRule;
import io.virbius.control.domain.ConstitutionTemplate;
import io.virbius.control.repository.ConstitutionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CRUD + compilation service for the enterprise AI Agent constitution.
 *
 * <p>The constitution is a set of rules that get compiled into prompt templates
 * and injected into LLM prompts by the edge-layer PromptGateway (§2.8).
 */
@Service
public class ConstitutionService {

    private static final Logger log = LoggerFactory.getLogger(ConstitutionService.class);

    private final ConstitutionRepository repo;
    private final ConstitutionCompiler compiler;

    public ConstitutionService(ConstitutionRepository repo, ConstitutionCompiler compiler) {
        this.repo = repo;
        this.compiler = compiler;
    }

    // ---- Rule CRUD ----

    public Map<String, Object> createRule(String tenantId, CreateConstitutionRuleRequest req) {
        validateCategory(req.category());
        String version = req.version() != null && !req.version().isBlank() ? req.version() : "1.0";
        if (repo.findRule(tenantId, req.ruleId(), version).isPresent()) {
            throw new BusinessException(409,
                    "Constitution rule already exists: " + req.ruleId() + " v" + version);
        }
        ConstitutionRule rule = new ConstitutionRule(
                null,
                tenantId,
                req.ruleId(),
                version,
                req.category(),
                req.priority() != null ? req.priority() : 50,
                req.sceneFilter() != null ? req.sceneFilter() : List.of(),
                req.ruleText(),
                ConstitutionRule.STATUS_ACTIVE,
                "admin",
                Instant.now(),
                Instant.now());
        repo.saveRule(rule);
        log.info("created constitution rule {} v{} for tenant {}", req.ruleId(), version, tenantId);
        return toRuleMap(rule);
    }

    public List<Map<String, Object>> listRules(String tenantId, String status) {
        return repo.listRules(tenantId, status).stream()
                .map(ConstitutionService::toRuleMap)
                .toList();
    }

    public Map<String, Object> getRule(String tenantId, String ruleId, String version) {
        ConstitutionRule rule = version != null && !version.isBlank()
                ? repo.findRule(tenantId, ruleId, version)
                        .orElseThrow(() -> new BusinessException(404, "rule not found: " + ruleId + " v" + version))
                : repo.findLatestRule(tenantId, ruleId)
                        .orElseThrow(() -> new BusinessException(404, "rule not found: " + ruleId));
        return toRuleMap(rule);
    }

    public void updateRuleStatus(String tenantId, String ruleId, String version, String status) {
        if (!ConstitutionRule.STATUS_ACTIVE.equals(status) && !ConstitutionRule.STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status: " + status);
        }
        repo.updateRuleStatus(tenantId, ruleId, version, status);
        log.info("updated constitution rule {} v{} status to {}", ruleId, version, status);
    }

    public void deleteRule(String tenantId, String ruleId, String version) {
        repo.deleteRule(tenantId, ruleId, version);
        log.info("deleted constitution rule {} v{} for tenant {}", ruleId, version, tenantId);
    }

    // ---- Compilation ----

    public List<Map<String, Object>> compile(String tenantId, String constitutionVersion, List<String> scenes) {
        List<ConstitutionTemplate> templates = compiler.compile(tenantId, constitutionVersion, scenes);
        return templates.stream()
                .map(ConstitutionService::toTemplateMap)
                .toList();
    }

    // ---- Template retrieval ----

    public Map<String, Object> getTemplate(String tenantId, String constitutionVersion, String scene) {
        ConstitutionTemplate tmpl = repo.findTemplate(tenantId, constitutionVersion, scene)
                .orElseThrow(() -> new BusinessException(404,
                        "template not found: version=" + constitutionVersion + " scene=" + scene));
        return toTemplateMap(tmpl);
    }

    public List<Map<String, Object>> listTemplates(String tenantId, String constitutionVersion) {
        if (constitutionVersion != null && !constitutionVersion.isBlank()) {
            return repo.listTemplatesByVersion(tenantId, constitutionVersion).stream()
                    .map(ConstitutionService::toTemplateMap)
                    .toList();
        }
        return repo.listTemplates(tenantId).stream()
                .map(ConstitutionService::toTemplateMap)
                .toList();
    }

    // ---- Helpers ----

    private void validateCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
        if (!ConstitutionRule.CATEGORY_PROHIBITION.equals(category)
                && !ConstitutionRule.CATEGORY_TOOL_RULE.equals(category)
                && !ConstitutionRule.CATEGORY_BOUNDARY.equals(category)
                && !ConstitutionRule.CATEGORY_PRINCIPLE.equals(category)) {
            throw new IllegalArgumentException("invalid category: " + category
                    + " (expected: prohibition, tool_rule, boundary, principle)");
        }
    }

    static Map<String, Object> toRuleMap(ConstitutionRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("rule_id", r.ruleId());
        m.put("version", r.version());
        m.put("category", r.category());
        m.put("priority", r.priority());
        m.put("scene_filter", r.sceneFilter());
        m.put("rule_text", r.ruleText());
        m.put("status", r.status());
        m.put("created_by", r.createdBy() != null ? r.createdBy() : "");
        m.put("created_at", r.createdAt() != null ? r.createdAt().toString() : "");
        m.put("updated_at", r.updatedAt() != null ? r.updatedAt().toString() : "");
        return m;
    }

    static Map<String, Object> toTemplateMap(ConstitutionTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id() != null ? t.id() : 0);
        m.put("constitution_version", t.constitutionVersion());
        m.put("scene", t.scene());
        m.put("system_prefix", t.systemPrefix());
        m.put("dynamic_suffix", t.dynamicSuffix());
        m.put("prohibitions", t.prohibitions());
        m.put("tool_rules", t.toolRules());
        m.put("compiled_at", t.compiledAt() != null ? t.compiledAt().toString() : "");
        return m;
    }

    // ---- Request DTOs ----

    public record CreateConstitutionRuleRequest(
            String ruleId,
            String version,
            String category,
            Integer priority,
            List<String> sceneFilter,
            String ruleText) {}
}
