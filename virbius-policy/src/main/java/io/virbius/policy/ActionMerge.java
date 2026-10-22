package io.virbius.policy;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Merge rule hits by intent_action priority + shared enforce_mode. */
public final class ActionMerge {

    public record RuleHit(
            String ruleId,
            int ruleRevision,
            String reasonCode,
            int riskScore,
            String intentAction,
            String enforceMode,
            Integer canaryPercent) {}

    /**
     * Merge result.
     *
     * @param suppressed true only when an allow hit overrode an enforceable
     *                   deny/challenge (FP-suppression). Allow-only hits that
     *                   merely attribute to an allow rule leave this false so
     *                   the audit sampler keeps routine benign traffic sampled.
     */
    public record MergeResult(String effectiveAction, int maxRiskScore, RuleHit primary, boolean suppressed) {}

    private ActionMerge() {}

    public static boolean inCanaryBucket(String sessionId, int percent) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(StandardCharsets.UTF_8));
        long bucket = crc.getValue() % 100;
        return bucket < percent;
    }

    public static MergeResult merge(List<RuleHit> hits, String sessionId) {
        if (hits == null || hits.isEmpty()) {
            return new MergeResult(IntentAction.ALLOW, 0, null, false);
        }
        // Allow hits only suppress when their enforcement is effective
        // (full, or canary with the session in bucket). A dry_run allow must
        // NOT hard-pass — it is an exception candidate under observation and
        // may not weaken any enforcement until promoted. Enforce-blank hits
        // were normalized to "dry_run" upstream, so the default is conservative.
        List<RuleHit> allowHits = hits.stream()
                .filter(h -> IntentAction.isAllowIntent(h.intentAction()))
                .toList();
        if (!allowHits.isEmpty() && effectiveEnforce(allowHits, sessionId)) {
            // Attribution: surface the strongest overridden enforcement hit as
            // primary so audits record (allow, deny-rule) instead of losing the
            // suppressed rule behind POLICY_ALLOW. Only deny/challenge hits are
            // enforcement; review hits never blocked and are not attribution.
            RuleHit topSuppressed = hits.stream()
                    .filter(h -> !IntentAction.isAllowIntent(h.intentAction()))
                    .filter(h -> IntentAction.priority(h.intentAction()) >= 50)
                    .max(Comparator.comparingInt(RuleHit::riskScore)
                            .thenComparingInt(RuleHit::ruleRevision)
                            .thenComparing(RuleHit::ruleId))
                    .orElse(null);
            if (topSuppressed != null) {
                return new MergeResult(IntentAction.ALLOW, 0, topSuppressed, true);
            }
            // Allow-only: nothing was overridden, but the release decision was
            // still caused by a specific allow rule. Attribute to the strongest
            // *enforced* allow hit (never a dry_run/out-of-bucket candidate)
            // so the operations console can name the rule instead of
            // POLICY_ALLOW. suppressed stays false: no enforcement was
            // overridden, so routine benign traffic stays on normal sampling.
            RuleHit attribution = allowHits.stream()
                    .filter(h -> isEffectivelyEnforced(h, sessionId))
                    .max(Comparator.comparingInt(RuleHit::riskScore)
                            .thenComparingInt(RuleHit::ruleRevision)
                            .thenComparing(RuleHit::ruleId))
                    .orElse(null);
            return new MergeResult(IntentAction.ALLOW, 0, attribution, false);
        }
        int maxPriority = hits.stream().mapToInt(h -> IntentAction.priority(h.intentAction())).max().orElse(0);
        if (maxPriority <= 0) {
            return new MergeResult(IntentAction.ALLOW, 0, null, false);
        }
        List<RuleHit> top = hits.stream()
                .filter(h -> IntentAction.priority(h.intentAction()) == maxPriority)
                .toList();
        int maxRiskScore = top.stream().mapToInt(RuleHit::riskScore).max().orElse(0);
        RuleHit primary = pickPrimary(top);
        String intent = IntentAction.normalize(primary.intentAction(), primary.riskScore());
        boolean effective = effectiveEnforce(top, sessionId);
        String action =
                switch (intent) {
                    case IntentAction.DENY -> effective ? "block" : "review";
                    case IntentAction.CHALLENGE -> effective ? "challenge" : "review";
                    case IntentAction.REVIEW -> "review";
                    default -> IntentAction.ALLOW;
                };
        return new MergeResult(action, maxRiskScore, primary, false);
    }

    private static boolean effectiveEnforce(List<RuleHit> hits, String sessionId) {
        if (hits.stream().anyMatch(h -> isFull(h.enforceMode()))) {
            return true;
        }
        return hits.stream().anyMatch(h -> isCanaryEffective(h, sessionId));
    }

    private static boolean isEffectivelyEnforced(RuleHit hit, String sessionId) {
        return isFull(hit.enforceMode()) || isCanaryEffective(hit, sessionId);
    }

    private static boolean isFull(String mode) {
        return "full".equalsIgnoreCase(normalizeMode(mode));
    }

    private static boolean isCanaryEffective(RuleHit hit, String sessionId) {
        if (!"canary".equalsIgnoreCase(normalizeMode(hit.enforceMode()))) {
            return false;
        }
        int pct = hit.canaryPercent() != null ? hit.canaryPercent() : 0;
        return inCanaryBucket(sessionId, pct);
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "dry_run" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static RuleHit pickPrimary(List<RuleHit> hits) {
        return hits.stream()
                .max(Comparator.comparingInt(RuleHit::riskScore)
                        .thenComparingInt(RuleHit::ruleRevision)
                        .thenComparing(RuleHit::ruleId))
                .orElse(null);
    }
}
