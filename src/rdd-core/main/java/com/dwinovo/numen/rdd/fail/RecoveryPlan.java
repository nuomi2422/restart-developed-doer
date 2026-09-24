package com.dwinovo.numen.rdd.fail;

import java.util.List;
import java.util.Objects;

/**
 * 恢复计划（P3）：一个恢复动作 + 有序步骤 + 判定依据。纯数据。
 *
 * @param action    动作
 * @param steps     有序步骤（人类/AI 可读；真实执行由宿主据动作翻译）
 * @param rationale 判定依据（可审计）
 */
public record RecoveryPlan(RecoveryAction action, List<String> steps, String rationale) {
    public RecoveryPlan {
        Objects.requireNonNull(action, "action required");
        steps = steps == null ? List.of() : List.copyOf(steps);
        rationale = rationale == null ? "" : rationale;
    }
}
