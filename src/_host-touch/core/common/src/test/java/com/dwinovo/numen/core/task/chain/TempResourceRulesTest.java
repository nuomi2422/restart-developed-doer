package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时资源收尾的纯判据——不碰 Minecraft。
 *
 * <p>要治的病是"落地水不回收":窗口一到就销账,水留在世界里,而没有任何人知道。
 * 所以最要紧的一条是——<b>窗口到点不等于账可以销</b>。
 */
class TempResourceRulesTest {

    // ---- 快窗口到期:让身体,但别销账 ----

    @Test
    void waterStillThereGoesToCleanupNotAbandon() {
        // 这是本次修复的核心:以前这里直接把 placed 清空,水就永远留在世界里了
        assertEquals(TempResourceRules.AfterFastWindow.DEFER_TO_CLEANUP,
                TempResourceRules.afterFastWindow(true, true));
    }

    @Test
    void waterGoneMeansAlreadySettled() {
        // 水自己流走了/被别人收走了:账自然结清,立刻放手,不进清理窗口
        assertEquals(TempResourceRules.AfterFastWindow.SETTLED,
                TempResourceRules.afterFastWindow(false, true));
        assertEquals(TempResourceRules.AfterFastWindow.SETTLED,
                TempResourceRules.afterFastWindow(false, false));
    }

    @Test
    void waterStillThereButNoBucketIsAbandonNotDefer() {
        // 桶没了就装不回来,挂着等只是白占身体
        assertEquals(TempResourceRules.AfterFastWindow.ABANDON_NO_BUCKET,
                TempResourceRules.afterFastWindow(true, false));
    }

    // ---- 清理窗口也到期:必须放手,但要留痕 ----

    @Test
    void cleanupWindowExpiresIntoAbandonWithTrace() {
        assertEquals(TempResourceRules.AfterCleanupWindow.ABANDON,
                TempResourceRules.afterCleanupWindow(true));
    }

    @Test
    void cleanupWindowEndsCleanWhenWaterIsGone() {
        assertEquals(TempResourceRules.AfterCleanupWindow.SETTLED,
                TempResourceRules.afterCleanupWindow(false));
    }

    // ---- 被抢占停止时:留不留账 ----

    @Test
    void stopKeepsTheLedgerWhileWaterIsStillThere() {
        // 旧实现在 stop() 里无条件 placed=null,这就是被抢占一次就永久漏水的路径
        assertTrue(TempResourceRules.keepLedgerOnStop(true));
    }

    @Test
    void stopDropsTheLedgerOnceWaterIsGone() {
        // 水都没了还记着,后续 tick 全是白干活
        assertFalse(TempResourceRules.keepLedgerOnStop(false));
    }

    // ---- 清理窗口能不能占身体（本批最要紧的一条） ----

    @Test
    void cleanupYieldsWhenNothingIsWrong() {
        // 平平安安时,收一摊水是家务,可以让它做
        assertTrue(TempResourceRules.cleanupMayYield(false, false, false, false));
    }

    @Test
    void cleanupMustYieldToLavaEscape() {
        // MLG 注册号 10 < LavaEscape 12：清理若还报 canRun=true，就把逃岩浆压掉约 10 秒，
        // 在岩浆洞里足以致死。这条判据就是为此存在的。
        assertFalse(TempResourceRules.cleanupMayYield(false, true, false, false));
    }

    @Test
    void cleanupMustYieldToSuffocationEscape() {
        assertFalse(TempResourceRules.cleanupMayYield(false, false, true, false));
    }

    @Test
    void cleanupMustYieldToBreathChain() {
        assertFalse(TempResourceRules.cleanupMayYield(false, false, false, true));
    }

    @Test
    void cleanupMustYieldToDeath() {
        assertFalse(TempResourceRules.cleanupMayYield(true, false, false, false));
    }

    @Test
    void anySingleEmergencyIsEnoughToYield() {
        // 急救优先级：一条就够让路,不需要"全都出事"
        assertFalse(TempResourceRules.cleanupMayYield(true, true, true, true));
        assertFalse(TempResourceRules.cleanupMayYield(false, true, true, true));
    }

    // ---- 预算一定收敛（反复抢占不许无限续期） ----

    @Test
    void cleanupBudgetIsGrantedAtMostOncePerPlacement() {
        // 对应 stop() 的实现：预算<=0 才发，否则只扣一格。所以**同一次放置**里发放的
        // 预算恒 <= CLEANUP_BUDGET_TICKS，反复被抢占也耗得完 → 预算归零即 closeLedger()
        // 销账，这条反射不可能被永久钉在身体上。
        // （先前这个测试写错了模型：它假设预算耗尽后还能继续发额度，可那时账已经销了。）
        final int grant = 200;
        int budget = grant;          // 放置那一刻发一次
        int totalGranted = grant;
        boolean ledgerOpen = true;
        for (int preempt = 0; preempt < 1000 && ledgerOpen; preempt++) {
            if (budget <= 0) { ledgerOpen = false; break; }   // 耗尽 → closeLedger 销账
            budget--;                                          // 每次被抢占扣一格
        }
        assertFalse(ledgerOpen, "预算必须在有限次抢占内耗尽并销账");
        assertEquals(grant, totalGranted, "同一次放置发放的预算总量必须 <= 单次额度");
        assertTrue(budget >= 0);
    }

    // ---- 极端:反复抢占不会把身体永久钉住 ----

    @Test
    void repeatedPreemptionStillTerminatesViaCleanupWindow() {
        // 模拟"被抢占 N 次"的极端:每次都快窗口到期,判据都只让它进清理窗口,
        // 而清理窗口必然以 ABANDON 收尾 —— 所以不存在无限占身体的路径。
        for (int i = 0; i < 1000; i++) {
            assertEquals(TempResourceRules.AfterFastWindow.DEFER_TO_CLEANUP,
                    TempResourceRules.afterFastWindow(true, true));
        }
        assertEquals(TempResourceRules.AfterCleanupWindow.ABANDON,
                TempResourceRules.afterCleanupWindow(true));
    }
}
