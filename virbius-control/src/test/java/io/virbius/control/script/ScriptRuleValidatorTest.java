package io.virbius.control.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScriptRuleValidatorTest {

    private static final String TENANT = "default";

    @Mock
    private ListMetaRepository listMetaRepo;

    @Mock
    private CumulativeRepository cumulativeRepo;

    private ScriptRuleValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ScriptRuleValidator(listMetaRepo, cumulativeRepo);
    }

    @Test
    void luaRejectsJsonDslBody() {
        String body = "{\"list_type\":\"deny\",\"keywords\":[\"x\"]}";
        Map<String, Object> r = validator.validate(TENANT, "lua", body);
        assertFalse((Boolean) r.get("valid"));
    }

    @Test
    void groovyValidWithExistingRefs() {
        when(listMetaRepo.getMeta(TENANT, "deny_keyword"))
                .thenReturn(Optional.of(new AccessListMeta(TENANT, "deny_keyword", "keyword", null)));
        String body = "def decide(ctx) { return ctx.listMatch('deny_keyword') }";
        Map<String, Object> r = validator.validate(TENANT, "groovy", body);
        assertTrue((Boolean) r.get("valid"));
        assertEquals(List.of("deny_keyword"), r.get("referenced_lists"));
    }

    @Test
    void luaRejectsMissingCumulative() {
        when(cumulativeRepo.get(TENANT, "user_req_1h")).thenReturn(Optional.empty());
        String body = "function decide(ctx)\n  return getCumulative('user_req_1h') >= 120\nend";
        Map<String, Object> r = validator.validate(TENANT, "lua", body);
        assertFalse((Boolean) r.get("valid"));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) r.get("errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("user_req_1h")));
    }

    @Test
    void luaValidWithExistingCumulative() {
        when(cumulativeRepo.get(TENANT, "user_req_1h"))
                .thenReturn(Optional.of(new CumulativeDef(
                        TENANT, "user_req_1h", null, "user_id", "rolling", 60, null, null, 0, "active", null, null)));
        String body = "function decide(ctx)\n  return getCumulative('user_req_1h') >= 120\nend";
        Map<String, Object> r = validator.validate(TENANT, "lua", body);
        assertTrue((Boolean) r.get("valid"));
    }
}
