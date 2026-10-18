package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「采到一部分」和「采够了」的纯判据——不碰 Minecraft。
 *
 * <p>要治的病是"挖了一半离开被当成完成"。终态 SUCCESS 是有意保留的契约,这里只保证
 * 缺口能被程序化读出来,不再依赖解析文案。
 */
class PartialGatherTest {

    @Test
    void gatheredExactlyRequestedIsNotPartial() {
        assertFalse(PartialGather.isPartial(8, 8));
    }

    @Test
    void gatheredMoreThanRequestedIsNotPartial() {
        // 一块矿出多个物品时超采很正常:超了不是缺口
        assertFalse(PartialGather.isPartial(12, 8));
    }

    @Test
    void shortOfRequestedIsPartial() {
        assertTrue(PartialGather.isPartial(3, 8));
    }

    @Test
    void zeroGatheredIsPartial() {
        assertTrue(PartialGather.isPartial(0, 8));
    }

    @Test
    void shortfallIsNeverNegative() {
        assertEquals(5, PartialGather.shortfall(3, 8));
        assertEquals(0, PartialGather.shortfall(8, 8));
        assertEquals(0, PartialGather.shortfall(12, 8));
    }

    @Test
    void zeroRequestedMeansNothingToProve() {
        // 主人没说要几根时 requested 可能是 0:那不是"采到 0/0 也不够"
        assertFalse(PartialGather.isPartial(0, 0));
        assertEquals(0, PartialGather.shortfall(0, 0));
    }

    @Test
    void negativeTalliesDoNotFakeACompleteGather() {
        // 账本异常时不能反过来报"够了"
        assertTrue(PartialGather.isPartial(-1, 8));
        assertEquals(9, PartialGather.shortfall(-1, 8));
    }

    @Test
    void largeCountsBehave() {
        assertTrue(PartialGather.isPartial(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
        assertEquals(1, PartialGather.shortfall(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
        assertFalse(PartialGather.isPartial(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
