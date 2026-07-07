package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.request.ValidateScriptRequest;
import io.virbius.control.domain.enums.IntentAction;
import io.virbius.control.domain.enums.RuleLayer;
import io.virbius.control.domain.enums.RuleRuntime;
import io.virbius.control.domain.enums.RolloutState;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.script.ScriptRuleValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Integration test for Agent rule CRUD — verifies that the RuleService correctly
 * handles agent-layer rules with agent-groovy runtime.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Agent rule upsert with layer=agent, runtime=agent-groovy</li>
 *   <li>Agent rule listing filtered by layer=agent</li>
 *   <li>Agent rule retrieval by rule_id</li>
 *   <li>Agent-groovy script validation</li>
 *   <li>Revision history retrieval</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuleCrudTest {

    private static final String TENANT = "default";

    @Mock
    private RegistryRepository store;

    @Mock
    private ScriptRuleValidator scriptRuleValidator;

    private RuleService ruleService;

    @BeforeEach
    void setUp() {
        ruleService = new RuleService(store, scriptRuleValidator);
        // Mock bundle lookup for upsert
        when(store.getBundle(eq(TENANT), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void upsertAgentRule_createsWithCorrectLayerAndRuntime() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "agent_tool_chain_detect",
                "poc-default",
                RuleLayer.AGENT.value(),
                RuleRuntime.AGENT_GROOVY.value(),
                "AGENT_TOOL_CHAIN_SUSPICIOUS",
                80,
                "deny",
                Map.of("bind_scope", "global"),
                "def decide(ctx) { ctx.sessionHistory(3).size() > 2 }",
                null,
                null,
                false,
                null);

        when(store.getCurrentRule(TENANT, "agent_tool_chain_detect"))
                .thenReturn(Optional.empty());

        RuleRevision saved = makeAgentRuleRevision("agent_tool_chain_detect", 1);
        when(store.upsertRule(eq(TENANT), any())).thenReturn(saved);

        Map<String, Object> result = ruleService.upsertRule(TENANT, req);

        assertEquals("agent_tool_chain_detect", result.get("rule_id"));
        assertEquals(RuleLayer.AGENT.value(), result.get("layer"));
        assertEquals(RuleRuntime.AGENT_GROOVY.value(), result.get("runtime"));
    }

    @Test
    void upsertAgentRule_validatesAgentGroovyScript() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "agent_rule_1",
                "poc-default",
                RuleLayer.AGENT.value(),
                RuleRuntime.AGENT_GROOVY.value(),
                "REASON",
                100,
                "deny",
                Map.of(),
                "def decide(ctx) { return true }",
                null,
                null,
                false,
                null);

        when(store.getCurrentRule(TENANT, "agent_rule_1")).thenReturn(Optional.empty());
        when(store.upsertRule(eq(TENANT), any())).thenReturn(makeAgentRuleRevision("agent_rule_1", 1));

        ruleService.upsertRule(TENANT, req);

        // Verify that script validation was called with agent-groovy runtime
        verify(scriptRuleValidator).validateOrThrow(eq(TENANT), eq(RuleRuntime.AGENT_GROOVY.value()), anyString());
    }

    @Test
    void listAgentRules_filtersByAgentLayer() {
        RuleRevision agentRule = makeAgentRuleRevision("agent_r1", 1);
        RuleRevision cloudRule = makeRuleRevision("cloud_r1", "cloud", "groovy", 1);

        when(store.listCurrentRules(TENANT, RuleLayer.AGENT.value()))
                .thenReturn(List.of(agentRule));
        when(store.listCurrentRules(TENANT, "cloud"))
                .thenReturn(List.of(cloudRule));

        List<Map<String, Object>> agentRules = ruleService.listRules(TENANT, RuleLayer.AGENT.value());
        assertEquals(1, agentRules.size());
        assertEquals("agent_r1", agentRules.get(0).get("rule_id"));

        List<Map<String, Object>> cloudRules = ruleService.listRules(TENANT, "cloud");
        assertEquals(1, cloudRules.size());
        assertEquals("cloud_r1", cloudRules.get(0).get("rule_id"));
    }

    @Test
    void getAgentRule_returnsDetail() {
        RuleRevision rule = makeAgentRuleRevision("agent_detail", 3);
        when(store.getCurrentRule(TENANT, "agent_detail")).thenReturn(Optional.of(rule));

        Map<String, Object> result = ruleService.getRule(TENANT, "agent_detail");
        assertEquals("agent_detail", result.get("rule_id"));
        assertEquals(RuleLayer.AGENT.value(), result.get("layer"));
        assertEquals(3, result.get("rule_revision"));
    }

    @Test
    void listAgentRuleRevisions_returnsHistory() {
        RuleRevision r1 = makeAgentRuleRevision("agent_rev", 1);
        RuleRevision r2 = makeAgentRuleRevision("agent_rev", 2);
        RuleRevision r3 = makeAgentRuleRevision("agent_rev", 3);

        when(store.listRuleRevisions(TENANT, "agent_rev"))
                .thenReturn(List.of(r1, r2, r3));

        List<Map<String, Object>> revisions = ruleService.listRevisions(TENANT, "agent_rev");
        assertEquals(3, revisions.size());
    }

    @Test
    void getAgentRuleRevision_returnsSpecificRevision() {
        RuleRevision r2 = makeAgentRuleRevision("agent_rev", 2);
        when(store.getRuleRevision(TENANT, "agent_rev", 2)).thenReturn(Optional.of(r2));

        Map<String, Object> result = ruleService.getRevision(TENANT, "agent_rev", 2);
        assertEquals(2, result.get("rule_revision"));
    }

    @Test
    void validateAgentGroovyScript_callsValidator() {
        when(scriptRuleValidator.validate(eq(TENANT), eq(RuleRuntime.AGENT_GROOVY.value()), any()))
                .thenReturn(Map.of("valid", true, "errors", List.of(), "warnings", List.of(),
                        "referenced_lists", List.of(), "referenced_cumulatives", List.of()));

        ValidateScriptRequest req = new ValidateScriptRequest(
                RuleLayer.AGENT.value(),
                RuleRuntime.AGENT_GROOVY.value(),
                "def decide(ctx) { return true }");

        Map<String, Object> result = ruleService.validateScript(TENANT, req);

        assertTrue((boolean) result.get("valid"));
        verify(scriptRuleValidator).validate(TENANT, RuleRuntime.AGENT_GROOVY.value(), "def decide(ctx) { return true }");
    }

    // ---- Helpers ----

    private RuleRevision makeAgentRuleRevision(String ruleId, int revision) {
        return makeRuleRevision(ruleId, RuleLayer.AGENT.value(), RuleRuntime.AGENT_GROOVY.value(), revision);
    }

    private RuleRevision makeRuleRevision(String ruleId, String layer, String runtime, int revision) {
        return new RuleRevision(
                TENANT,
                ruleId,
                revision,
                "poc-default",
                layer,
                runtime,
                "AGENT_REASON",
                80,
                IntentAction.DENY.value(),
                Map.of("bind_scope", "global"),
                "def decide(ctx) { return true }",
                RolloutState.DRAFT.value(),
                null,
                Instant.now(),
                Instant.now(),
                null,
                false,
                null);
    }
}
