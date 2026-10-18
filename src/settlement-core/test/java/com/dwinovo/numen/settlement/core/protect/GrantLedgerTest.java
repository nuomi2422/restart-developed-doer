package com.dwinovo.numen.settlement.core.protect;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 施工授权生命周期：用户点名"所有终止路径回收、重启/重复请求不乱账"。 */
class GrantLedgerTest {

    private static final String DIM = TestData.DIM;

    private static Grant grant(String facilityId) {
        return Grant.of(facilityId, DIM, BlockBox.of(0, 64, 0, 9, 69, 9));
    }

    @Test
    void openThenActiveIsVisible() {
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        assertEquals(1, led.size());
        assertTrue(led.isOpen("pen_a"));
        assertEquals(1, led.activeGrants().size());
    }

    @Test
    void reopeningSameFacilityReplacesInsteadOfLeakingTwo() {
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        led.open("pen_a", "t-2", grant("pen_a"));
        assertEquals(1, led.size(), "同一设施不许留两条授权");
        assertEquals("t-2", led.openGrants().get(0).taskId());
    }

    @Test
    void taskReplacementReclaimsGrant() {
        // 终止路径 ②：任务被别的动作顶替
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        var released = led.sweep("t-2");
        assertEquals(1, released.size());
        assertEquals("pen_a", released.get(0));
        assertFalse(led.isOpen("pen_a"), "被顶替后授权必须回收");
        assertTrue(led.activeGrants().isEmpty());
    }

    @Test
    void taskFinishedReclaimsGrant() {
        // 终止路径 ①：任务正常结束（槽空了）
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        assertEquals(1, led.sweep(null).size());
        assertTrue(led.activeGrants().isEmpty());
    }

    @Test
    void stillRunningTaskKeepsItsGrant() {
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        assertTrue(led.sweep("t-1").isEmpty(), "任务还在跑就不许撤");
        assertTrue(led.isOpen("pen_a"));
    }

    @Test
    void unboundGrantIsNotSweptAway() {
        // taskId 为空 = 调用方显式管理，不该被 tick 清扫误撤
        GrantLedger led = new GrantLedger();
        led.open("vault", null, grant("vault"));
        assertTrue(led.sweep("anything").isEmpty());
        assertTrue(led.isOpen("vault"));
    }

    @Test
    void expiredGrantIsSwept() {
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1",
                Grant.expiring("pen_a", DIM, BlockBox.of(0, 64, 0, 9, 69, 9), 1000L));
        assertEquals(0, led.sweepExpired(500L).size());
        assertEquals(1, led.sweepExpired(2000L).size());
        assertTrue(led.activeGrants().isEmpty());
    }

    @Test
    void restartLeavesNothingBehind() {
        // 终止路径 ③：进程重启 → 账本清空，持久保护继续生效
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        led.open("pen_b", "t-2", grant("pen_b"));
        led.clear();
        assertEquals(0, led.size());
        assertTrue(led.activeGrants().isEmpty());
    }

    @Test
    void explicitReleaseReclaimsOnlyThatFacility() {
        GrantLedger led = new GrantLedger();
        led.open("pen_a", "t-1", grant("pen_a"));
        led.open("pen_b", "t-2", grant("pen_b"));
        assertTrue(led.release("pen_a"));
        assertFalse(led.release("pen_a"), "重复回收返回 false");
        assertTrue(led.isOpen("pen_b"));
    }

    @Test
    void grantCoversOnlyItsOwnBoxAndDimension() {
        Grant g = Grant.of("pen_a", DIM, BlockBox.of(0, 64, 0, 9, 69, 9));
        assertTrue(g.covers(DIM, 5, 65, 5, 0L));
        assertFalse(g.covers(DIM, 50, 65, 5, 0L), "范围外不覆盖");
        assertFalse(g.covers("minecraft:the_nether", 5, 65, 5, 0L), "别的维度不覆盖");
    }
}
