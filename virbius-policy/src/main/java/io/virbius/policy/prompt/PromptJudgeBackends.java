package io.virbius.policy.prompt;

import java.util.Set;

public final class PromptJudgeBackends {

    public static final String NEOHORSE = "neohorse-4b";
    public static final String STARTLUX = "startlux-4b";
    public static final String LAYA = "laya-zh";

    public static final Set<String> ALL = Set.of(NEOHORSE, STARTLUX, LAYA);

    private PromptJudgeBackends() {}
}
