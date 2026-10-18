package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 死亡台账：每一次死亡单独一行，带掉落物到期时刻。
 *
 * <p>这些断言锁的是用户 2026-09-29 报的两个真 bug：
 * ①「他以为自己的东西已经没有了」——死亡后 5 分钟内掉落物**还在地上**，
 * 但旧实现只发"失效 N 项"，没有任何人告诉她还剩多少秒；
 * ②「死多次就分不清」——实机 15:50:53 死在 (200,51,-130)、15:51:09 又死在
 * (134,31,-96)，相隔 16 秒，两个掉落点的到期时刻不同。
 */
class RddDeathLedgerTest {

    private UUID id;

    @BeforeEach void setUp() {
        RddDeathLedger.clearAll();
        id = UUID.randomUUID();
    }

    @Test void nothingRecordedMeansNothingToRecover() {
        assertEquals(0, RddDeathLedger.deathsRecorded(id));
        assertNull(RddDeathLedger.latest(id));
        assertTrue(RddDeathLedger.render(id, 1000L).contains("no death recorded"));
    }

    @Test void dropsAreStillRecoverableForFiveMinutes() {
        var d = RddDeathLedger.record(id, 1000L, System.currentTimeMillis(), "200, 51, -130", 29);
        assertEquals(1, d.seq());
        assertEquals("200, 51, -130", d.deathAt());
        assertTrue(d.stillRecoverable(1000L));
        assertEquals(300, d.secondsLeft(1000L), "刚死就该有 5 分钟 = 300 秒");
        // 4 分 59 秒：还在
        assertTrue(d.stillRecoverable(1000L + (20 * 60 * 4) + 19));
        // 正好 5 分钟：过期（与原版 despawn 同口径）
        // ★ 6000 是字面量（20 tick × 60 秒 × 5 分钟 = 6000 tick），刻意不用 DROPS_LIVE_TICKS。
        //   变异测试实测：写成常量时改掉窗口本测试不会红。详见 RddRedlineContractPinTest。
        assertFalse(d.stillRecoverable(1000L + 6000L));
        assertEquals(0, d.ticksLeft(1000L + 6000L));
    }

    @Test void twoDeathsSixteenSecondsApartKeepSeparateCountdowns() {
        // 实机原样复现：15:50:53 与 15:51:09，相隔 16 秒（320 tick）
        var first = RddDeathLedger.record(id, 1000L, 0L, "200, 51, -130", 29);
        var second = RddDeathLedger.record(id, 1320L, 0L, "134, 31, -96", 29);
        assertEquals(1, first.seq());
        assertEquals(2, second.seq(), "序号必须递增，否则分不清哪一次是哪一次");

        // 过 50 秒（2000 tick）：第一次还剩 250 秒，第二次还剩 266 秒
        long now = 2000L;
        assertEquals(250, first.secondsLeft(now));
        assertEquals(266, second.secondsLeft(now));
        assertEquals(2, RddDeathLedger.recoverable(id, now).size());

        // 过 100 秒（7000 tick）：第一次刚好满 5 分钟已过期，第二次还剩 16 秒
        now = 7000L;
        assertFalse(first.stillRecoverable(now));
        assertTrue(second.stillRecoverable(now));
        assertEquals(1, RddDeathLedger.recoverable(id, now).size());
        assertEquals(1, RddDeathLedger.expired(id, now).size());
    }

    @Test void recoverableIsOrderedSoTheMostUrgentComesFirst() {
        RddDeathLedger.record(id, 1000L, 0L, "A", 5);
        RddDeathLedger.record(id, 2000L, 0L, "B", 5);
        var live = RddDeathLedger.recoverable(id, 2500L);
        assertEquals(2, live.size());
        assertEquals("A", live.get(0).deathAt(), "先捡最先过期的那个");
        assertEquals("B", live.get(1).deathAt());
    }

    @Test void renderTellsHerToGoWhenThereIsTimeAndToStopWhenThereIsNot() {
        // 有窗口：必须给出坐标 + 倒计时，否则她就按"东西没了"重新规划
        RddDeathLedger.record(id, 1000L, 0L, "200, 51, -130", 29);
        var live = RddDeathLedger.render(id, 2000L);
        assertTrue(live.contains("200, 51, -130"), live);
        assertTrue(live.contains("est. despawns in 250s"), live);
        assertTrue(live.contains("still within the pickup window"), live);

        // 超窗但**没有证据** → 必须说"可能还在，别断言没了"（深审 R07）
        var gone = RddDeathLedger.render(id, 1000L + RddDeathLedger.DROPS_LIVE_TICKS + 1);
        assertTrue(gone.contains("does NOT prove"), gone);
        assertTrue(gone.contains("unloaded"), gone);
        assertFalse(gone.contains("permanently gone"),
                "没有真实证据就不能宣布永久丢失：" + gone);
    }

    @Test void onlyConfirmedLossMayBeCalledPermanentlyGone() {
        RddDeathLedger.record(id, 1000L, 0L, "200, 51, -130", 29);
        long after = 1000L + RddDeathLedger.DROPS_LIVE_TICKS + 1;

        // 超窗但未确认：不算 confirmedLost
        assertEquals(1, RddDeathLedger.expired(id, after).size());
        assertEquals(0, RddDeathLedger.confirmedLost(id).size());

        // 拿到真实证据后，才允许说"永久没了"
        assertTrue(RddDeathLedger.confirmLost(id, 1, "scanned the area: no drop entities remain"));
        assertEquals(0, RddDeathLedger.expired(id, after).size(), "确认丢失后不再计入过期列表");
        var said = RddDeathLedger.render(id, after);
        assertTrue(said.contains("CONFIRMED gone"), said);
    }

    @Test void recoveredDropsAreNoLongerRecoveryTargets() {
        RddDeathLedger.record(id, 1000L, 0L, "A", 5);
        assertEquals(1, RddDeathLedger.recoverable(id, 2000L).size());
        assertTrue(RddDeathLedger.confirmRecovered(id, 1, "picked up all 5"));
        assertEquals(0, RddDeathLedger.recoverable(id, 2000L).size(), "已捡回的不该再出现在待捡列表");
        assertEquals(1, RddDeathLedger.recovered(id).size());
    }

    @Test void renderExplainsWhichOfTwoToDoFirst() {
        RddDeathLedger.record(id, 1000L, 0L, "A", 5);
        RddDeathLedger.record(id, 1320L, 0L, "B", 5);
        var r = RddDeathLedger.render(id, 2000L);
        assertTrue(r.contains("2 death drop(s) still within the pickup window"), r);
        assertTrue(r.contains("NEAREST one first"), r);
    }

    @Test void ledgerIsBoundedSoACrashLoopCannotInflateThePayload() {
        // ★ 8 与 24 都是字面量（上限 8 条 / 灌 24 条逼出封顶），刻意不用 MAX_ENTRIES。
        //   变异测试实测：写成常量时把上限改成 64，本测试仍然绿——循环也跟着变成灌 192 条，
        //   于是「封顶到 64」照样成立，测不出「上限其实是 8」。
        for (int i = 0; i < 24; i++) {
            RddDeathLedger.record(id, 1000L + i * 20L, 0L, "P" + i, 1);
        }
        assertEquals(8, RddDeathLedger.deathsRecorded(id));
        // 保留的是最新的那几条
        assertEquals("P23", RddDeathLedger.latest(id).deathAt());
    }

    @Test void ledgersArePerCompanionAndClearable() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        RddDeathLedger.record(a, 1L, 0L, "A1", 1);
        RddDeathLedger.record(b, 1L, 0L, "B1", 1);
        assertEquals(1, RddDeathLedger.deathsRecorded(a));
        assertEquals(1, RddDeathLedger.deathsRecorded(b));
        RddDeathLedger.clear(a);
        assertEquals(0, RddDeathLedger.deathsRecorded(a));
        assertEquals(1, RddDeathLedger.deathsRecorded(b), "清一个不能连带清另一个");
    }
}
