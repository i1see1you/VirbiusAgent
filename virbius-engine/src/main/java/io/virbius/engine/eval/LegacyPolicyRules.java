package io.virbius.engine.eval;

/** Deprecated meta-rules superseded by {@link PolicyMerger} / {@link io.virbius.policy.ActionMerge}. */
final class LegacyPolicyRules {

    /** Former L3 Groovy shell; merge is now code-only. */
    static final String CLOUD_GROOVY_L3 = "cloud_groovy_l3";

    private LegacyPolicyRules() {}

    static boolean isDeprecatedMetaRule(String ruleId) {
        return CLOUD_GROOVY_L3.equals(ruleId);
    }
}
