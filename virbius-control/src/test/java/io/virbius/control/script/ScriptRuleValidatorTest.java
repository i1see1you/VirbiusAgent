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
    void promptJudgeBodyIsValidated() {
        String body = """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": "具体基金买卖建议",
                  "threshold": 0.5,
                  "on_error": "fail_closed"
                }
                """;

        Map<String, Object> r = validator.validate(TENANT, "e2e-judge-fund", "prompt", body);

        assertFalse((Boolean) r.get("valid"));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) r.get("errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("enforcement_ready")));
    }

    @Test
    void promptJudgeBodyAcceptsReadyFailClosed() {
        String body = """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": "具体基金买卖建议",
                  "threshold": 0.5,
                  "on_error": "fail_closed",
                  "enforcement_ready": true
                }
                """;

        Map<String, Object> r = validator.validate(TENANT, "e2e-judge-fund", "prompt", body);

        assertTrue((Boolean) r.get("valid"));
    }

    @Test
    void legacyPromptTextRemainsValid() {
        Map<String, Object> r = validator.validate(
                TENANT, "prompt-pii", "prompt", "当用户请求具体基金买卖建议时命中");

        assertTrue((Boolean) r.get("valid"));
    }

    @Test
    void customPromptTextIsRejectedAsInvalidJudge() {
        Map<String, Object> r = validator.validate(
                TENANT, "e2e-custom-prompt", "prompt", "当用户请求具体基金买卖建议时命中");

        assertFalse((Boolean) r.get("valid"));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) r.get("errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("valid JSON")));
    }

    @Test
    void promptJudgeCriterionDoesNotTriggerListReferenceValidation() {
        String body = """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": "命中 listMatch('missing_list') 或 getCumulative('missing_cum')",
                  "threshold": 0.5
                }
                """;

        Map<String, Object> r = validator.validate(TENANT, "e2e-judge-fund", "prompt", body);

        assertTrue((Boolean) r.get("valid"));
        assertEquals(List.of(), r.get("referenced_lists"));
        assertEquals(List.of(), r.get("referenced_cumulatives"));
    }

    @Test
    void luaRejectsJsonDslBody() {
        String body = "{\"list_type\":\"deny\",\"keywords\":[\"x\"]}";
        Map<String, Object> r = validator.validate(TENANT, null, "lua", body);
        assertFalse((Boolean) r.get("valid"));
    }

    @Test
    void groovyValidWithExistingRefs() {
        when(listMetaRepo.getMeta(TENANT, "deny_keyword"))
                .thenReturn(Optional.of(new AccessListMeta(TENANT, "deny_keyword", "keyword", null)));
        String body = "def decide(ctx) { return ctx.listMatch('deny_keyword') }";
        Map<String, Object> r = validator.validate(TENANT, null, "groovy", body);
        assertTrue((Boolean) r.get("valid"));
        assertEquals(List.of("deny_keyword"), r.get("referenced_lists"));
    }

    @Test
    void luaRejectsMissingCumulative() {
        when(cumulativeRepo.get(TENANT, "user_req_1h")).thenReturn(Optional.empty());
        String body = "function decide(ctx)\n  return getCumulative('user_req_1h') >= 120\nend";
        Map<String, Object> r = validator.validate(TENANT, null, "lua", body);
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
        Map<String, Object> r = validator.validate(TENANT, null, "lua", body);
        assertTrue((Boolean) r.get("valid"));
    }
}
