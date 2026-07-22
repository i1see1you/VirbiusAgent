package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import io.virbius.engine.cache.RuleCache;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolicyDeciderTest {

    @Mock
    private RuleCache cache;

    private PolicyDecider decider;

    @BeforeEach
    void setUp() {
        decider = new PolicyDecider(cache);
    }

    @Test
    void decideAllowFromIntentAction() {
        List<SignalDto> signals = List.of(
                new SignalDto("Rule_1", 1, "cloud", "cloud", 0, "ALLOW", "allow", "dry_run", null, null));

        EngineDecisionDto decision = decider.decide("default", "sess-1", signals, null);

        assertEquals("allow", decision.effectiveAction());
        assertEquals(0, decision.maxRiskScore());
    }

    @Test
    void decideDenyFromIntentAction() {
        List<SignalDto> signals = List.of(
                new SignalDto("Rule_2", 2, "cloud", "cloud", 80, "DENY", "deny", "full", null, null));

        EngineDecisionDto decision = decider.decide("default", "sess-1", signals, null);

        assertEquals("block", decision.effectiveAction());
        assertEquals(80, decision.maxRiskScore());
    }

    @Test
    void decideReturnsAllowWhenFirstSignalIsAllow() {
        List<SignalDto> signals = List.of(
                new SignalDto("Rule_Allow", 1, "cloud", "cloud", 0, "SAFE", "allow", "dry_run", null, null),
                new SignalDto("Rule_Deny", 2, "cloud", "cloud", 80, "DANGER", "deny", "full", null, null));

        EngineDecisionDto decision = decider.decide("default", "sess-1", signals, null);

        // First allow intent short-circuits to ALLOW
        assertEquals("allow", decision.effectiveAction());
    }

    @Test
    void decideEmptySignals() {
        List<SignalDto> signals = List.of();

        EngineDecisionDto decision = decider.decide("default", "sess-1", signals, null);

        assertNotNull(decision);
    }

    @Test
    void decideFillsNullIntentFromCache() {
        List<SignalDto> signals = List.of(
                new SignalDto("Rule_3", 3, "cloud", "cloud",
                        60, "TOOL_HIGH", null, "full", null, null));

        io.virbius.engine.cache.RuleEntry entry = new io.virbius.engine.cache.RuleEntry(
                "default", "Rule_3", 3, "cloud", "groovy",
                "TOOL_HIGH", 60, "deny", "full", 0, "full",
                "body() => true", Map.of("bind_scope", "global"), false, null);
        when(cache.get("default", "Rule_3")).thenReturn(entry);

        EngineDecisionDto decision = decider.decide("default", "sess-1", signals, null);

        assertEquals("block", decision.effectiveAction());
    }

    @Test
    void decideFillsNullEnforceFromCache() {
        List<SignalDto> signals = List.of(
                new SignalDto("Rule_4", 1, "cloud", "cloud",
                        50, "TOOL_MED", null, null, null, null));

        io.virbius.engine.cache.RuleEntry entry = new io.virbius.engine.cache.RuleEntry(
                "default", "Rule_4", 1, "cloud", "groovy",
                "TOOL_MED", 50, "warn", "full", 0, "full",
                "body() => true", Map.of("bind_scope", "global"), false, null);
        when(cache.get("default", "Rule_4")).thenReturn(entry);

        EngineDecisionDto decision = decider.decide("default", "sess-1", signals, null);

        assertNotNull(decision);
    }
}
