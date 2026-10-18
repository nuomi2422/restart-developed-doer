package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换气反射"反复抢身体"的纯判据——不碰 Minecraft。
 *
 * <p>要治的病是"下水捡东西→浮上来→又下水"的无限恢复。救命反射本身必须留着,
 * 所以判据只回答一件事:<b>该不该把"这儿反复不行"告诉上层</b>。
 */
class BreathEpisodeRulesTest {

    // ---- 同一片水 ----

    @Test
    void rightNextToEachOtherIsSameArea() {
        assertTrue(BreathEpisodeRules.sameArea(10, 60, 10, 12, 40, 11, 100));
    }

    @Test
    void verticalDistanceDoesNotSplitTheArea() {
        // 从水底浮到水面是同一次救援的两个端点,不能算两片水
        assertTrue(BreathEpisodeRules.sameArea(10, 20, 10, 10, 64, 10, 50));
    }

    @Test
    void farApartHorizontallyIsNotSameArea() {
        // 换了地方就不该继承上一片水的计数
        assertFalse(BreathEpisodeRules.sameArea(0, 60, 0, 40, 60, 0, 100));
    }

    @Test
    void areaRadiusEdgeCounts() {
        // 边界:正好差 AREA_RADIUS 格算同一片(容许,宁可多报一次也别漏掉循环)
        assertTrue(BreathEpisodeRules.sameArea(0, 60, 0, BreathEpisodeRules.AREA_RADIUS, 60, 0, 100));
        assertFalse(BreathEpisodeRules.sameArea(0, 60, 0, BreathEpisodeRules.AREA_RADIUS + 1, 60, 0, 100));
    }

    @Test
    void longGapIsNotSameArea() {
        // 十分钟前的一次上浮和现在这次无关,不能累积成"反复"
        assertFalse(BreathEpisodeRules.sameArea(0, 60, 0, 0, 60, 0, BreathEpisodeRules.WINDOW_TICKS + 1));
        assertTrue(BreathEpisodeRules.sameArea(0, 60, 0, 0, 60, 0, BreathEpisodeRules.WINDOW_TICKS));
    }

    @Test
    void negativeGapIsRejected() {
        // 时钟倒流/未初始化:宁可当作"不是同一片",不往计数里加
        assertFalse(BreathEpisodeRules.sameArea(0, 60, 0, 0, 60, 0, -1));
    }

    // ---- 几次才报 ----

    @Test
    void firstTwoRescuesAreNotWorthReporting() {
        assertEquals(BreathEpisodeRules.Verdict.OK, BreathEpisodeRules.onEpisodeEnd(1));
        assertEquals(BreathEpisodeRules.Verdict.OK, BreathEpisodeRules.onEpisodeEnd(2));
    }

    @Test
    void thirdRescueInTheSameSpotGetsReported() {
        assertEquals(BreathEpisodeRules.Verdict.REPORT_REPEATED_RESCUE,
                BreathEpisodeRules.onEpisodeEnd(BreathEpisodeRules.REPORT_AFTER_EPISODES));
    }

    @Test
    void keepsReportingWhileTheLoopContinues() {
        // 循环不会因为报过一次就静音——否则上层看不到它还在继续
        assertEquals(BreathEpisodeRules.Verdict.REPORT_REPEATED_RESCUE, BreathEpisodeRules.onEpisodeEnd(4));
        assertEquals(BreathEpisodeRules.Verdict.REPORT_REPEATED_RESCUE, BreathEpisodeRules.onEpisodeEnd(97));
    }

    @Test
    void zeroEpisodesIsNotAReportableLoop() {
        // 极端:一次都没发生过,不能凭空报"反复"
        assertEquals(BreathEpisodeRules.Verdict.OK, BreathEpisodeRules.onEpisodeEnd(0));
    }
}
