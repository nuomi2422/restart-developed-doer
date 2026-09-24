package com.dwinovo.numen.rdd.replan;

import com.dwinovo.numen.rdd.fail.FailureEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 重规划上下文构建器（P2.4，纯函数）：组装 {@link ReplanContext} 并渲染成给 Planner 的提示块。
 *
 * <p>定位：TaskChain 会“安排事情”，但不知道“为什么安排/未来要什么/失败后还能不能救”；
 * 本层把真实状态喂给规划师，输出只描述事实与约束（不替 AI 决策）。
 */
public final class ReplanContextBuilder {

    private ReplanContextBuilder() {}

    /**
     * @param availableAssets 当前真实可用资产计数（通常 PlanningAssetSnapshot.availableCounts）
     * @param completedStages 已完成阶段键（CompletedFactStore.satisfiedStageKeys）
     * @param riskGaps        该风险级别的缺口（ResourceBudget.missingFor）
     */
    public static ReplanContext build(String goalDescription, String currentPrimaryId, String failedSubtaskId,
                                      FailureEvent failure, List<String> completedStages,
                                      Map<String, Integer> availableAssets,
                                      com.dwinovo.numen.rdd.policy.RiskLevel riskLevel,
                                      List<String> riskGaps, List<String> experiences) {
        return new ReplanContext(goalDescription, currentPrimaryId, failedSubtaskId, failure,
                completedStages, availableAssets, riskLevel, riskGaps, experiences);
    }

    /** 渲染成对齐“重规划铁律”的提示块（供 Planner 使用）。 */
    public static String render(ReplanContext ctx) {
        if (ctx == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【重规划上下文｜基于真实世界，不要凭空假设】\n");
        sb.append("目标：").append(ctx.goalDescription()).append("\n");
        if (ctx.currentPrimaryId() != null) {
            sb.append("当前阶段：").append(ctx.currentPrimaryId()).append("\n");
        }
        if (ctx.failure() != null) {
            FailureEvent f = ctx.failure();
            sb.append("失败：[").append(f.kind()).append("] ")
                    .append(f.reason()).append(f.subtaskId() == null ? "" : "（子步 " + f.subtaskId() + "）").append("\n");
        }
        if (!ctx.completedStages().isEmpty()) {
            sb.append("已完成阶段（不要重复规划）：").append(String.join("、", ctx.completedStages())).append("\n");
        }
        sb.append("当前可用资产：").append(renderAssets(ctx.availableAssets())).append("\n");
        sb.append("风险级别：").append(ctx.riskLevel()).append(ctx.riskGaps().isEmpty()
                ? "（已达标）" : "，缺口：" + String.join("; ", ctx.riskGaps())).append("\n");
        if (!ctx.experiences().isEmpty()) {
            sb.append("参考经验：\n");
            for (String e : ctx.experiences()) {
                sb.append("- ").append(e).append("\n");
            }
        }
        sb.append("请只规划达成目标所缺的剩余步骤：已完成的不重做；资源不足或风险未达标时先补准备；"
                + "每步给真实可检测条件。\n");
        return sb.toString();
    }

    private static String renderAssets(Map<String, Integer> assets) {
        if (assets == null || assets.isEmpty()) {
            return "（空）";
        }
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(assets).entrySet()) {
            items.add(e.getKey() + "×" + e.getValue());
        }
        return String.join(", ", items);
    }
}
