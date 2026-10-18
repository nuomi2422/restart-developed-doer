package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 台账落盘契约 + F6 按批次回收判据 + F4 重复死亡判据（纯 JVM，无 IO）。
 *
 * <p>覆盖的旧缺口：台账只活在 {@code ConcurrentHashMap}（重启丢账）、
 * 回收判据"任意一件 LOST 物品回来即成功"（假完成）、连死两次分不清批次。
 */
class RddDeathLedgerBatchTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void snapshotRoundTripsThroughRestore() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "10, 64, -20", 12,
                Map.of("minecraft:iron_ingot", 8, "minecraft:bread", 4));
        RddDeathLedger.Death original = RddDeathLedger.latest(ID);

        RddDeathLedger.LedgerSnapshot saved = RddDeathLedger.snapshot(ID);
        RddDeathLedger.clearAll();
        assertTrue(RddDeathLedger.all(ID).isEmpty(), "clear 后应为空");

        RddDeathLedger.restore(ID, saved);
        RddDeathLedger.Death restored = RddDeathLedger.latest(ID);
        assertNotNull(restored, "重启后应能恢复出死亡记录");
        assertEquals(original.seq(), restored.seq());
        assertEquals(original.deathAt(), restored.deathAt());
        assertEquals(original.lostEntries(), restored.lostEntries());
        assertEquals(Map.of("minecraft:iron_ingot", 8, "minecraft:bread", 4), restored.lostItems());
    }

    @Test
    void restoreKeepsNextSeqAheadOfDiskSoSeqNeverCollides() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "1, 2, 3", 1, Map.of("minecraft:stone", 1));
        RddDeathLedger.LedgerSnapshot saved = RddDeathLedger.snapshot(ID);

        RddDeathLedger.clearAll();
        // 模拟"本次运行已记过一条 seq=1，磁盘上也有 seq=1"这种最坏时序
        RddDeathLedger.record(ID, 9000L, 9_000L, "9, 9, 9", 1, Map.of("minecraft:dirt", 1));
        RddDeathLedger.restore(ID, saved);

        // 下一条必须是新 seq（不能复用磁盘上的号，否则判据会串批）
        RddDeathLedger.Death next = RddDeathLedger.record(ID, 9500L, 9_500L, "5, 5, 5", 1);
        assertTrue(next.seq() > 1, "restore 后的新记录 seq 必须大于已有 seq，实际=" + next.seq());
    }

    @Test
    void oldRecordWithoutLostItemsIsToleratedAndNeverAutoSatisfied() {
        RddDeathLedger.clearAll();
        // 旧形状：没有 lostItems（本次改动之前写下的记录）
        RddDeathLedger.record(ID, 1000L, 5_000L, "10, 64, -20", 12);
        assertTrue(RddDeathLedger.latest(ID).lostItems().isEmpty());

        RddDeathLedger.LedgerSnapshot saved = RddDeathLedger.snapshot(ID);
        RddDeathLedger.clearAll();
        RddDeathLedger.restore(ID, saved);

        RddDeathLedger.Death restored = RddDeathLedger.latest(ID);
        assertEquals(12, restored.lostEntries(), "旧记录的丢失格数仍要留住");
        var r = RddDeathLedger.checkBatchRecovery(ID, restored.seq(),
                Map.of("minecraft:iron_ingot", 99));
        assertFalse(r.satisfied(), "无法逐项核对的旧批次不得判成功");
        assertEquals(0, r.tracked());
    }

    @Test
    void batchRecoveryRequiresEveryItemOfThatBatch() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "10, 64, -20", 12,
                Map.of("minecraft:iron_ingot", 8, "minecraft:bread", 4));

        // 旧判据在这一步会判成功（任意一件 >0）；新判据必须判未达成
        var oneOfEight = RddDeathLedger.checkBatchRecovery(ID, 1, Map.of("minecraft:iron_ingot", 1));
        assertFalse(oneOfEight.satisfied(), "只捡回 1/8 铁锭就判整批成功 = 旧假完成缺陷");
        assertEquals(2, oneOfEight.tracked());
        assertEquals(2, oneOfEight.outstanding(), "面包一个没捡，铁锭也只捡了 1/8 → 两项都算未齐");
        assertEquals(0.0d, oneOfEight.fraction(), "两个条目都未达标 → 比例 0");
        assertFalse(oneOfEight.anyBack(), "没有任何条目达到记录数量 = 无进展");

        var partial = RddDeathLedger.checkBatchRecovery(ID, 1,
                Map.of("minecraft:iron_ingot", 8, "minecraft:bread", 1));
        assertFalse(partial.satisfied(), "面包没捡齐就不算完成");
        assertEquals(1, partial.outstanding());
        assertTrue(partial.anyBack(), "应有进展，供宿主发 progress 事件");
        assertEquals(0.5d, partial.fraction());

        var full = RddDeathLedger.checkBatchRecovery(ID, 1,
                Map.of("minecraft:iron_ingot", 8, "minecraft:bread", 4));
        assertTrue(full.satisfied());
        assertEquals(1.0d, full.fraction());
    }

    @Test
    void recoveredQuantityIsCappedAtWhatWasActuallyLost() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 64, 0", 3, Map.of("minecraft:bread", 3));
        // 背包里 20 个面包 ≠ 当次丢了 20 个；但这次丢了 3 个，已捡齐 → 满足
        assertTrue(RddDeathLedger.checkBatchRecovery(ID, 1, Map.of("minecraft:bread", 20)).satisfied());
    }

    @Test
    void closedBatchCannotBeSatisfiedAgain() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 64, 0", 2, Map.of("minecraft:bread", 2));
        assertTrue(RddDeathLedger.checkBatchRecovery(ID, 1, Map.of("minecraft:bread", 2)).satisfied());

        RddDeathLedger.confirmRecovered(ID, 1, "picked up");
        assertNull(RddDeathLedger.openDeath(ID, 1), "已闭合批次不再开放");
        assertFalse(RddDeathLedger.checkBatchRecovery(ID, 1, Map.of("minecraft:bread", 2)).satisfied(),
                "闭合批次不得被再次判成功（否则同一条支线会重复结账）");
    }

    @Test
    void latestOpenIgnoresOlderBatchSoOldDropsCannotPayNewDeath() {
        RddDeathLedger.clearAll();
        // 第一次死亡：丢 5 个面包（后来捡回来了）
        RddDeathLedger.record(ID, 1000L, 5_000L, "10, 64, -20", 5, Map.of("minecraft:bread", 5));
        RddDeathLedger.confirmRecovered(ID, 1, "first batch done");
        // 第二次死亡：丢铁锭，一个都还没捡
        RddDeathLedger.record(ID, 2000L, 6_000L, "20, 64, -20", 3,
                Map.of("minecraft:iron_ingot", 3));

        assertEquals(2, RddDeathLedger.latestOpen(ID).seq(), "判据只认最新未闭合批次");
        // 背包里还有第一次捡回的面包 —— 绝不能替第二次结账
        assertFalse(RddDeathLedger.checkBatchRecovery(ID, 2, Map.of("minecraft:bread", 5)).satisfied(),
                "旧批次的物品回来，不得判新批次已回收");
    }

    @Test
    void batchWithoutMatchingSeqIsNotMatched() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 64, 0", 1, Map.of("minecraft:bread", 1));
        var r = RddDeathLedger.checkBatchRecovery(ID, 99, Map.of("minecraft:bread", 1));
        assertFalse(r.matched(), "不存在的 seq 必须显式 not matched");
        assertFalse(r.satisfied());
    }

    @Test
    void repeatDeathDetectedOnlyInsideWindowAndRadius() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "700, 64, 671", 32);
        long now = 1000L + 62L * 20L;   // 实机 #3→#4 只隔 62 秒

        assertNotNull(RddDeathLedger.repeatDeathWithin(ID, now, 700, 65, 671, 120L * 20L, 8.0d),
                "62 秒内、同一点附近 = 重复死亡");
        assertNull(RddDeathLedger.repeatDeathWithin(ID, now, 900, 65, 671, 120L * 20L, 8.0d),
                "距离远超半径 = 不是重复死亡");
        assertNull(RddDeathLedger.repeatDeathWithin(ID, now + 400L * 20L, 700, 65, 671, 120L * 20L, 8.0d),
                "超出时间窗 = 不是重复死亡");
    }

    @Test
    void repeatDeathIgnoresUnparseableDeathLocation() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "?", 1);   // deathAt 不可解析
        assertNull(RddDeathLedger.repeatDeathWithin(ID, 1100L, 1, 2, 3, 120L * 20L, 8.0d),
                "位置未知就不能断言'同一点'，宁可漏判不可误判");
    }

    /**
     * 2026-09-30 实机回归：只杀一次就误发 {@code repeat_death_nearby}。
     *
     * <p>根因：宿主是「先 record 再问判据」，刚写下的那条就在台账里，
     * 与自己距离 0、时间差 0 → 自己跟自己构成"重复死亡"。
     * 实机事件原文：{@code previousDeathSeq=1}、{@code nowAt} 与 previousDeathAt 完全相同、
     * {@code secondsSincePrevious=0}。后果是每次死亡都走保守恢复分支。
     */
    @Test
    void justRecordedDeathDoesNotMatchItself() {
        RddDeathLedger.clearAll();
        RddDeathLedger.Death first = RddDeathLedger.record(ID, 1000L, 5_000L, "620, 71, 704", 20,
                Map.of("minecraft:iron_ingot", 20));

        // 宿主视角：刚记完 seq=1，立刻问"是不是又死在同一点"
        assertNull(RddDeathLedger.repeatDeathWithin(ID, 1000L, 620, 71, 704, 180L * 20L, 8.0d, first.seq()),
                "必须排除本次死亡自己，否则每次死亡都被误判成'同一点连死'");

        // 不传 excludeSeq 的旧重载确实会自匹配 —— 这正是实机看到的形状，锁住它以防有人改回去
        assertEquals(1, RddDeathLedger.repeatDeathWithin(ID, 1000L, 620, 71, 704, 180L * 20L, 8.0d).seq(),
                "无排除参数的旧路径仍然会自匹配（生产路径不得使用它）");

        // 真正的第二次死亡：排除自己之后仍应命中上一次
        RddDeathLedger.Death second = RddDeathLedger.record(ID, 1000L + 62L * 20L, 6_000L, "620, 71, 705", 20,
                Map.of("minecraft:iron_ingot", 20));
        assertEquals(1, RddDeathLedger.repeatDeathWithin(ID, 1000L + 62L * 20L, 620, 71, 705,
                180L * 20L, 8.0d, second.seq()).seq(),
                "真正的重复死亡（62 秒、1 格）必须仍被判出");
    }

    @Test
    void snapshotOfUnknownCompanionIsEmptyNotNull() {
        RddDeathLedger.clearAll();
        RddDeathLedger.LedgerSnapshot s = RddDeathLedger.snapshot(UUID.randomUUID());
        assertNotNull(s, "宿主要能对'没死过的同伴'也导出空账本");
        assertEquals(1, s.nextSeq(), "空账本的下一个 seq 是 1");
        assertTrue(s.deaths().isEmpty());
        assertEquals(List.of(), s.deaths());
    }

    @Test
    void nextSeqMeansTheSeqTheNextRecordWillUse() {
        RddDeathLedger.clearAll();
        assertEquals(1, RddDeathLedger.snapshot(ID).nextSeq(), "空账本：下一条是 seq 1");
        assertEquals(1, RddDeathLedger.record(ID, 1L, 1L, "0,0,0", 0).seq());
        assertEquals(2, RddDeathLedger.snapshot(ID).nextSeq(), "记过一条后：下一条是 seq 2");
        assertEquals(2, RddDeathLedger.record(ID, 2L, 2L, "0,0,0", 0).seq());

        // 落盘 → 恢复 → 下一条仍不得撞号
        RddDeathLedger.LedgerSnapshot saved = RddDeathLedger.snapshot(ID);
        RddDeathLedger.clearAll();
        RddDeathLedger.restore(ID, saved);
        assertEquals(3, RddDeathLedger.snapshot(ID).nextSeq());
        assertEquals(3, RddDeathLedger.record(ID, 3L, 3L, "0,0,0", 0).seq(),
                "恢复后继续递增，seq 永不撞号（撞号 = 批次身份混淆）");
    }

    @Test
    void restoreTrimsToMaxEntriesAndKeepsNewest() {
        RddDeathLedger.clearAll();
        for (int i = 1; i <= RddDeathLedger.MAX_ENTRIES + 4; i++) {
            RddDeathLedger.record(ID, 1000L * i, 5_000L, i + ", 64, 0", 1, Map.of("minecraft:stone", 1));
        }
        RddDeathLedger.LedgerSnapshot saved = RddDeathLedger.snapshot(ID);
        RddDeathLedger.clearAll();
        RddDeathLedger.restore(ID, saved);

        List<RddDeathLedger.Death> all = RddDeathLedger.all(ID);
        assertTrue(all.size() <= 8, "有界：不得无限增长（契约上限 8，用字面量钉，见 RddRedlineContractPinTest）");
        assertEquals(saved.deaths().size(), all.size());
        for (int i = 0; i < all.size() - 1; i++) {
            assertTrue(all.get(i).gameTime() >= all.get(i + 1).gameTime(), "仍按时间倒序");
        }
    }

    @Test
    void restoreWithNullSnapshotIsNoOp() {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 64, 0", 1, Map.of("minecraft:bread", 1));
        RddDeathLedger.restore(ID, null);
        assertEquals(1, RddDeathLedger.all(ID).size(), "坏/空快照不得清掉内存里的记录");
    }

    @Test
    void lostItemsAreDefensivelyCopied() {
        RddDeathLedger.clearAll();
        java.util.Map<String, Integer> mutable = new java.util.LinkedHashMap<>();
        mutable.put("minecraft:bread", 2);
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 64, 0", 2, mutable);
        mutable.put("minecraft:bread", 999);
        assertEquals(Map.of("minecraft:bread", 2), RddDeathLedger.latest(ID).lostItems(),
                "记完就不可变：调用方改自己的 map 不得改写账本");
    }
}