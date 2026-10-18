package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 背包空间提醒的纯判据——不碰 Minecraft。
 *
 * <p>要治的病：背包满了之后采集/拾取才失败，<b>事前没人提醒它去卸货</b>。
 * 所以最要紧的两条是：快到阈值就提醒；以及**别刷屏**（本项目有等待类事件每 tick 重发的前科）。
 */
class BackpackSpaceTest {

    // ---- 阈值 ----

    @Test
    void plentyOfRoomIsNotNearFull() {
        assertFalse(BackpackSpace.nearFull(36));
        assertFalse(BackpackSpace.nearFull(10));
        assertFalse(BackpackSpace.nearFull(BackpackSpace.WARN_FREE_SLOTS + 1));
    }

    @Test
    void atOrBelowThresholdIsNearFull() {
        assertTrue(BackpackSpace.nearFull(BackpackSpace.WARN_FREE_SLOTS));
        assertTrue(BackpackSpace.nearFull(2));
        assertTrue(BackpackSpace.nearFull(0));
    }

    @Test
    void thresholdLeavesRoomToFinishTheCurrentTrip() {
        // 故意不设 0：留几格让它把手头这趟跑完，而不是当场丢东西
        assertTrue(BackpackSpace.WARN_FREE_SLOTS >= 1);
    }

    // ---- 该不该真发（防刷屏） ----

    @Test
    void firstTimeNearFullWarns() {
        assertTrue(BackpackSpace.shouldWarn(2, 0, -1, 1000, 600));
    }

    @Test
    void roomAvailableNeverWarns() {
        assertFalse(BackpackSpace.shouldWarn(20, 0, -1, 1000, 600));
    }

    @Test
    void doesNotRepeatWithinCooldownAtTheSameLevel() {
        // 刚提醒过、还是同样紧、还没到冷却 → 闭嘴
        assertFalse(BackpackSpace.shouldWarn(2, 1000, 2, 1100, 600));
    }

    @Test
    void warnsAgainWhenItGetsTighter() {
        // 比上次更紧（3 格 -> 1 格）→ 值得再说一次，不必等冷却
        assertTrue(BackpackSpace.shouldWarn(1, 1000, 3, 1050, 600));
    }

    @Test
    void warnsAgainAfterCooldown() {
        assertTrue(BackpackSpace.shouldWarn(2, 1000, 2, 1000 + 600, 600));
    }

    @Test
    void cooldownBoundaryCounts() {
        assertFalse(BackpackSpace.shouldWarn(2, 1000, 2, 1599, 600));
        assertTrue(BackpackSpace.shouldWarn(2, 1000, 2, 1600, 600));
    }

    @Test
    void stopsWarningOnceThereIsRoomAgain() {
        // 卸完货当然就不该再念叨
        assertFalse(BackpackSpace.shouldWarn(12, 1000, 1, 100000, 600));
    }

    // ---- 文案 ----

    @Test
    void messageSaysWhatWhoAndWhatToDo() {
        String m = BackpackSpace.warnMessage(2, 36);
        assertTrue(m.contains("34/36"), m);
        assertTrue(m.contains("只剩 2 格"), m);
        assertTrue(m.contains("transfer"), m);   // 得告诉它"怎么办"，不能只说"满了"
    }

    @Test
    void messageHandlesZeroFreeWithoutNegativeNumbers() {
        String m = BackpackSpace.warnMessage(0, 36);
        assertTrue(m.contains("36/36"), m);
        assertFalse(m.contains("-"), m);
    }
}
