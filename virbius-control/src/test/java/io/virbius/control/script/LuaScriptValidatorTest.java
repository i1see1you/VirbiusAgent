package io.virbius.control.script;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LuaScriptValidatorTest {

    @Test
    void rejectsLegacyJsonDsl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LuaScriptValidator.validate("{\"list_type\":\"deny\",\"keywords\":[\"x\"]}"));
    }

    @Test
    void acceptsValidDecide() {
        String body = "function decide(ctx)\n  return getCumulative('x') >= 1\nend";
        assertDoesNotThrow(() -> LuaScriptValidator.validate(body));
    }

    @Test
    void rejectsForbiddenToken() {
        assertThrows(IllegalArgumentException.class, () -> LuaScriptValidator.validate("function decide(ctx) os.exit() end"));
    }
}
