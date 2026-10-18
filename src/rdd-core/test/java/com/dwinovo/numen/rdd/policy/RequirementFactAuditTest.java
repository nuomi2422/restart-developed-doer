package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求清单 × 共同事实对账的离线检查（第三批 N2 第一步）。
 *
 * <p><b>这条链的键是真的</b>：{@code Manifest.goalId} 与 {@code StageFact.goalId}
 * 同名同源（都来自 {@code Goal}）—— 这与 N1「stageKey vs artifact_id 不同源」
 * 完全不同，所以这里可以做强 join。
 *
 * <p>守三件事：
 * <ol>
 *   <li>措辞不许越界 —— 「事实里没有」≠「没做」；</li>
 *   <li>没有清单不是「一切正常」；</li>
 *   <li>重复需求/多次完成要说出来，不静默去重或合并。</li>
 * </ol>
 */
class RequirementFactAuditTest {

        /** 造一个 goal。★ 用真实的 Goal 构造器（id/description/primaryGoals），不编不存在的重载。 */
    private static Goal goal(String goalId, String description) {
        return new Goal(goalId, description, List.of(new PrimaryGoal("p", "primary", List.of(new Subtask("s1", "do it", DetectionMode.HARD_CODED, Map.of("k", "v"), 5L, 3, false, null)), List.of(), false)));
    }

    private static RequirementManifest.Manifest manifest(String goalId, String... keys) {
        List<RequirementManifest.Requirement> reqs = new java.util.ArrayList<>();
        for (String k : keys) {
            reqs.add(new RequirementManifest.Requirement(k, 1, List.of()));
        }
        return new RequirementManifest.Manifest(goalId, reqs);
    }

    private static CompletedFactStore factsWith(Goal g, String... stageKeys) {
        CompletedFactStore s = new CompletedFactStore();
        for (String k : stageKeys) {
            s.recordStage(g, k, 1000L, "ev");
        }
        return s;
    }

    /**
     * 事实里真实的 stageKey = {@code StageKeyNormalizer.normalize(原始描述)}
     * —— 小写 + 只留字母数字（见 CompletedFactStore.recordStage）。
     *
     * <p>★ 需求清单的键要与**规范化后**的键比对：拿原始描述去比会一条都命中不了，
     * 而那看起来像「事实里没有」，实则是键口径不对 —— 又一处「读得出来但读错了」。
     */
    private static String factKey(String raw) {
        return com.dwinovo.numen.rdd.fact.StageKeyNormalizer.normalize(raw);
    }

    @Test
    void requirementWithMatchingFact_isCovered() {
        Goal g = goal("goal-1", "mine ore");
        CompletedFactStore f = factsWith(g, "mine_ore");
        var r = RequirementFactAudit.audit(manifest("goal-1", factKey("mine_ore")), f);
        assertEquals(1, r.items().size());
        assertEquals(RequirementFactAudit.Verdict.COVERED_BY_FACT, r.items().get(0).verdict());
        assertEquals(1, r.items().get(0).factCount());
    }

    @Test
    void requirementWithoutFact_saysNoFactRecord_notNotDone() {
        // ★ 措辞边界：事实里没有 ≠ 没做（可能做了没记）。Verdict 名字必须体现这一点。
        Goal g = goal("goal-1", "mine ore");
        CompletedFactStore f = factsWith(g, "mine_ore");
        var r = RequirementFactAudit.audit(manifest("goal-1", factKey("build_house")), f);
        assertEquals(RequirementFactAudit.Verdict.NO_FACT_RECORD, r.items().get(0).verdict());
        assertEquals("NO_FACT_RECORD",
                RequirementFactAudit.Verdict.NO_FACT_RECORD.name(),
                "★ 名字不能是 NOT_DONE / FAILED 之类 —— 那会把「没记录」说成「没做到」");
    }

    @Test
    void noManifest_isReportedNotTreatedAsFine() {
        var r = RequirementFactAudit.audit(null, new CompletedFactStore());
        assertFalse(r.hasManifest(), "没有需求清单不能当成「正常」");
        assertEquals("HAS_NO_MANIFEST", r.notes().get(0));
        assertTrue(r.toMap().containsKey("has_manifest"));
    }

    @Test
    void emptyRequirementsList_isAlsoNoManifest() {
        var m = new RequirementManifest.Manifest("goal-1", List.of());
        var r = RequirementFactAudit.audit(m, new CompletedFactStore());
        assertFalse(r.hasManifest());
    }

    @Test
    void reworkSignalIsNotFakedBecauseStoreDedups() {
        // ★ CompletedFactStore 按 lineage+stageKey 去重（recordStage 是覆盖不是追加），
        //   所以「同一阶段做了两次」在这个结构里**观测不到**。
        //   本类据此**不报**返工信号，并在报告里说明原因 ——
        //   报一个结构上不可能出现的结论，等于对外承诺了观测不到的能力。
        Goal g = goal("goal-1", "mine ore");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "mine_ore", 1000L, "ev1");
        f.recordStage(g, "mine_ore", 2000L, "ev2");
        assertEquals(1, f.stageCount(), "先确认前提：结构确实去重了（所以重复不可观测）");

        var r = RequirementFactAudit.audit(manifest("goal-1", factKey("mine_ore")), f);
        assertEquals(RequirementFactAudit.Verdict.COVERED_BY_FACT, r.items().get(0).verdict());
        assertEquals(1, r.items().get(0).factCount(), "去重后只剩一条");
        assertTrue(String.valueOf(r.toMap().get("rework_signal")).contains("REWORK_NOT_OBSERVABLE"),
                "★ 必须明说返工信号观测不到，不能让人以为查过了");
    }

    @Test
    void duplicateRequirement_isCountedOnce_andNoted() {
        var m = new RequirementManifest.Manifest("goal-1",
                List.of(new RequirementManifest.Requirement("mine_ore", 1, List.of()),
                        new RequirementManifest.Requirement("mine_ore", 2, List.of())));
        var r = RequirementFactAudit.audit(m, new CompletedFactStore());
        assertEquals(1, r.items().size(), "重复需求只算一条");
        assertTrue(String.valueOf(r.notes()).contains("重复"),
                "★ 去重要说出来，不能静默: " + r.notes());
    }

    @Test
    void factsOfAnotherGoal_areNotCounted() {
        // ★ 用 goalId 过滤：别的目标的完成事实不该算作这条需求的覆盖
        Goal g1 = goal("goal-1", "mine ore");
        Goal g2 = goal("goal-2", "mine ore");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g1, "mine_ore", 1000L, "ev1");
        f.recordStage(g2, "mine_ore", 2000L, "ev2");
        var r = RequirementFactAudit.audit(manifest("goal-1", factKey("mine_ore")), f);
        assertEquals(1, r.items().get(0).factCount(),
                "只算同一 goal 下的事实（否则覆盖率会虚高）");
    }

    @Test
    void prefixMatchIsStatedNotSilent() {
        // 前缀命中是一种口径选择，必须在 notes 里说明，不能让读者以为它是精确匹配
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = factsWith(g, "mine_ore_deep");
        var r = RequirementFactAudit.audit(manifest("goal-1", factKey("mine_ore")), f);
        assertEquals(RequirementFactAudit.Verdict.COVERED_BY_FACT, r.items().get(0).verdict());
        assertTrue(String.valueOf(r.notes()).contains("PREFIX"),
                "★ 前缀命中要标明口径: " + r.notes());
    }

    @Test
    void emptyFacts_isNotAnError() {
        Goal g = goal("goal-1", "x");
        var r = RequirementFactAudit.audit(manifest("goal-1", "anything"), new CompletedFactStore());
        assertEquals(1, r.items().size());
        assertEquals(RequirementFactAudit.Verdict.NO_FACT_RECORD, r.items().get(0).verdict());
    }

    @Test
    void auditDoesNotMutateEitherSide() {
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = factsWith(g, "mine_ore");
        int before = f.stageCount();
        RequirementFactAudit.audit(manifest("goal-1", factKey("mine_ore"), factKey("build")), f);
        assertEquals(before, f.stageCount(), "★ 对账绝不改事实");
    }
}