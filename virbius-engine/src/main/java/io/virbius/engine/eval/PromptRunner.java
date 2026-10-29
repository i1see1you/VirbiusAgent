package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptJudgeProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.config.TenantAwareTaskExecutor;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import io.virbius.engine.eval.judge.PromptJudgeBackend;
import io.virbius.engine.eval.judge.PromptJudgeBackendRegistry;
import io.virbius.engine.eval.judge.PromptJudgeSupport;
import io.virbius.policy.MatchContext;
import io.virbius.policy.prompt.PromptRuleKind;
import io.virbius.policy.prompt.PromptRuleParser;
import io.virbius.policy.prompt.PromptRuleSpec;
import io.virbius.policy.prompt.PromptRuleValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PromptRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private record JudgeRuleContext(PromptRuleSpec spec, String parseError) {}

    private final RuleCache cache;
    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;
    private final TenantAwareTaskExecutor tenantTaskExecutor;
    private final AsyncActionHandler asyncActionHandler;
    private final PromptJudgeBackendRegistry judgeRegistry;
    private final PromptJudgeProperties judgeProps;

    public PromptRunner(
            RuleCache cache,
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser,
            TenantAwareTaskExecutor tenantTaskExecutor,
            AsyncActionHandler asyncActionHandler,
            PromptJudgeBackendRegistry judgeRegistry,
            PromptJudgeProperties judgeProps) {
        this.cache = cache;
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
        this.tenantTaskExecutor = tenantTaskExecutor;
        this.asyncActionHandler = asyncActionHandler;
        this.judgeRegistry = judgeRegistry;
        this.judgeProps = judgeProps;
    }

    public List<SignalDto> run(String tenantId, MatchContext matchCtx) {
        if (matchCtx == null || matchCtx.content() == null || matchCtx.content().isBlank()) {
            return List.of();
        }
        List<RuleEntry> allPromptRules = promptRulesBound(tenantId, matchCtx);
        if (allPromptRules.isEmpty()) {
            return List.of();
        }
        Map<RuleEntry, JudgeRuleContext> judgeRules = new LinkedHashMap<>();
        List<RuleEntry> categoryRules = new ArrayList<>();
        for (RuleEntry rule : allPromptRules) {
            if (PromptRuleKind.isBuiltInCategory(rule.ruleId())) {
                categoryRules.add(rule);
            } else {
                judgeRules.put(rule, parseJudgeRule(rule));
            }
        }

        List<RuleEntry> syncCategory = new ArrayList<>();
        List<RuleEntry> asyncCategory = new ArrayList<>();
        for (RuleEntry rule : categoryRules) {
            (rule.isAsync() ? asyncCategory : syncCategory).add(rule);
        }
        Map<RuleEntry, JudgeRuleContext> syncJudge = new LinkedHashMap<>();
        Map<RuleEntry, JudgeRuleContext> asyncJudge = new LinkedHashMap<>();
        judgeRules.forEach((rule, judge) -> (rule.isAsync() ? asyncJudge : syncJudge).put(rule, judge));

        if (!asyncCategory.isEmpty() || !asyncJudge.isEmpty()) {
            tenantTaskExecutor.submit(
                    tenantId, () -> runPromptAsync(tenantId, matchCtx, asyncCategory, asyncJudge));
        }

        List<SignalDto> signals = new ArrayList<>();
        if (!syncCategory.isEmpty()) {
            signals.addAll(runMatrixLlm(matchCtx.content(), syncCategory));
        }
        if (!syncJudge.isEmpty()) {
            signals.addAll(runJudge(tenantId, matchCtx.content(), syncJudge));
        }
        return signals;
    }

    private void runPromptAsync(
            String tenantId,
            MatchContext matchCtx,
            List<RuleEntry> categoryRules,
            Map<RuleEntry, JudgeRuleContext> judgeRules) {
        try {
            List<SignalDto> signals = new ArrayList<>();
            if (!categoryRules.isEmpty()) {
                signals.addAll(runMatrixLlm(matchCtx.content(), categoryRules));
            }
            if (!judgeRules.isEmpty()) {
                signals.addAll(runJudge(tenantId, matchCtx.content(), judgeRules));
            }
            List<RuleEntry> allRules = new ArrayList<>(categoryRules);
            allRules.addAll(judgeRules.keySet());
            for (SignalDto signal : signals) {
                RuleEntry matched = null;
                for (RuleEntry r : allRules) {
                    if (r.ruleId().equals(signal.ruleId())) {
                        matched = r;
                        break;
                    }
                }
                if (matched != null) {
                    asyncActionHandler.executeIfConfigured(matched, signal, matchCtx);
                    log.info("async prompt rule hit tenant={} rule={} revision={}",
                            matched.tenantId(), matched.ruleId(), matched.ruleRevision());
                }
            }
        } catch (Exception e) {
            log.warn("async prompt rule eval failed: {}", e.getMessage());
        }
    }

    private List<SignalDto> runMatrixLlm(String content, List<RuleEntry> promptRules) {
        PromptLlmClient.CompleteResult result =
                llmClient.completeDetail(llmProps.systemPrompt(), content);
        String assistant = result.content();
        if (result.error() != null && !result.error().isBlank()) {
            log.warn("prompt-llm error: {}", result.error());
        }
        if (assistant == null || assistant.isBlank()) {
            if (llmProps.failOpen()) {
                log.warn("prompt-llm empty response; fail-open");
                return List.of();
            }
            return fallbackOnLlmError(promptRules, "LLM unavailable");
        }
        PromptAuditResult audit = auditParser.parse(assistant);
        if (!audit.hitRule()) {
            return List.of();
        }

        SignalDto signal = buildSignal(audit.reason(), promptRules, assistant);
        return signal != null ? List.of(signal) : List.of();
    }

    private List<SignalDto> runJudge(
            String tenantId,
            String content,
            Map<RuleEntry, JudgeRuleContext> judgeRules) {
        List<SignalDto> signals = new ArrayList<>();
        Map<RuleEntry, PromptRuleSpec> validRules = new LinkedHashMap<>();
        for (Map.Entry<RuleEntry, JudgeRuleContext> entry : judgeRules.entrySet()) {
            JudgeRuleContext judge = entry.getValue();
            if (judge.parseError() != null) {
                signals.add(invalidJudgeSignal(entry.getKey(), judge.parseError()));
            } else {
                validRules.put(entry.getKey(), judge.spec());
            }
        }
        if (validRules.isEmpty()) {
            return signals;
        }

        PromptJudgeBackend backend = resolveBackend(tenantId, validRules.size());
        if (backend == null) {
            log.warn("prompt judge backend unavailable tenant={}; applying per-rule on_error", tenantId);
            signals.addAll(judgeErrorSignals(validRules, "judge backend unavailable"));
            return signals;
        }

        int batchSize = batchSize(backend);
        List<Map.Entry<RuleEntry, PromptRuleSpec>> entries = new ArrayList<>(validRules.entrySet());
        for (int from = 0; from < entries.size(); from += batchSize) {
            int to = Math.min(entries.size(), from + batchSize);
            List<Map.Entry<RuleEntry, PromptRuleSpec>> batch = entries.subList(from, to);
            List<PromptJudgeBackend.JudgeRule> requestRules = new ArrayList<>();
            for (Map.Entry<RuleEntry, PromptRuleSpec> entry : batch) {
                requestRules.add(new PromptJudgeBackend.JudgeRule(
                        entry.getKey().ruleId(), "noul", PromptJudgeSupport.instructions(entry.getValue())));
            }
            PromptJudgeBackend.JudgeResult result =
                    backend.judge(new PromptJudgeBackend.JudgeRequest(content, requestRules));
            if (result.error() != null && !result.error().isBlank()) {
                log.warn("prompt judge failed tenant={} backend={} error={}",
                        tenantId, backend.id(), result.error());
            }
            for (Map.Entry<RuleEntry, PromptRuleSpec> entry : batch) {
                SignalDto signal = evaluateAnswer(backend.id(), entry.getKey(), entry.getValue(), result, tenantId);
                if (signal != null) {
                    signals.add(signal);
                }
            }
        }
        return signals;
    }

    private SignalDto evaluateAnswer(
            String backendId,
            RuleEntry rule,
            PromptRuleSpec spec,
            PromptJudgeBackend.JudgeResult result,
            String tenantId) {
        PromptJudgeSupport.Outcome outcome =
                PromptJudgeSupport.evaluate(spec, result, rule.ruleId());
        if (outcome.error() != null) {
            return judgeErrorSignal(rule, spec, outcome.error());
        }
        log.info("prompt judge tenant={} rule={} backend={} probability={} threshold={}",
                tenantId, rule.ruleId(), backendId, outcome.probability(), spec.threshold());
        return outcome.signal() ? toSignal(rule, null) : null;
    }

    private List<SignalDto> judgeErrorSignals(
            Map<RuleEntry, PromptRuleSpec> judgeRules, String error) {
        List<SignalDto> out = new ArrayList<>();
        for (Map.Entry<RuleEntry, PromptRuleSpec> entry : judgeRules.entrySet()) {
            SignalDto signal = judgeErrorSignal(entry.getKey(), entry.getValue(), error);
            if (signal != null) {
                out.add(signal);
            }
        }
        return out;
    }

    private SignalDto invalidJudgeSignal(RuleEntry rule, String error) {
        log.warn("invalid prompt judge rule tenant={} rule={} error={}",
                rule.tenantId(), rule.ruleId(), error);
        return toSignal(rule, null);
    }

    private SignalDto judgeErrorSignal(RuleEntry rule, PromptRuleSpec spec, String error) {
        if (spec.onError() == PromptRuleSpec.ErrorPolicy.FAIL_CLOSED) {
            log.warn("prompt judge fail-closed tenant={} rule={} error={}",
                    rule.tenantId(), rule.ruleId(), error);
            return toSignal(rule, null);
        }
        log.warn("prompt judge fail-open tenant={} rule={} error={}",
                rule.tenantId(), rule.ruleId(), error);
        return null;
    }

    private PromptJudgeBackend resolveBackend(String tenantId, int ruleCount) {
        String selected = judgeRegistry.defaultBackendId();
        Optional<PromptJudgeBackend> backend = judgeRegistry.findEnabled(selected);
        if (backend.isPresent()) {
            return backend.get();
        }
        log.warn("prompt judge backend {} is not enabled tenant={} rules={}",
                selected, tenantId, ruleCount);
        return null;
    }

    private int batchSize(PromptJudgeBackend backend) {
        int declaredMax = backend.capabilities().maxRulesPerBatch();
        PromptJudgeProperties.Backend config = judgeProps.backends().get(backend.id());
        int backendLimit = config == null ? 0 : config.maxRulesPerBatch();
        int requested = backendLimit > 0 ? backendLimit : judgeProps.maxRulesPerBatch();
        int limit = declaredMax > 0 ? Math.min(declaredMax, requested) : requested;
        return Math.max(1, limit);
    }

    private JudgeRuleContext parseJudgeRule(RuleEntry rule) {
        try {
            return new JudgeRuleContext(PromptRuleParser.parse(rule.body(), JSON), null);
        } catch (PromptRuleValidationException e) {
            return new JudgeRuleContext(null, e.getMessage());
        }
    }

    private SignalDto buildSignal(String category, List<RuleEntry> promptRules, String raw) {
        if (category != null && !category.isBlank()) {
            Map<String, String> mapping = llmProps.categoryRuleMapping();
            String ruleId = mapping.get(category);
            if (ruleId != null) {
                for (RuleEntry rule : promptRules) {
                    if (rule.ruleId().equals(ruleId)) {
                        return toSignal(rule, raw);
                    }
                }
            }
            log.warn("prompt-llm hit but category '{}' unmapped; using first rule", category);
        }
        return toSignal(promptRules.get(0), raw);
    }

    private List<SignalDto> fallbackOnLlmError(List<RuleEntry> promptRules, String reason) {
        log.warn("prompt-llm fail-closed: {}", reason);
        RuleEntry top = promptRules.get(0);
        return List.of(toSignal(top, reason));
    }

    private List<RuleEntry> promptRulesBound(String tenantId, MatchContext matchCtx) {
        List<RuleEntry> out = new ArrayList<>();
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!"prompt".equals(rule.runtime())) {
                continue;
            }
            if (RuleScopeSupport.matchesBind(rule, matchCtx)) {
                out.add(rule);
            }
        }
        return out;
    }

    private static SignalDto toSignal(RuleEntry rule, String auditRaw) {
        return new SignalDto(
                rule.ruleId(),
                rule.ruleRevision(),
                "cloud",
                "cloud",
                rule.riskScore(),
                rule.reasonCode(),
                rule.intentAction(),
                rule.enforceMode(),
                rule.canaryPercent() > 0 ? rule.canaryPercent() : null,
                null);
    }
}
