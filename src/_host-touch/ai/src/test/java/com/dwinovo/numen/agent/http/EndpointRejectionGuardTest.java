package com.dwinovo.numen.agent.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「端点连续拒收」判据的契约测试（2026-10-06）。
 *
 * <p><b>为什么值得钉</b>：这条判据决定「什么时候停止开新轮」。判松了 → 继续拿坏端点白烧调用
 * （实测烧光 3 次重规划预算、卡 16 分钟）；判紧了 → 把本来只是偶发一次的同伴按死。
 * 两个方向都出过事，所以边界值必须逐条写死。
 */
class EndpointRejectionGuardTest {

    private static final long T0 = 1_000_000L;

    @Test
    void threeConsecutiveRejections_isRejecting() {
        EndpointRejectionGuard g = new EndpointRejectionGuard(3, 60_000L);
        assertFalse(g.rejecting(T0), "一次都没被拒时不许判故障");

        assertTrue(g.noteTurnFailure(400, T0));
        assertFalse(g.rejecting(T0), "1 个回合还不算连续拒收");
        assertTrue(g.noteTurnFailure(400, T0 + 1_000));
        assertFalse(g.rejecting(T0 + 1_000), "2 个回合还不算");
        assertTrue(g.noteTurnFailure(400, T0 + 2_000));
        assertTrue(g.rejecting(T0 + 2_000), "★ 第 3 个回合必须判成「正在拒收」");
        assertEquals(3, g.consecutiveTurns());
        assertEquals(400, g.lastStatus());
    }

    @Test
    void cooldownLetsItRecover_byItself() {
        // ★ 端点拒收常常是 payload 相关、下一轮就可能好了 —— 永久锁死会把已恢复的同伴按死。
        EndpointRejectionGuard g = new EndpointRejectionGuard(3, 60_000L);
        g.noteTurnFailure(400, T0);
        g.noteTurnFailure(400, T0);
        g.noteTurnFailure(400, T0);
        assertTrue(g.rejecting(T0));
        assertTrue(g.rejecting(T0 + 59_999), "冷却期没过，仍然算拒收");
        assertFalse(g.rejecting(T0 + 60_000), "★ 冷却期一过必须自动放行（否则永久锁死）");
    }

    @Test
    void oneSuccessClearsTheStreak() {
        EndpointRejectionGuard g = new EndpointRejectionGuard(3, 60_000L);
        g.noteTurnFailure(400, T0);
        g.noteTurnFailure(400, T0);
        g.clear();                                  // 中间成功了一回合
        assertEquals(0, g.consecutiveTurns(), "清零后不该还攒着旧账");

        g.noteTurnFailure(400, T0);
        g.noteTurnFailure(400, T0);
        assertFalse(g.rejecting(T0), "★ 熔断只针对「连续」被拒，不针对「历史上被拒过」");
    }

    @Test
    void rateLimitAndServerAndNetworkFailures_doNotCount() {
        EndpointRejectionGuard g = new EndpointRejectionGuard(3, 60_000L);
        assertFalse(g.noteTurnFailure(429, T0), "429 是「待会儿行」，不算故障");
        assertFalse(g.noteTurnFailure(500, T0), "5xx 由传输层自己退避，不算");
        assertFalse(g.noteTurnFailure(503, T0), "5xx 同上");
        assertFalse(g.noteTurnFailure(-1, T0), "网络类故障（非 HTTP）不算");
        assertFalse(g.noteTurnFailure(200, T0), "200 当然不算");
        assertEquals(0, g.consecutiveTurns());
        assertFalse(g.rejecting(T0));
    }

    @Test
    void allClientErrorsExcept429_count() {
        for (int s : new int[] { 400, 401, 403, 404, 422 }) {
            EndpointRejectionGuard g = new EndpointRejectionGuard(1, 60_000L);
            assertTrue(g.noteTurnFailure(s, T0), s + " 是「请求不被接受」，必须计入");
            assertTrue(g.rejecting(T0), s + " 计满阈值后要判故障");
        }
    }

    @Test
    void thresholdIsAtLeastOne_evenIfConstructorGetsZero() {
        // ⚠️ 冷却期要传一个正常值：cooldownMs=0 的语义是「不设冷却」⇒ rejecting() 恒为 false
        //    （见构造函数注释）。这里要验的是「阈值被收敛成 ≥1」，不是冷却。
        EndpointRejectionGuard g = new EndpointRejectionGuard(0, 60_000L);
        assertEquals(0, g.consecutiveTurns());
        g.noteTurnFailure(400, T0);
        assertTrue(g.rejecting(T0), "阈值被传 0 时收敛成 1，而不是「永不触发」");
    }

    @Test
    void zeroCooldownMeansNoCooldown_soItNeverBlocks() {
        // 把这条退化语义写死：免得以后有人以为 cooldownMs=0 会「立刻恢复」以外的意思。
        EndpointRejectionGuard g = new EndpointRejectionGuard(1, 0L);
        g.noteTurnFailure(400, T0);
        assertFalse(g.rejecting(T0), "cooldownMs=0 ⇒ 不设冷却 ⇒ 不判持续故障");
    }
}
