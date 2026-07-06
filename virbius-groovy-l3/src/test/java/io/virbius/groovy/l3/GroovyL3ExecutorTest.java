package io.virbius.groovy.l3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GroovyL3ExecutorTest {

    private static final GroovyL3Executor executor = new GroovyL3Executor(5000);

    @BeforeAll
    static void warmUp() {
        // Warm up Groovy compilation + class loading before any test to avoid CI cold-start timeout
        PolicyContext ctx = new PolicyContext("t", "s", "chat", "r", Map.of(), List.of());
        try {
            executor.precompile(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT);
            executor.executeDecide(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT, ctx);
        } catch (Exception ignored) {
            // warm-up failure is non-fatal; actual tests will catch real issues
        }
    }

    @Test
    void dryRunReturnsReview() throws Exception {
        PolicyContext ctx = context("dry_run", signalBlock());
        GroovyL3Decision d = executor.execute(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT, ctx);
        assertEquals("review", d.effectiveAction());
    }

    @Test
    void fullBlock() throws Exception {
        PolicyContext ctx = context("full", signalBlock());
        GroovyL3Decision d = executor.execute(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT, ctx);
        assertEquals("block", d.effectiveAction());
    }

    @Test
    void rejectsRuntimeExec() {
        assertThrows(
                GroovyL3ValidationException.class,
                () -> GroovyL3Validator.validate("def decide(ctx) { Runtime.getRuntime().exec('x') }"));
    }

    private static PolicyContext context(String enforceMode, L3SignalView signal) {
        L3RuleView l3 = new L3RuleView("cloud_groovy_l3", 1, enforceMode, 100, 100);
        return new PolicyContext(
                "default",
                "sess-1",
                "chat",
                "cloud_groovy_l3",
                Map.of("cloud_groovy_l3", l3),
                List.of(signal));
    }

    private static L3SignalView signalBlock() {
        return new L3SignalView("cloud_prompt_l1", 1, "cloud", 100, "block", "PROMPT_INJECTION");
    }
}
