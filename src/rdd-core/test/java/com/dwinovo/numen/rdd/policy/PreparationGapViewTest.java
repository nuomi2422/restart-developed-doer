package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险准备缺口视图的离线检查（第三批 N3 第一步）。
 *
 * <p>守一条<b>会真出事</b>的规则：
 * <b>没有持有数据时不得产出准备缺口</b>。因为
 * {@code RiskGate.check} 在持有表为空时会把所有准备都判成 missing ——
 * 直接报出去等于让人去准备一堆其实已经有的东西。
 */
class PreparationGapViewTest {

    private static Goal goal(String id, String description) {
        return new Goal(id, description,
                List.of(new PrimaryGoal("p", "primary",
                        List.of(new Subtask("s1", "do", DetectionMode.HARD_CODED,
                                Map.of("k", "v"), 5L, 3, false, null)),
                        List.of(), false)));
    }

    private static RequirementManifest.Manifest manifest(String goalId, int min, String... keys) {
        List<RequirementManifest.Requirement> reqs = new java.util.ArrayList<>();
        for (String k : keys) {
            reqs.add(new RequirementManifest.Requirement(k, min, List.of()));
        }
        return new RequirementManifest.Manifest(goalId, reqs);
    }

    private static AssetRegistry registryWith(Map<String, Integer> held) {
        AssetRegistry reg = new AssetRegistry();
        int i = 0;
        for (var e : held.entrySet()) {
            reg.apply(new Observation("obs-" + (i++), "inventory_scan", "test", "env-1", 1000L,
                            Map.of("count", e.getValue())),
                    e.getKey(), AssetScope.TASK_BOUND, "node-1");
        }
        return reg;
    }

    @Test
    void emptyRegistry_producesNoPreparationGaps_andSaysWhy() {
        // ★ 本类最重要的一条：没扫背包 ⇒ 不产出准备缺口
        var v = PreparationGapView.build(manifest("goal-1", 1, "diamond"), null,
                new CompletedFactStore(), new AssetRegistry());
        assertFalse(v.usable());
        assertEquals(PreparationGapView.HeldState.UNKNOWN_HELD, v.heldState());
        assertTrue(v.preparationGaps().isEmpty(),
                "★ 没有持有数据时准备缺口必须为空（RiskGate 在空表下会判一切 missing）");
        assertTrue(String.valueOf(v.toMap().get("warning")).contains("判断不了"),
                "★ 空列表要标成「判断不了」而不是「什么都不缺」");
    }

    @Test
    void nullRegistry_alsoProducesNoGaps() {
        var v = PreparationGapView.build(manifest("goal-1", 1, "diamond"), null, null, null);
        assertFalse(v.usable());
        assertTrue(v.preparationGaps().isEmpty());
    }

    @Test
    void withHeldData_itIsUsable() {
        var v = PreparationGapView.build(manifest("goal-1", 1, "diamond"), null,
                new CompletedFactStore(), registryWith(Map.of("torch", 8)));
        assertTrue(v.usable(), "有持有数据就该可用");
        assertEquals(PreparationGapView.HeldState.KNOWN, v.heldState());
    }

    @Test
    void noManifest_isReportedAndNotUsable() {
        var v = PreparationGapView.build(null, null, null, registryWith(Map.of("torch", 8)));
        assertFalse(v.usable());
        assertTrue(String.valueOf(v.notes()).contains("HAS_NO_MANIFEST"));
    }

    @Test
    void riskLevelAndVerdict_areRecordedAsNotes() {
        // RiskGate 的判定是别人的结论，这里只转述并标注来源，不改它
        var v = PreparationGapView.build(manifest("goal-1", 1, "diamond"),
                RiskLevel.NORMAL, new CompletedFactStore(),
                registryWith(Map.of("torch", 8)));
        assertTrue(String.valueOf(v.notes()).contains("risk_level="),
                "要把 RiskGate 的判定与等级记进 notes（可追溯）: " + v.notes());
    }

    @Test
    void viewDoesNotMutateAnything() {
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "diamond", 1000L, "ev");
        AssetRegistry reg = registryWith(Map.of("torch", 8));
        int facts = f.stageCount();
        int held = reg.usableCounts().size();

        PreparationGapView.build(manifest("goal-1", 2, "diamond"), null, f, reg);

        assertEquals(facts, f.stageCount(), "★ 不改事实");
        assertEquals(held, reg.usableCounts().size(), "★ 不改资产登记");
    }

    @Test
    void preparationGaps_areNotIntersectedWithRequirementGaps() {
        // 两边键空间不保证同源 ⇒ 报告里必须说「刻意不求交」
        var v = PreparationGapView.build(manifest("goal-1", 1, "diamond"), null,
                new CompletedFactStore(), registryWith(Map.of("torch", 8)));
        assertTrue(String.valueOf(v.toMap().get("note")).contains("不求交"),
                "★ 要明说不求交（求交需要键空间一致，那正是 N1 踩过的坑）");
    }
}