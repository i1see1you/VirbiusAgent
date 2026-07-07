package io.virbius.control.service;

import io.virbius.control.domain.ConstitutionRule;
import io.virbius.control.domain.ConstitutionTemplate;
import io.virbius.control.repository.ConstitutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compiles active {@link ConstitutionRule} entries into scene-specific
 * {@link ConstitutionTemplate} prompt templates.
 *
 * <p>The compilation process:
 * <ol>
 *   <li>Fetch all active rules for the tenant</li>
 *   <li>Group rules by scene (rules with empty scene_filter apply to all scenes)</li>
 *   <li>For each scene, render the system_prefix by concatenating rules in priority order,
 *       grouped by category (prohibitions → tool_rules → boundaries → principles)</li>
 *   <li>Generate the dynamic_suffix with template variables</li>
 *   <li>Persist the compiled template</li>
 * </ol>
 *
 * <p>The output template is consumed by {@code PromptGateway} in virbius-core.
 */
@Component
public class ConstitutionCompiler {

    private static final Logger log = LoggerFactory.getLogger(ConstitutionCompiler.class);

    private final ConstitutionRepository repo;

    public ConstitutionCompiler(ConstitutionRepository repo) {
        this.repo = repo;
    }

    /**
     * Compile all active constitution rules into prompt templates for a tenant.
     *
     * @param tenantId           the tenant
     * @param constitutionVersion the version label for this compilation (e.g. "1.2")
     * @param scenes             the list of scene names to compile for; always includes wildcard "*"
     * @return the list of compiled templates
     */
    public List<ConstitutionTemplate> compile(String tenantId, String constitutionVersion, List<String> scenes) {
        List<ConstitutionRule> allRules = repo.listActiveRulesForScene(tenantId, null);

        // Collect all scenes: provided scenes + wildcard + scenes from rule filters
        Set<String> sceneSet = new TreeSet<>();
        sceneSet.add(ConstitutionTemplate.SCENE_WILDCARD);
        if (scenes != null) {
            sceneSet.addAll(scenes);
        }
        for (ConstitutionRule rule : allRules) {
            if (rule.sceneFilter() != null && !rule.sceneFilter().isEmpty()) {
                sceneSet.addAll(rule.sceneFilter());
            }
        }

        List<ConstitutionTemplate> compiled = new ArrayList<>();
        for (String scene : sceneSet) {
            ConstitutionTemplate tmpl = compileForScene(tenantId, constitutionVersion, scene, allRules);
            repo.saveTemplate(tmpl);
            compiled.add(tmpl);
        }

        log.info("compiled constitution v{} for tenant {}: {} scenes, {} total rules",
                constitutionVersion, tenantId, sceneSet.size(), allRules.size());
        return compiled;
    }

    /**
     * Compile a single scene template from the given rules.
     */
    ConstitutionTemplate compileForScene(
            String tenantId,
            String constitutionVersion,
            String scene,
            List<ConstitutionRule> allRules) {

        // Filter rules applicable to this scene
        List<ConstitutionRule> applicable = allRules.stream()
                .filter(r -> r.appliesToScene(scene))
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList();

        // Group by category
        Map<String, List<ConstitutionRule>> byCategory = applicable.stream()
                .collect(Collectors.groupingBy(ConstitutionRule::category, LinkedHashMap::new, Collectors.toList()));

        List<String> prohibitions = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_PROHIBITION));
        List<String> toolRules = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_TOOL_RULE));
        List<String> boundaries = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_BOUNDARY));
        List<String> principles = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_PRINCIPLE));

        String systemPrefix = renderSystemPrefix(constitutionVersion, scene, prohibitions, toolRules, boundaries, principles);
        String dynamicSuffix = renderDynamicSuffix();

        return new ConstitutionTemplate(
                null,
                tenantId,
                constitutionVersion,
                scene,
                systemPrefix,
                dynamicSuffix,
                prohibitions,
                toolRules,
                Instant.now());
    }

    private List<String> extractTexts(List<ConstitutionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
                .map(ConstitutionRule::ruleText)
                .toList();
    }

    /**
     * Render the system prefix — the text prepended to the system message.
     *
     * Format:
     * <pre>
     * ## Virbius Agent Constitution {version} (scene: {scene})
     *
     * ### 绝对禁止
     * 1. {prohibition_1}
     * 2. {prohibition_2}
     *
     * ### 工具使用规则
     * 1. {tool_rule_1}
     *
     * ### 边界约束
     * 1. {boundary_1}
     *
     * ### 运行原则
     * 1. {principle_1}
     * </pre>
     */
    private String renderSystemPrefix(
            String version,
            String scene,
            List<String> prohibitions,
            List<String> toolRules,
            List<String> boundaries,
            List<String> principles) {

        StringBuilder sb = new StringBuilder();
        sb.append("## Virbius Agent Constitution ").append(version);
        sb.append(" (scene: ").append(scene).append(")\n\n");

        if (!prohibitions.isEmpty()) {
            sb.append("### 绝对禁止\n");
            appendNumbered(sb, prohibitions);
            sb.append('\n');
        }
        if (!toolRules.isEmpty()) {
            sb.append("### 工具使用规则\n");
            appendNumbered(sb, toolRules);
            sb.append('\n');
        }
        if (!boundaries.isEmpty()) {
            sb.append("### 边界约束\n");
            appendNumbered(sb, boundaries);
            sb.append('\n');
        }
        if (!principles.isEmpty()) {
            sb.append("### 运行原则\n");
            appendNumbered(sb, principles);
        }
        return sb.toString().trim();
    }

    private void appendNumbered(StringBuilder sb, List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
    }

    /**
     * Render the dynamic suffix — appended to the system message with template variables.
     * These variables are filled at runtime by PromptGateway.
     */
    private String renderDynamicSuffix() {
        return """
                ## 当前会话上下文
                - 风险分: {{risk_score}}/100
                - 已调用工具: {{recent_tools}}
                - 场景: {{scene}}
                - License 权限: {{license_permissions}}""";
    }
}
