package io.virbius.engine.eval.judge;

import java.util.List;
import java.util.Map;

public interface PromptJudgeBackend {

    String id();

    JudgeCapabilities capabilities();

    JudgeResult judge(JudgeRequest request);

    record JudgeRule(String id, String type, String instructions) {}

    // allowBusyRetry=false on the sync decision path: a busy sleep-retry would block effective_action.
    record JudgeRequest(String content, List<JudgeRule> rules, boolean allowBusyRetry) {
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
            int maxQuestionsPerRequest,
            boolean multiQuestionSingleForward,
            int maxStateTokens) {}
}
