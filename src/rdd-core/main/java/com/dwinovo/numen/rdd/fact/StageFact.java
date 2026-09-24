package com.dwinovo.numen.rdd.fact;

/**
 * 阶段级完成事实（P0 主事实）："某个血缘下的某个战略阶段主题，过去被可靠完成过"。
 *
 * <p>刻意不绑定二级 id/文本——那两样在 LLM 重规划里不稳定；只认血缘 + 归一阶段键。
 *
 * @param lineageId  目标血缘（见 {@link GoalLineage}）
 * @param goalId     Goal 基 id（同同伴同权重，用于 Level-2 近似继承的同源判定）
 * @param objective  该血缘对应的目标正文指纹（用于 Level-2 近似继承）
 * @param stageKey   归一阶段键（见 {@link StageKeyNormalizer}）
 * @param rawStage   原始阶段主题文本（留档，便于将来升级归一算法）
 * @param completedAtMillis 记录时间戳
 * @param evidence   完成依据（人类可读，例如 "primary confirmed" 或条件摘要）
 */
public record StageFact(String lineageId,
                        String goalId,
                        String objective,
                        String stageKey,
                        String rawStage,
                        long completedAtMillis,
                        String evidence) {
}
