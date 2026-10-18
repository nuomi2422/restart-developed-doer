package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmActivity} 契约测试。
 *
 * <p>要锁的核心有两条：
 * <ol>
 *   <li><b>在飞状态不许卡死</b> —— 若这张表因为某个 future 挂死/被取消而永远非空，
 *       卡死监督就被静默关掉了，那比误拍更糟（没人拍 = 永远卡）。</li>
 *   <li><b>销账不许误伤并发请求</b> —— 同一同伴可以同时有多个请求在飞
 *       （execution / execution_retry / goal_judging / compaction；军师 RddDecomposer
 *       也会为同一 UUID 发请求）。A 先落地绝不能把还在飞的 B 标成已落地。</li>
 * </ol>
 */
class LlmActivityTest {

    private static final String ID = "companion-under-test";

    @AfterEach void clearTable() {
        LlmActivity.reset();
    }

    // ---------- 基础 ----------

    @Test void neverSeenCompanionIsUnknownAndNotInFlight() {
        LlmActivity.Snapshot s = LlmActivity.snapshot("nobody");
        assertFalse(s.known(), "没见过这个同伴就不能替它担保");
        assertFalse(s.inFlight(), "没见过 = 不认在飞 = 不许抑制拍醒");
    }

    @Test void blankIdIsNeverTracked() {
        assertEquals(0L, LlmActivity.markDispatched(null, "execution"));
        assertEquals(0L, LlmActivity.markDispatched("  ", "execution"));
        assertFalse(LlmActivity.snapshot(null).known());
        assertFalse(LlmActivity.snapshot("  ").known());
        LlmActivity.markSettled(ID, 0L, "stop", 0);
        assertFalse(LlmActivity.snapshot(ID).known(), "token=0 不该凭空造出一行");
    }

    @Test void dispatchThenSettleFlipsInFlight() {
        long token = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.Snapshot flying = LlmActivity.snapshot(ID);
        assertTrue(flying.inFlight());
        assertEquals(1, flying.inFlightCount());
        assertEquals("execution", flying.phase());

        LlmActivity.markSettled(ID, token, "stop", 3);
        LlmActivity.Snapshot after = LlmActivity.snapshot(ID);
        assertFalse(after.inFlight(), "落地了就不该还在飞");
        assertEquals("stop", after.lastFinish());
        assertEquals(3, after.lastToolCalls());
        assertTrue(after.known());
    }

    @Test void failedSettleRecordsMinusOneToolCalls() {
        long token = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.markSettled(ID, token, null, -1);
        LlmActivity.Snapshot s = LlmActivity.snapshot(ID);
        assertFalse(s.inFlight(), "失败落地也必须销账,否则挂死请求会让监督永远不敢拍");
        assertEquals(-1, s.lastToolCalls(), "-1 是失败落地的标记,不能和 0 混淆");
    }

    // ---------- ★ P0：并发请求交错，绝不误销 ----------

    @Test void anEarlierSettleMustNotClearALaterInFlightRequest() {
        // Codex 审稿 P0 的直接复现：
        //   A dispatch → B dispatch → A 先落地 → 布尔版会写 inFlight=false，B 其实还在飞。
        long a = LlmActivity.markDispatched(ID, "execution");
        long b = LlmActivity.markDispatched(ID, "execution_retry");
        LlmActivity.Snapshot both = LlmActivity.snapshot(ID);
        assertEquals(2, both.inFlightCount(), "两个请求都该在飞");

        LlmActivity.markSettled(ID, a, "stop", 2);
        LlmActivity.Snapshot afterA = LlmActivity.snapshot(ID);
        assertTrue(afterA.inFlight(), "A 落地不许把还在飞的 B 标成已落地 ← 这就是 P0");
        assertEquals(1, afterA.inFlightCount(), "只该销掉 A 那一个");

        LlmActivity.markSettled(ID, b, "stop", 0);
        assertFalse(LlmActivity.snapshot(ID).inFlight(), "B 也落地后才算真不在飞");
    }

    @Test void settleWithAWrongTokenChangesNothing() {
        long a = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.markSettled(ID, a + 9999L, "stop", 0);
        LlmActivity.Snapshot s = LlmActivity.snapshot(ID);
        assertTrue(s.inFlight(), "不认识��� token 不许销别人的账");
        assertEquals(1, s.inFlightCount());
    }

    @Test void doubleSettleIsIdempotent() {
        long a = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.markSettled(ID, a, "stop", 1);
        LlmActivity.markSettled(ID, a, null, -1); // whenComplete 兜底会再销一次
        assertFalse(LlmActivity.snapshot(ID).inFlight());
        assertEquals(0, LlmActivity.snapshot(ID).inFlightCount());
    }

    // ---------- stale ----------

    @Test void inFlightTurnsStalePastTheUpperBound() {
        // 时基走单调纳秒；snapshot(nowNanos) 由调用方给，所以能确定性跨过上界。
        LlmActivity.markDispatched(ID, "execution");
        LlmActivity.Snapshot fresh = LlmActivity.snapshot(ID);
        assertFalse(fresh.stale(), "刚发出去不算 stale");

        // 用真实的 nanoTime 基准 + 人为推进的"现在"
        long now = System.nanoTime();
        LlmActivity.Snapshot atBound = LlmActivity.snapshot(ID, now);
        assertFalse(atBound.stale(), "在真实时刻上界内就不算 stale");

        // 人为把"现在"推到 2 分钟之后（token 的出生时刻是真实的，所以这里只能用
        // 「刚发出去 + 推进时钟」的组合来构造；直接构造超上界的快照）
        LlmActivity.Snapshot hung = LlmActivity.snapshot(ID, now + LlmActivity.MAX_INFLIGHT_NANOS + 1L);
        assertTrue(hung.inFlight(), "记录本身还是 inFlight");
        assertTrue(hung.stale(), "挂死的请求必须被判 stale,好让监督恢复 —— 宁可误催不可静默失明");
    }

    @Test void staleAlertIsDeduplicatedByTokenNotByAge() {
        // Codex 审稿 P1-4：不去重的话 track() 每秒发一条，挂死几小时就是几万条日志。
        // 去重身份必须是**飞行期间稳定的 token**，拿「年龄」去重等于没去重。
        LlmActivity.markDispatched(ID, "execution");
        long far = System.nanoTime() + LlmActivity.MAX_INFLIGHT_NANOS + 1L;
        LlmActivity.Snapshot first = LlmActivity.snapshot(ID, far);
        assertTrue(first.staleUnreported(), "第一次该报");

        LlmActivity.markStaleReported(ID, first.oldestInFlightToken());
        LlmActivity.Snapshot second = LlmActivity.snapshot(ID, far + 5_000_000_000L);
        assertTrue(second.stale(), "还是 stale");
        assertFalse(second.staleUnreported(),
                "报过就不许再报 —— 哪怕年龄又变了(年龄每 tick 都变，拿它去重是无效的)");
    }

    @Test void settledIsNeverStale() {
        long t = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.markSettled(ID, t, "stop", 0);
        LlmActivity.Snapshot s = LlmActivity.snapshot(ID, System.nanoTime() + LlmActivity.MAX_INFLIGHT_NANOS * 10);
        assertFalse(s.stale(), "已落地的东西不存在 stale");
        assertFalse(s.staleUnreported());
    }

    // ---------- forget ----------

    @Test void forgetDropsTheRow() {
        LlmActivity.markDispatched(ID, "execution");
        assertTrue(LlmActivity.snapshot(ID).known());
        LlmActivity.forget(ID);
        assertFalse(LlmActivity.snapshot(ID).known(), "下线要清行,别让快照在内存里过夜");
    }

    @Test void lateSettleAfterForgetMustNotResurrectTheRow() {
        // Codex 审稿 P1-5：forget 之后旧请求迟到落地，若用 put 会把刚清掉的行又塞回来。
        long a = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.forget(ID);
        LlmActivity.markSettled(ID, a, "stop", 0);
        assertFalse(LlmActivity.snapshot(ID).known(), "迟到落地不许复活这一行");
    }

    @Test void lateSettleAfterForgetMustNotLeakTheNewCompanionWithSameId() {
        long a = LlmActivity.markDispatched(ID, "execution");
        LlmActivity.forget(ID);
        long b = LlmActivity.markDispatched(ID, "execution"); // 换存档后同名重连
        LlmActivity.markSettled(ID, a, "stop", 0);            // 旧请求迟到
        LlmActivity.Snapshot s = LlmActivity.snapshot(ID);
        assertTrue(s.inFlight(), "旧请求的迟到落地不许销掉新请求");
        assertEquals(1, s.inFlightCount());
        LlmActivity.markSettled(ID, b, "stop", 0);
        assertFalse(LlmActivity.snapshot(ID).inFlight());
    }

    @Test void companionsAreIsolatedFromEachOther() {
        LlmActivity.markDispatched("a", "execution");
        assertTrue(LlmActivity.snapshot("a").inFlight());
        assertFalse(LlmActivity.snapshot("b").inFlight(), "b 没有在飞,不许被 a 连累");
    }

    // ---------- 真并发 ----------

    @Test void concurrentDispatchAndSettleNeverLosesOrOvercounts() throws Exception {
        // 单测最怕「看着对其实有竞态」。这里让 8 线程各发 50 次、随机交错落地，
        // 断言终态是 0 在飞且落地计数准确。
        int threads = 8, per = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        long[] tokens = new long[threads * per];

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < per; i++) {
                    tokens[tid * per + i] = LlmActivity.markDispatched(ID, "execution");
                }
                for (int i = per - 1; i >= 0; i--) { // 倒序落地，制造交错
                    LlmActivity.markSettled(ID, tokens[tid * per + i], "stop", i);
                    settled.incrementAndGet();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发用例超时");

        assertEquals(threads * per, settled.get());
        LlmActivity.Snapshot s = LlmActivity.snapshot(ID);
        assertFalse(s.inFlight(), "全部落地后必须干净归零,不能有请求被漏销");
        assertEquals(0, s.inFlightCount());
    }
}
