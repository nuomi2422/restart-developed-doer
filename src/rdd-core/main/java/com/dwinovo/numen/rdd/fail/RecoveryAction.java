package com.dwinovo.numen.rdd.fail;

/**
 * 恢复动作（P3）：诊断出口对应的“接下来做什么”。代码先定动作，具体方案再交 Planner/AI。
 */
public enum RecoveryAction {
    /** 回基地取备用装备后继续（RECOVER）。 */
    GOTO_BASE_AND_EQUIP,
    /** 补缺资源/风险缺口后继续（REPAIR 且已知缺口）。 */
    PREPARE_MISSING_ASSETS,
    /** 换策略重试（REPAIR 但缺口未知）。 */
    RETRY_WITH_NEW_STRATEGY,
    /** 请求重规划（REPLAN）。 */
    REQUEST_REPLAN,
    /** 走自变异流水线（SELF_COMPILE）。 */
    REQUEST_SELF_COMPILE,
    /** 停车等待外部/资产（兜底）。 */
    PARK
}
