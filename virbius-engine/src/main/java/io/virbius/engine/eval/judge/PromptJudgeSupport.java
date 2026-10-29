package io.virbius.engine.eval.judge;

import io.virbius.policy.prompt.PromptRuleSpec;

public final class PromptJudgeSupport {

    private PromptJudgeSupport() {}

    public static String instructions(PromptRuleSpec spec) {
        StringBuilder out = new StringBuilder(spec.criterion());
        if (!spec.hitExamples().isEmpty()) {
            out.append("\nExamples that must be classified as hit:");
            for (String example : spec.hitExamples()) {
                out.append("\n- ").append(example);
            }
        }
        return out.toString();
    }

    public static Outcome evaluate(
            PromptRuleSpec spec, PromptJudgeBackend.JudgeResult result, String ruleId) {
        PromptJudgeBackend.JudgeResult actual =
                result != null ? result : PromptJudgeBackend.JudgeResult.failed("missing judge result", 0);
        PromptJudgeBackend.JudgeAnswer answer = actual.answers().get(ruleId);
        String error = actual.error();
        if (answer == null) {
            error = error != null ? error : "missing answer";
        } else if (answer.error() != null && !answer.error().isBlank()) {
            error = answer.error();
        }
        if (error != null || answer == null || answer.probability() == null) {
            boolean failClosed = spec.onError() == PromptRuleSpec.ErrorPolicy.FAIL_CLOSED;
            return new Outcome(
                    answer != null ? answer.probability() : null,
                    false,
                    failClosed,
                    error != null ? error : "missing probability",
                    true);
        }
        double probability = answer.probability();
        if (!Double.isFinite(probability)) {
            boolean failClosed = spec.onError() == PromptRuleSpec.ErrorPolicy.FAIL_CLOSED;
            return new Outcome(probability, false, failClosed, "invalid probability", true);
        }
        boolean hit = probability >= spec.threshold();
        return new Outcome(probability, hit, hit, null, false);
    }

    public record Outcome(
            Double probability,
            boolean probabilityHit,
            boolean signal,
            String error,
            boolean errorApplied) {}
}
