package io.virbius.control.service;

import io.virbius.control.domain.ConstitutionRule;
import io.virbius.control.domain.ConstitutionTemplate;
import io.virbius.control.repository.ConstitutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compiles active {@link ConstitutionRule} entries into a
 * {@link ConstitutionTemplate} prompt template.
 *
 * <p>The compilation process:
 * <ol>
 *   <li>Fetch all active rules for the tenant</li>
 *   <li>Render the system_prefix by concatenating rules in priority order,
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
     * Compile all active constitution rules into a prompt template for a tenant.
     *
     * @param tenantId           the tenant
     * @param constitutionVersion the version label for this compilation (e.g. "1.2")
     * @return the compiled template
     */
    public ConstitutionTemplate compile(String tenantId, String constitutionVersion) {
        List<ConstitutionRule> allRules = repo.listActiveRules(tenantId);

        ConstitutionTemplate tmpl = compileTemplate(tenantId, constitutionVersion, allRules);
        repo.saveTemplate(tmpl);

        log.info("compiled constitution v{} for tenant {}: {} total rules",
                constitutionVersion, tenantId, allRules.size());
        return tmpl;
    }

    /**
     * Compile a single template from the given rules.
     */
    ConstitutionTemplate compileTemplate(
            String tenantId,
            String constitutionVersion,
            List<ConstitutionRule> allRules) {

        List<ConstitutionRule> applicable = allRules.stream()
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList();

        // Group by category
        Map<String, List<ConstitutionRule>> byCategory = applicable.stream()
                .collect(Collectors.groupingBy(ConstitutionRule::category, LinkedHashMap::new, Collectors.toList()));

        List<String> prohibitions = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_PROHIBITION));
        List<String> toolRules = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_TOOL_RULE));
        List<String> boundaries = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_BOUNDARY));
        List<String> principles = extractTexts(byCategory.get(ConstitutionRule.CATEGORY_PRINCIPLE));

        String systemPrefix = renderSystemPrefix(constitutionVersion, prohibitions, toolRules, boundaries, principles);
        String dynamicSuffix = renderDynamicSuffix();

        return new ConstitutionTemplate(
                null,
                tenantId,
                constitutionVersion,
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
     */
    private String renderSystemPrefix(
            String version,
            List<String> prohibitions,
            List<String> toolRules,
            List<String> boundaries,
            List<String> principles) {

        StringBuilder sb = new StringBuilder();
        sb.append("## Virbius Agent Constitution ").append(version).append("\n\n");

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
     */
    private String renderDynamicSuffix() {
        return """
                ## 当前会话上下文
                - 风险分: {{risk_score}}/100
                - 已调用工具: {{recent_tools}}
                - License 权限: {{license_permissions}}""";
    }
}
