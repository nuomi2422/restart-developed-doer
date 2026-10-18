package com.dwinovo.numen.rdd.fact;

import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照 + 投影的离线检查（第三批 N1 地基）。
 *
 * <p>逐条对齐计划里 N1 的验收：
 * <ol>
 *   <li>同伴/维度隔离；</li>
 *   <li><b>未知 ≠ 0 ≠ false</b>；</li>
 *   <li><b>过期不当当前</b>；</li>
 *   <li><b>同次采样一致</b>；</li>
 *   <li><b>投影不改变事实</b>；</li>
 *   <li>时钟可注入（不读系统时钟，所以过期行为可被单测钉住）。</li>
 * </ol>
 */
class FactSnapshotTest {

    private static final UUID A = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("22222222-0000-0000-0000-00000000000b");
    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    private static Goal goal(String id, String desc) {
        return new Goal(id, desc, List.of(new PrimaryGoal("p", "primary",
                List.of(new Subtask("s1", "do", DetectionMode.HARD_CODED,
                        Map.of("k", "v"), 5L, 3, false, null)), List.of(), false)));
    }

    private static CompletedFactStore factsAt(String goalId, String stage, long at) {
        CompletedFactStore s = new CompletedFactStore();
        s.recordStage(goal(goalId, "obj"), stage, at, "ev-" + stage);
        return s;
    }

    private static AssetRegistry registryWith(String key, int count) {
        AssetRegistry reg = new AssetRegistry();
        reg.apply(new Observation("o1", "inventory_scan", "test", "env-1", NOW, Map.of("count", count)),
                key, AssetScope.TASK_BOUND, "node-1");
        return reg;
    }

    // ── ① 同伴/维度隔离 ────────────────────────────────────────────────────

    @Test
    void snapshotIsBoundToItsCompanionAndDimension() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld", factsAt("g1", "mine", NOW),
                null, null, NOW, TTL);
        assertEquals(A, s.companionId());
        assertEquals("minecraft:overworld", s.dimensionId());
    }

    @Test
    void blankDimension_isRejected_notAcceptedAsNoIsolation() {
        // 空串会「看起来有维度」但实际不隔离 ⇒ 比 null 更危险
        assertThrows(IllegalArgumentException.class,
                () -> FactSnapshot.capture(A, "  ", null, null, null, NOW, TTL));
    }

    @Test
    void nullCompanion_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FactSnapshot.capture(null, "minecraft:overworld", null, null, null, NOW, TTL));
    }

    @Test
    void otherCompanionsSnapshot_isADifferentObject() {
        // 隔离是结构性的：A 的快照回答不了 B 的问题
        FactSnapshot sa = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, null, NOW, TTL);
        FactSnapshot sb = FactSnapshot.capture(B, "minecraft:overworld",
                factsAt("g2", "mine", NOW), null, null, NOW, TTL);
        // lookup 返回 Entry（带 rawStage/evidence），确定度要用 certaintyOf 取
        assertEquals(FactSnapshot.Certainty.KNOWN, sa.certaintyOf("mine"));
        assertEquals(FactSnapshot.Certainty.KNOWN, sb.certaintyOf("mine"));
        assertEquals("mine", sa.lookup("mine").stageKey());
        assertEquals("mine", sb.lookup("mine").stageKey());
        assertFalse(sa.companionId().equals(sb.companionId()),
                "两份快照必须属于不同同伴");
    }

    // ── ② 未知 ≠ 0 ≠ false ────────────────────────────────────────────────

    @Test
    void missingFact_isUnknown_notFalse() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, null, NOW, TTL);
        var e = s.lookup("build_house");
        assertEquals(FactSnapshot.Certainty.UNKNOWN, e.certainty(),
                "★ 没有记录 = 不知道，不是「false/没做」");
        assertFalse(s.isCurrent("build_house", NOW), "未知的东西不能当「已完成」用");
    }

    @Test
    void noFactsSource_yieldsAllUnknown_andSaysSo() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld", null, null, null, NOW, TTL);
        assertEquals(FactSnapshot.Certainty.UNKNOWN, s.lookup("anything").certainty());
        assertTrue(String.valueOf(s.notes()).contains("FACTS_UNAVAILABLE"),
                "要明说「没有事实来源」而不是让调用方以为「什么都没发生」");
    }

    @Test
    void emptyRegistry_meansUnknownQuantities_notZero() {
        // ★ DL-6 在快照层的落点
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), new AssetRegistry(), null, NOW, TTL);
        assertFalse(s.quantitiesKnown(),
                "空登记表 = 不知道持有量，不是「持有量为 0」");
        assertTrue(s.quantityOf("torch").isEmpty(),
                "★ 不知道时必须 Optional.empty()，绝不能返回 0");
    }

    @Test
    void knownQuantities_areDistinguishableFromUnknownOnes() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), registryWith("torch", 3), null, NOW, TTL);
        assertTrue(s.quantitiesKnown());
        assertEquals(3, s.quantityOf("torch").orElseThrow());
        assertTrue(s.quantityOf("diamond").isEmpty(),
                "★ 登记了但没有这一项 = 该项未知（没扫到），不是「有 0 个」");
    }

    // ── ② 用量维度未知 ≠ 0 ────────────────────────────────────────────────
    // （DL-4：空用量表不等于用量为 0；调用方必须先问 usageKnown()）

    @Test
    void noUsageSource_meansUnknownUsage_notZero() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, null, NOW, TTL);
        assertFalse(s.usageKnown(),
                "没有用量来源 ⇒ 用量表为空 = 不知道，不是「用量为 0」");
        assertTrue(s.observedUsageOf("anything").isEmpty(),
                "★ 不知道用量时必须 OptionalInt.empty()，绝不能返回 0");
    }

    @Test
    void usageIsolationPerCompanion() {
        // 同伴隔离也适用于用量：A 的快照不可见 B 的用量
        Map<String, Integer> usageA = Map.of("memo-1", 10);
        Map<String, Integer> usageB = Map.of("memo-1", 20);
        FactSnapshot sa = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, usageA, NOW, TTL);
        FactSnapshot sb = FactSnapshot.capture(B, "minecraft:overworld",
                factsAt("g2", "mine", NOW), null, usageB, NOW, TTL);
        assertTrue(sa.usageKnown());
        assertTrue(sb.usageKnown());
        assertEquals(10, sa.observedUsageOf("memo-1").orElseThrow());
        assertEquals(20, sb.observedUsageOf("memo-1").orElseThrow());
        assertFalse(sa.companionId().equals(sb.companionId()));
    }

    @Test
    void missingUsageKey_isUnknown_notZero() {
        Map<String, Integer> usage = Map.of("known-key", 5);
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, usage, NOW, TTL);
        // usageOf 只对已知键有值；查不存在的键必须是 UNKNOWN
        assertTrue(s.observedUsageOf("missing-key").isEmpty(),
                "★ 没有用量记录 = 不知道，不是 0");
    }

    // ── ③ 过期不当当前 ─────────────────────────────────────────────────────

    @Test
    void oldFact_isStale_notCurrent() {
        long at = NOW - TTL - 1;
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", at), null, null, NOW, TTL);
        assertEquals(FactSnapshot.Certainty.STALE, s.lookup("mine").certainty());
        assertFalse(s.isCurrent("mine", NOW), "★ 过期的事实在任何时候都不能当「当前」用");
    }

    @Test
    void freshFact_isCurrent() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW - 10), null, null, NOW, TTL);
        assertEquals(FactSnapshot.Certainty.KNOWN, s.lookup("mine").certainty());
        assertTrue(s.isCurrent("mine", NOW));
    }

    @Test
    void factThatGoesStaleAfterCapture_isDetectedAtReadTime() {
        // 快照采集时是 KNOWN，但读取时已超 TTL ⇒ 读取路径也要判一次
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, null, NOW, TTL);
        long later = NOW + TTL + 1;
        assertEquals(FactSnapshot.Certainty.KNOWN, s.certaintyOf("mine"),
                "采集时它确实是 KNOWN");
        assertEquals(FactSnapshot.Certainty.STALE,
                FactProjection.stageState(s, "mine", later),
                "★ 但过了一会儿再看，它必须已经是 STALE");
    }

    @Test
    void nonPositiveTtl_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FactSnapshot.capture(A, "minecraft:overworld", null, null, null, NOW, 0));
    }

    // ── ④ 同次采样一致 ─────────────────────────────────────────────────────

    @Test
    void repeatedReadsFromOneSnapshot_areIdentical() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), registryWith("torch", 3), null, NOW, TTL);
        var first = FactProjection.full(s);
        var second = FactProjection.full(s);
        assertEquals(first.rows().size(), second.rows().size());
        for (int i = 0; i < first.rows().size(); i++) {
            assertEquals(first.rows().get(i), second.rows().get(i),
                    "★ 同一次采样的两次读取必须完全一致（否则调用方会看到自己造成的漂移）");
        }
    }

    @Test
    void mutatingSourceAfterCapture_doesNotChangeSnapshot() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "snap-" + System.nanoTime());
        java.nio.file.Files.createDirectories(dir);
        AssetRegistry reg = registryWith("torch", 3);
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), reg, null, NOW, TTL);
        int before = s.entries().size();

        // 采集之后继续往登记里加东西
        reg.apply(new Observation("o2", "inventory_scan", "test", "env-1", NOW, Map.of("count", 9)),
                "diamond", AssetScope.TASK_BOUND, "node-2");

        assertEquals(before, s.entries().size(), "★ 快照必须是不可变的");
        assertTrue(s.quantityOf("diamond").isEmpty(),
                "采集之后的写入不许渗进已采集的快照");
        assertThrows(UnsupportedOperationException.class,
                () -> s.quantities().put("x", 1), "数量表必须不可修改");
        assertThrows(UnsupportedOperationException.class,
                () -> s.entries().clear(), "条目列表必须不可修改");
    }

    // ── ⑤ 投影不改变事实 ───────────────────────────────────────────────────

    @Test
    void projections_doNotMutateTheSnapshot() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                factsAt("g1", "mine", NOW), null, null, NOW, TTL);
        int before = s.entries().size();
        FactProjection.brief(s, FactProjection.Options.brief());
        FactProjection.full(s);
        FactProjection.forTask(s, List.of("mine", "build"), FactProjection.Options.task());
        assertEquals(before, s.entries().size());
        assertEquals(FactSnapshot.Certainty.KNOWN, s.certaintyOf("mine"),
                "投影后事实的确定度不能变");
    }

    @Test
    void nullSnapshot_isRejected_notTreatedAsEmpty() {
        // 「没有快照」与「快照是空的」是两件事
        assertThrows(IllegalArgumentException.class, () -> FactProjection.brief(null, null));
        assertThrows(IllegalArgumentException.class, () -> FactProjection.full(null));
    }
}
