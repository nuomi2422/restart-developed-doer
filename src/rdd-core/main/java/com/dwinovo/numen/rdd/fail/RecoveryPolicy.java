package com.dwinovo.numen.rdd.fail;

import java.util.ArrayList;
import java.util.List;

/**
 * 恢复策略（P3，纯函数）：把 {@link RecoveryDecision}（谁负责救）+ 已知缺口
 * 翻成 {@link RecoveryPlan}（具体先做什么）。**代码先给动作与步骤骨架，方案生成交 AI/Planner。**
 *
 * <p>与 {@link FailureClassifier} 的分工：Classifier 判“出口”，Policy 把出口落成可执行动作。
 */
public final class RecoveryPolicy {

    private RecoveryPolicy() {}

    /**
     * @param missing 已知缺口（"key need X have Y"，来自 ResourceBudget.missingFor；可为空）
     */
    public static RecoveryPlan plan(RecoveryDecision decision, List<String> missing) {
        if (decision == null) {
            return new RecoveryPlan(RecoveryAction.PARK, List.of("停车等待外部/资产"), "无诊断决策");
        }
        List<String> gaps = missing == null ? List.of() : missing;
        return switch (decision.outcome()) {
            case RECOVER -> new RecoveryPlan(RecoveryAction.GOTO_BASE_AND_EQUIP,
                    List.of("回基地（个人重生点）", "取用备用装备（death_recovery 用途）", "回到原位置恢复任务"),
                    decision.reason());
            case REPAIR -> gaps.isEmpty()
                    ? new RecoveryPlan(RecoveryAction.RETRY_WITH_NEW_STRATEGY,
                    List.of("换策略/换路线/换工具", "从上一个合法状态重试该步"),
                    decision.reason())
                    : new RecoveryPlan(RecoveryAction.PREPARE_MISSING_ASSETS,
                    prepareSteps(gaps), decision.reason());
            case REPLAN -> new RecoveryPlan(RecoveryAction.REQUEST_REPLAN,
                    List.of("收集失败事实与现有资产", "请求重规划（改变计划，非原地重试）"),
                    decision.reason());
            case SELF_COMPILE -> new RecoveryPlan(RecoveryAction.REQUEST_SELF_COMPILE,
                    List.of("整理现象与最小复现", "提交 selfcompile_request"),
                    decision.reason());
        };
    }

    private static List<String> prepareSteps(List<String> gaps) {
        List<String> steps = new ArrayList<>();
        for (String g : gaps) {
            steps.add("补齐 " + g);
        }
        steps.add("补齐后回到该步继续");
        return List.copyOf(steps);
    }
}
