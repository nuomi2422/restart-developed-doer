package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1/P0-2/P0-3/P0-4 跨状态回归：P0 修复打的封闭性测试。
 * 覆盖外脑审查点：REPLANNING 出口、WAITING 语义不混用、RECOVERING 停机归一、
 * 执行元数据、依赖门接注册表。
 */
class TaskChainStateMachineExitTest {

    private static TaskChain chain(PrimaryGoal... primaries) {
        List<PrimaryGoal> ps = List.of(primaries);
        return new TaskChain(new Goal("g", "goal", ps));
    }

    private static Subtask hc(String id) {
        return Subtask.hardCoded(id, "do " + id, Map.of("asset_key", "minecraft:stone", "minimum", 1));
    }

    // ===== P0-1 REPLANNING 必须有可离开的出口 =====

    @Test void replanningCanResumeSamePlan() {
        var chain = chain(new PrimaryGoal("p", "prepare", List.of(hc("s1"))));
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.REPLAN, "p", "plan wrong"));
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        // 宿主决定沿用现有二级 → resumeFromReplanning 回到 PENDING，可再激活
        chain.resumeFromReplanning();
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertThrows(IllegalStateException.class, () -> chain.resumeFromReplanning());
        assertTrue(chain.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
    }

    @Test void replanningCanReplaceSubtasks() {
        var chain = chain(new PrimaryGoal("p", "prepare", List.of(hc("s1"), hc("s2"))));
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.startCurrent();
        chain.applyHardCodedResult("s2", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.REJECT, "p", "wrong approach"));
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        chain.replaceCurrentSubtasks(List.of(hc("s1r"), hc("s2r")));
        // REPLAN 出口 B：宿主注入全新替换计划（沿用一级 id/desc/waitFor）
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertEquals("s1r", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        assertFalse(chain.subtaskStatuses().containsKey("s1"));
        assertFalse(chain.subtaskStatuses().containsKey("s2"));
        assertTrue(chain.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals("s1r", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void replaceRejectsMatchesOutsideReplacedPrimary() {
        var chain = chain(
                new PrimaryGoal("p1", "a", List.of(hc("s1"))),
                new PrimaryGoal("p2", "b", List.of(hc("s2"))));
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.REPLAN, "p1", "replan"));
        // 新 id 撞了 p2 的二级 → 拒绝，绝不允许跨一级改写状态
        assertThrows(IllegalArgumentException.class,
                () -> chain.replaceCurrentSubtasks(List.of(hc("s2"))));
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
    }

    @Test void replanningRejectsReplaceWhenNotReplanning() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        assertThrows(IllegalStateException.class,
                () -> chain.replaceCurrentSubtasks(List.of(hc("x"))));
    }

    @Test void needMoreEvidenceDoesNotAbuseWaitingSemantics() {
        var p1 = new PrimaryGoal("p1", "a", List.of(hc("s1")));
        // p2 有前置依赖（waitFor）：下列场景能证明 NEED_MORE_EVIDENCE 不再产生 ACTIVE+COMPLETED 死组合
        var p2 = new PrimaryGoal("p2", "b", List.of(hc("s2")),
                List.of(new AssetRequirement("minecraft:diamond_pickaxe", 1)));
        var chain = chain(p1, p2);
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        // 监督对 s1 的证据存疑：NEED_MORE_EVIDENCE → 停在 AWAITING_SUPERVISOR（不是 WAITING 缺资产）
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.NEED_MORE_EVIDENCE, "p1", "doubt"));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        // 监督补齐证据 CONFIRM → 进下一级；不应出现 ACTIVE + 已 COMPLETED 二级
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "now sure"));
        assertEquals("p2", chain.currentPrimary().id());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
    }

    @Test void deferStopsAtAwaitingUntilResolved() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.DEFER, "p", "park it"));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        // 仍可被新的 CONFIRM 解决
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p", "ok"));
        assertEquals(PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
    }

    // ===== P0-2 依赖门接真实注册表 =====

    @Test void primaryReadyHonorsRegistryUsableCounts() {
        var p1 = new PrimaryGoal("p1", "a", List.of(hc("s1")));
        var p2 = new PrimaryGoal("p2", "b", List.of(hc("s2")),
                List.of(new AssetRequirement("minecraft:diamond_pickaxe", 2)));
        var chain = chain(p1, p2);
        var assets = new AssetRegistry();
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "ok"));
        // 依赖门走注册表：只有 1 个 → 不满足
        assertFalse(chain.currentPrimaryReady(assets));
        assertFalse(chain.activateCurrentWithRegistry(assets));
        assertEquals(PrimaryGoalStatus.WAITING, chain.primaryStatus());
        // 注册表补足 2 个可用资产 → 放行激活
        observeInventory(assets, "minecraft:diamond_pickaxe", 2);
        assertTrue(chain.currentPrimaryReady(assets));
        assertTrue(chain.activateCurrentWithRegistry(assets));
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void registryUnknownOrInvalidNeverSatisfiesDependencyGate() {
        var p1 = new PrimaryGoal("p1", "a", List.of(hc("s1")));
        var p2 = new PrimaryGoal("p2", "b", List.of(hc("s2")),
                List.of(new AssetRequirement("minecraft:obsidian", 1)));
        var chain = chain(p1, p2);
        var assets = new AssetRegistry();
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "ok"));
        // 观测到但随后失效/未知 → usableCounts 不含它，依赖门仍不满足
        observeInventory(assets, "minecraft:obsidian", 1);
        assertTrue(chain.currentPrimaryReady(assets));
        assets.invalidate("minecraft:obsidian");
        assertFalse(chain.currentPrimaryReady(assets));
        assets.markUnknown("minecraft:obsidian");
        assertFalse(chain.currentPrimaryReady(assets));
    }

    // ===== P0-3 执行元数据：attempts / 执行身份绑定 =====

    @Test void executionIdentityTracksAttemptsAndRuntimeId() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        assertEquals(1, chain.attempts("s1"));
        chain.bindExecution("s1", "exec-1");
        assertEquals("exec-1", chain.activeExecutionId());
        // 同一二级再启动 → attempt 累加、旧执行身份作废
        chain.markFailed("s1", "failed once");
        chain.retrySubtask("s1");
        chain.startCurrent();
        assertEquals(2, chain.attempts("s1"));
        assertNull(chain.activeExecutionId());
        chain.bindExecution("s1", "exec-2");
        assertEquals("exec-2", chain.activeExecutionId());
    }

    @Test void executionIdentityPersistsButClearsOnRecovering() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.bindExecution("s1", "exec-runtime-777");
        String json = chain.toJson();
        assertTrue(json.contains("exec-runtime-777"));
        // 重启恢复：在途执行归一 RECOVERING，宿主执行身份作废但不丢 attempt 计数
        var restored = TaskChain.fromJson(json);
        assertEquals(PrimaryGoalStatus.RECOVERING, restored.primaryStatus());
        assertNull(restored.activeExecutionId());
        assertEquals(1, restored.attempts("s1"));
    }

    // ===== P0-4 停机归一：未开始的链原样恢复，不做假续跑 =====

    @Test void pendingChainRestoresAsPendingNotRecovering() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(PrimaryGoalStatus.PENDING, restored.primaryStatus());
        assertNull(restored.activeExecutionId());
        assertEquals(0, restored.attempts("s1"));
    }

    @Test void awaitingSupervisorRestoresAsAwaitingNotRecovering() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, restored.primaryStatus());
        // 恢复后监督仍能出新决策，不被 P0-4 卡在 RECOVERING
        restored.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p", "ok"));
        assertEquals(PrimaryGoalStatus.COMPLETED, restored.primaryStatus());
    }

    @Test void recoveringResumeRequiresExplicitHostVerdict() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(PrimaryGoalStatus.RECOVERING, restored.primaryStatus());
        // RECOVERING 不是可继续推进状态：宿主必须先 resumeFromRecovering 拍板
        assertThrows(IllegalStateException.class, restored::startCurrent);
        restored.resumeFromRecovering();
        assertEquals(PrimaryGoalStatus.ACTIVE, restored.primaryStatus());
        assertEquals(SubtaskStatus.RUNNING, restored.currentSubtaskStatus());
        // 恢复后不能再次 resume（幂等守卫）
        assertThrows(IllegalStateException.class, restored::resumeFromRecovering);
    }

    @Test void persistedAttemptsSurviveRoundTrip() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"), hc("s2"))));
        chain.startCurrent();
        chain.applyHardCodedResult("s1", true);
        chain.startCurrent();
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(1, restored.attempts("s1"));
        assertEquals(1, restored.attempts("s2"));
    }

    private static void observeInventory(AssetRegistry assets, String assetId, int count) {
        var obs = new Observation("o-" + assetId.replace(':', '-') + "-" + System.nanoTime(),
                "inventory_scan", "test", "env", System.currentTimeMillis(), Map.of("count", count));
        assets.apply(obs, assetId, AssetScope.GLOBAL, "test-node");
    }

    // ===== Phase 1-2：resume* 一律作废执行元数据（activeExecutionId / lastStartedAtMillis） =====

    @Test void resumeReplanningClearsExecutionMetadata() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.bindExecution("s1", "exec-old");
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.REPLAN, "p", "redo"));
        chain.resumeFromReplanning();
        assertNull(chain.activeExecutionId());
    }

    @Test void replaceSubtasksClearsExecutionMetadata() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.bindExecution("s1", "exec-old");
        chain.applyHardCodedResult("s1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.REJECT, "p", "no"));
        chain.replaceCurrentSubtasks(List.of(hc("s1r")));
        assertNull(chain.activeExecutionId());
    }

    @Test void resumeRecoveringClearsExecutionMetadata() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.bindExecution("s1", "exec-old");
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(PrimaryGoalStatus.RECOVERING, restored.primaryStatus());
        restored.resumeFromRecovering();
        assertNull(restored.activeExecutionId());
    }

    @Test void recoveredChainNeverKeepsStaleExecutionIdAcrossReboots() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        chain.startCurrent();
        chain.bindExecution("s1", "exec-first-reboot");
        var reboot1 = TaskChain.fromJson(chain.toJson()); // ACTIVE+RUNNING → RECOVERING，清 id
        assertNull(reboot1.activeExecutionId());
        // 即使磁盘 JSON 被宿主在 RECOVERING 期间写回一个残留 id，恢复到本地也必须清
        String jsonWithResidue = reboot1.toJson()
                .replaceFirst("\"activeExecutionId\"[^,]*", "\"activeExecutionId\":\"stale-residue\"");
        var reboot2 = TaskChain.fromJson(jsonWithResidue);
        assertNull(reboot2.activeExecutionId());
    }

    // ===== Phase 1-4：restored state 非法组合拦截 =====

    @Test void restoreRejectsActiveUnexpandedCombination() {
        var unexpanded = new PrimaryGoal("p", "unexpanded", List.of(), List.of(
                new AssetRequirement("minecraft:iron_ingot", 1)), true);
        var chain = new TaskChain(new Goal("g", "goal", List.of(unexpanded)));
        // 模拟损坏 JSON：只把 primaryStatus 硬塞成 ACTIVE（正常情况下未展开绝不 ACTIVE）
        String corrupt = chain.toJson().replace("\"primaryStatus\":\"PENDING\"", "\"primaryStatus\":\"ACTIVE\"");
        assertThrows(IllegalArgumentException.class, () -> TaskChain.fromJson(corrupt));
    }

    @Test void restoreRejectsUnknownAttemptSubtask() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        String corrupt = chain.toJson().replace("\"attempts\":{}", "\"attempts\":{\"ghost\":5}");
        assertThrows(IllegalArgumentException.class, () -> TaskChain.fromJson(corrupt));
    }

    @Test void restoreRejectsSkipReasonOnNonSkipped() {
        var chain = chain(new PrimaryGoal("p", "a", List.of(hc("s1"))));
        String corrupt = chain.toJson().replace("\"skipReasons\":{}", "\"skipReasons\":{\"s1\":\"nope\"}");
        assertThrows(IllegalArgumentException.class, () -> TaskChain.fromJson(corrupt));
    }
}