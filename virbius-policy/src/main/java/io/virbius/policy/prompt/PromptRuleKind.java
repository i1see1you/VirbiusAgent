package io.virbius.policy.prompt;

import java.util.Set;

public enum PromptRuleKind {
    CATEGORY,
    JUDGE;

    public static final Set<String> BUILT_IN_CATEGORY_RULE_IDS = Set.of(
            "prompt-violent",
            "prompt-illegal",
            "prompt-sexual",
            "prompt-pii",
            "prompt-self-harm",
            "prompt-unethical",
            "prompt-political",
            "prompt-copyright",
            "prompt-jailbreak",
            "prompt-agent");

    public static PromptRuleKind of(String ruleId) {
        return isBuiltInCategory(ruleId) ? CATEGORY : JUDGE;
    }

    public static boolean isBuiltInCategory(String ruleId) {
        return ruleId != null && BUILT_IN_CATEGORY_RULE_IDS.contains(ruleId);
    }
}
