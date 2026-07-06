package io.virbius.control.script;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** PoC Lua script gate for gateway rules (syntax-lite + forbidden tokens). */
public final class LuaScriptValidator {

    public static final int MAX_BODY_BYTES = 32 * 1024;

    private static final Set<String> FORBIDDEN =
            Set.of("os.", "io.", "load(", "loadstring", "dofile", "require(", "package.", "debug.");

    private LuaScriptValidator() {}

    public static void validate(String scriptBody) {
        if (scriptBody == null || scriptBody.isBlank()) {
            throw new IllegalArgumentException("lua body is empty");
        }
        if (ScriptRuleBodies.isLegacyJsonDsl(scriptBody)) {
            throw new IllegalArgumentException(
                    "lua runtime requires decide(ctx) script body, not JSON list/cumulative DSL");
        }
        if (scriptBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("lua body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        String lower = scriptBody.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("forbidden token in lua script: " + token);
            }
        }
        if (!lower.contains("decide")) {
            throw new IllegalArgumentException("lua script must define decide(ctx)");
        }
    }
}
