package com.dwinovo.numen.rdd.fact;

/**
 * 二级级完成事实（P0 辅助留档）：阶段内部的完成细节，供后续 Planner/Context 参考，
 * <b>不</b>作为状态恢复依据（二级文本天然不稳定，恢复只认 {@link StageFact}）。
 *
 * @param lineageId  目标血缘
 * @param stageKey   所属阶段的归一键
 * @param rawSubtask 原始二级描述
 * @param completedAtMillis 记录时间戳
 */
public record SubtaskFact(String lineageId,
                          String stageKey,
                          String rawSubtask,
                          long completedAtMillis) {
}
