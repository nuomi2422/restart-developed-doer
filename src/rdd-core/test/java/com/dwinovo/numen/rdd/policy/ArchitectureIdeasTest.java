package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 架构概念 4 点纯函数骨架单测：支线任务护栏 / 元件检测清单 / 小规划方案选择。 */
class ArchitectureIdeasTest {

    // ---------- 支线任务：中断风暴护栏 ----------

    @Test void guardSuppressesWithinCooldownButAllowsAfter() {
        LocalRepairTask.Guard guard = new LocalRepairTask.Guard(10 * 20, 6, 3);
        LocalRepairTask.Guard.Tracker t = new LocalRepairTask.Guard.Tracker();
        t.recordFire(LocalRepairTask.Trigger.ENV_CHANGED, 1000);
        // 冷却内 -> SUPPRESS
        assertEquals(LocalRepairTask.Guard.Decision.SUPPRESS,
                guard.evaluate(t, LocalRepairTask.Trigger.ENV_CHANGED, 1050, LocalRepairTask.Priority.NORMAL));
        // 冷却外 -> ALLOW
        assertEquals(LocalRepairTask.Guard.Decision.ALLOW,
                guard.evaluate(t, LocalRepairTask.Trigger.ENV_CHANGED, 1000 + 10 * 20, LocalRepairTask.Priority.NORMAL));
    }

    @Test void guardEscalatesAfterConsecutiveFailures() {
        LocalRepairTask.Guard guard = new LocalRepairTask.Guard(0, 6, 3);
        LocalRepairTask.Guard.Tracker t = new LocalRepairTask.Guard.Tracker();
        t.recordFailure(LocalRepairTask.Trigger.ITEM_LOST);
        t.recordFailure(LocalRepairTask.Trigger.ITEM_LOST);
        t.recordFailure(LocalRepairTask.Trigger.ITEM_LOST);
        assertEquals(LocalRepairTask.Guard.Decision.ESCALATE,
                guard.evaluate(t, LocalRepairTask.Trigger.ITEM_LOST, 9999, LocalRepairTask.Priority.HIGH));
    }

    @Test void criticalBypassesCooldown() {
        LocalRepairTask.Guard guard = new LocalRepairTask.Guard(10 * 20, 6, 3);
        LocalRepairTask.Guard.Tracker t = new LocalRepairTask.Guard.Tracker();
        t.recordFire(LocalRepairTask.Trigger.DEATH, 500);
        assertEquals(LocalRepairTask.Guard.Decision.ALLOW,
                guard.evaluate(t, LocalRepairTask.Trigger.DEATH, 505, LocalRepairTask.Priority.CRITICAL));
    }

    @Test void guardSuppressesOverRateLimit() {
        LocalRepairTask.Guard guard = new LocalRepairTask.Guard(0, 3, 100);
        LocalRepairTask.Guard.Tracker t = new LocalRepairTask.Guard.Tracker();
        for (int i = 0; i < 3; i++) t.recordFire(LocalRepairTask.Trigger.ENV_CHANGED, 100 + i);
        assertEquals(LocalRepairTask.Guard.Decision.SUPPRESS,
                guard.evaluate(t, LocalRepairTask.Trigger.ENV_CHANGED, 200, LocalRepairTask.Priority.NORMAL));
    }

    @Test void taskRequiresParentLinkAndPreemptiveByPriority() {
        LocalRepairTask.Task death = LocalRepairTask.Task.of("t1", "goal-x", "death-event",
                LocalRepairTask.Trigger.DEATH, LocalRepairTask.Priority.CRITICAL, 100,
                LocalRepairTask.ResumePolicy.RESUME_CHECKPOINT, "recover gear", "ESCALATE", "get backup gear");
        assertTrue(death.preemptive());
        assertFalse(death.hasCheckpoint());
        assertThrows(IllegalArgumentException.class,
                () -> LocalRepairTask.Task.of("t2", "", "c", LocalRepairTask.Trigger.MANUAL,
                        LocalRepairTask.Priority.LOW, 100, LocalRepairTask.ResumePolicy.RESUME_CHECKPOINT, "s", "f", "d"));
    }

    // ---------- 元件检测：需求清单 ----------

    @Test void manifestDetectsSatisfiedAndGaps() {
        RequirementManifest.Manifest m = new RequirementManifest.Manifest("goal-1", List.of(
                new RequirementManifest.Requirement("minecraft:diamond", 2, List.of()),
                new RequirementManifest.Requirement("minecraft:oak_log", 1, List.of("minecraft:spruce_log"))));
        // 钻石够、原木用替代满足
        assertTrue(m.allSatisfied(Map.of("minecraft:diamond", 2, "minecraft:spruce_log", 3)));
        // 钻石不够 -> 有缺口
        RequirementManifest.Detection d = m.detect(Map.of("minecraft:diamond", 1, "minecraft:oak_log", 5));
        assertFalse(d.satisfied());
        assertTrue(d.gaps().stream().anyMatch(s -> s.contains("diamond")));
    }

    @Test void requirementAlternativeSatisfies() {
        RequirementManifest.Requirement r =
                new RequirementManifest.Requirement("minecraft:oak_planks", 4, List.of("minecraft:birch_planks"));
        assertTrue(r.satisfiedBy(Map.of("minecraft:birch_planks", 4)));
        assertFalse(r.satisfiedBy(Map.of("minecraft:birch_planks", 3)));
    }

    // ---------- 小规划：方案选择 ----------

    private static Deliberation.Candidate c(String id, double feasible, double risk) {
        return new Deliberation.Candidate(id, "do " + id, feasible, 0.5, 0.5, risk, 0.5);
    }

    @Test void fastPathWhenOnlyOneViable() {
        List<Deliberation.Candidate> cands = List.of(
                c("a", 1.0, 0.1), c("b", 0.0, 0.9));
        // 硬过滤掉不满足的 b -> 只剩 a -> 快路径
        Deliberation.Decision d = Deliberation.deliberate(cands,
                x -> !x.id().equals("b"), Deliberation.Weights.defaults());
        assertEquals(Deliberation.Decision.Kind.EXECUTE, d.kind());
        assertEquals("a", d.chosen().id());
        assertTrue(d.trace().stream().anyMatch(s -> s.contains("快路径")));
    }

    @Test void replanWhenNoCandidateSurvives() {
        Deliberation.Decision d = Deliberation.deliberate(List.of(c("a", 1.0, 0.1)),
                x -> false, Deliberation.Weights.defaults());
        assertEquals(Deliberation.Decision.Kind.REPLAN_NEEDED, d.kind());
        assertNull(d.chosen());
    }

    @Test void picksHigherScoreAmongMany() {
        // 同效率/工具/耗时，a 可行高+风险低 -> 应胜 b
        Deliberation.Decision d = Deliberation.deliberate(List.of(
                c("a", 0.9, 0.1), c("b", 0.4, 0.8)), null, Deliberation.Weights.defaults());
        assertEquals(Deliberation.Decision.Kind.EXECUTE, d.kind());
        assertEquals("a", d.chosen().id());
    }

    @Test void thirdExitReconWhenNoCandidateAndReconAvailable() {
        // W 审核补：无候选但可侦察 -> INSERT_RECON（不升级重规划）
        Deliberation.Decision d = Deliberation.deliberate(List.of(),
                null, Deliberation.Weights.defaults(), Deliberation.MIN_ACCEPTABLE_SCORE, true);
        assertEquals(Deliberation.Decision.Kind.INSERT_RECON, d.kind());
    }

    @Test void minAcceptableRejectsNegativeBest() {
        // 全负分方案 + 有最低分数线 -> 不硬选最烂的，走侦察
        // 构造：可行0 效率0 工具0 风险1 耗时1 -> 得分 -0.20 -0.10 = -0.30
        Deliberation.Candidate bad1 = new Deliberation.Candidate("x", "x", 0, 0, 0, 1.0, 1.0);
        Deliberation.Candidate bad2 = new Deliberation.Candidate("y", "y", 0, 0, 0, 0.9, 0.9);
        Deliberation.Decision d = Deliberation.deliberate(List.of(bad1, bad2),
                null, Deliberation.Weights.defaults(), Deliberation.MIN_ACCEPTABLE_SCORE, true);
        assertEquals(Deliberation.Decision.Kind.INSERT_RECON, d.kind());
    }

    @Test void minAcceptablePassesPositiveBest() {
        Deliberation.Decision d = Deliberation.deliberate(List.of(
                c("a", 1.0, 0.0), c("b", 0.3, 0.5)),
                null, Deliberation.Weights.defaults(), Deliberation.MIN_ACCEPTABLE_SCORE, true);
        assertEquals(Deliberation.Decision.Kind.EXECUTE, d.kind());
    }

    @Test void criticalStillSubjectToRateLimit() {
        // W 审核修：critical 不受冷却，但持续 critical 源仍受每分钟上限
        LocalRepairTask.Guard guard = new LocalRepairTask.Guard(10 * 20, 2, 100);
        LocalRepairTask.Guard.Tracker t = new LocalRepairTask.Guard.Tracker();
        t.recordFire(LocalRepairTask.Trigger.DEATH, 100);
        t.recordFire(LocalRepairTask.Trigger.DEATH, 110);
        // 已达 2 次/分钟上限 -> critical 也被压掉
        assertEquals(LocalRepairTask.Guard.Decision.SUPPRESS,
                guard.evaluate(t, LocalRepairTask.Trigger.DEATH, 120, LocalRepairTask.Priority.CRITICAL));
    }
}
