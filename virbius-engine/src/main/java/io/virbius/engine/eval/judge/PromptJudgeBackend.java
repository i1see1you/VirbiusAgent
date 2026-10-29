package io.virbius.engine.eval.judge;

import java.util.List;
import java.util.Map;

public interface PromptJudgeBackend {

    String id();

    JudgeCapabilities capabilities();

    JudgeResult judge(JudgeRequest request);

    JudgeHealth health();

    record JudgeRule(String id, String type, String instructions) {}

    record JudgeRequest(String content, List<JudgeRule> rules) {
        public JudgeRequest {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    record JudgeAnswer(Double probability, Boolean hit, String error) {
        public static JudgeAnswer probability(double probability) {
            return new JudgeAnswer(probability, null, null);
        }

        public static JudgeAnswer failed(String error) {
            return new JudgeAnswer(null, null, error);
        }
    }

    record JudgeResult(Map<String, JudgeAnswer> answers, long latencyMs, String error) {
        public JudgeResult {
            answers = answers == null ? Map.of() : Map.copyOf(answers);
        }

        public static JudgeResult failed(String error, long latencyMs) {
            return new JudgeResult(Map.of(), latencyMs, error);
        }
    }

    record JudgeCapabilities(
            boolean supportsNoul,
            boolean supportsChoice,
            boolean supportsBatch,
            int maxContextTokens,
            int maxRulesPerBatch,
            String license,
            boolean commercialUseAllowed) {}

    record JudgeHealth(boolean healthy, String detail) {
        public static JudgeHealth up() {
            return new JudgeHealth(true, "ok");
        }

        public static JudgeHealth down(String detail) {
            return new JudgeHealth(false, detail);
        }
    }
}
