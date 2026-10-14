package io.virbius.engine.api;

import io.virbius.engine.audit.AuditWriter;
import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Output review over the evaluate pipeline ({@code role=output}).
 *
 * <p>Two-stage judgment, mirroring the former chat-embedded review:
 * <ol>
 *   <li><b>Deterministic content rules</b> ({@link OutputContentRules}) — code /
 *       SQL / template injection, XSS, credential leak. A hit denies immediately
 *       (an audit event with {@code scene=output} is still written so the
 *       output-review statistics and the alert pipeline see it).</li>
 *   <li><b>Policy pipeline</b> — {@link EvaluateOrchestrator#evaluate} with
 *       {@code role=output} (zero side effects: no session-risk update, no
 *       challenge, no counters, no tool-call history).</li>
 * </ol>
 *
 * <p>The verdict is translated into a disposal action
 * ({@code reject|omit|redact|warn}) by {@link OutputActionResolver} (configure
 * per reason code via {@code virbius.output-review.action-map}); {@code redact}
 * also returns the masked text from {@link OutputMasker} as
 * {@code sanitized_content}. Callers (the official SDK or a raw API client)
 * apply the disposal verbatim — the mapping lives here so every caller behaves
 * identically.
 */
@Service
public class OutputReviewService {

    /** Risk score attached to deterministic content-rule hits (aligns with the alert threshold). */
    static final int CONTENT_RULE_RISK = 80;

    private final EvaluateOrchestrator orchestrator;
    private final OutputContentRules contentRules;
    private final OutputActionResolver actionResolver;
    private final AuditWriter auditWriter;

    public OutputReviewService(
            EvaluateOrchestrator orchestrator,
            OutputContentRules contentRules,
            OutputActionResolver actionResolver,
            AuditWriter auditWriter) {
        this.orchestrator = orchestrator;
        this.contentRules = contentRules;
        this.actionResolver = actionResolver;
        this.auditWriter = auditWriter;
    }

    public EvaluateResponseDto review(EvaluateRequestDto req) {
        List<OutputContentRules.Hit> hits = contentRules.scan(req.content());
        if (!hits.isEmpty()) {
            OutputContentRules.Hit hit = hits.get(0);
            String disposal = actionResolver.resolve(hit.reasonCode(), true);
            String sanitized = OutputActionResolver.REDACT.equals(disposal)
                    ? OutputMasker.redact(req.content()) : null;
            auditWriter.write(req, new EngineDecisionDto("deny", CONTENT_RULE_RISK, "full"),
                    null, 0, hit.reasonCode(), false);
            return new EvaluateResponseDto(
                    "deny", CONTENT_RULE_RISK, 0, null, 0, hit.reasonCode(),
                    req.traceId(), false, "full", null, null, disposal, sanitized);
        }

        EvaluateResponseDto resp = orchestrator.evaluate(req);
        boolean blocked = "deny".equalsIgnoreCase(resp.effectiveAction())
                || "block".equalsIgnoreCase(resp.effectiveAction());
        String disposal = actionResolver.resolve(resp.reasonCode(), blocked);
        String sanitized = blocked && OutputActionResolver.REDACT.equals(disposal)
                ? OutputMasker.redact(req.content()) : null;
        return new EvaluateResponseDto(
                resp.effectiveAction(), resp.maxRiskScore(), resp.sessionRiskScore(),
                resp.ruleId(), resp.ruleRevision(), resp.reasonCode(), resp.traceId(),
                resp.degraded(), resp.enforceMode(), resp.challengeId(), resp.argsHash(),
                disposal, sanitized);
    }
}
