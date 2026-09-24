package com.dwinovo.numen.rdd.replan;

import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.policy.RiskLevel;

import java.util.List;
import java.util.Map;

/**
 * 重规划上下文（P2.4）：把“失败原因 + 真实资产 + 完成事实 + 风险状态 + 经验”汇总成一份
 * 交给 Planner 的结构化输入，避免重规划“凭空想”。
 *
 * <p>纯数据；渲染见 {@link ReplanContextBuilder#render(ReplanContext)}。
 *
 * @param goalDescription 目标正文
 * @param currentPrimaryId 当前一级 id（可为 null）
 * @param failedSubtaskId 失败二级 id（可为 null）
 * @param failure 失败事件（可为 null=非失败触发）
 * @param completedStages 已完成阶段键（归一，来自 CompletedFactStore）
 * @param availableAssets 当前真实可用资产（来自 PlanningAssetSnapshot.availableCounts）
 * @param riskLevel 相关风险级别（可为 NORMAL）
 * @param riskGaps 风险缺口（"key need X have Y"，来自 ResourceBudget.missingFor）
 * @param experiences 召回的参考经验条目（可为空）
 */
public record ReplanContext(String goalDescription,
                            String currentPrimaryId,
                            String failedSubtaskId,
                            FailureEvent failure,
                            List<String> completedStages,
                            Map<String, Integer> availableAssets,
                            RiskLevel riskLevel,
                            List<String> riskGaps,
                            List<String> experiences) {
    public ReplanContext {
        goalDescription = goalDescription == null ? "" : goalDescription;
        completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
        availableAssets = availableAssets == null ? Map.of() : Map.copyOf(availableAssets);
        riskLevel = riskLevel == null ? RiskLevel.NORMAL : riskLevel;
        riskGaps = riskGaps == null ? List.of() : List.copyOf(riskGaps);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
    }
}
