package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.audit.AuditEventIngestor;
import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.evaluation.EvaluationProxyController.LicenseInfo;
import io.virbius.control.evaluation.EvaluationProxyController.Status;
import io.virbius.control.service.PublishService;
import io.virbius.control.service.RolloutService;
import io.virbius.control.service.RuleService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

/** 一键处置（M1-min）：preview/apply 幂等、unsupported、429 cap、许可闸、变量校验。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemediationServiceTest {

    private static final String TENANT = "default";
    private static final String REPORT = "re-1";
    private static final String RISK = "aabbccdd11223344";

    private static final String REPORT_JSON = """
            {"report_id":"re-1","agent_name":"mail-agent","findings":[{
              "risk_id":"aabbccdd11223344","check_id":"high-risk-actions","risk_name":"x",
              "severity":"high","evidence":[{"type":"tool","ref":"tools.json:3","snippet":"send_email"}],
              "remediations":[{
                "plan_id":"challenge_highrisk","type":"create_rule","title":"高危工具挑战审批",
                "vars":{"tool":{"source":"evidence","bind":"tool","danger":"deny"}},
                "bound_vars":{"tool":"send_email"},
                "payload_template":{"layer":"edge","runtime":"lua-dsl","reason_code":"HITL_REQUIRED",
                  "risk_score":80,"intent_action":"challenge","is_async":false,
                  "body":{"keywords":["{{tool}}"],"list_type":"deny"}}}],
              "remediations_dl":[{"plan_id":"x","type":"register_tool","title":"t","payload_template":{}}]}],
              "findings_extra":0}
            """;

    @Mock
    private EvaluationProxyController proxy;
    @Mock
    private RuleService ruleService;
    @Mock
    private RolloutService rolloutService;
    @Mock
    private PublishService publishService;
    @Mock
    private AuditEventIngestor audit;

    private RemediationService service;

    @BeforeEach
    void setUp() {
        service = new RemediationService(proxy, ruleService, rolloutService, publishService, audit);
        when(proxy.forwardGet(anyString(), anyString()))
                .thenReturn(ResponseEntity.ok(REPORT_JSON));
        when(proxy.status(anyString(), any())).thenReturn(activeStatus());
        when(ruleService.getRule(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("rule not found"));
        when(ruleService.listRuleDetails(anyString())).thenReturn(List.of());
    }

    /** full 态等价规则（global scope）：layer/runtime/intent 相同 + body 相等。 */
    private Map<String, Object> fullEquivalentRule(String ruleId, String keywordsJson, Object scope) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rule_id", ruleId);
        m.put("layer", "edge");
        m.put("runtime", "lua-dsl");
        m.put("reason_code", "HITL_REQUIRED");
        m.put("intent_action", "challenge");
        m.put("rollout_state", "full");
        m.put("scope", scope);
        m.put("body", "{\"keywords\":" + keywordsJson + ",\"list_type\":\"deny\"}");
        return m;
    }

    private Status activeStatus() {
        LicenseInfo license = new LicenseInfo("valid", "pro", List.of(), null, 30L, true,
                false, "i", null);
        return new Status(true, true, true, license, false, null);
    }

    private RemediationService.ApplyItem item(Map<String, String> vars, String appId) {
        return new RemediationService.ApplyItem(RISK, "challenge_highrisk", vars, appId);
    }

    @Test
    void previewBindsEvidenceAndDetectsExisting() {
        when(ruleService.getRule(eq(TENANT), eq("eval-high-risk-actions-aabbccdd")))
                .thenReturn(Map.of("rule_id", "eval-high-risk-actions-aabbccdd",
                        "rollout_state", "dry_run", "enforce_mode", "dry_run"));

        Map<String, Object> out = service.preview(TENANT, REPORT, List.of(RISK));
        assertEquals("mail-agent", out.get("agent_name"));
        assertTrue(((List<?>) out.get("app_candidates")).isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = ((List<Map<String, Object>>) out.get("plans")).get(0);
        assertTrue((Boolean) plan.get("executable"));
        assertEquals("eval-high-risk-actions-aabbccdd", plan.get("rule_id"));
        assertNotNull(plan.get("payload_preview"));
        assertNotNull(plan.get("existing_rule"));
        assertTrue(((List<?>) plan.get("unresolved_vars")).isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> toolVar = ((Map<String, Map<String, Object>>) plan.get("vars")).get("tool");
        assertEquals("send_email", toolVar.get("bound_value"));
        assertEquals("deny", toolVar.get("danger"));
    }

    @Test
    void previewDlpNormalization() {
        String dlpReport = """
                {"report_id":"re-1","agent_name":"a","findings":[{
                  "risk_id":"1122334455667788","check_id":"secrets","severity":"high",
                  "remediations":[{"plan_id":"dlp_secrets","type":"create_rule","title":"t",
                  "vars":{"pattern":{"source":"user","default_value":"AKIA[0-9A-Z]{16}"}},
                  "payload_template":{"layer":"edge","runtime":"dlp-dsl","reason_code":"DLP_SECRETS",
                    "risk_score":60,"intent_action":"mask","is_async":false,
                    "body":{"entity_type":"credential","pattern":"{{pattern}}"}}}]}]}
                """;
        when(proxy.forwardGet(anyString(), anyString())).thenReturn(ResponseEntity.ok(dlpReport));

        Map<String, Object> out = service.preview(TENANT, REPORT, List.of("1122334455667788"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = ((List<Map<String, Object>>) out.get("plans")).get(0);
        assertTrue((Boolean) plan.get("normalized"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) plan.get("payload_preview");
        assertEquals("allow", payload.get("intent_action"));
        assertEquals(0, payload.get("risk_score"));
    }

    @Test
    void applyDlpForcesNormalizedIntentAction() {
        String dlpReport = """
                {"report_id":"re-1","agent_name":"a","findings":[{
                  "risk_id":"1122334455667788","check_id":"secrets","severity":"high",
                  "remediations":[{"plan_id":"dlp_secrets","type":"create_rule","title":"t",
                  "vars":{"pattern":{"source":"user","default_value":"AKIA[0-9A-Z]{16}"}},
                  "payload_template":{"layer":"edge","runtime":"dlp-dsl","reason_code":"DLP_SECRETS",
                    "risk_score":60,"intent_action":"mask","is_async":false,
                    "body":{"entity_type":"credential","pattern":"{{pattern}}"}}}]}]}
                """;
        when(proxy.forwardGet(anyString(), anyString())).thenReturn(ResponseEntity.ok(dlpReport));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(
                        new RemediationService.ApplyItem("1122334455667788", "dlp_secrets", null, null))));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("created", r.get("status"));
        // BUG-1 回归网：模板 mask/60 必须在发射点预归一化为 allow/null（platform 强制 allow/risk0），
        // 否则 RuleService 的 IntentAction.parse("mask") 先于归一化抛错，apply 恒 failed。
        verify(ruleService).upsertRule(eq(TENANT), argThat(req ->
                "allow".equals(req.intentAction()) && req.riskScore() == null
                        && "dlp-dsl".equals(req.runtime())));
    }

    @Test
    void applyCreatesDryRunRuleAndAudits() {
        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "mail-app"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("created", r.get("status"));
        assertEquals("eval-high-risk-actions-aabbccdd", r.get("rule_id"));

        verify(ruleService).upsertRule(eq(TENANT), any());
        verify(rolloutService).publish(TENANT, "eval-high-risk-actions-aabbccdd");
        verify(publishService, times(1)).runtimeSnapshot(TENANT);
        verify(audit).ingestEvent(argThat(e -> "remediation_apply".equals(e.get("scene"))
                && "created".equals(e.get("effective_action"))
                && REPORT.equals(e.get("trace_id"))));
    }

    @Test
    void applyIsIdempotentWhenRuleExists() {
        when(ruleService.getRule(eq(TENANT), eq("eval-high-risk-actions-aabbccdd")))
                .thenReturn(Map.of("rule_id", "eval-high-risk-actions-aabbccdd",
                        "rollout_state", "dry_run"));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("skipped_exists", r.get("status"));
        assertEquals("dry_run", r.get("existing_state"));
        assertNotNull(r.get("hint"));

        verify(ruleService, never()).upsertRule(anyString(), any());
        verify(rolloutService, never()).publish(anyString(), anyString());
    }

    @Test
    void applySkippedCoveredWhenEquivalentFullRuleExists() {
        when(ruleService.listRuleDetails(TENANT)).thenReturn(List.of(
                fullEquivalentRule("cloud-hitl", "[\"send_email\"]", Map.of())));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "mail-app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("skipped_covered", r.get("status"));
        assertEquals("cloud-hitl", r.get("covered_by"));
        assertEquals("full", r.get("existing_state"));

        verify(ruleService, never()).upsertRule(anyString(), any());
        verify(rolloutService, never()).publish(anyString(), anyString());
        verify(audit).ingestEvent(argThat(e -> "equivalent".equals(e.get("dedup_layer"))
                && "cloud-hitl".equals(e.get("rule_id"))));
    }

    @Test
    void applyCreatesWhenEquivalentRuleOnlyDryRun() {
        Map<String, Object> dry = fullEquivalentRule("cloud-hitl", "[\"send_email\"]", Map.of());
        dry.put("rollout_state", "dry_run");
        when(ruleService.listRuleDetails(TENANT)).thenReturn(List.of(dry));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "mail-app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("created", r.get("status"));
    }

    @Test
    void applyCreatesWhenVariablesDiffer() {
        when(ruleService.listRuleDetails(TENANT)).thenReturn(List.of(
                fullEquivalentRule("cloud-hitl", "[\"other_tool\"]", Map.of())));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "mail-app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("created", r.get("status"));
    }

    @Test
    void applyCreatesWhenScopeDoesNotCoverApp() {
        when(ruleService.listRuleDetails(TENANT)).thenReturn(List.of(
                fullEquivalentRule("cloud-hitl", "[\"send_email\"]",
                        Map.of("bind_scope", "service",
                                "bind_ref", Map.of("app_ids", List.of("other-app"))))));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "mail-app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("created", r.get("status"));
    }

    @Test
    void applyBatchSecondEquivalentSkippedCovered() {
        String report = """
                {"report_id":"re-1","agent_name":"mail-agent","findings":[
                  {"risk_id":"aabbccdd11223344","check_id":"high-risk-actions","severity":"high",
                   "remediations":[{"plan_id":"challenge_highrisk","type":"create_rule","title":"t",
                     "vars":{"tool":{"source":"evidence","bind":"tool"}},
                     "bound_vars":{"tool":"send_email"},
                     "payload_template":{"layer":"edge","runtime":"lua-dsl","reason_code":"HITL_REQUIRED",
                       "risk_score":80,"intent_action":"challenge","is_async":false,
                       "body":{"keywords":["{{tool}}"],"list_type":"deny"}}}]},
                  {"risk_id":"bbbbbbbb22334455","check_id":"high-risk-actions","severity":"high",
                   "remediations":[{"plan_id":"challenge_highrisk","type":"create_rule","title":"t",
                     "vars":{"tool":{"source":"evidence","bind":"tool"}},
                     "bound_vars":{"tool":"send_email"},
                     "payload_template":{"layer":"edge","runtime":"lua-dsl","reason_code":"HITL_REQUIRED",
                       "risk_score":80,"intent_action":"challenge","is_async":false,
                       "body":{"keywords":["{{tool}}"],"list_type":"deny"}}}]}]}
                """;
        when(proxy.forwardGet(anyString(), anyString())).thenReturn(ResponseEntity.ok(report));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(
                        new RemediationService.ApplyItem(
                                "aabbccdd11223344", "challenge_highrisk", null, "app"),
                        new RemediationService.ApplyItem(
                                "bbbbbbbb22334455", "challenge_highrisk", null, "app"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals("created", results.get(0).get("status"));
        assertEquals("skipped_covered", results.get(1).get("status"));
        assertEquals("eval-high-risk-actions-aabbccdd", results.get(1).get("covered_by"));
        verify(ruleService, times(1)).upsertRule(anyString(), any());
    }

    @Test
    void previewShowsEquivalentAndSimilarRules() {
        Map<String, Object> drySameReason = fullEquivalentRule(
                "eval-old", "[\"other\"]", Map.of());
        drySameReason.put("rollout_state", "dry_run");
        when(ruleService.listRuleDetails(TENANT)).thenReturn(List.of(
                fullEquivalentRule("cloud-hitl", "[\"send_email\"]", Map.of()),
                drySameReason));

        Map<String, Object> out = service.preview(TENANT, REPORT, List.of(RISK));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = ((List<Map<String, Object>>) out.get("plans")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> eq = (Map<String, Object>) plan.get("equivalent_rule");
        assertNotNull(eq);
        assertEquals("cloud-hitl", eq.get("rule_id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> similar = (List<Map<String, Object>>) plan.get("similar_rules");
        assertNotNull(similar);
        assertEquals("eval-old", similar.get(0).get("rule_id"));
        assertEquals("same_reason_code", similar.get(0).get("reason"));
    }

    @Test
    void applyRolloutCapYieldsSkippedCap() {
        when(rolloutService.publish(anyString(), anyString()))
                .thenThrow(new BusinessException(429, "进行中的放量已有 N 条"));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("skipped_cap", r.get("status"));
        assertTrue(r.get("error").toString().contains("draft"));
    }

    @Test
    void applyRejectsUndeclaredOrMissingVars() {
        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(Map.of("evil", "1"), "app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("failed", r.get("status"));
        assertTrue(r.get("error").toString().contains("未声明的变量"));
    }

    @Test
    void applyFailsOnIllegalVarValue() {
        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(
                        item(Map.of("tool", "a b\nc"), "app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("failed", r.get("status"));
        assertTrue(r.get("error").toString().contains("非法字符"));
    }

    @Test
    void applyUnknownRiskFails() {
        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(
                        new RemediationService.ApplyItem("nope", "challenge_highrisk", null, "app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("failed", r.get("status"));
    }

    @Test
    void applyBlocksWhenLicenseInactive() {
        LicenseInfo inactive = new LicenseInfo("expired", "pro", List.of(), null, 0L, false,
                false, "i", null);
        when(proxy.status(anyString(), any()))
                .thenReturn(new Status(true, true, true, inactive, false, null));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(
                TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "app")))));
        assertEquals(40306, ex.getCode());
        verify(ruleService, never()).upsertRule(anyString(), any());
    }

    @Test
    void applyValidatesItemsSize() {
        List<RemediationService.ApplyItem> many = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> item(null, "app"))
                .toList();
        assertThrows(BusinessException.class, () -> service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(many)));
        assertThrows(BusinessException.class, () -> service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of())));
    }

    @Test
    void applyPropagatesReportFetchFailure() {
        when(proxy.forwardGet(anyString(), anyString()))
                .thenReturn(ResponseEntity.status(503).body("{\"code\":5032,\"message\":\"down\"}"));
        assertThrows(BusinessException.class, () -> service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(item(null, "app")))));
    }

    @Test
    void registerToolPlanIsUnsupported() {
        String report = """
                {"report_id":"re-1","agent_name":"a","findings":[{
                  "risk_id":"11223344","check_id":"permission-scope","severity":"medium",
                  "remediations":[{"plan_id":"reg","type":"register_tool","title":"t",
                  "payload_template":{"tool_name":"x","risk_class":"high"}}]}]}
                """;
        when(proxy.forwardGet(anyString(), anyString())).thenReturn(ResponseEntity.ok(report));

        Map<String, Object> out = service.apply(TENANT, REPORT,
                new RemediationService.ApplyRequest(List.of(
                        new RemediationService.ApplyItem("11223344", "reg", null, "app"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("results")).get(0);
        assertEquals("unsupported", r.get("status"));

        Map<String, Object> preview = service.preview(TENANT, REPORT, List.of("11223344"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = ((List<Map<String, Object>>) preview.get("plans")).get(0);
        assertFalse((Boolean) plan.get("executable"));
        assertNull(plan.get("payload_preview"));
    }

    @Test
    void substitutionIsLeafLevelOnly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.readTree(
                "{\"layer\":\"cloud\",\"runtime\":\"groovy\",\"body\":{\"script\":\"x {{v}} y\"}}");
        java.lang.reflect.Method sub = RemediationService.class.getDeclaredMethod(
                "substitute", com.fasterxml.jackson.databind.JsonNode.class, Map.class);
        sub.setAccessible(true);
        @SuppressWarnings("unchecked")
        var replaced = (com.fasterxml.jackson.databind.JsonNode) sub.invoke(service, payload,
                Map.of("v", "'; DROP --"));
        assertEquals("x '; DROP -- y",
                replaced.path("body").path("script").asText());
        assertEquals("groovy", replaced.path("runtime").asText());
    }
}
