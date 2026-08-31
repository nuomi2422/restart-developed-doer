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
        assertEquals(PrimaryGoalStatus.ACTIVE, restored.primaryStatus());
        // 恢复后可继续走状态机（STALLED → RUNNING）
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
}
