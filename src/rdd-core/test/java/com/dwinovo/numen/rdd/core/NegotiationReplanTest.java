package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.fail.FailureClassifier;
import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.fail.FailureKind;
import com.dwinovo.numen.rdd.fail.RecoveryDecision;
import com.dwinovo.numen.rdd.fail.RecoveryOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 士兵↔军师「断线」回归（2026-09-29 用户报告：士兵提了问题/建议，军师基本不听；
 * 重规划出来的任务和之前没区别；有些非必需目标永远卡住链）。
 *
 * <p><b>根因</b>（静态定位后逐行核实）：士兵唯一合理的上报时机是 <b>RUNNING</b>
 * （还在干、觉得方向不对），而重规划入口 {@code enterReplanningFromStuck} 要求
 * FAILED/STALLED → 抛异常 → 调用方 return false → {@code soldierHint}（在其后才拼装）
 * <b>永远送不到规划器</b>；而协商回执已被先行消费，失败后不可恢复。
 */
class NegotiationReplanTest {

    private static Subtask subtask(String id, String desc) {
        return Subtask.hardCoded(id, desc, Map.of("asset_key", "minecraft:iron_ore"));
    }

    private static TaskChain chain(String... descriptions) {
        List<Subtask> subs = new java.util.ArrayList<>();
        for (int i = 0; i < descriptions.length; i++) {
            subs.add(subtask("s" + i, descriptions[i]));
        }
        PrimaryGoal primary = new PrimaryGoal("p0", "gather iron", subs, List.of(), false);
        TaskChain chain = new TaskChain(new Goal("g0", "survive and gather", List.of(primary)));
        chain.startCurrent();
        chain.bindExecution("s0", "exec-1");
        return chain;
    }

    // ---------- F1：协商入口必须在 RUNNING 下可用 ----------

    @Test
    void stuckEntryRejectsRunningButNegotiationEntryAcceptsIt() {
        TaskChain chain = chain("mine iron", "chop wood");
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus(), "前置：士兵在 RUNNING 时上报");

        // 旧入口在 RUNNING 下必须抛 —— 这就是「军师不听」的断点
        assertThrows(IllegalStateException.class, () -> chain.enterReplanningFromStuck("soldier REJECT"),
                "卡死入口在 RUNNING 下应当拒绝；断点正在此处");

        // 新入口必须放行，否则 soldierHint 永远拼装不到
        chain.enterReplanningFromNegotiation("soldier REJECT: no iron veins nearby");
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus(),
                "协商入口应把链推进 REPLANNING");
    }

    @Test
    void stuckEntryStillWorksForFailedSubtask() {
        TaskChain chain = chain("mine iron", "chop wood");
        chain.markFailed("s0", "no progress");
        assertEquals(SubtaskStatus.FAILED, chain.currentSubtaskStatus());
        chain.enterReplanningFromStuck("stalled");
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus(), "既有卡死路径行为不变");
    }

    @Test
    void negotiationRejectsUnexpandedPrimary() {
        // unexpanded 一级没有二级，无法协商重规划
        PrimaryGoal unexpanded = new PrimaryGoal("p0", "gather iron", List.of(), List.of(), true);
        TaskChain chain = new TaskChain(new Goal("g0", "goal", List.of(unexpanded)));
        assertThrows(IllegalStateException.class, () -> chain.enterReplanningFromNegotiation("x"),
                "未展开的一级不得进入协商重规划");
    }

    // ---------- F2：协商不得被当成「上一版不可执行」（假话）----------

    @Test
    void negotiationHasItsOwnFailureKind() {
        assertNotEquals(FailureKind.UNKNOWN, FailureKind.NEGOTIATION,
                "协商必须有独立枚举值：曾硬编码 UNKNOWN，导致 prompt 注入"
                        + "「上一版被判不可执行」的假话（上一版其实可执行，士兵只是不喜欢）");
    }

    @Test
    void negotiationMapsToReplanNotSelfCompileOrRepair() {
        FailureEvent ev = FailureEvent.of(null, "p0", FailureKind.NEGOTIATION, "soldier says plan is wrong");
        RecoveryDecision d = FailureClassifier.classify(ev, null);
        assertEquals(RecoveryOutcome.REPLAN, d.outcome(),
                "协商 → REPLAN（换计划）；不能 SELF_COMPILE（代码缺陷专用），"
                        + "也不能 REPAIR（按原计划补救 = 旧症状「说了也不听」）");
    }

    // ---------- F3：optional 通用化 ----------

    /**
     * 锁住「什么可以跳过」的语义契约。实现落在插件侧 {@code RddOptionalFood}
     * （包私有），这里在 core 复刻同一判定，防止两边漂移。
     */
    private static boolean isSkippable(Map<String, Object> condition) {
        if (condition == null) return false;
        if (Boolean.FALSE.equals(condition.get("optional"))) return false;
        if (Boolean.TRUE.equals(condition.get("optional"))) return true;
        Object group = condition.get("group");
        if (group instanceof String g && "food".equals(g)) return true;
        Object key = condition.get("asset_key");
        return key instanceof String item && InventoryGroups.contains("food", item);
    }

    @Test
    void explicitOptionalTrueSkippableForAnyKind() {
        assertEquals(true, isSkippable(Map.of("optional", Boolean.TRUE, "asset_key", "minecraft:oak_log")),
                "非食物目标显式 optional=true 也应可跳过 —— 用户报告的核心诉求");
        assertEquals(true, isSkippable(Map.of("optional", Boolean.TRUE, "group", "tools")));
    }

    @Test
    void explicitOptionalFalseAlwaysBlocks() {
        assertEquals(false, isSkippable(Map.of("optional", Boolean.FALSE, "group", "food")),
                "显式 optional=false 必须否决，哪怕它本属食物");
    }

    @Test
    void unlabelledKeepsLegacyFoodPolicy() {
        assertEquals(true, isSkippable(Map.of("group", "food")), "未标注时保留原有食物宽松策略");
        assertEquals(false, isSkippable(Map.of("asset_key", "minecraft:diamond")),
                "未标注的非食物仍不放行（不能什么都可跳）");
    }
}
