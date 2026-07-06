package io.virbius.control.script;

import io.virbius.control.groovy.GroovyRuleBodies;
import java.util.Locale;
import java.util.Map;

/** Normalizes gateway/cloud script rule bodies for storage and artifact export. */
public final class ScriptRuleBodies {

    private ScriptRuleBodies() {}

    /**
     * Returns executable script text for {@code lua} / {@code groovy} rules.
     * Rejects legacy JSON list DSL maps mistakenly stored under script runtimes.
     */
    public static String asExecutableScript(Object body, String runtime) {
        if (body == null) {
            return "";
        }
        String rt = runtime != null ? runtime.trim().toLowerCase(Locale.ROOT) : "";
        if (body instanceof String s) {
            String script = normalizeEscapedNewlines(s.trim());
            if ("lua".equals(rt)) {
                rejectJsonDslIfPresent(script);
            }
            return script;
        }
        if (("lua".equals(rt) || "groovy".equals(rt)) && body instanceof Map<?, ?>) {
            throw new IllegalArgumentException(
                    rt + " runtime requires decide(ctx) script body, not JSON list/cumulative DSL");
        }
        return GroovyRuleBodies.asScript(body);
    }

    /** Artifact export: same as {@link #asExecutableScript} but never throws on legacy JSON (skip export). */
    public static String asArtifactScript(Object body, String runtime) {
        try {
            return asExecutableScript(body, runtime);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public static boolean isLegacyJsonDsl(String script) {
        if (script == null || script.isBlank()) {
            return false;
        }
        String t = script.trim();
        if (!t.startsWith("{")) {
            return false;
        }
        return t.contains("\"list_type\"")
                || t.contains("\"keywords\"")
                || t.contains("\"subjects\"")
                || t.contains("list_type")
                || t.contains("keywords")
                || t.contains("subjects");
    }

    static String normalizeEscapedNewlines(String script) {
        if (script == null || script.isEmpty()) {
            return script;
        }
        if (script.indexOf('\n') >= 0) {
            return script;
        }
        if (script.contains("\\n")) {
            return script.replace("\\n", "\n");
        }
        return script;
    }

    private static void rejectJsonDslIfPresent(String script) {
        if (isLegacyJsonDsl(script)) {
            throw new IllegalArgumentException(
                    "lua runtime requires decide(ctx) script body, not JSON list/cumulative DSL");
        }
    }
}
