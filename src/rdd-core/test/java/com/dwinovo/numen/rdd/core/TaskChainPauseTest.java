package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 暂停（PAUSED）＝「暂时不要开，但留着、以后还能开」。
 *
 * <p>要锁住的边界全在"与 SKIPPED / FAILED 的区别"上：
 * 跳过会推进并放弃，失败会进重试/能力缺口循环，暂停两者都不做。
 */
class TaskChainPauseTest {

    private static TaskChain twoStepChain() {
        var first = Subtask.hardCoded("s1", "mine diamonds", Map.of("asset_key", "minecraft:diamond", "minimum", 8));
        var second = Subtask.hardCoded("s2", "forge the set", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1));
        return new TaskChain(new Goal("g", "gear up", List.of(new PrimaryGoal("p", "phase", List.of(first, second)))));
    }

    @Test void pauseKeepsTheStepInPlaceAndDoesNotAdvance() {
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "no diamond vein within reach; hold it for later");
        // 核心语义：不推进 —— 当前二级仍是它自己
        assertEquals("s1", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.PAUSED, chain.currentSubtaskStatus());
        assertTrue(chain.isPaused("s1"));
        assertFalse(chain.isPaused("s2"));
        assertEquals(SubtaskStatus.PENDING, chain.subtaskStatuses().get("s2"));
    }

    @Test void pausedIsNotASkipAndNotAFailure() {
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "hold");
        // SKIPPED 语义：推进 + 放弃
        assertNotEquals(SubtaskStatus.SKIPPED, chain.currentSubtaskStatus());
        // FAILED 语义：会消耗重试预算并进能力缺口循环；暂停不碰 attempts
        assertThrows(IllegalStateException.class, () -> chain.retrySubtask("s1"));
        assertThrows(IllegalStateException.class, () -> chain.markFailed("s1", "nope"));
        assertThrows(IllegalStateException.class, () -> chain.markStalled("s1", "nope"));
        // 达成判定必须拒绝暂停中的二级：否则"暂停"会被静默算成"这步过了"
        assertThrows(IllegalStateException.class, () -> chain.applyHardCodedResult("s1", true));
    }

    @Test void resumeReopensTheSameStepInPlace() {
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "hold");
        chain.resumeFromPaused("s1");
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        assertFalse(chain.isPaused("s1"));
        assertTrue(chain.pauseReasonsView().isEmpty(), "恢复后暂停原因必须清掉，不能留悬空记录");
        // 恢复后可以照常推进：暂停不是终态
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
    }

    @Test void pauseReasonIsRequiredAndBlankIsRejected() {
        var chain = twoStepChain();
        chain.startCurrent();
        assertThrows(IllegalArgumentException.class, () -> chain.pauseSubtask("s1", null));
        assertThrows(IllegalArgumentException.class, () -> chain.pauseSubtask("s1", "  "));
    }

    @Test void pauseOnlyFromRunningOrPending() {
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "hold");
        assertThrows(IllegalStateException.class, () -> chain.pauseSubtask("s1", "again"));
        // s2 不是当前二级：requireCurrent 抛的是参数类异常（不是状态类），别混为一谈
        assertThrows(IllegalArgumentException.class, () -> chain.resumeFromPaused("s2"));
        // 开一次就 RUNNING 了，再开必须拒绝（否则暂停/恢复可以无限抖动）
        chain.resumeFromPaused("s1");
        assertThrows(IllegalStateException.class, () -> chain.resumeFromPaused("s1"));
    }

    @Test void pauseIsAllowedFromStalledBecauseThatIsWhenSoldiersActuallyReport() {
        // 2026-09-29 实机修正：第一跑落在 subtask_pause_rejected: cannot be paused from STALLED。
        // 真实时序是「做不到 → 资产无变化 → 15s grace → STALLED → 士兵才上报 PAUSE」，
        // 所以 STALLED 才是上报时最常见的状态，守卫必须放行它。
        var chain = twoStepChain();
        chain.startCurrent();
        chain.markStalled("s1", "no tool call for 15 checks");
        assertEquals(SubtaskStatus.STALLED, chain.currentSubtaskStatus());
        chain.pauseSubtask("s1", "soldier PAUSE: no village within 500 blocks");
        assertEquals(SubtaskStatus.PAUSED, chain.currentSubtaskStatus());
        // 关键收益：暂停后不再被 handleStalled 判失败、不再烧重试预算
        assertThrows(IllegalStateException.class, () -> chain.markStalled("s1", "again"));
        assertThrows(IllegalStateException.class, () -> chain.markFailed("s1", "stalled after nudges"));
        // 而且能原地开回来
        chain.resumeFromPaused("s1");
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void pausedSurvivesRestartAndKeepsItsReason() {
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "no diamond vein within reach; hold it for later");
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(SubtaskStatus.PAUSED, restored.currentSubtaskStatus());
        assertEquals("s1", restored.currentSubtask().id());
        assertEquals("no diamond vein within reach; hold it for later",
                restored.pauseReasonsView().get("s1"));
        assertTrue(restored.toJson().contains("no diamond vein within reach"));
    }

    @Test void jsonWithoutPauseReasonsIsStillReadable() {
        // 回归（改接口先查消费者）：PAUSED 引入前写出的存档没有 pauseReasons 键。
        // 老存档必须照常读回来，不能因为"少一个键"就当损坏数据拒绝。
        var chain = twoStepChain();
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        String legacy = chain.toJson().replace("\"pauseReasons\":{},", "").replace("\"pauseReasons\":{ },", "");
        var restored = TaskChain.fromJson(legacy);
        assertEquals("s2", restored.currentSubtask().id());
        assertTrue(restored.pauseReasonsView().isEmpty());
    }

    @Test void pausedIsNotSatisfiedAndDoesNotCompleteTheChain() {
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "hold");
        // 达成判定只看 COMPLETED：暂停绝不能被算成"这一级过了"
        var snap = chain.snapshot();
        @SuppressWarnings("unchecked")
        var primaries = (java.util.List<Map<String, Object>>) snap.get("primaries");
        @SuppressWarnings("unchecked")
        var subtasks = (java.util.List<Map<String, Object>>) primaries.get(0).get("subtasks");
        assertEquals("PAUSED", subtasks.get(0).get("status"));
        assertEquals("hold", subtasks.get(0).get("pauseReason"));
        assertNotEquals(PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
    }

    @Test void replacingThePlanDropsThePauseMark() {
        // 换计划会把二级重置成 PENDING；暂停标记若不清，就与新状态自相矛盾
        // （随后存档校验会报 "restored pauseReason on non-paused subtask"）。
        var chain = twoStepChain();
        chain.startCurrent();
        chain.pauseSubtask("s1", "hold");
        chain.enterReplanningFromNegotiation("planner wants a different first step");
        chain.replaceCurrentSubtasks(List.of(Subtask.hardCoded("fresh",
                "strip the nearby trees first", Map.of("asset_key", "minecraft:oak_log", "minimum", 8))));
        assertEquals("fresh", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        assertTrue(chain.pauseReasonsView().isEmpty(), "换计划后不得残留暂停原因");
        // 新计划可以照常开
        chain.startCurrent();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }
}
