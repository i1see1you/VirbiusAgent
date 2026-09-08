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
}
