package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBindScopeValidatorTest {

    private static final Map<String, Object> METADATA = Map.of();

    @Test
    void acceptsValidToolName() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("read_file"))),
                Map.of(),
                null,
                null,
                null,
                null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateToolScope(req, METADATA));
    }

    @Test
    void rejectsInvalidToolName() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("UPPERCASE"))),
                Map.of(),
                null,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateToolScope(req, METADATA));
    }

    @Test
    void skipsWhenNoScope() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1", "poc-default", "cloud", "groovy", "X", 100, "deny", null, Map.of(), null, null, null, null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateToolScope(req, METADATA));
    }

    @Test
    void edgeServiceBindRequiresAppIds() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "edge_r1",
                "poc-default",
                "edge",
                "lua-dsl",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "service", "bind_ref", Map.of()),
                Map.of("list_type", "deny", "keywords", List.of("x")),
                null,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateToolScope(req, METADATA));
    }

    @Test
    void edgeToolBindForwardedAsGlobal() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "edge_r2",
                "poc-default",
                "edge",
                "lua-dsl",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("read_file"))),
                Map.of("list_type", "deny", "keywords", List.of("x")),
                null,
                null,
                null,
                null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateToolScope(req, METADATA));
    }
}
