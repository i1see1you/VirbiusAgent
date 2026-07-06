package io.virbius.engine.eval;

import io.virbius.engine.config.PromptLlmProperties;

/** Builds a ChatML prompt for safety classification (single LLM call). */
public final class PromptMatrixBuilder {

    private PromptMatrixBuilder() {}

    public static String buildChatMlPrompt(
            PromptLlmProperties props, String userContent) {
        return props.imStart()
                + "system\n"
                + props.systemPrompt()
                + props.imEnd()
                + "\n"
                + props.imStart()
                + "user\n"
                + userContent
                + props.imEnd()
                + "\n"
                + props.imStart()
                + "assistant\n";
    }
}
