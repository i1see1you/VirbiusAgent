package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.RegistryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The falco YAML must only contain execution-plane rules (dry_run/canary/full), aligned with
 * the other layer builders. Draft and disabled rules must never reach the device.
 */
class FalcoConfigBuilderTest {

    private final RegistryRepository repo = mock(RegistryRepository.class);
    private final FalcoConfigBuilder builder = new FalcoConfigBuilder(repo);

    private static RuleRevision rule(String id, String runtime, String state) {
        return new RuleRevision(
                "t1", id, 1, "b1", "falco", runtime,
                "WARNING", 0, "enforce", Map.of(),
                "{\"condition\":\"evt.num > 0\",\"output\":\"x\"}",
                state, null, null, null, null, false, null);
    }

    private static RuleRevision list(String id, String state) {
        return new RuleRevision(
                "t1", id, 1, "b1", "falco", "falco_list",
                "WARNING", 0, "enforce", Map.of(),
                "{\"items\":\"a, b\"}",
                state, null, null, null, null, false, null);
    }

    @Test
    void executionPlaneRulesAreEmitted() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                rule("r_dry", "falco", "dry_run"),
                rule("r_canary", "falco", "canary"),
                rule("r_full", "falco", "full")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("- rule: r_dry"), yaml);
        assertTrue(yaml.contains("- rule: r_canary"), yaml);
        assertTrue(yaml.contains("- rule: r_full"), yaml);
    }

    @Test
    void draftAndDisabledRulesAreExcluded() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                rule("r_draft", "falco", "draft"),
                rule("r_disabled", "falco", "disabled"),
                rule("r_full", "falco", "full")));
        String yaml = builder.buildRulesYaml("t1");
        assertFalse(yaml.contains("r_draft"), yaml);
        assertFalse(yaml.contains("r_disabled"), yaml);
        assertTrue(yaml.contains("- rule: r_full"), yaml);
    }

    @Test
    void listsFollowTheSameExecutionPlaneFilter() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                list("l_draft", "draft"),
                list("l_full", "full"),
                rule("r_full", "falco", "full")));
        String yaml = builder.buildRulesYaml("t1");
        assertFalse(yaml.contains("- list: l_draft"), yaml);
        assertTrue(yaml.contains("- list: l_full"), yaml);
    }

    @Test
    void nullStateIsTreatedAsDraftAndExcluded() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                rule("r_null", "falco", null),
                rule("r_full", "falco", "full")));
        String yaml = builder.buildRulesYaml("t1");
        assertFalse(yaml.contains("r_null"), yaml);
        assertTrue(yaml.contains("- rule: r_full"), yaml);
    }

    // ── Regression tests for the JSON parsing / priority / condition fixes ──

    private static RuleRevision ruleWithBody(String id, String reasonCode, String body) {
        return new RuleRevision(
                "t1", id, 1, "b1", "falco", "falco",
                reasonCode, 0, "enforce", Map.of(),
                body, "full", null, null, null, null, false, null);
    }

    @Test
    void escapedQuoteConditionSurvivesIntact() {
        // Old hand-written parser truncated at the first escaped quote.
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_quote", "WARNING",
                        "{\"condition\":\"evt.type=execve and not proc.name startswith \\\"falco\\\"\",\"output\":\"x\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("condition: evt.type=execve and not proc.name startswith \"falco\""), yaml);
    }

    @Test
    void reasonCodeIsNotUsedAsPriority() {
        // reason_code is a business code, not a falco severity; must fall back to WARNING.
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_prio", "SENSITIVE_FILE_WRITE",
                        "{\"condition\":\"evt.type=open\",\"output\":\"x\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("priority: WARNING"), yaml);
        assertFalse(yaml.contains("SENSITIVE_FILE_WRITE"), yaml);
    }

    @Test
    void bodyPriorityIsUsedWhenValid() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_prio", "WHATEVER",
                        "{\"condition\":\"evt.type=open\",\"output\":\"x\",\"priority\":\"critical\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("priority: CRITICAL"), yaml);
    }

    @Test
    void invalidBodyPriorityFallsBackToWarning() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_prio", "WARNING",
                        "{\"condition\":\"evt.type=open\",\"output\":\"x\",\"priority\":\"SUPER_URGENT\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("priority: WARNING"), yaml);
    }

    @Test
    void emptyConditionRuleIsSkipped() {
        // Never fall back to a match-all condition.
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_empty", "WARNING", "{\"condition\":\"  \",\"output\":\"x\"}"),
                ruleWithBody("r_ok", "WARNING", "{\"condition\":\"evt.type=open\",\"output\":\"x\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertFalse(yaml.contains("r_empty"), yaml);
        assertFalse(yaml.contains("evt.num > 0"), yaml);
        assertTrue(yaml.contains("- rule: r_ok"), yaml);
    }

    @Test
    void malformedJsonBodyIsSkipped() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_bad", "WARNING", "not json at all"),
                ruleWithBody("r_ok", "WARNING", "{\"condition\":\"evt.type=open\",\"output\":\"x\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertFalse(yaml.contains("r_bad"), yaml);
        assertTrue(yaml.contains("- rule: r_ok"), yaml);
    }

    @Test
    void arrayTagsAndItemsAreRenderedQuoted() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                new RuleRevision(
                        "t1", "l_full", 1, "b1", "falco", "falco_list",
                        "WARNING", 0, "enforce", Map.of(),
                        "{\"items\":[\"/etc/shadow\",\"/etc/passwd\"]}",
                        "full", null, null, null, null, false, null),
                ruleWithBody("r_tags", "WARNING",
                        "{\"condition\":\"evt.type=open\",\"output\":\"x\",\"tags\":[\"agent\",\"process\"]}")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("items: [\"/etc/shadow\", \"/etc/passwd\"]"), yaml);
        assertTrue(yaml.contains("tags: [\"agent\", \"process\", virbius_state:full]"), yaml);
    }

    @Test
    void stringTagsAreKeptAsIs() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                ruleWithBody("r_tags", "WARNING",
                        "{\"condition\":\"evt.type=open\",\"output\":\"x\",\"tags\":\"e2e,docker\"}")));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("tags: [e2e,docker, virbius_state:full]"), yaml);
    }

    @Test
    void rolloutStateTagReflectsRuleState() {
        when(repo.listCurrentRules("t1", "falco")).thenReturn(List.of(
                new RuleRevision(
                        "t1", "r_dry", 1, "b1", "falco", "falco",
                        "WARNING", 0, "enforce", Map.of(),
                        "{\"condition\":\"evt.type=open\",\"output\":\"x\"}",
                        "dry_run", null, null, null, null, false, null)));
        String yaml = builder.buildRulesYaml("t1");
        assertTrue(yaml.contains("tags: [virbius_state:dry_run]"), yaml);
    }
}
