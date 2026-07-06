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

    public record MergeResult(String effectiveAction, int maxRiskScore, RuleHit primary) {}

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
            return new MergeResult(IntentAction.ALLOW, 0, null);
        }
        if (hits.stream().anyMatch(h -> IntentAction.isAllowIntent(h.intentAction()))) {
            return new MergeResult(IntentAction.ALLOW, 0, null);
        }
        int maxPriority = hits.stream().mapToInt(h -> IntentAction.priority(h.intentAction())).max().orElse(0);
        if (maxPriority <= 0) {
            return new MergeResult(IntentAction.ALLOW, 0, null);
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
                    case IntentAction.CAPTCHA -> effective ? "captcha" : "review";
                    case IntentAction.REVIEW -> "review";
                    default -> IntentAction.ALLOW;
                };
        return new MergeResult(action, maxRiskScore, primary);
    }

    private static boolean effectiveEnforce(List<RuleHit> hits, String sessionId) {
        if (hits.stream().anyMatch(h -> isFull(h.enforceMode()))) {
            return true;
        }
        return hits.stream().anyMatch(h -> isCanaryEffective(h, sessionId));
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
