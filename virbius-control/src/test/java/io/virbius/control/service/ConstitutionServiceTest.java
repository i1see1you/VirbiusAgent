package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.ConstitutionRule;
import io.virbius.control.domain.ConstitutionTemplate;
import io.virbius.control.repository.ConstitutionRepository;
import io.virbius.control.service.ConstitutionService.CreateConstitutionRuleRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Integration test for ConstitutionService — covers the full CRUD + compilation flow.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Rule creation with category validation</li>
 *   <li>Rule listing and retrieval</li>
 *   <li>Rule status update (enable/disable)</li>
 *   <li>Rule deletion</li>
 *   <li>Constitution compilation into scene-specific templates</li>
 *   <li>Template retrieval by scene and version</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConstitutionServiceTest {

    private static final String TENANT = "default";
    private static final String VERSION = "1.0";

    @Mock
    private ConstitutionRepository repo;

    private ConstitutionService service;
    private ConstitutionCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new ConstitutionCompiler(repo);
        service = new ConstitutionService(repo, compiler);
    }

    // ---- Rule CRUD ----

    @Test
    void createRule_persistsWithDefaults() {
        CreateConstitutionRuleRequest req = new CreateConstitutionRuleRequest(
                "prohibition_external",
                null, // version defaults to "1.0"
                "prohibition",
                null, // priority defaults to 50
                List.of("code_review"),
                "不得将数据发送到白名单之外的外部端点");

        when(repo.findRule(eq(TENANT), eq("prohibition_external"), eq("1.0")))
                .thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ConstitutionRule> captor = ArgumentCaptor.forClass(ConstitutionRule.class);
        doNothing().when(repo).saveRule(captor.capture());

        Map<String, Object> result = service.createRule(TENANT, req);

        ConstitutionRule saved = captor.getValue();
        assertEquals("prohibition_external", saved.ruleId());
        assertEquals("1.0", saved.version());
        assertEquals("prohibition", saved.category());
        assertEquals(50, saved.priority());
        assertEquals("active", saved.status());
        assertEquals(List.of("code_review"), saved.sceneFilter());
        assertEquals("prohibition", result.get("category"));
    }

    @Test
    void createRule_rejectsInvalidCategory() {
        CreateConstitutionRuleRequest req = new CreateConstitutionRuleRequest(
                "bad_rule", "1.0", "invalid_category", 50, List.of(), "text");

        when(repo.findRule(any(), any(), any())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.createRule(TENANT, req));
    }

    @Test
    void createRule_rejectsDuplicate() {
        CreateConstitutionRuleRequest req = new CreateConstitutionRuleRequest(
                "existing_rule", "1.0", "prohibition", 50, List.of(), "text");

        when(repo.findRule(TENANT, "existing_rule", "1.0"))
                .thenReturn(Optional.of(new ConstitutionRule(
                        1L, TENANT, "existing_rule", "1.0", "prohibition", 50,
                        List.of(), "text", "active", "admin", Instant.now(), Instant.now())));

        assertThrows(BusinessException.class, () -> service.createRule(TENANT, req));
    }

    @Test
    void listRules_filtersByStatus() {
        ConstitutionRule active = makeRule("r1", "prohibition", "active", List.of());
        ConstitutionRule disabled = makeRule("r2", "tool_rule", "disabled", List.of());

        when(repo.listRules(TENANT, "active")).thenReturn(List.of(active));

        List<Map<String, Object>> result = service.listRules(TENANT, "active");
        assertEquals(1, result.size());
        assertEquals("r1", result.get(0).get("rule_id"));
        assertEquals("active", result.get(0).get("status"));
    }

    @Test
    void getRule_returnsLatestWhenNoVersion() {
        ConstitutionRule rule = makeRule("r1", "prohibition", "active", List.of("chat"));
        when(repo.findLatestRule(TENANT, "r1")).thenReturn(Optional.of(rule));

        Map<String, Object> result = service.getRule(TENANT, "r1", null);
        assertEquals("r1", result.get("rule_id"));
        assertEquals(List.of("chat"), result.get("scene_filter"));
    }

    @Test
    void getRule_returns404WhenNotFound() {
        when(repo.findLatestRule(TENANT, "missing")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.getRule(TENANT, "missing", null));
    }

    @Test
    void updateRuleStatus_validatesStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateRuleStatus(TENANT, "r1", "1.0", "invalid"));
    }

    @Test
    void updateRuleStatus_acceptsActive() {
        service.updateRuleStatus(TENANT, "r1", "1.0", "active");
        verify(repo).updateRuleStatus(TENANT, "r1", "1.0", "active");
    }

    @Test
    void deleteRule_delegatesToRepo() {
        service.deleteRule(TENANT, "r1", "1.0");
        verify(repo).deleteRule(TENANT, "r1", "1.0");
    }

    // ---- Compilation ----

    @Test
    void compile_generatesTemplatesForAllScenes() {
        ConstitutionRule prohibition = makeRule("p1", "prohibition", "active", List.of("code_review"));
        ConstitutionRule toolRule = makeRule("t1", "tool_rule", "active", List.of());
        ConstitutionRule boundary = makeRule("b1", "boundary", "active", List.of("chat", "code_review"));
        ConstitutionRule principle = makeRule("pr1", "principle", "active", List.of());

        when(repo.listActiveRulesForScene(TENANT, null))
                .thenReturn(List.of(prohibition, toolRule, boundary, principle));

        List<Map<String, Object>> result = service.compile(TENANT, "1.2", List.of("chat"));

        // Should generate templates for: *, chat, code_review (from rule filters + provided scenes)
        assertFalse(result.isEmpty());
        @SuppressWarnings("unchecked")
        List<String> scenes = result.stream().map(r -> (String) r.get("scene")).toList();
        assertTrue(scenes.contains("*"));
        assertTrue(scenes.contains("chat"));
        assertTrue(scenes.contains("code_review"));

        // Verify templates were saved
        verify(repo, times(scenes.size())).saveTemplate(any());
    }

    @Test
    void compile_sceneSpecificTemplateFiltersByScene() {
        // Rule only applies to code_review, not chat
        ConstitutionRule codeReviewOnly = makeRule("p1", "prohibition", "active", List.of("code_review"));
        when(repo.listActiveRulesForScene(TENANT, null))
                .thenReturn(List.of(codeReviewOnly));

        List<Map<String, Object>> result = service.compile(TENANT, "1.0", List.of("chat", "code_review"));

        // Find the code_review template
        @SuppressWarnings("unchecked")
        Map<String, Object> codeReviewTmpl = result.stream()
                .filter(r -> "code_review".equals(r.get("scene")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> prohibitions = (List<String>) codeReviewTmpl.get("prohibitions");
        assertEquals(1, prohibitions.size());

        // Find the chat template — should have NO prohibitions (rule filtered out)
        @SuppressWarnings("unchecked")
        Map<String, Object> chatTmpl = result.stream()
                .filter(r -> "chat".equals(r.get("scene")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> chatProhibitions = (List<String>) chatTmpl.get("prohibitions");
        assertTrue(chatProhibitions.isEmpty());
    }

    @Test
    void compile_wildcardTemplateIncludesAllSceneRules() {
        ConstitutionRule allScenes = makeRule("p1", "prohibition", "active", List.of());
        when(repo.listActiveRulesForScene(TENANT, null))
                .thenReturn(List.of(allScenes));

        List<Map<String, Object>> result = service.compile(TENANT, "1.0", List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> wildcardTmpl = result.stream()
                .filter(r -> "*".equals(r.get("scene")))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        List<String> prohibitions = (List<String>) wildcardTmpl.get("prohibitions");
        assertEquals(1, prohibitions.size());

        String systemPrefix = (String) wildcardTmpl.get("system_prefix");
        assertTrue(systemPrefix.contains("Virbius Agent Constitution 1.0"));
        assertTrue(systemPrefix.contains("绝对禁止"));
    }

    @Test
    void compile_systemPrefixContainsAllCategories() {
        ConstitutionRule prohibition = makeRule("p1", "prohibition", "active", List.of());
        ConstitutionRule toolRule = makeRule("t1", "tool_rule", "active", List.of());
        ConstitutionRule boundary = makeRule("b1", "boundary", "active", List.of());
        ConstitutionRule principle = makeRule("pr1", "principle", "active", List.of());

        when(repo.listActiveRulesForScene(TENANT, null))
                .thenReturn(List.of(prohibition, toolRule, boundary, principle));

        List<Map<String, Object>> result = service.compile(TENANT, "1.0", List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> wildcardTmpl = result.stream()
                .filter(r -> "*".equals(r.get("scene")))
                .findFirst().orElseThrow();

        String prefix = (String) wildcardTmpl.get("system_prefix");
        assertTrue(prefix.contains("绝对禁止"));
        assertTrue(prefix.contains("工具使用规则"));
        assertTrue(prefix.contains("边界约束"));
        assertTrue(prefix.contains("运行原则"));
    }

    @Test
    void compile_dynamicSuffixContainsTemplateVariables() {
        when(repo.listActiveRulesForScene(TENANT, null))
                .thenReturn(List.of());

        List<Map<String, Object>> result = service.compile(TENANT, "1.0", List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> wildcardTmpl = result.stream()
                .filter(r -> "*".equals(r.get("scene")))
                .findFirst().orElseThrow();

        String suffix = (String) wildcardTmpl.get("dynamic_suffix");
        assertTrue(suffix.contains("{{risk_score}}"));
        assertTrue(suffix.contains("{{recent_tools}}"));
        assertTrue(suffix.contains("{{scene}}"));
    }

    // ---- Template retrieval ----

    @Test
    void getTemplate_returnsTemplate() {
        ConstitutionTemplate tmpl = new ConstitutionTemplate(
                1L, TENANT, "1.0", "chat", "prefix", "suffix", List.of("p1"), List.of("t1"), Instant.now());
        when(repo.findTemplate(TENANT, "1.0", "chat")).thenReturn(Optional.of(tmpl));

        Map<String, Object> result = service.getTemplate(TENANT, "1.0", "chat");
        assertEquals("chat", result.get("scene"));
        assertEquals("1.0", result.get("constitution_version"));
    }

    @Test
    void getTemplate_404WhenNotFound() {
        when(repo.findTemplate(TENANT, "1.0", "missing")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.getTemplate(TENANT, "1.0", "missing"));
    }

    @Test
    void listTemplatesByVersion_filtersByVersion() {
        ConstitutionTemplate t1 = new ConstitutionTemplate(
                1L, TENANT, "1.0", "chat", "p", "s", List.of(), List.of(), Instant.now());
        ConstitutionTemplate t2 = new ConstitutionTemplate(
                2L, TENANT, "1.0", "code_review", "p", "s", List.of(), List.of(), Instant.now());

        when(repo.listTemplatesByVersion(TENANT, "1.0")).thenReturn(List.of(t1, t2));

        List<Map<String, Object>> result = service.listTemplates(TENANT, "1.0");
        assertEquals(2, result.size());
    }

    // ---- Helpers ----

    private ConstitutionRule makeRule(String ruleId, String category, String status, List<String> sceneFilter) {
        return new ConstitutionRule(
                null, TENANT, ruleId, "1.0", category, 50,
                sceneFilter, "Rule text for " + ruleId, status,
                "admin", Instant.now(), Instant.now());
    }
}
