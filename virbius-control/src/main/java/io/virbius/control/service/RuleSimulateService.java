package io.virbius.control.service;

import io.virbius.control.domain.RolloutEnforceExport;
import io.virbius.control.gateway.RuleBindScopeValidator;
import io.virbius.control.groovy.GroovyRuleBodies;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.ruleauthoring.ConditionCompiler;
import io.virbius.control.ruleauthoring.ConditionEvaluator;
import io.virbius.control.ruleauthoring.ConditionParser;
import io.virbius.control.ruleauthoring.ContextVarResolver;
import io.virbius.control.gateway.SceneRegistryHelper;
import io.virbius.groovy.l3.GroovyL3Executor;
import io.virbius.groovy.l3.L3RuleView;
import io.virbius.groovy.l3.L3SignalView;
import io.virbius.groovy.l3.PolicyContext;
import io.virbius.groovy.l3.ScriptEnvironment;
import io.virbius.policy.BindScope;
import io.virbius.policy.MatchContext;
import io.virbius.policy.SceneRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RuleSimulateService {

    private final RegistryRepository store;
    private final ListMetaRepository listMetaRepo;
    private final PromptSimulateClient promptSimulateClient;
    private final GroovyL3Executor groovyExecutor = new GroovyL3Executor();

    public RuleSimulateService(
            RegistryRepository store, ListMetaRepository listMetaRepo, PromptSimulateClient promptSimulateClient) {
        this.store = store;
        this.listMetaRepo = listMetaRepo;
        this.promptSimulateClient = promptSimulateClient;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> simulate(String tenantId, Map<String, Object> request) {
        Map<String, Object> rule = map(request.get("rule"));
        Map<String, Object> fixture = map(request.get("fixture"));
        Map<String, Object> options = map(request.get("options"));
        String editorMode = str(request.get("editor_mode"));
        Map<String, Object> condition = map(request.get("condition"));

        String layer = str(rule.get("layer"));
        String runtime = str(rule.get("runtime"));
        Map<String, Object> scope = map(rule.get("scope"));
        boolean promptRuntime = "prompt".equalsIgnoreCase(runtime);
        String script = promptRuntime
                ? GroovyRuleBodies.asScript(rule.get("body"))
                : resolveScript(layer, runtime, editorMode, condition, rule.get("body"));

        String bundleId = str(rule.get("bundle_id"));
        if (bundleId == null || bundleId.isBlank()) {
            bundleId = "poc-default";
        }
        Map<String, Object> metadata = store.getBundle(
                        tenantId, bundleId, RuleBindScopeValidator.defaultBundleVersion())
                .map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                .orElse(Map.of());

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, String> headers = stringMap(fixture.get("headers"));
        Map<String, String> query = stringMap(fixture.get("query"));
        Map<String, String> fixtureVars = stringMap(fixture.get("vars"));
        var bindings = store.listContextBindings(tenantId, bundleId, RuleBindScopeValidator.defaultBundleVersion());
        Map<String, String> vars = ContextVarResolver.resolve(bindings, headers, query, fixtureVars);
        steps.add(step("vars", true, Map.of("vars", vars)));

        String routeUri = str(fixture.get("route_uri"));
        if (routeUri == null) {
            routeUri = "/v1/chat/completions";
        }
        String content = str(fixture.get("content"));
        String userId = header(headers, "X-Virbius-User-Id");
        SceneRegistry registry = SceneRegistryHelper.parseRegistry(metadata);
        String appId = vars.get("app_id");
        String sceneId = null;
        String sceneSource = null;
        if (appId != null && !appId.isBlank()) {
            var resolved = registry.resolve(appId, routeUri, query, headers);
            if (resolved.isPresent()) {
                sceneId = resolved.get().sceneId();
                sceneSource = resolved.get().source();
            }
        }
        steps.add(step(
                "scene",
                sceneId != null,
                Map.of("scene_id", sceneId != null ? sceneId : "", "source", sceneSource != null ? sceneSource : "")));

        MatchContext matchCtx = new MatchContext(
                content,
                userId,
                header(headers, "X-Virbius-Device-Id"),
                header(headers, "X-Forwarded-For"),
                header(headers, "X-Virbius-Session-Id"),
                vars,
                query,
                headers,
                sceneId,
                routeUri,
                null,
                null,
                null);

        boolean bindMatched = BindScope.matches(
                BindScope.scopeFromRuleScope(scope), BindScope.bindRefFromScope(scope), matchCtx);
        steps.add(step("bind", bindMatched, Map.of(
                "matched", bindMatched,
                "bind_scope", BindScope.scopeFromRuleScope(scope))));

        boolean hit = false;
        boolean wouldExecute = bindMatched;
        String decideError = null;
        Map<String, Object> decideDetail = new LinkedHashMap<>();
        if (bindMatched) {
            try {
                if (promptRuntime) {
                    hit = evaluatePromptDecide(rule, matchCtx, decideDetail);
                } else {
                    hit = evaluateDecide(
                            tenantId, layer, runtime, script, condition, matchCtx, fixture, rule, metadata);
                }
            } catch (Exception e) {
                decideError = e.getMessage();
                wouldExecute = false;
            }
        }
        decideDetail.put("result", hit);
        decideDetail.put("skipped", !bindMatched);
        if (decideError != null) {
            decideDetail.put("error", decideError);
        }
        steps.add(step("decide", decideError == null, decideDetail));

        String intent = str(rule.get("intent_action"));
        if (intent == null) {
            intent = "deny";
        }
        int risk = intVal(rule.get("risk_score"), 100);
        String rolloutState = str(rule.get("rollout_state"));
        if (rolloutState == null) {
            rolloutState = "dry_run";
        }
        String effective = effectiveAction(hit, intent, rolloutState);
        steps.add(step("signal", true, Map.of(
                "intent_action", intent,
                "risk_score", risk,
                "rollout_state", rolloutState,
                "enforce_mode", RolloutEnforceExport.enforceMode(rolloutState),
                "effective_action", effective)));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("would_execute", wouldExecute && bindMatched);
        summary.put("hit", hit && bindMatched);
        summary.put("effective_action", bindMatched && hit ? effective : "allow");
        summary.put(
                "message",
                buildSummary(bindMatched, hit, effective, rolloutState, promptRuntime, decideDetail));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", decideError == null);
        out.put("compiled_script", script);
        out.put("steps", steps);
        out.put("summary", summary);
        if (decideError != null) {
            out.put("errors", List.of(decideError));
        }
        return out;
    }

    private boolean evaluatePromptDecide(
            Map<String, Object> rule, MatchContext matchCtx, Map<String, Object> decideDetail) throws Exception {
        String ruleId = str(rule.get("rule_id"));
        if (ruleId == null || ruleId.isBlank()) {
            ruleId = "draft-preview";
        }
        String body = GroovyRuleBodies.asScript(rule.get("body"));
        String reasonCode = str(rule.get("reason_code"));
        Map<String, Object> llm = promptSimulateClient.simulate(ruleId, body, reasonCode, matchCtx.content());
        putPromptLlmDetail(decideDetail, llm);
        Object err = llm.get("error");
        if (err != null && !String.valueOf(err).isBlank()) {
            throw new IllegalStateException(String.valueOf(err));
        }
        return Boolean.TRUE.equals(llm.get("hit"));
    }

    private static void putPromptLlmDetail(Map<String, Object> decideDetail, Map<String, Object> llm) {
        decideDetail.put("llm_hit_rule", llm.get("llm_hit_rule"));
        decideDetail.put("triggered_id", llm.get("triggered_id"));
        decideDetail.put("reason", llm.get("reason"));
        if (isAbnormalLlmResponse(llm)) {
            Object raw = llm.get("llm_response");
            if (raw != null && !String.valueOf(raw).isBlank()) {
                decideDetail.put("llm_response", raw);
            }
        }
    }

    private static boolean isAbnormalLlmResponse(Map<String, Object> llm) {
        Object err = llm.get("error");
        if (err != null && !String.valueOf(err).isBlank()) {
            return true;
        }
        String raw = str(llm.get("llm_response"));
        return raw != null && !raw.isBlank() && !raw.contains("hit_rule");
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateDecide(
            String tenantId,
            String layer,
            String runtime,
            String script,
            Map<String, Object> condition,
            MatchContext matchCtx,
            Map<String, Object> fixture,
            Map<String, Object> rule,
            Map<String, Object> metadata)
            throws Exception {
        Map<String, Long> cumOverrides = cumulativeOverrides(fixture);
        Map<String, Object> overrides = map(fixture.get("overrides"));
        boolean forceList = overrides != null && Boolean.TRUE.equals(overrides.get("force_list_hit"));

        Map<String, Object> parsed = condition;
        if (parsed == null || parsed.isEmpty()) {
            parsed = map(ConditionParser.parse(layer, runtime, script).get("condition"));
        }
        if (parsed != null && !parsed.isEmpty()) {
            if ("groovy".equalsIgnoreCase(runtime) && isL3Aggregate(parsed)) {
                PolicyContext ctx = buildGroovyContext(tenantId, matchCtx, rule, fixture);
                return groovyExecutor.executeDecide(script, ctx);
            }
            return ConditionEvaluator.evaluate(
                    parsed, matchCtx, tenantId, listMetaRepo, cumOverrides, forceList);
        }
        if ("groovy".equalsIgnoreCase(runtime)) {
            PolicyContext ctx = buildGroovyContext(tenantId, matchCtx, rule, fixture);
            return groovyExecutor.executeDecide(script, ctx);
        }
        throw new IllegalArgumentException("advanced lua simulate requires parseable condition");
    }

    private PolicyContext buildGroovyContext(
            String tenantId, MatchContext matchCtx, Map<String, Object> rule, Map<String, Object> fixture) {
        String ruleId = str(rule.get("rule_id"));
        if (ruleId == null) {
            ruleId = "simulate";
        }
        String rollout = str(rule.get("rollout_state"));
        if (rollout == null) {
            rollout = "dry_run";
        }
        L3RuleView rv = new L3RuleView(
                ruleId,
                1,
                RolloutEnforceExport.enforceMode(rollout),
                rule.get("canary_percent") instanceof Number n ? n.intValue() : 100,
                intVal(rule.get("risk_score"), 100));
        Map<String, L3RuleView> rules = Map.of(ruleId, rv);
        List<L3SignalView> signals = priorSignals(fixture);
        ScriptEnvironment env = buildScriptEnv(tenantId, matchCtx, fixture);
        return new PolicyContext(
                tenantId,
                matchCtx.sessionId() != null ? matchCtx.sessionId() : "simulate",
                matchCtx.scene() != null ? matchCtx.scene() : "",
                ruleId,
                rules,
                signals,
                matchCtx.vars(),
                env);
    }

    private ScriptEnvironment buildScriptEnv(String tenantId, MatchContext matchCtx, Map<String, Object> fixture) {
        Map<String, ScriptEnvironment.ListDefinition> lists = new LinkedHashMap<>();
        for (var meta : listMetaRepo.listMeta(tenantId)) {
            List<String> entries = listMetaRepo.listEntries(tenantId, meta.listName()).stream()
                    .map(e -> e.value())
                    .toList();
            lists.put(
                    meta.listName(),
                    new ScriptEnvironment.ListDefinition(
                            meta.listName(), meta.dimension(), entries, null));
        }
        Map<String, Long> cumOverrides = cumulativeOverrides(fixture);
        ScriptEnvironment.CumulativeReader reader = (t, name, value, wMin, kind, zone) -> cumOverrides.getOrDefault(name, 0L);
        return new ScriptEnvironment(tenantId, matchCtx, lists, Map.of(), reader);
    }

    @SuppressWarnings("unchecked")
    private List<L3SignalView> priorSignals(Map<String, Object> fixture) {
        Object raw = fixture.get("prior_signals");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<L3SignalView> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> row = (Map<String, Object>) m;
                out.add(new L3SignalView(
                        str(row.get("rule_id")),
                        1,
                        "cloud",
                        intVal(row.get("risk_score"), 100),
                        "deny".equalsIgnoreCase(str(row.get("intent_action"))) ? "block" : str(row.get("intent_action")),
                        str(row.get("reason_code"))));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> cumulativeOverrides(Map<String, Object> fixture) {
        Map<String, Object> overrides = map(fixture.get("overrides"));
        if (overrides == null) {
            return Map.of();
        }
        Object cum = overrides.get("cumulative");
        if (!(cum instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v instanceof Number n) {
                out.put(String.valueOf(k), n.longValue());
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean isL3Aggregate(Map<String, Object> condition) {
        if ("l3_aggregate".equals(str(condition.get("type")))) {
            return true;
        }
        if ("and".equals(str(condition.get("op"))) || "or".equals(str(condition.get("op")))) {
            Object children = condition.get("children");
            if (children instanceof List<?> list) {
                for (Object child : list) {
                    if (child instanceof Map<?, ?> m && isL3Aggregate((Map<String, Object>) m)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String resolveScript(
            String layer, String runtime, String editorMode, Map<String, Object> condition, Object body) {
        if ("simple".equalsIgnoreCase(editorMode) && condition != null && !condition.isEmpty()) {
            return String.valueOf(ConditionCompiler.compile(layer, runtime, condition).get("script"));
        }
        return GroovyRuleBodies.asScript(body);
    }

    private static String effectiveAction(boolean hit, String intent, String rolloutState) {
        if (!hit) {
            return "allow";
        }
        if ("dry_run".equalsIgnoreCase(rolloutState)) {
            return "review";
        }
        if ("deny".equalsIgnoreCase(intent)) {
            return "block";
        }
        if ("captcha".equalsIgnoreCase(intent)) {
            return "captcha";
        }
        return intent != null ? intent.toLowerCase(Locale.ROOT) : "review";
    }

    private static String buildSummary(
            boolean bindMatched,
            boolean hit,
            String effective,
            String rollout,
            boolean promptRuntime,
            Map<String, Object> decideDetail) {
        if (!bindMatched) {
            return promptRuntime ? "bind_scope did not match, will not invoke 1B" : "bind_scope did not match, script will not execute";
        }
        if (!hit) {
            Object llmErr = decideDetail.get("error");
            if (promptRuntime && llmErr != null && !String.valueOf(llmErr).isBlank()) {
                return "bind matched but 1B call failed: " + llmErr;
            }
            if (promptRuntime && Boolean.TRUE.equals(decideDetail.get("llm_hit_rule"))) {
                return "bind matched but triggered_id is not this rule, draft did not hit";
            }
            return promptRuntime ? "bind matched but 1B did not trigger this rule" : "bind matched but decide=false, rule does not trigger";
        }
        if ("dry_run".equalsIgnoreCase(rollout)) {
            return "rule hit; under dry_run effective_action=" + effective + ", not blocking";
        }
        return "rule hit; effective_action=" + effective;
    }

    private static Map<String, Object> step(String id, boolean ok, Map<String, Object> detail) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("ok", ok);
        s.put("detail", detail);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object o) {
        Map<String, Object> m = map(o);
        if (m == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v != null) {
                out.put(k, String.valueOf(v));
            }
        });
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static int intVal(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return def;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
