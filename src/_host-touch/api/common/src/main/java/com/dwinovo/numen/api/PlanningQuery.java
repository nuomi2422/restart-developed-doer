package com.dwinovo.numen.api;

import java.util.List;
import java.util.UUID;

/**
 * 一次任务链规划请求的背景，供知识贡献者判断「这次该想起什么」。
 *
 * <p>刻意只带规划器本来就有的那点信息：给谁规划、目标是什么、处在哪个阶段、已知哪些失败。
 * 不带背包、不带世界状态——那些由规划器自己渲染进请求，知识侧不该重复一份、更不该据此
 * 影响任务结构。
 *
 * @param companion     为哪只同伴规划（经验按同伴隔离，必须原样透传）
 * @param objective     当前目标或当前阶段主题
 * @param stage         阶段名（{@code stage_a} / {@code stage_b} / {@code fallback}）
 * @param knownFailures 已知失败事实（重试/回退时携带），是召回最相关的依据
 */
public record PlanningQuery(UUID companion, String objective, String stage, List<String> knownFailures) {

    public PlanningQuery {
        objective = objective == null ? "" : objective;
        stage = stage == null ? "" : stage;
        knownFailures = knownFailures == null ? List.of() : List.copyOf(knownFailures);
    }

    public static PlanningQuery of(UUID companion, String objective, String stage, List<String> knownFailures) {
        return new PlanningQuery(companion, objective, stage, knownFailures);
    }
}
