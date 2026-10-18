package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.llm.LlmActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接缝测试：{@code :ai} 的在飞快照 → {@code plugins:rdd} 的拍醒闸。
 *
 * <p><b>为什么要有这个类，而不只是各侧各测一遍</b>（2026-09-30 教训）：
 * 统一版 36 号页第四节记过一次同族事故 ——
 * 「<b>生产侧与消费侧的『新东西认不认识』必须同时改</b>」，漏改入队条件就是
 * <b>静默失败：事件照发，但没人数</b>。这次改的是同一种接缝
 * （:ai 产出快照 / rdd 消费快照），所以必须在<b>插件这一侧</b>证明它真的读得到。
 *
 * <p>它同时是 36 号页那条「插件 compileOnly 类路径」约束的活体检：
 * {@code plugins/rdd/build.gradle} 里有 {@code testImplementation project(':ai')}，
 * 插件运行时靠 {@code compileOnly project(':ai')} 拿类 —— 两边都得通。
 */
class LlmActivityGateTest {

    private final String companionId = UUID.nameUUIDFromBytes("rdd-gate-test".getBytes()).toString();

    /** rdd 侧真正用到的那个组合表达式，在这里复刻一次，避免两侧算法漂移。 */
    private static boolean treatAsInFlight(LlmActivity.Snapshot s) {
        return s.inFlight() && !s.stale();
    }

    @AfterEach void forget() {
        LlmActivity.forget(companionId);
    }

    /** 验收判据①：LLM 在飞 30s、资产零变化 → 绝不能拍醒。 */
    @Test void inFlightCompanionIsNeverNudged() {
        long token = LlmActivity.markDispatched(companionId, "execution");
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        for (int n = 0; n <= threshold + 30; n++) {
            LlmActivity.Snapshot s = LlmActivity.snapshot(companionId);
            assertFalse(s.stale(), "刚发出去不算 stale");
            assertTrue(s.inFlight(), "接缝断了：rdd 侧读不到 :ai 的在飞状态");
            assertFalse(RddStallPolicy.shouldNudgeLlmIdle(n, "idle", treatAsInFlight(s)),
                    "在飞时 n=" + n + " 绝不能拍");
        }
        LlmActivity.markSettled(companionId, token, "stop", 0);
    }

    /** 验收判据②：LLM 已返回（不在飞）→ 必须还能拍。 */
    @Test void settledCompanionStillGetsNudged() {
        long token = LlmActivity.markDispatched(companionId, "execution");
        LlmActivity.markSettled(companionId, token, "stop", 0);
        LlmActivity.Snapshot s = LlmActivity.snapshot(companionId);
        assertFalse(s.inFlight(), "落地了就不该还在飞");
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        assertFalse(RddStallPolicy.shouldNudgeLlmIdle(threshold - 1, "idle", treatAsInFlight(s)));
        assertTrue(RddStallPolicy.shouldNudgeLlmIdle(threshold, "idle", treatAsInFlight(s)),
                "已落地却不敢拍 = 卡死监督被静默关掉，比误拍更糟");
    }

    /**
     * 挂死/被取消的请求不许把监督关掉：stale 之后按「不在飞」用。
     * 这是唯一一条「在飞为真但仍要拍」的路，锁死它。
     */
    @Test void hungRequestFallsBackToNudging() {
        LlmActivity.markDispatched(companionId, "execution");
        long now = System.nanoTime() + LlmActivity.MAX_INFLIGHT_NANOS + 1L;
        LlmActivity.Snapshot s = LlmActivity.snapshot(companionId, now);
        assertTrue(s.inFlight(), "记录本身还是 inFlight");
        assertTrue(s.stale(), "挂死必须被判 stale");
        assertTrue(RddStallPolicy.shouldNudgeLlmIdle(
                        RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS, "idle", treatAsInFlight(s)),
                "stale 之后必须恢复拍醒能力，否则 future 挂一次 = 监督永久失明");
    }

    /** 没见过这个同伴 → 不认在飞 → 行为与加闸前逐字相同（fail-open）。 */
    @Test void unknownCompanionBehavesExactlyAsBeforeTheGate() {
        LlmActivity.Snapshot s = LlmActivity.snapshot("never-seen-" + companionId);
        assertFalse(s.known());
        assertFalse(s.inFlight());
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        assertTrue(RddStallPolicy.shouldNudgeLlmIdle(threshold, "idle", treatAsInFlight(s)),
                "宿主认不出这个同伴时，不许悄悄把监督关掉");
    }

    /**
     * ★ Codex 审稿 P0 的接缝侧：并发两个请求，先落地的那个不许让 rdd 恢复拍醒。
     * 单测在 :ai 侧已锁；这里证明 rdd 消费的是同一套 token 语义。
     */
    @Test void concurrentRequestsDoNotDisableTheGate() {
        long a = LlmActivity.markDispatched(companionId, "execution");
        long b = LlmActivity.markDispatched(companionId, "execution_retry");
        LlmActivity.markSettled(companionId, a, "stop", 0);
        LlmActivity.Snapshot s = LlmActivity.snapshot(companionId);
        assertTrue(s.inFlight(), "还有一个请求在飞");
        assertEquals(1, s.inFlightCount());
        assertFalse(RddStallPolicy.shouldNudgeLlmIdle(
                        RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS, "idle", treatAsInFlight(s)),
                "B 还在飞就不许拍 ← P0 在消费侧的断言");
        LlmActivity.markSettled(companionId, b, "stop", 0);
        assertFalse(LlmActivity.snapshot(companionId).inFlight());
    }

    /**
     * stale 告警只发一次（Codex 审稿 P1-4：否则 track() 每秒一条，挂死几小时几万条）。
     */
    @Test void staleAlertIsDeduplicatedAcrossTicks() {
        LlmActivity.markDispatched(companionId, "execution");
        long far = System.nanoTime() + LlmActivity.MAX_INFLIGHT_NANOS + 1L;
        assertTrue(LlmActivity.snapshot(companionId, far).staleUnreported(), "第一次该报");
        LlmActivity.markStaleReported(companionId, LlmActivity.snapshot(companionId, far).oldestInFlightToken());
        for (long tick = 1; tick <= 5; tick++) {
            LlmActivity.Snapshot s = LlmActivity.snapshot(companionId, far + tick * 1_000_000_000L);
            assertTrue(s.stale(), "tick " + tick + " 仍然 stale");
            assertFalse(s.staleUnreported(), "tick " + tick + " 不许重复报");
        }
    }
}
