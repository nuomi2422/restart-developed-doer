package com.dwinovo.numen.core.task.collect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拾取对账的纯判据——不碰 Minecraft。
 *
 * <p>这一层存在的全部理由是那条被治的病:<b>掉落物消失 ≠ 本人拿到</b>。所以最要紧的
 * 两条不是"能不能走到",而是"东西没了之后,背包到底涨没涨"。
 */
class CollectDecisionsTest {

    // ---- 归属:消失之后背包涨没涨 ----

    @Test
    void goneButInventoryGainedIsOurKill() {
        // 正常结局:原版把它吸进了包
        assertEquals(CollectDecisions.Outcome.CREDITED,
                CollectDecisions.classify(true, 1));
    }

    @Test
    void goneButInventoryUnchangedIsNotOurKill() {
        // 这就是"黑曜石挖了不捡"能被算成完成的根因:烧了/被抢了/despawn 了,
        // 旧实现照样 collected++
        assertEquals(CollectDecisions.Outcome.VANISHED,
                CollectDecisions.classify(true, 0));
    }

    @Test
    void goneWhileCountShrankIsNotOurKill() {
        // 极端但真实:同一瞬间我们把这一种东西用掉了(比如垫脚),净增量为负。
        // 绝不能因此记成战果。
        assertEquals(CollectDecisions.Outcome.VANISHED,
                CollectDecisions.classify(true, -3));
    }

    @Test
    void stillThereIsNeverACreditNoMatterTheInventory() {
        // 背包涨了但这东西还在面前 —— 涨的是别处来的,不能记这一件
        assertEquals(CollectDecisions.Outcome.STILL_THERE,
                CollectDecisions.classify(false, 5));
    }

    @Test
    void stillThereWithFlatInventoryIsAlsoNotDone() {
        assertEquals(CollectDecisions.Outcome.STILL_THERE,
                CollectDecisions.classify(false, 0));
    }

    // ---- 一次接近最多记几件 ----

    @Test
    void onlyARealCreditCounts() {
        assertEquals(1, CollectDecisions.creditFor(CollectDecisions.Outcome.CREDITED));
        assertEquals(0, CollectDecisions.creditFor(CollectDecisions.Outcome.VANISHED));
        assertEquals(0, CollectDecisions.creditFor(CollectDecisions.Outcome.STILL_THERE));
        assertEquals(0, CollectDecisions.creditFor(CollectDecisions.Outcome.UNREACHABLE));
    }

    // ---- 收尾:扫完了 == 拿到了吗 ----

    @Test
    void nothingShortMeansSweepComplete() {
        assertTrue(CollectDecisions.sweepComplete(true, 0, 0, 0));
    }

    @Test
    void anyShortfallMeansNotComplete() {
        // 三种缺口任何一种非零,"扫完了"就都不能读成"都拿到了"
        assertFalse(CollectDecisions.sweepComplete(true, 1, 0, 0));
        assertFalse(CollectDecisions.sweepComplete(true, 0, 1, 0));
        assertFalse(CollectDecisions.sweepComplete(true, 0, 0, 1));
        assertFalse(CollectDecisions.sweepComplete(true, 3, 2, 1));
    }

    @Test
    void notReachedEndIsNeverComplete() {
        // 超时/被取消:三个缺口计数都还是 0,但一件都没拿到。
        // 只看缺口就会报 all_picked_up=true —— 恰好是这一类要治的病。
        assertFalse(CollectDecisions.sweepComplete(false, 0, 0, 0));
        assertFalse(CollectDecisions.sweepComplete(false, 0, 0, 0));
    }

    @Test
    void negativeTalliesDoNotFakeACompleteSweep() {
        // 账本只增不减;真出现负数说明别处有 bug,这里不能因此报"干净完成"
        assertFalse(CollectDecisions.sweepComplete(true, -1, 0, 0));
        assertFalse(CollectDecisions.sweepComplete(true, 0, 0, -1));
    }

    // ---- 多数据:一整趟的分类不会互相污染 ----

    @Test
    void aWholeSweepCountsEachDropIndependently() {
        int credited = 0, vanished = 0, stillThere = 0;
        int[] deltas = {1, 0, -1, 2, 0, 1, 0, 5, 0, 1};
        for (int d : deltas) {
            switch (CollectDecisions.classify(true, d)) {
                case CREDITED -> credited += CollectDecisions.creditFor(CollectDecisions.Outcome.CREDITED);
                case VANISHED -> vanished++;
                default -> stillThere++;
            }
        }
        assertEquals(5, credited);      // deltas 1,2,1,5,1
        assertEquals(5, vanished);      // deltas 0,-1,0,0,0
        assertEquals(0, stillThere);
        assertFalse(CollectDecisions.sweepComplete(true, vanished, 0, stillThere));
    }
}
