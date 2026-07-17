package io.mo.mnblocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Unit tests for the root-free decision chain. Pure JVM — no Android.
 *
 * Locks down the same precedence root/hook path already relies on: override >
 * app whitelist > allow rule > block rule > default-allow, plus the reason
 * strings the detail screens surface.
 */
public final class BlockDecisionTest {

    private static RuleMatcher matcher() {
        return RuleMatcher.compile(Arrays.asList(".*营销.*"), Arrays.asList(".*验证码.*"));
    }

    @Test
    public void overrideBlockBeatsEverything() {
        BlockDecision.Result r = BlockDecision.decide(matcher(), Boolean.TRUE, false, "验证码");
        assertTrue(r.block);
        assertEquals("override(block)", r.reason);
    }

    @Test
    public void overrideAllowBeatsEverything() {
        BlockDecision.Result r = BlockDecision.decide(matcher(), Boolean.FALSE, false, "营销活动");
        assertFalse(r.block);
        assertEquals("override(allow)", r.reason);
    }

    @Test
    public void appWhitelistBeatsAllowAndBlock() {
        BlockDecision.Result r = BlockDecision.decide(matcher(), null, true, "营销验证码");
        assertFalse(r.block);
        assertEquals("app-whitelist", r.reason);
    }

    @Test
    public void allowRuleBeatsBlockRule() {
        BlockDecision.Result r = BlockDecision.decide(matcher(), null, false, "营销验证码");
        assertFalse(r.block);
        assertEquals("allow:.*验证码.*", r.reason);
    }

    @Test
    public void blockRuleMatchesWhenNothingElseApplies() {
        BlockDecision.Result r = BlockDecision.decide(matcher(), null, false, "营销活动");
        assertTrue(r.block);
        assertEquals("regex:.*营销.*", r.reason);
    }

    @Test
    public void noMatchDefaultsToAllow() {
        BlockDecision.Result r = BlockDecision.decide(matcher(), null, false, "普通通知");
        assertFalse(r.block);
        assertEquals("no-match", r.reason);
    }
}
