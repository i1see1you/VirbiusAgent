package io.virbius.policy.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptRuleParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesJudgeRuleWithDefaults() {
        PromptRuleSpec spec = PromptRuleParser.parse(
                """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": " 用户要求具体基金买卖建议时命中 ",
                  "examples": {"hit": [" 现在能买吗？ "]},
                  "threshold": 0.45
                }
                """,
                mapper);

        assertEquals(1, spec.version());
        assertEquals(PromptRuleSpec.Mode.JUDGE, spec.mode());
        assertEquals("用户要求具体基金买卖建议时命中", spec.criterion());
        assertEquals(List.of("现在能买吗？"), spec.hitExamples());
        assertEquals(0.45, spec.threshold());
        assertEquals(PromptRuleSpec.ErrorPolicy.FAIL_OPEN, spec.onError());
        assertFalse(spec.enforcementReady());
    }

    @Test
    void parsesObjectBody() {
        PromptRuleSpec spec = PromptRuleParser.parse(
                Map.of(
                        "version", 1,
                        "mode", "judge",
                        "criterion", "criterion",
                        "threshold", 0.5),
                mapper);

        assertEquals(0.5, spec.threshold());
    }

    @Test
    void parsesFailClosedOnlyWhenEnforcementReady() {
        PromptRuleSpec spec = PromptRuleParser.parse(
                """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": "criterion",
                  "threshold": 0.5,
                  "on_error": "fail_closed",
                  "enforcement_ready": true
                }
                """,
                mapper);

        assertEquals(PromptRuleSpec.ErrorPolicy.FAIL_CLOSED, spec.onError());
        assertTrue(spec.enforcementReady());
    }

    @Test
    void rejectsFailClosedWithoutEnforcementReady() {
        PromptRuleValidationException ex = assertThrows(
                PromptRuleValidationException.class,
                () -> parse("""
                        {
                          "version": 1,
                          "mode": "judge",
                          "criterion": "criterion",
                          "threshold": 0.5,
                          "on_error": "fail_closed"
                        }
                        """));

        assertTrue(ex.getMessage().contains("enforcement_ready"));
    }

    @Test
    void rejectsNonJudgeMode() {
        PromptRuleValidationException ex = assertThrows(
                PromptRuleValidationException.class,
                () -> parse("""
                        {
                          "version": 1,
                          "mode": "category",
                          "criterion": "criterion",
                          "threshold": 0.5
                        }
                        """));

        assertTrue(ex.getMessage().contains("mode must be judge"));
    }

    @Test
    void rejectsNonObjectAndMissingThresholds() {
        assertThrows(PromptRuleValidationException.class, () -> parse("\"plain text\""));
        assertThrows(PromptRuleValidationException.class, () -> parse("not-json"));

        PromptRuleValidationException ex = assertThrows(
                PromptRuleValidationException.class,
                () -> parse("""
                        {"version": 1, "mode": "judge", "criterion": "criterion"}
                        """));
        assertTrue(ex.getMessage().contains("threshold"));
    }

    @Test
    void rejectsInvalidThresholdAndLimits() {
        PromptRuleValidationException ex = assertThrows(
                PromptRuleValidationException.class,
                () -> parse("""
                        {
                          "version": 1,
                          "mode": "judge",
                          "criterion": "criterion",
                          "threshold": 1.2
                        }
                        """));

        assertTrue(ex.getMessage().contains("between 0.05 and 0.95"));
    }

    @Test
    void rejectsOversizedCriterionAndExamples() {
        String criterion = "x".repeat(PromptRuleParser.MAX_CRITERION_CHARS + 1);
        String examples = String.join(",", java.util.Collections.nCopies(6, "\"x\""));
        PromptRuleValidationException ex = assertThrows(
                PromptRuleValidationException.class,
                () -> parse("""
                        {
                          "version": 1,
                          "mode": "judge",
                          "criterion": "%s",
                          "examples": {"hit": [%s]},
                          "threshold": 0.5
                        }
                        """.formatted(criterion, examples)));

        assertTrue(ex.getMessage().contains("criterion must be at most"));
        assertTrue(ex.getMessage().contains("at most 5 items"));
    }

    private PromptRuleSpec parse(String json) {
        return PromptRuleParser.parse(json, mapper);
    }
}
