package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Merge semantics: allow hits suppress ONLY when effectively enforced
 * (full, or canary with the session in bucket); dry_run allow is an
 * observation-only exception candidate and must never weaken enforcement.
 * On suppression, primary carries the strongest overridden deny/challenge
 * hit so audits retain (allow, deny-rule) attribution.
 */
class ActionMergeTest {

    private static ActionMerge.RuleHit hit(
            String ruleId, int revision, String reason, int risk,
            String intent, String enforce, Integer canaryPercent) {
        return new ActionMerge.RuleHit(ruleId, revision, reason, risk, intent, enforce, canaryPercent);
    }

    @Test
    void emptyHitsAllow() {
        ActionMerge.MergeResult r = ActionMerge.merge(List.of(), "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertEquals(0, r.maxRiskScore());
        assertNull(r.primary());
    }

    // ── dry_run allow must NOT suppress ──────────────────────────────

    @Test
    void dryRunAllowDoesNotSuppressFullDeny() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "dry_run", null),
                hit("deny_rule", 1, "DANGER", 100, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("block", r.effectiveAction());
        assertEquals(100, r.maxRiskScore());
        assertEquals("deny_rule", r.primary().ruleId());
    }

    @Test
    void blankEnforceAllowDefaultsToNonEffective() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "", null),
                hit("deny_rule", 1, "DANGER", 80, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("block", r.effectiveAction());
    }

    @Test
    void dryRunAllowAloneStillAllows() {
        // No enforcement hit exists — nothing to suppress; plain pass-through.
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "dry_run", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertNull(r.primary());
        assertFalse(r.suppressed());
    }

    // ── full allow suppresses with attribution ───────────────────────

    @Test
    void fullAllowSuppressesDenyWithAttribution() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "full", null),
                hit("deny_llm", 1, "PROMPT_INJECTION", 100, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertEquals(0, r.maxRiskScore());
        assertEquals("deny_llm", r.primary().ruleId());
        assertEquals("PROMPT_INJECTION", r.primary().reasonCode());
        assertTrue(r.suppressed());
    }

    @Test
    void fullAllowSuppressesChallengeWithAttribution() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "full", null),
                hit("chal_rule", 1, "CHAIN_ABUSE", 25, "challenge", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertEquals("chal_rule", r.primary().ruleId());
        assertTrue(r.suppressed());
    }

    @Test
    void attributionPicksHighestRiskSuppressedHit() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "full", null),
                hit("deny_groovy", 1, "SQL_DANGEROUS", 80, "deny", "full", null),
                hit("deny_llm", 1, "PROMPT_INJECTION", 100, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("deny_llm", r.primary().ruleId());
        assertTrue(r.suppressed());
    }

    // ── allow-only attribution (nothing overridden) ──────────────────

    @Test
    void fullAllowAloneAttributesToAllowRule() {
        // Nothing to suppress — but the release decision was caused by this
        // specific allow rule, so it is named as primary (ops attribution);
        // suppressed stays false so routine benign traffic stays sampled.
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertEquals("allow_rule", r.primary().ruleId());
        assertEquals("SAFE", r.primary().reasonCode());
        assertFalse(r.suppressed());
    }

    @Test
    void fullAllowWithOnlyReviewHitAttributesToAllowRule() {
        // Review never enforced anything — it is not suppression attribution,
        // but the allow rule still caused the release and is named.
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "full", null),
                hit("rev_rule", 1, "WATCH", 30, "review", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertEquals("allow_rule", r.primary().ruleId());
        assertFalse(r.suppressed());
    }

    @Test
    void allowOnlyAttributionIgnoresNonEffectiveAllow() {
        // The dry_run allow has the higher risk_score but is NOT enforced;
        // attribution must go to the enforced full allow, never to an
        // observation-only candidate that did not cause the release.
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_dry", 1, "CANDIDATE", 50, "allow", "dry_run", null),
                hit("allow_full", 1, "SAFE", 0, "allow", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow_full", r.primary().ruleId());
        assertFalse(r.suppressed());
    }

    @Test
    void allowOnlyAttributionPicksHighestRiskEnforcedAllow() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_a", 1, "SAFE", 0, "allow", "full", null),
                hit("allow_b", 1, "SAFE", 30, "allow", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow_b", r.primary().ruleId());
    }

    @Test
    void allowOnlyAttributionTieBreakIsDeterministic() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_old", 1, "SAFE", 0, "allow", "full", null),
                hit("allow_new", 2, "SAFE", 0, "allow", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow_new", r.primary().ruleId());
    }

    // ── canary allow: bucket-scoped rollout ──────────────────────────

    @Test
    void canaryAllowInBucketSuppresses() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "canary", 100),
                hit("deny_rule", 1, "DANGER", 100, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("allow", r.effectiveAction());
        assertEquals("deny_rule", r.primary().ruleId());
    }

    @Test
    void canaryAllowOutOfBucketKeepsDeny() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("allow_rule", 1, "SAFE", 0, "allow", "canary", 0),
                hit("deny_rule", 1, "DANGER", 100, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("block", r.effectiveAction());
    }

    // ── deny/challenge merging unchanged ─────────────────────────────

    @Test
    void dryRunDenyDegradesToReview() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("deny_rule", 1, "DANGER", 80, "deny", "dry_run", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("review", r.effectiveAction());
    }

    @Test
    void fullDenyBlocks() {
        List<ActionMerge.RuleHit> hits = List.of(
                hit("deny_rule", 1, "DANGER", 80, "deny", "full", null));
        ActionMerge.MergeResult r = ActionMerge.merge(hits, "sess-1");
        assertEquals("block", r.effectiveAction());
        assertEquals("deny_rule", r.primary().ruleId());
    }
}
