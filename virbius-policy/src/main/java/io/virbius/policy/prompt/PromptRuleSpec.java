package io.virbius.policy.prompt;

import java.util.List;

public record PromptRuleSpec(
        int version,
        Mode mode,
        String criterion,
        List<String> hitExamples,
        double threshold,
        ErrorPolicy onError,
        boolean enforcementReady) {

    public PromptRuleSpec {
        hitExamples = hitExamples == null ? List.of() : List.copyOf(hitExamples);
    }

    public enum Mode {
        JUDGE
    }

    public enum ErrorPolicy {
        FAIL_OPEN,
        FAIL_CLOSED
    }
}
