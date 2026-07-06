package io.virbius.groovy.l3;

/** PoC default L3 script: dry_run/canary returns review, full returns block. */
public final class GroovyL3Defaults {

    public static final String DEFAULT_DECIDE_SCRIPT =
            """
            def decide(ctx) {
              def ruleId = ctx.currentRuleId()
              def mode = ctx.enforceMode(ruleId)
              if (!ctx.wouldHitBlock()) {
                return false
              }
              if (mode == 'dry_run') {
                return true
              }
              if (mode == 'canary' && !ctx.inCanaryBucket(ctx.sessionId(), ctx.canaryPercent(ruleId))) {
                return false
              }
              return true
            }
            """;

    private GroovyL3Defaults() {}
}
