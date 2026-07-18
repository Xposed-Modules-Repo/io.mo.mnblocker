package io.mo.mnblocker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the shared block/allow rule engine. Pure JVM — no Android.
 *
 * These lock down the semantics both the hook (RegexConfig) and the settings UI
 * (MainActivity) now depend on: default rule, block/allow precedence, per-channel
 * override precedence, and robustness to bad / blank / comment rules.
 */
public final class RuleMatcherTest {

    private static RuleMatcher m(List<String> block, List<String> allow) {
        return RuleMatcher.compile(block, allow);
    }

    @Test
    public void defaultRuleAlwaysPresentAndBlocksMarketing() {
        RuleMatcher rm = m(null, null);
        assertEquals(Collections.singletonList(RuleMatcher.DEFAULT_BLOCK_RULE), rm.blockRules());
        assertTrue(rm.shouldBlock("channel_ads", "营销推广", null));
        assertEquals(RuleMatcher.DEFAULT_BLOCK_RULE, rm.firstBlockMatch("双十一营销活动"));
    }

    @Test
    public void noMatchIsNotBlocked() {
        RuleMatcher rm = m(null, null);
        assertFalse(rm.shouldBlock("chat_messages", "聊天消息", null));
        assertNull(rm.firstBlockMatch("订单通知"));
    }

    @Test
    public void userBlockRuleMatches() {
        RuleMatcher rm = m(Arrays.asList(".*(推广|促销|优惠).*"), null);
        assertTrue(rm.shouldBlock("promo", "限时促销", null));
        assertTrue(rm.shouldBlock("coupon", "优惠券到账", null));
        assertFalse(rm.shouldBlock("bill", "账单提醒", null));
    }

    @Test
    public void allowRuleBeatsBlockRule() {
        // A broad block rule would catch everything with "通知"...
        RuleMatcher rm = m(Arrays.asList(".*通知.*"), Arrays.asList(".*(验证码|验证).*"));
        assertTrue(rm.shouldBlock("push", "营销通知", null));           // blocked
        assertFalse(rm.shouldBlock("otp", "验证码通知", null));         // allow wins
        assertEquals(".*(验证码|验证).*", rm.firstAllowMatch("您的验证码是1234"));
    }

    @Test
    public void overridePrecedenceBeatsEverything() {
        RuleMatcher rm = m(Arrays.asList(".*营销.*"), Arrays.asList(".*验证码.*"));
        // force block even though nothing matches / allow would win
        assertTrue(rm.decideBlock(Boolean.TRUE, "验证码", "验证码", null));
        // force allow even though a block rule matches
        assertFalse(rm.decideBlock(Boolean.FALSE, "营销短信", "营销短信", null));
        // null override -> defer to regex
        assertTrue(rm.decideBlock(null, "营销短信", null));
        assertFalse(rm.decideBlock(null, "普通消息", null));
    }

    @Test
    public void blankCommentAndInvalidRulesAreSkipped() {
        RuleMatcher rm = m(Arrays.asList("", "  ", "# a comment", "[unclosed", ".*促销.*"), null);
        // only the default + the one valid user rule survive
        assertEquals(Arrays.asList(RuleMatcher.DEFAULT_BLOCK_RULE, ".*促销.*"), rm.blockRules());
        assertTrue(rm.shouldBlock("x", "促销", null));
    }

    @Test
    public void duplicateRulesAreDeduped() {
        RuleMatcher rm = m(Arrays.asList(".*营销.*", ".*营销.*", ".*ad.*"), null);
        // default equals the first user rule -> deduped; ".*ad.*" kept
        assertEquals(Arrays.asList(RuleMatcher.DEFAULT_BLOCK_RULE, ".*ad.*"), rm.blockRules());
    }

    @Test
    public void nullAndEmptyCandidatesAreIgnored() {
        RuleMatcher rm = m(Arrays.asList(".*促销.*"), null);
        assertFalse(rm.shouldBlock((String[]) null));
        assertFalse(rm.shouldBlock(null, "", null));
        assertNull(rm.firstBlockMatch("", null));
    }

    @Test
    public void descriptionCandidateIsMatchedWhenSupplied() {
        RuleMatcher rm = m(Arrays.asList(".*促销.*"), null);
        // id + name miss, but description hits
        assertTrue(rm.shouldBlock("ch01", "系统通知", "本渠道用于促销信息"));
        // without the description candidate it must NOT match
        assertFalse(rm.shouldBlock("ch01", "系统通知"));
    }

    @Test
    public void allowRulesEmptyByDefault() {
        assertArrayEquals(new String[0], m(null, null).allowRules().toArray());
    }

    // ------------------------------------------------------------------
    // content rule sets (compileContent): default rule must NOT leak in
    // ------------------------------------------------------------------

    @Test
    public void contentSetDoesNotInjectDefaultRule() {
        // The bug: an IM message whose BODY merely said 营销 got dropped by the
        // built-in channel default, even though the user's content rules never
        // mentioned it.
        RuleMatcher rm = RuleMatcher.compileContent(Arrays.asList("免息"), null);
        assertEquals(Collections.singletonList("免息"), rm.blockRules());
        assertFalse(rm.shouldBlock("晚风吻花眠", "[5条]晚风吻花眠: 营销"));
        assertNull(rm.firstBlockMatch("营销"));
        // the user's own content rule still works
        assertTrue(rm.shouldBlock("晚风吻花眠", "[7条]晚风吻花眠: 免息"));
    }

    @Test
    public void emptyContentSetBlocksNothing() {
        // "content interception on, no content rules" must mean exactly that —
        // not "fall back to blocking 营销".
        for (RuleMatcher rm : Arrays.asList(
                RuleMatcher.compileContent(null, null),
                RuleMatcher.compileContent(Collections.<String>emptyList(), null),
                RuleMatcher.compileContent(Arrays.asList("", "  ", "# only a comment"), null))) {
            assertArrayEquals(new String[0], rm.blockRules().toArray());
            assertFalse(rm.shouldBlock("营销", "双十一营销活动"));
        }
    }

    @Test
    public void contentSetStillHonoursAllowWhitelist() {
        RuleMatcher rm = RuleMatcher.compileContent(
                Arrays.asList(".*(优惠|促销).*"), Arrays.asList(".*验证码.*"));
        assertTrue(rm.shouldBlock("限时优惠"));
        assertFalse(rm.shouldBlock("您的验证码是1234，优惠码勿泄露")); // allow wins
    }

    @Test
    public void channelSetStillInjectsDefault() {
        // Guards the other half: compile() must keep injecting the default, so
        // fixing the content set cannot silently weaken channel blocking.
        RuleMatcher rm = RuleMatcher.compile(Arrays.asList("免息"), null);
        assertEquals(Arrays.asList(RuleMatcher.DEFAULT_BLOCK_RULE, "免息"), rm.blockRules());
        assertTrue(rm.shouldBlock("ch", "营销推广", null));
    }
}
