package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回话措辞与限频的离线检查（2026-10-05）。
 *
 * <p>守四件事：
 * <ol>
 *   <li><b>只说事实</b> —— 不出现「学会了」「变聪明」这类自评；</li>
 *   <li><b>限频</b> —— 批量复审不该刷屏；</li>
 *   <li><b>不同同伴互不影响</b>；</li>
 *   <li><b>没东西可说就闭嘴</b>（沉默优于噪声）。</li>
 * </ol>
 */
class AnnounceTextTest {

    private static final UUID A = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID B = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @BeforeEach
    void reset() {
        AnnounceText.reset();
        AnnounceText.setEnabled(true);
        AnnounceText.setMinGapSeconds(AnnounceText.DEFAULT_MIN_GAP_SECONDS);
    }

    @Test
    void reviewLine_reportsFactsOnly() {
        String s = AnnounceText.reviewLine(2, 2, 0, 1, 0, 0, 0);
        assertNotNull(s);
        assertTrue(s.contains("2 条判定"), "要说判了几条: " + s);
        assertTrue(s.contains("入库 2"), "要说入了几条: " + s);
        assertTrue(s.contains("AC 草稿 1 条"), "要说产物落了几条: " + s);
    }

    @Test
    void reviewLine_hasNoSelfPraise() {
        String s = AnnounceText.reviewLine(1, 1, 0, 0, 0, 0, 0);
        assertNotNull(s);
        for (String bad : new String[]{"学会", "聪明", "变强", "以后不会", "进步"}) {
            assertFalse(s.contains(bad),
                    "★ 措辞不许自评（自我印证会立刻变成假证据），但出现了 '" + bad + "': " + s);
        }
    }

    @Test
    void reviewLine_saysRestoredBecauseOwnerMustKnow() {
        // 退回是真事实：主人要知道有东西没被判掉
        String s = AnnounceText.reviewLine(1, 0, 3, 0, 0, 0, 0);
        assertNotNull(s);
        assertTrue(s.contains("退回 3"), "要说退回了多少: " + s);
        assertTrue(s.contains("不丢"), "要说清没丢，否则主人会以为白干了: " + s);
    }

    @Test
    void reviewLine_separatesAdoptedFromLanded() throws Exception {
        // ★ 已采纳 ≠ 已上线，两件事混成一句就是撒谎
        String s = AnnounceText.reviewLine(1, 1, 0, 2, 0, 3, 5);
        assertNotNull(s);
        assertTrue(s.contains("2 条在等采纳"), "还在等的要单列: " + s);
        assertTrue(s.contains("已采纳 5 条") && s.contains("未上线"),
                "已采纳必须明说未上线: " + s);
        assertTrue(s.contains("3 条 AC 草稿被拒收"), "被拒的要单列: " + s);
    }

    @Test
    void reviewLine_nullWhenNothingToSay() {
        // 沉默优于噪声
        assertNull(AnnounceText.reviewLine(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void rateLimit_blocksSecondMessageWithinGap() {
        long t0 = 1_000_000L;
        assertTrue(AnnounceText.tryClaim(A, t0), "第一次应当放行");
        assertFalse(AnnounceText.tryClaim(A, t0 + 1000), "间隔内的第二句应被吞掉（否则刷屏）");
    }

    @Test
    void rateLimit_allowsAfterGap() {
        long t0 = 1_000_000L;
        assertTrue(AnnounceText.tryClaim(A, t0));
        long gapMs = AnnounceText.minGapSeconds() * 1000L;
        assertTrue(AnnounceText.tryClaim(A, t0 + gapMs + 1), "过了间隔就应放行");
    }

    @Test
    void rateLimit_isPerCompanion() {
        long t0 = 1_000_000L;
        assertTrue(AnnounceText.tryClaim(A, t0));
        assertTrue(AnnounceText.tryClaim(B, t0),
                "★ 不同同伴互不影响 —— 否则一个同伴刚说完就把另一个的吞掉");
    }

    @Test
    void undo_releasesTheSlotSoFailedSendDoesNotWasteIt() {
        // 主人不在线时发送失败 → 必须撤回占用，否则这次白占额度
        long t0 = 1_000_000L;
        assertTrue(AnnounceText.tryClaim(A, t0));
        AnnounceText.undo(A, t0);
        assertTrue(AnnounceText.tryClaim(A, t0 + 1000),
                "撤回后同间隔内也应能再试一次（额度没被那次失败吃掉）");
    }

    @Test
    void disabled_blocksEverything() {
        AnnounceText.setEnabled(false);
        assertFalse(AnnounceText.tryClaim(A, 1_000_000L), "关掉后必须一句都不说（止损开关）");
    }

    @Test
    void clip_flattensAndTruncates() {
        String s = AnnounceText.clip("a\n\n  b\t c");
        assertEquals("a b c", s, "聊天栏不该出现换行与多空格");
        String long200 = "x".repeat(AnnounceText.MAX_CHARS + 50);
        String c = AnnounceText.clip(long200);
        assertTrue(c.length() <= AnnounceText.MAX_CHARS + 1, "超长要截断（加省略号）: " + c.length());
        assertTrue(c.endsWith("…"), "截断要有省略号，让人知道被截了");
    }

    @Test
    void zeroGap_meansNoLimit() {
        AnnounceText.setMinGapSeconds(0);
        long t0 = 1_000_000L;
        assertTrue(AnnounceText.tryClaim(A, t0));
        assertTrue(AnnounceText.tryClaim(A, t0), "间隔设 0 = 不限频（主人明确要求时用）");
    }
}