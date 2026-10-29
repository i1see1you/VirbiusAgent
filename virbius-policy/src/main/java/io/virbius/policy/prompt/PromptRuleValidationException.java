package io.virbius.policy.prompt;

public class PromptRuleValidationException extends IllegalArgumentException {

    public PromptRuleValidationException(String message) {
        super(message);
    }

    public PromptRuleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
