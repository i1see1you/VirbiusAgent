package io.virbius.policy.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptRuleKindTest {

    @Test
    void builtInCategoryIdsAreExactlyTheDefaultPromptRules() {
        assertEquals(
                java.util.Set.of(
                        "prompt-violent",
                        "prompt-illegal",
                        "prompt-sexual",
                        "prompt-pii",
                        "prompt-self-harm",
                        "prompt-unethical",
                        "prompt-political",
                        "prompt-copyright",
                        "prompt-jailbreak",
                        "prompt-agent"),
                PromptRuleKind.BUILT_IN_CATEGORY_RULE_IDS);
    }

    @Test
    void builtInNamesAreCategory() {
        assertTrue(PromptRuleKind.isBuiltInCategory("prompt-pii"));
        assertEquals(PromptRuleKind.CATEGORY, PromptRuleKind.of("prompt-pii"));
    }

    @Test
    void customPromptNamesAreJudge() {
        assertFalse(PromptRuleKind.isBuiltInCategory("e2e-judge-fund"));
        assertEquals(PromptRuleKind.JUDGE, PromptRuleKind.of("e2e-judge-fund"));
        assertEquals(PromptRuleKind.JUDGE, PromptRuleKind.of("prompt-unknown"));
        assertEquals(PromptRuleKind.JUDGE, PromptRuleKind.of(null));
    }
}
