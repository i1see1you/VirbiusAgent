package io.virbius.control.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.virbius.control.audit.AuditEventIngestor;
import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.gateway.DlpRuleValidator;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.service.PublishService;
import io.virbius.control.service.RolloutService;
import io.virbius.control.service.RuleService;
import io.virbius.policy.BindScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * 一键处置（M1-min）：报告 Finding 的结构化处方 → dry_run 规则。
 *
 * <p>红线：单一幂等键 eval-{check_id}-{risk_id8}；已存在不覆盖（skipped_exists）；
 * 等价判重（skipped_covered）：同 layer+runtime+intent + 归一化 body 相等 + scope 覆盖目标 app，
 * 仅 full 态（或本批新建）算已覆盖，dry_run/canary 降级为 similar_rules 提示；
 * 一律经 RolloutService.publish 进入 dry_run（不触碰 canary/full）；变量仅白名单键、
 * {{var}} 只做 JsonNode 叶子级替换；register_tool/egress_allowlist M1 返回 unsupported；
 * evaluation 许可 inactive 时 apply 整体拒绝（Owner 2026-10-23 决策）。
 */
@Service
public class RemediationService {

    private static final Logger log = LoggerFactory.getLogger(RemediationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");
    private static final int MAX_ITEMS = 10;
    private static final int LICENSE_INACTIVE_CODE = 40306;

    public record ApplyItem(String riskId, String planId, Map<String, String> variables, String appId) {
    }

    public record ApplyRequest(List<ApplyItem> items) {
    }

    private final EvaluationProxyController proxy;
    private final RuleService ruleService;
    private final RolloutService rolloutService;
    private final PublishService publishService;
    private final AuditEventIngestor audit;

    public RemediationService(
            EvaluationProxyController proxy,
            RuleService ruleService,
            RolloutService rolloutService,
            PublishService publishService,
            AuditEventIngestor audit) {
        this.proxy = proxy;
        this.ruleService = ruleService;
        this.rolloutService = rolloutService;
        this.publishService = publishService;
        this.audit = audit;
    }

    public Map<String, Object> preview(String tenantId, String reportId, List<String> riskIds) {
        JsonNode report = fetchReport(tenantId, reportId);
        Map<String, JsonNode> findings = indexFindings(report);
        List<Map<String, Object>> rules = allRuleDetails(tenantId);
        String agentApp = report.path("agent_name").asText(null);
        List<Map<String, Object>> plans = new ArrayList<>();
        for (String riskId : riskIds) {
            JsonNode finding = findings.get(riskId);
            if (finding == null) {
                continue;
            }
            for (JsonNode plan : finding.path("remediations")) {
                plans.add(previewPlan(tenantId, finding, plan, rules, agentApp));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_id", reportId);
        out.put("agent_name", report.path("agent_name").asText(null));
        out.put("app_candidates", appCandidates(tenantId));
        out.put("plans", plans);
        return out;
    }

    public Map<String, Object> apply(String tenantId, String reportId, ApplyRequest req) {
        List<ApplyItem> items = req.items() == null ? List.of() : req.items();
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new BusinessException(400, "items 必须为 1-" + MAX_ITEMS + " 条");
        }
        requireLicenseActive(tenantId);
        JsonNode report = fetchReport(tenantId, reportId);
        Map<String, JsonNode> findings = indexFindings(report);

        List<Map<String, Object>> results = new ArrayList<>();
        boolean anyCreated = false;
        String agentApp = report.path("agent_name").asText("default");
        List<Map<String, Object>> rules = new ArrayList<>(allRuleDetails(tenantId));
        for (ApplyItem item : items) {
            Map<String, Object> result = applyItem(tenantId, reportId, findings, item, agentApp, rules);
            results.add(result);
            anyCreated |= "created".equals(result.get("status"));
        }
        if (anyCreated) {
            try {
                publishService.runtimeSnapshot(tenantId);
            } catch (Exception e) {
                log.warn("publish-snapshot failed after remediation apply: {}", e.toString());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_id", reportId);
        out.put("results", results);
        return out;
    }

    private Map<String, Object> applyItem(
            String tenantId, String reportId, Map<String, JsonNode> findings,
            ApplyItem item, String agentApp, List<Map<String, Object>> rules) {
        JsonNode finding = findings.get(item.riskId());
        if (finding == null) {
            return result(item, "failed", null, "risk_id 不在报告中: " + item.riskId());
        }
        JsonNode plan = findPlan(finding, item.planId());
        if (plan == null) {
            return result(item, "failed", null, "plan_id 不在该 finding: " + item.planId());
        }
        if (!"create_rule".equals(plan.path("type").asText())) {
            audit(tenantId, reportId, item, null, "skipped", "none", finding);
            return result(item, "unsupported", null,
                    "M1 仅支持 create_rule（register_tool/egress_allowlist 留 M2）");
        }
        JsonNode payload = plan.path("payload_template");
        String ruleId = ruleId(finding, item.riskId());
        String appId = item.appId() == null || item.appId().isBlank() ? agentApp : item.appId().strip();

        Resolved resolved = resolveVars(plan, item.variables());
        if (resolved.error() != null) {
            return result(item, resolved.invalid() ? "failed" : "needs_input", ruleId, resolved.error());
        }
        String substError = validateSubstituted(payload, resolved.values());
        if (substError != null) {
            return result(item, "failed", ruleId, substError);
        }
        Map<String, Object> existing = existingRule(tenantId, ruleId);
        if (existing != null) {
            audit(tenantId, reportId, item, ruleId, "skipped", "key", finding);
            Map<String, Object> r = result(item, "skipped_exists", ruleId, null);
            r.put("existing_state", existing.get("rollout_state"));
            r.put("hint", "规则已存在（不覆盖）；如需改变量请到规则页下线→编辑→重新发布");
            return r;
        }
        JsonNode substituted = substitute(payload.deepCopy(), resolved.values());
        Map<String, Object> coveredBy = findCoveringRule(rules, substituted, appId);
        if (coveredBy != null) {
            String coveredId = String.valueOf(coveredBy.get("rule_id"));
            audit(tenantId, reportId, item, coveredId, "skipped", "equivalent", finding);
            Map<String, Object> r = result(item, "skipped_covered", ruleId, null);
            r.put("covered_by", coveredId);
            r.put("existing_state", coveredBy.get("rollout_state"));
            r.put("hint", "已有等价规则 " + coveredId + " 在防护中（内容一致）；"
                    + "如需独立观察请到规则页手动复制后修改变量");
            return r;
        }
        try {
            UpsertRuleRequest upsert = buildUpsert(substituted, ruleId, appId);
            ruleService.upsertRule(tenantId, upsert);
        } catch (Exception e) {
            audit(tenantId, reportId, item, ruleId, "failed", "none", finding);
            return result(item, "failed", ruleId, firstLine(e.getMessage()));
        }
        String status = "created";
        String error = null;
        try {
            rolloutService.publish(tenantId, ruleId);
        } catch (Exception e) {
            status = "skipped_cap";
            error = "规则已创建（draft）但进入 dry_run 被拒: " + firstLine(e.getMessage());
        }
        audit(tenantId, reportId, item, ruleId,
                "created".equals(status) ? "created" : "skipped", "none", finding);
        rules.add(createdRuleShadow(substituted, ruleId, appId));
        return result(item, status, ruleId, error);
    }

    private UpsertRuleRequest buildUpsert(JsonNode payload, String ruleId, String appId) {
        JsonNode body = payload.path("body");
        Object bodyValue;
        if ("groovy".equals(payload.path("runtime").asText()) && body.has("script")) {
            bodyValue = body.path("script").asText();
        } else {
            // DlpRuleValidator.validateUpsert 只认真实 Map（asMap instanceof Map），JsonNode 会被判空
            // 报 "dlp-dsl body required"；控制台 upsert 入参经 Jackson 绑定即为 Map，此处对齐。
            bodyValue = JSON.convertValue(body, Map.class);
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("bind_scope", "service");
        scope.put("bind_ref", Map.of("app_ids", List.of(appId)));
        boolean dlp = DlpRuleValidator.isDlpRuntime(payload.path("runtime").asText());
        return new UpsertRuleRequest(
                ruleId,
                "poc-default",
                payload.path("layer").asText(),
                payload.path("runtime").asText(),
                payload.path("reason_code").asText(),
                // dlp 模板的 mask/60 是给评估读者的语义描述；平台强制 allow/risk0（与 previewPlan 对称），
                // 直接透传会在 RuleService 被 IntentAction.parse("mask") 先拒，故发射点预归一化。
                dlp ? null : (payload.path("risk_score").isNumber() ? payload.path("risk_score").asInt() : null),
                dlp ? "allow" : payload.path("intent_action").asText(null),
                scope,
                bodyValue,
                null,
                null,
                payload.path("is_async").isBoolean() && payload.path("is_async").asBoolean(),
                null);
    }

    private record Resolved(Map<String, String> values, String error, boolean invalid) {
    }

    /** 变量合并：user > boundVars > default；白名单外键拒绝；空值拒绝。 */
    private Resolved resolveVars(JsonNode plan, Map<String, String> userVars) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode vars = plan.path("vars");
        JsonNode bound = plan.path("bound_vars");
        Map<String, String> user = userVars == null ? Map.of() : userVars;
        for (String key : user.keySet()) {
            if (!vars.has(key)) {
                return new Resolved(null, "未声明的变量: " + key, true);
            }
        }
        List<String> names = new ArrayList<>();
        vars.fieldNames().forEachRemaining(names::add);
        java.util.Set<String> unresolved = new LinkedHashSet<>();
        for (String name : names) {
            String value = user.get(name);
            if (value == null || value.isBlank()) {
                value = bound.has(name) ? bound.get(name).asText(null) : null;
            }
            if (value == null || value.isBlank()) {
                JsonNode def = vars.path(name).path("default_value");
                value = def.isTextual() ? def.asText() : null;
            }
            if (value == null || value.isBlank()) {
                unresolved.add(name);
            } else if (value.length() > 512 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\0') >= 0) {
                return new Resolved(null, "变量 " + name + " 含非法字符或超长", true);
            } else {
                values.put(name, value);
            }
        }
        if (!unresolved.isEmpty()) {
            return new Resolved(null, "缺少变量值: " + String.join(", ", unresolved), false);
        }
        return new Resolved(values, null, false);
    }

    /** 替换后形态校验：body.pattern 可编译；keywords 项非空无空白。 */
    private String validateSubstituted(JsonNode payload, Map<String, String> values) {
        JsonNode body = substitute(payload.path("body").deepCopy(), values);
        if (body.isObject()) {
            JsonNode pattern = body.path("pattern");
            if (pattern.isTextual()) {
                try {
                    Pattern.compile(pattern.asText());
                } catch (Exception e) {
                    return "pattern 非法正则: " + firstLine(e.getMessage());
                }
            }
            JsonNode keywords = body.path("keywords");
            if (keywords.isArray()) {
                for (JsonNode k : keywords) {
                    String s = k.asText("");
                    if (s.isBlank() || s.chars().anyMatch(Character::isWhitespace) || s.length() > 256) {
                        return "keywords 项非法（空/含空白/超长）: " + s;
                    }
                }
            }
        }
        return null;
    }

    /** P3 红线：仅叶子 TextNode 上的 {{var}} 替换，禁止整包文本替换。 */
    private JsonNode substitute(JsonNode node, Map<String, String> values) {
        if (node instanceof TextNode t) {
            String s = t.asText();
            Matcher m = PLACEHOLDER.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = values.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : v));
            }
            m.appendTail(sb);
            return TextNode.valueOf(sb.toString());
        }
        if (node instanceof ObjectNode obj) {
            ObjectNode out = obj.objectNode();
            obj.fields().forEachRemaining(e -> out.set(e.getKey(), substitute(e.getValue(), values)));
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = arr.arrayNode();
            arr.forEach(n -> out.add(substitute(n, values)));
            return out;
        }
        return node;
    }

    /**
     * 等价判重：同 layer+runtime+intent + 归一化 body 相等 + 已有规则 scope 覆盖目标 app。
     * 仅 full 态（或本批刚创建）算"已覆盖"；dry_run/canary/draft 只观察不防护，留给 similar 提示。
     */
    private Map<String, Object> findCoveringRule(
            List<Map<String, Object>> rules, JsonNode payload, String appId) {
        String layer = payload.path("layer").asText(null);
        String runtime = payload.path("runtime").asText(null);
        boolean dlp = DlpRuleValidator.isDlpRuntime(runtime);
        String intent = dlp ? "allow" : payload.path("intent_action").asText(null);
        for (Map<String, Object> rule : rules) {
            if (!java.util.Objects.equals(layer, rule.get("layer"))
                    || !java.util.Objects.equals(runtime, rule.get("runtime"))) {
                continue;
            }
            if (!java.util.Objects.equals(intent, String.valueOf(rule.get("intent_action")))) {
                continue;
            }
            if (!bodyEquals(runtime, rule.get("body"), payload.path("body"))) {
                continue;
            }
            Object scope = rule.get("scope");
            if (!coversApp(scope instanceof Map<?, ?> s ? castScope(s) : null, appId)) {
                continue;
            }
            String state = rule.get("rollout_state") == null ? "draft"
                    : String.valueOf(rule.get("rollout_state"));
            if ("full".equals(state) || Boolean.TRUE.equals(rule.get("_batch_created"))) {
                return rule;
            }
        }
        return null;
    }

    /** 软提示：body 等价但未防护（dry_run/canary/draft），或同 layer+runtime+reason_code。 */
    private List<Map<String, Object>> findSimilarRules(
            List<Map<String, Object>> rules, JsonNode payload, String appId, String selfRuleId) {
        String layer = payload.path("layer").asText(null);
        String runtime = payload.path("runtime").asText(null);
        String reason = payload.path("reason_code").asText(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            if (out.size() >= 5) {
                break;
            }
            String id = String.valueOf(rule.get("rule_id"));
            if (id.equals(selfRuleId)) {
                continue;
            }
            String state = rule.get("rollout_state") == null ? "draft"
                    : String.valueOf(rule.get("rollout_state"));
            if ("disabled".equals(state)) {
                continue;
            }
            if (!java.util.Objects.equals(layer, rule.get("layer"))
                    || !java.util.Objects.equals(runtime, rule.get("runtime"))) {
                continue;
            }
            Object scope = rule.get("scope");
            if (!coversApp(scope instanceof Map<?, ?> s ? castScope(s) : null, appId)) {
                continue;
            }
            boolean sameBody = bodyEquals(runtime, rule.get("body"), payload.path("body"));
            if (sameBody && "full".equals(state)) {
                continue;
            }
            boolean sameReason = reason != null && reason.equals(rule.get("reason_code"));
            if (!sameBody && !sameReason) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("rule_id", id);
            s.put("rollout_state", state);
            s.put("intent_action", rule.get("intent_action"));
            s.put("reason", sameBody ? "same_body_not_enforcing" : "same_reason_code");
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castScope(Map<?, ?> s) {
        return (Map<String, Object>) s;
    }

    /** scope 覆盖语义与 BindScope 对齐：global=全覆盖；service=app_ids 命中；tool=无 app_ids 限定时全覆盖。 */
    private static boolean coversApp(Map<String, Object> scope, String appId) {
        String bind = BindScope.scopeFromRuleScope(scope);
        if (BindScope.GLOBAL.equals(bind)) {
            return true;
        }
        List<String> appIds = new ArrayList<>();
        Object raw = BindScope.bindRefFromScope(scope).get("app_ids");
        if (raw instanceof List<?> list) {
            list.forEach(id -> {
                if (id != null && !String.valueOf(id).isBlank()) {
                    appIds.add(String.valueOf(id));
                }
            });
        }
        if (BindScope.SERVICE.equals(bind)) {
            return appId != null && appIds.contains(appId);
        }
        return appIds.isEmpty() || (appId != null && appIds.contains(appId));
    }

    private static boolean bodyEquals(String runtime, Object existingBody, JsonNode newBody) {
        if (existingBody == null || newBody == null || newBody.isMissingNode()) {
            return false;
        }
        String existing = String.valueOf(existingBody);
        if ("groovy".equals(runtime) || "agent-groovy".equals(runtime) || "lua".equals(runtime)) {
            String script = newBody.path("script").asText(null);
            return script != null && normalizeScript(existing).equals(normalizeScript(script));
        }
        try {
            return JSON.readTree(existing).equals(newBody);
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeScript(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private List<Map<String, Object>> allRuleDetails(String tenantId) {
        try {
            List<Map<String, Object>> out = ruleService.listRuleDetails(tenantId);
            return out == null ? List.of() : out;
        } catch (Exception e) {
            log.debug("rule details scan failed (dedup degraded to key-only): {}", e.toString());
            return List.of();
        }
    }

    /** 本批新建规则的影子记录，保证同批后续等价项判重命中（视为已覆盖）。 */
    private Map<String, Object> createdRuleShadow(JsonNode payload, String ruleId, String appId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule_id", ruleId);
        m.put("layer", payload.path("layer").asText(null));
        m.put("runtime", payload.path("runtime").asText(null));
        m.put("reason_code", payload.path("reason_code").asText(null));
        boolean dlp = DlpRuleValidator.isDlpRuntime(payload.path("runtime").asText());
        m.put("intent_action", dlp ? "allow" : payload.path("intent_action").asText(null));
        m.put("rollout_state", "dry_run");
        m.put("scope", Map.of("bind_scope", "service",
                "bind_ref", Map.of("app_ids", List.of(appId))));
        JsonNode body = payload.path("body");
        if ("groovy".equals(payload.path("runtime").asText()) && body.has("script")) {
            m.put("body", body.path("script").asText());
        } else {
            m.put("body", body.toString());
        }
        m.put("_batch_created", Boolean.TRUE);
        return m;
    }

    private Map<String, Object> previewPlan(String tenantId, JsonNode finding, JsonNode plan,
            List<Map<String, Object>> rules, String agentApp) {
        Map<String, Object> p = new LinkedHashMap<>();
        String riskId = finding.path("risk_id").asText();
        p.put("risk_id", riskId);
        p.put("check_id", finding.path("check_id").asText(null));
        p.put("plan_id", plan.path("plan_id").asText(null));
        p.put("type", plan.path("type").asText(null));
        p.put("title", plan.path("title").asText(null));
        boolean executable = "create_rule".equals(plan.path("type").asText());
        p.put("executable", executable);
        if (!executable) {
            p.put("executable_reason", "M1 仅支持 create_rule（register_tool/egress_allowlist 留 M2）");
        }
        p.put("rule_id", ruleId(finding, riskId));

        Map<String, Object> vars = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        Map<String, String> previewValues = new LinkedHashMap<>();
        plan.path("vars").fields().forEachRemaining(e -> {
            JsonNode spec = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("source", spec.path("source").asText(null));
            v.put("bind", spec.path("bind").asText(null));
            v.put("hint", spec.path("hint").asText(null));
            v.put("default_value", spec.path("default_value").asText(null));
            v.put("danger", spec.path("danger").asText("none"));
            String bound = plan.path("bound_vars").path(e.getKey()).asText(null);
            if (bound != null) {
                v.put("bound_value", bound);
                previewValues.put(e.getKey(), bound);
            } else if (spec.hasNonNull("default_value")) {
                previewValues.put(e.getKey(), spec.path("default_value").asText());
            } else {
                unresolved.add(e.getKey());
            }
            vars.put(e.getKey(), v);
        });
        p.put("vars", vars);
        p.put("unresolved_vars", unresolved);
        if (executable && unresolved.isEmpty()) {
            JsonNode payload = substitute(plan.path("payload_template").deepCopy(), previewValues);
            if (DlpRuleValidator.isDlpRuntime(payload.path("runtime").asText())) {
                ((ObjectNode) payload).put("intent_action", "allow");
                ((ObjectNode) payload).put("risk_score", 0);
                p.put("normalized", true);
                p.put("normalized_note", "DLP 类规则由平台强制 risk=0 / intent=allow（脱敏检测语义）");
            }
            p.put("payload_preview", JSON.convertValue(payload, Map.class));
            Map<String, Object> covering = findCoveringRule(rules, payload, agentApp);
            if (covering != null) {
                p.put("equivalent_rule", Map.of(
                        "rule_id", String.valueOf(covering.get("rule_id")),
                        "rollout_state", String.valueOf(covering.get("rollout_state")),
                        "intent_action", String.valueOf(covering.get("intent_action"))));
            }
            List<Map<String, Object>> similar =
                    findSimilarRules(rules, payload, agentApp, ruleId(finding, riskId));
            if (!similar.isEmpty()) {
                p.put("similar_rules", similar);
            }
        }
        Map<String, Object> existing = existingRule(tenantId, ruleId(finding, riskId));
        if (existing != null) {
            p.put("existing_rule", Map.of(
                    "rule_id", existing.get("rule_id"),
                    "rollout_state", existing.get("rollout_state"),
                    "enforce_mode", existing.get("enforce_mode")));
        }
        return p;
    }

    private JsonNode fetchReport(String tenantId, String reportId) {
        ResponseEntity<String> resp = proxy.forwardGet(
                tenantId, "/api/v1/evaluations/reports/" + reportId);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new BusinessException(resp.getStatusCode().value(),
                    "评估报告读取失败: " + firstLine(resp.getBody()));
        }
        try {
            JsonNode root = JSON.readTree(resp.getBody());
            JsonNode data = root.has("data") ? root.path("data") : root;
            if (data.isMissingNode() || data.isNull()) {
                throw new BusinessException(502, "评估报告响应缺少 data");
            }
            return data;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "评估报告解析失败: " + e.getMessage());
        }
    }

    private static Map<String, JsonNode> indexFindings(JsonNode report) {
        Map<String, JsonNode> byRisk = new LinkedHashMap<>();
        for (JsonNode f : report.path("findings")) {
            String id = f.path("risk_id").asText(null);
            if (id != null) {
                byRisk.put(id, f);
            }
        }
        return byRisk;
    }

    private static JsonNode findPlan(JsonNode finding, String planId) {
        for (JsonNode plan : finding.path("remediations")) {
            if (plan.path("plan_id").asText().equals(planId)) {
                return plan;
            }
        }
        return null;
    }

    private Map<String, Object> existingRule(String tenantId, String ruleId) {
        try {
            return ruleService.getRule(tenantId, ruleId);
        } catch (IllegalArgumentException | BusinessException e) {
            return null;
        }
    }

    private List<String> appCandidates(String tenantId) {
        LinkedHashSet<String> apps = new LinkedHashSet<>();
        try {
            for (Map<String, Object> rule : ruleService.listRules(tenantId, null)) {
                Object scope = rule.get("scope");
                if (scope instanceof Map<?, ?> s && s.get("bind_ref") instanceof Map<?, ?> ref) {
                    Object ids = ref.get("app_ids");
                    if (ids instanceof List<?> list) {
                        list.forEach(id -> {
                            if (id != null && !String.valueOf(id).isBlank()) {
                                apps.add(String.valueOf(id));
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            log.debug("app candidates scan failed: {}", e.toString());
        }
        return new ArrayList<>(apps);
    }

    private void requireLicenseActive(String tenantId) {
        EvaluationProxyController.Status status;
        try {
            status = proxy.status(tenantId, null);
        } catch (Exception e) {
            return;
        }
        EvaluationProxyController.LicenseInfo license = status.license();
        if (license != null && Boolean.FALSE.equals(license.active())) {
            throw new BusinessException(LICENSE_INACTIVE_CODE,
                    "评估许可已到期/失效，一键处置被禁止（license mode=" + license.mode() + "）");
        }
    }

    private void audit(String tenantId, String reportId, ApplyItem item, String ruleId,
            String action, String dedupLayer, JsonNode finding) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_id", UUID.randomUUID().toString());
            event.put("trace_id", reportId);
            event.put("tenant_id", tenantId);
            event.put("scene", "remediation_apply");
            event.put("layer", "control");
            event.put("tool_name", item.planId());
            event.put("rule_id", ruleId);
            event.put("effective_action", action);
            event.put("dedup_layer", dedupLayer);
            event.put("session_id", item.riskId());
            var principal = ApiKeyAuthContext.current();
            if (principal != null) {
                event.put("user_id", principal.label() != null ? principal.label()
                        : principal.credentialId());
            }
            audit.ingestEvent(event);
        } catch (Exception e) {
            log.warn("remediation audit ingest failed: {}", e.toString());
        }
    }

    private static String ruleId(JsonNode finding, String riskId) {
        String check = finding.path("check_id").asText("unknown");
        String r = riskId == null ? "00000000" : riskId;
        return "eval-" + check + "-" + r.substring(0, Math.min(8, r.length()));
    }

    private static Map<String, Object> result(ApplyItem item, String status, String ruleId, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("risk_id", item.riskId());
        r.put("plan_id", item.planId());
        r.put("status", status);
        if (ruleId != null) {
            r.put("rule_id", ruleId);
        }
        if (error != null) {
            r.put("error", error);
        }
        return r;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "unknown";
        }
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
