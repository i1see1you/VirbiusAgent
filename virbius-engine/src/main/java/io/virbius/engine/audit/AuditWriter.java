package io.virbius.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.policy.ActionMerge;
import io.virbius.policy.audit.AuditEventPublisher;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);
    private static final Logger auditLog = LoggerFactory.getLogger("virbius.audit.events");
    private static final Logger allowLog = LoggerFactory.getLogger("virbius.audit.allow");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditEventPublisher publisher;
    private final RuleCache ruleCache;
    private final double auditSampleRateAllow;

    public AuditWriter(
            @Value("${virbius.audit.sample-rate-allow:0.1}") double auditSampleRateAllow,
            AuditEventPublisher publisher,
            RuleCache ruleCache) {
        this.auditSampleRateAllow = auditSampleRateAllow;
        this.publisher = publisher;
        this.ruleCache = ruleCache;
    }

    public void write(
            EvaluateRequestDto req,
            EngineDecisionDto decision,
            String ruleId,
            int ruleRevision,
            String reasonCode,
            boolean degraded) {
        try {
            RuleEntry rule = ruleId != null ? ruleCache.get(req.tenantId(), ruleId) : null;
            String rolloutState = rule != null ? rule.rolloutStateOrDefault() : "dry_run";
            Integer canaryPercent = null;
            Boolean inBucket = null;
            if (rule != null && "canary".equalsIgnoreCase(rolloutState) && rule.canaryPercent() > 0) {
                canaryPercent = rule.canaryPercent();
                inBucket = ActionMerge.inCanaryBucket(req.sessionId(), canaryPercent);
            }

            Map<String, Object> event = new HashMap<>();
            event.put("event_id", UUID.randomUUID().toString());
            event.put("trace_id", req.traceId());
            event.put("trace_id_source", "client");
            event.put("tenant_id", req.tenantId());
            event.put("scene", req.scene());
            event.put("layer", "cloud");
            event.put("rule_id", ruleId != null ? ruleId : "");
            event.put("rule_revision", ruleRevision);
            event.put("reason_code", reasonCode != null ? reasonCode : "");
            event.put("effective_action", decision.effectiveAction());
            event.put("max_risk_score", decision.maxRiskScore());
            event.put("rollout_state", rolloutState);
            if (canaryPercent != null) {
                event.put("canary_percent", canaryPercent);
            }
            if (inBucket != null) {
                event.put("in_canary_bucket", inBucket);
            }
            event.put("degraded", degraded);
            event.put("intercepted_at", Instant.now().toString());
            if (req.userId() != null && !req.userId().isBlank()) {
                event.put("user_id", req.userId());
            }
            if (req.deviceId() != null && !req.deviceId().isBlank()) {
                event.put("device_id", req.deviceId());
            }
            boolean isAllow = "allow".equalsIgnoreCase(decision.effectiveAction());
            if (isAllow && auditSampleRateAllow > 0) {
                boolean sampled = ThreadLocalRandom.current().nextDouble() < auditSampleRateAllow;
                if (sampled) {
                    event.put("sampled_allow", true);
                    event.put("sample_rate_allow", auditSampleRateAllow);
                }
            }
            String json = mapper.writeValueAsString(event);
            if (isAllow) {
                allowLog.info(json);
            } else {
                auditLog.info(json);
            }
            if (!isAllow || event.containsKey("sampled_allow")) {
                publisher.publish(Map.of("payload", json));
            }
        } catch (Exception e) {
            log.warn("audit write failed: {}", e.getMessage());
        }
    }
}
