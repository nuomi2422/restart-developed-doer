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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三列并排视图的离线检查（第三批 N2 第三步）。
 *
 * <p>守两件真正会出事的事：
 * <ol>
 *   <li>★ <b>「事实说完成、持有却不够」必须被挑出来</b> ——
 *       这是两份报告分开看时需要人自己在脑子里做减法才能发现的组合；</li>
 *   <li>★ 持有量<b>未知</b>时不能报「不用看」——那是「判断不了」，不是「没问题」。</li>
 * </ol>
 */
class RequirementViewTest {

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

    /** 照 AssetRegistry.usableCounts() 的真实口径造登记：type=inventory_scan + value.count。 */
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
    void doneButNotHeld_isFlaggedAsAttention() {
        // ★ 核心：事实里有完成记录，但持有量不足 ⇒ 值得人看一眼
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "diamond", 1000L, "ev");
        // 需求 minimum=2，持有 1
        var v = RequirementView.build(manifest("goal-1", 2, "diamond"), f,
                registryWith(Map.of("diamond", 1)), null);

        assertEquals(1, v.rows().size());
        var row = v.rows().get(0);
        assertEquals(RequirementView.Attention.DONE_BUT_NOT_HELD, row.attention());
        assertEquals(1, row.heldGap());
        assertTrue(row.factCount() >= 1, "事实列要能看到记录数");
        assertEquals(1, v.attentionCount());
    }

    @Test
    void notDoneAndNotHeld_isFlagged() {
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "other_stage", 1000L, "ev");
        var v = RequirementView.build(manifest("goal-1", 2, "diamond"), f,
                registryWith(Map.of("diamond", 0)), null);
        assertEquals(RequirementView.Attention.NOT_DONE_AND_NOT_HELD, v.rows().get(0).attention());
    }

    @Test
    void heldEnough_needsNoAttention() {
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "diamond", 1000L, "ev");
        var v = RequirementView.build(manifest("goal-1", 2, "diamond"), f,
                registryWith(Map.of("diamond", 5)), null);
        assertEquals(RequirementView.Attention.NONE, v.rows().get(0).attention());
        assertEquals(0, v.attentionCount());
    }

    @Test
    void heldButNoFact_isFlaggedSeparately() {
        // 持有已达标但事实里没有 ⇒ 可能是事实没记，不一定是没做
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        var v = RequirementView.build(manifest("goal-1", 1, "diamond"), f,
                registryWith(Map.of("diamond", 2)), null);
        assertEquals(RequirementView.Attention.HELD_BUT_NO_FACT, v.rows().get(0).attention());
    }

    @Test
    void unknownHeld_isNotReportedAsNone() {
        // ★ 持有量未知 ⇒ 不能说「不用看」；那是判断不了，不是没问题
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "diamond", 1000L, "ev");
        var v = RequirementView.build(manifest("goal-1", 2, "diamond"), f, new AssetRegistry(), null);
        var row = v.rows().get(0);
        assertEquals("UNKNOWN_HELD", row.heldVerdict());
        assertEquals(RequirementView.Attention.NONE, row.attention());
        assertTrue(String.valueOf(v.notes()).contains("判断不了"),
                "★ 必须明说「不是不用看，是判断不了」: " + v.notes());
    }

    @Test
    void attentionRowsComeFirst() {
        // ★ 键要选**规范化后不变**的（StageKeyNormalizer 会去掉下划线与标点）：
        //   用 "a_ok" 这种带下划线的，事实键会变成 "aok"，压根匹配不上，
        //   断言就会因为键口径而失败 —— 那是测试写错，不是实现错。
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "diamond", 1000L, "ev");
        var v = RequirementView.build(manifest("goal-1", 2, "diamond", "emerald"), f,
                registryWith(Map.of("diamond", 5, "emerald", 1)), null);
        assertEquals(2, v.rows().size(), "rows=" + v.rows());
        assertEquals(RequirementView.Attention.NOT_DONE_AND_NOT_HELD, v.rows().get(0).attention(),
                "关注项（emerald 不足）在前；rows=" + v.rows());
        assertEquals(RequirementView.Attention.NONE, v.rows().get(1).attention(),
                "已达标的（diamond）排后面；rows=" + v.rows());
    }

    @Test
    void noManifest_givesEmptyViewAndSaysSo() {
        var v = RequirementView.build(null, null, null, null);
        assertEquals(0, v.rows().size());
        assertTrue(String.valueOf(v.notes()).contains("HAS_NO_MANIFEST"));
        assertNotNull(v.toMap().get("column_meaning"));
    }

    @Test
    void viewDoesNotMutateEitherSide() {
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "diamond", 1000L, "ev");
        AssetRegistry reg = registryWith(Map.of("diamond", 1));
        int facts = f.stageCount();
        int held = reg.usableCounts().size();

        RequirementView.build(manifest("goal-1", 3, "diamond", "emerald"), f, reg, null);

        assertEquals(facts, f.stageCount(), "★ 视图不改事实");
        assertEquals(held, reg.usableCounts().size(), "★ 视图不改资产登记");
    }
}