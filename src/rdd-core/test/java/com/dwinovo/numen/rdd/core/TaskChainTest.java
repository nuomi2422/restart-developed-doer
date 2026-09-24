package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskChainTest {
    @Test void hardCodedSubtaskAdvancesWithoutSupervisor() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("item", "stone")),
                Subtask.hardCoded("s2", "get wood", Map.of("item", "wood"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        assertThrows(IllegalStateException.class, () -> chain.applyHardCodedResult("s2", true));
    }

    @Test void multiSubtaskStaysActiveUntilLastCompletes() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1)),
                Subtask.hardCoded("s2", "get wood", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus()); // 未到最后一级仍 ACTIVE
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
    }

    @Test void stalledSubtaskCanResumeAndFail() {
        var primary = new PrimaryGoal("p", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "mine diamond", Map.of("item", "diamond"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        // RUNNING → STALLED（监督检测卡死：资产指纹无变化）
        chain.markStalled("s1", "asset fingerprint unchanged");
        assertEquals(SubtaskStatus.STALLED, chain.currentSubtaskStatus());
        // STALLED → RUNNING（拍醒后行为恢复）
        chain.resumeFromStalled("s1");
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        // STALLED → FAILED（多次拍醒无效，Level 1 恢复兜底）
        chain.markStalled("s1", "stalled again");
        chain.markFailed("s1", "stalled after nudges");
        assertEquals(SubtaskStatus.FAILED, chain.currentSubtaskStatus());
    }

    @Test void persistsAndRestoresStateAcrossInstances() {
        var primary = new PrimaryGoal("p", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "mine diamond", Map.of("item", "diamond")),
                Subtask.hardCoded("s2", "mine iron", Map.of("item", "iron"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.markStalled("s1", "stalled");
        // 导出 → 新实例恢复（模拟游戏重启）
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(SubtaskStatus.STALLED, restored.currentSubtaskStatus());
        assertEquals("s1", restored.currentSubtask().id());
        // P0-4：在途执行(ACTIVE+RUNNING/STALLED)重启后归一为 RECOVERING，不默认续跑
        assertEquals(PrimaryGoalStatus.RECOVERING, restored.primaryStatus());
        // 宿主核实后拍板续跑 → 回到 ACTIVE → STALLED → RUNNING
        restored.resumeFromRecovering();
        assertEquals(PrimaryGoalStatus.ACTIVE, restored.primaryStatus());
        restored.resumeFromStalled("s1");
        assertEquals(SubtaskStatus.RUNNING, restored.currentSubtaskStatus());
    }

    @Test void failedSubtaskCanRetry() {
        var primary = new PrimaryGoal("p", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "mine diamond", Map.of("item", "diamond"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.markFailed("s1", "body task ended");
        assertEquals(SubtaskStatus.FAILED, chain.currentSubtaskStatus());
        // Level 2 局部恢复：失败二级重置 PENDING → 可重新 startCurrent（AI 换策略再试）
        chain.retrySubtask("s1");
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        chain.startCurrent();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void supervisorMustMatchAwaitingPrimary() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s", "get stone", Map.of("item", "stone"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.applyHardCodedResult("s", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p", "observed"));
        assertEquals(PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
    }

    @Test void snapshotContainsWholeChainAndCurrentPointer() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1)),
                Subtask.hardCoded("s2", "get wood", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        var snapshot = chain.snapshot();
        assertEquals("g", snapshot.get("goalId"));
        assertEquals("s1", snapshot.get("currentSubtaskId"));
        var primaries = (java.util.List<?>) snapshot.get("primaries");
        var primaryView = (java.util.Map<?, ?>) primaries.get(0);
        var subtasks = (java.util.List<?>) primaryView.get("subtasks");
        assertEquals(2, subtasks.size());
        assertEquals("RUNNING", ((java.util.Map<?, ?>) subtasks.get(0)).get("status"));
        assertEquals("PENDING", ((java.util.Map<?, ?>) subtasks.get(1)).get("status"));
    }

    @Test void dependencyGatedPrimaryWaitsUntilAssetsPresent() {
        var p1 = new PrimaryGoal("p1", "forge pickaxe", java.util.List.of(
                Subtask.hardCoded("s1", "make diamond pickaxe", Map.of("asset_key", "minecraft:diamond_pickaxe", "minimum", 1))));
        // p2 声明前置：必须有钻石镐 1 个才开工
        var p2 = new PrimaryGoal("p2", "mine obsidian", java.util.List.of(
                Subtask.hardCoded("s2", "hold obsidian", Map.of("asset_key", "minecraft:obsidian", "minimum", 1))),
                java.util.List.of(new AssetRequirement("minecraft:diamond_pickaxe", 1)));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true)); // p1 完成 → AWAITING_SUPERVISOR
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "done"));
        assertEquals("p2", chain.currentPrimary().id());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        // 前置钻石镐不在背包 → 拒绝激活，保持 WAITING，不派给 AI
        assertFalse(chain.activateCurrent(Map.of("minecraft:obsidian", 0)));
        assertEquals(PrimaryGoalStatus.WAITING, chain.primaryStatus());
        assertEquals(java.util.List.of("minecraft:diamond_pickaxe"), chain.snapshot().get("waitingFor"));
        // 前置到位 → 放行激活，首个二级 RUNNING
        assertTrue(chain.activateCurrent(Map.of("minecraft:obsidian", 0, "minecraft:diamond_pickaxe", 1)));
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        assertEquals("s2", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void persistenceKeepsPrimaryDependency() {
        var p1 = new PrimaryGoal("p1", "a", java.util.List.of(
                Subtask.hardCoded("s1", "x", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var p2 = new PrimaryGoal("p2", "b", java.util.List.of(
                Subtask.hardCoded("s2", "y", Map.of("asset_key", "minecraft:obsidian", "minimum", 1))),
                java.util.List.of(new AssetRequirement("minecraft:diamond_pickaxe", 3)));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(2, restored.goal().primaryGoals().size());
        var rp2 = restored.goal().primaryGoals().get(1);
        assertEquals(1, rp2.waitFor().size());
        assertEquals("minecraft:diamond_pickaxe", rp2.waitFor().get(0).assetKey());
        assertEquals(3, rp2.waitFor().get(0).minimum());
    }

    // ===== 批A-2：懒加载未展开一级（V2 懒展开数据模型不变式）=====

    @Test void unexpandedCurrentIsNotRunnableAndReportsCleanly() {
        var p1 = new PrimaryGoal("p1", "stage one", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1))));
        var p2 = PrimaryGoal.unexpanded("p2", "stage two", java.util.List.of()); // 未展开，0 二级
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "observed"));
        // 到达 p2 边界：PENDING、unexpanded、无当前二级（诚实空，不伪造占位节点）
        assertEquals("p2", chain.currentPrimary().id());
        assertTrue(chain.currentPrimary().unexpanded());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertNull(chain.currentSubtask());
        assertNull(chain.currentSubtaskStatus());
        // snapshot 不崩：currentSubtaskId=null、该级 unexpanded=true、subtasks 空
        var snapshot = chain.snapshot();
        assertNull(snapshot.get("currentSubtaskId"));
        var primaries = (java.util.List<?>) snapshot.get("primaries");
        var p2View = (java.util.Map<?, ?>) primaries.get(1);
        assertEquals(Boolean.TRUE, p2View.get("unexpanded"));
        assertEquals(0, ((java.util.List<?>) p2View.get("subtasks")).size());
        // 未展开 ≠ 依赖未满足：拒绝激活/启动，且绝不误置 WAITING(缺资产语义)
        assertThrows(IllegalStateException.class, () -> chain.activateCurrent(Map.of()));
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertThrows(IllegalStateException.class, chain::startCurrent);
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
    }

    @Test void expandInjectsSecondariesThenDependencyGateAndActivationWork() {
        var p1 = new PrimaryGoal("p1", "stage one", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1))));
        // p2 未展开且带前置依赖（跨一级 wait_for）——注入后必须保留
        var p2 = PrimaryGoal.unexpanded("p2", "stage two",
                java.util.List.of(new AssetRequirement("minecraft:diamond_pickaxe", 1)));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "observed"));
        // 宿主注入已生成的二级：unexpanded→false、保留 waitFor、状态不被改写
        chain.expandCurrentPrimary(java.util.List.of(Subtask.hardCoded("s2", "hold obsidian",
                Map.of("asset_key", "minecraft:obsidian", "minimum", 1))));
        assertFalse(chain.currentPrimary().unexpanded());
        assertEquals("s2", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        // 依赖门仍在：钻石镐不在背包 → 真 WAITING
        assertFalse(chain.activateCurrent(Map.of("minecraft:obsidian", 0)));
        assertEquals(PrimaryGoalStatus.WAITING, chain.primaryStatus());
        assertEquals(java.util.List.of("minecraft:diamond_pickaxe"), chain.snapshot().get("waitingFor"));
        // 依赖到位 → 激活、二级 RUNNING → 走既有推进
        assertTrue(chain.activateCurrent(Map.of("minecraft:obsidian", 0, "minecraft:diamond_pickaxe", 1)));
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
    }

    @Test void unexpandedChainRoundTripsThroughJson() {
        var p1 = new PrimaryGoal("p1", "stage one", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1))));
        var p2 = PrimaryGoal.unexpanded("p2", "stage two", java.util.List.of());
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "observed"));
        // 游戏重启：停靠在未展开 p2 (PENDING) 的链 → 持久化 → 恢复
        var restored = TaskChain.fromJson(chain.toJson());
        assertTrue(restored.currentPrimary().unexpanded());
        assertEquals(PrimaryGoalStatus.PENDING, restored.primaryStatus());
        assertNull(restored.currentSubtask());
        // 重启后仍可懒展开 + 激活（重启不吞懒加载能力）
        restored.expandCurrentPrimary(java.util.List.of(Subtask.hardCoded("s2", "hold obsidian",
                Map.of("asset_key", "minecraft:obsidian", "minimum", 1))));
        assertFalse(restored.currentPrimary().unexpanded());
        assertTrue(restored.activateCurrent(Map.of("minecraft:obsidian", 1)));
        assertEquals(SubtaskStatus.RUNNING, restored.currentSubtaskStatus());
    }

    @Test void legacyJsonWithoutUnexpandedFieldLoadsAndRuns() {
        var primary = new PrimaryGoal("p1", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        // 模拟 A-1 之前的旧持久化文件（无 unexpanded 字段）
        String legacyJson = chain.toJson().replace(",\"unexpanded\":false", "");
        assertFalse(legacyJson.contains("unexpanded"));
        var restored = TaskChain.fromJson(legacyJson);
        // 旧链默认按已展开读（unexpanded=false），行为与原实现一致
        assertFalse(restored.currentPrimary().unexpanded());
        assertEquals("s1", restored.currentSubtask().id());
        restored.startCurrent();
        assertTrue(restored.applyHardCodedResult("s1", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, restored.primaryStatus());
    }

    @Test void restoreRejectsOutOfRangePointer() {
        var primary = new PrimaryGoal("p1", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("item", "stone"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        String corrupt = chain.toJson().replace("\"subtaskIndex\":0", "\"subtaskIndex\":9");
        assertThrows(IllegalArgumentException.class, () -> TaskChain.fromJson(corrupt));
    }

    @Test void restoreRejectsMissingStatusEntry() {
        var primary = new PrimaryGoal("p1", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("item", "stone"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        String corrupt = chain.toJson().replace("\"s1\":\"PENDING\"", "");
        assertThrows(IllegalArgumentException.class, () -> TaskChain.fromJson(corrupt));
    }
}
