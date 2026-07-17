package io.mo.mnblocker;

/**
 * Pure block/allow decision helper: the full priority chain (override > app
 * whitelist > allow rule > block rule > default-allow) that {@link NotificationHook}
 * hand-writes twice (once for the create/update hook, once for the query-hook
 * backfill) so it can also emit the {@code decisionReason} it logs.
 *
 * {@link RuleMatcher#decideBlock} is NOT that chain — it has no app-whitelist
 * step and no reason string, and only {@code RuleMatcherTest} calls it in
 * practice. This class exists so the root-free path (see
 * {@code docs/rootfree-mode-plan.md}) has ONE place to compute a verdict
 * instead of writing a third, possibly-diverging copy.
 *
 * Root-path scope note: {@link NotificationHook} is intentionally left
 * untouched and keeps its own two hand-written chains — this class is only
 * ever called from the root-free listener.
 */
final class BlockDecision {

    private BlockDecision() {}

    /**
     * @param matcher         channel or content matcher to test candidates against
     * @param override        Boolean.TRUE = force block, Boolean.FALSE = force
     *                        allow, null = defer to regex rules
     * @param appWhitelisted  true if the owning app is fully exempt
     * @param candidates      text fields to match (id/name/description, or title/text)
     */
    static Result decide(RuleMatcher matcher, Boolean override, boolean appWhitelisted,
                         String... candidates) {
        if (override != null) {
            return new Result(override, "override(" + (override ? "block" : "allow") + ")");
        }
        if (appWhitelisted) {
            return new Result(false, "app-whitelist");
        }
        String allowRule = matcher.firstAllowMatch(candidates);
        if (allowRule != null) {
            return new Result(false, "allow:" + allowRule);
        }
        String blockRule = matcher.firstBlockMatch(candidates);
        if (blockRule != null) {
            return new Result(true, "regex:" + blockRule);
        }
        return new Result(false, "no-match");
    }

    static final class Result {
        final boolean block;
        final String reason;

        Result(boolean block, String reason) {
            this.block = block;
            this.reason = reason;
        }
    }
}
