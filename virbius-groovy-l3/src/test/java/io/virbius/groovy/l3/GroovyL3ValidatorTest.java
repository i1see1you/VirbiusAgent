package io.virbius.groovy.l3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GroovyL3Validator} return-type checking.
 *
 * <p>Tests cover both the AST heuristic (direct literal returns) and the
 * trial-execution check (indirect returns via variables).
 */
class GroovyL3ValidatorTest {

    // ========== ✅ Valid scripts (should pass) ==========

    @Test
    void validBooleanLiteralTrue() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate("def decide(ctx) { return true }"));
    }

    @Test
    void validBooleanLiteralFalse() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate("def decide(ctx) { return false }"));
    }

    @Test
    void validBooleanExpression() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate("def decide(ctx) { return ctx.wouldHitBlock() }"));
    }

    @Test
    void validBinaryComparison() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate(
                        "def decide(ctx) { return ctx.var('app_id') == 'admin' }"));
    }

    @Test
    void validLogicalAndOr() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  return ctx.var('app_id') == 'admin' && ctx.wouldHitBlock()\n"
                                + "}"));
    }

    @Test
    void validMultiBranchBoolean() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  if (ctx.wouldHitBlock()) return true\n"
                                + "  if (ctx.var('app_id') == 'admin') return true\n"
                                + "  return false\n"
                                + "}"));
    }

    @Test
    void validDefaultScript() {
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT));
    }

    // ========== ❌ Invalid scripts (should fail) — AST heuristic ==========

    @Test
    void rejectReturnMapLiteral() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { return [action: 'deny'] }"));
        assertTrue(e.getMessage().contains("Map"), "Error should mention Map: " + e.getMessage());
    }

    @Test
    void rejectReturnStringLiteral() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { return 'block' }"));
        assertTrue(e.getMessage().contains("String"), "Error should mention String: " + e.getMessage());
    }

    @Test
    void rejectReturnNumberLiteral() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { return 100 }"));
        assertTrue(e.getMessage().contains("numeric"), "Error should mention numeric: " + e.getMessage());
    }

    @Test
    void rejectReturnListLiteral() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { return ['a', 'b'] }"));
        assertTrue(e.getMessage().contains("List"), "Error should mention List: " + e.getMessage());
    }

    @Test
    void rejectReturnInOneBranchMap() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  if (ctx.wouldHitBlock()) return [action: 'deny']\n"
                                + "  return false\n"
                                + "}"));
        assertTrue(e.getMessage().contains("Map"), "Error should mention Map: " + e.getMessage());
    }

    // ========== ❌ Invalid scripts (should fail) — Trial execution ==========

    @Test
    void rejectIndirectReturnMap() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  def result = [action: 'deny', reason: 'test']\n"
                                + "  return result\n"
                                + "}"));
        assertTrue(e.getMessage().contains("must return boolean"),
                "Error should mention return type: " + e.getMessage());
    }

    @Test
    void rejectIndirectReturnString() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  def msg = 'blocked'\n"
                                + "  return msg\n"
                                + "}"));
        assertTrue(e.getMessage().contains("must return boolean"),
                "Error should mention return type: " + e.getMessage());
    }

    @Test
    void rejectIndirectReturnNumber() {
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  def score = 42\n"
                                + "  return score\n"
                                + "}"));
        assertTrue(e.getMessage().contains("must return boolean"),
                "Error should mention return type: " + e.getMessage());
    }

    @Test
    void rejectNoExplicitReturn() {
        // Script with no explicit return → Groovy returns null (result of last expression)
        GroovyL3ValidationException e = assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  def x = ctx.var('app_id')\n"
                                + "  println(x)\n"
                                + "}"));
        // Trial execution catches null return
        assertTrue(e.getMessage().contains("null") || e.getMessage().contains("must return boolean"),
                "Error should mention null or return type: " + e.getMessage());
    }

    // ========== ❌ Invalid scripts — existing checks still work ==========

    @Test
    void rejectEmptyBody() {
        assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate(""));
    }

    @Test
    void rejectMissingDecide() {
        assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def foo(ctx) { return true }"));
    }

    @Test
    void rejectForbiddenToken() {
        assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { Runtime.getRuntime().exec('x'); return true }"));
    }

    @Test
    void rejectUnknownCtxMethod() {
        assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { return ctx.exec('rm -rf /') }"));
    }

    // ========== ✅ AST-only mode (no trial execution) ==========

    @Test
    void astOnlyModeSkipsTrialExecution() {
        // This script returns a variable holding a Map — AST allows it (VariableExpression),
        // but trial execution would catch it. With trialExecution=false, it should pass.
        assertDoesNotThrow(() ->
                GroovyL3Validator.validate(
                        "def decide(ctx) {\n"
                                + "  def result = [action: 'deny']\n"
                                + "  return result\n"
                                + "}",
                        false));
    }

    @Test
    void astOnlyModeStillCatchesLiteralMap() {
        // Even in AST-only mode, direct Map literal returns are caught
        assertThrows(GroovyL3ValidationException.class, () ->
                GroovyL3Validator.validate("def decide(ctx) { return [action: 'deny'] }", false));
    }
}
