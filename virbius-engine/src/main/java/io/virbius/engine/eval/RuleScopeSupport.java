package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.BindScope;
import io.virbius.policy.MatchContext;

/** Shared bind_scope parsing and matching for cloud script / prompt rules. */
final class RuleScopeSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleScopeSupport() {}

    static boolean matchesBind(RuleEntry rule, MatchContext matchCtx) {
        JsonNode scope = parseScope(rule);
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        return BindScope.matches(scope, matchCtx);
    }

    static JsonNode parseScope(RuleEntry rule) {
        if (rule.scope() == null) {
            return null;
        }
        try {
            if (rule.scope() instanceof String s) {
                if (s.isBlank()) {
                    return null;
                }
                return MAPPER.readTree(s);
            }
            return MAPPER.valueToTree(rule.scope());
        } catch (Exception e) {
            return null;
        }
    }
}
