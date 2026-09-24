package com.dwinovo.numen.rdd.fail;

import java.util.Objects;

/**
 * 恢复决策（P2.0）：诊断层输出。{@code auto=true} 表示出口由代码硬规则直接可执行
 * （如 RECOVER 回基地取备用装备）；{@code auto=false} 表示需 Planner/AI 出方案（REPAIR/REPLAN）。
 *
 * @param outcome 出口
 * @param reason  判定依据（可审计）
 * @param auto    是否代码可直接执行
 */
public record RecoveryDecision(RecoveryOutcome outcome, String reason, boolean auto) {
    public RecoveryDecision {
        Objects.requireNonNull(outcome, "outcome required");
        reason = reason == null ? "" : reason;
    }
}
