package com.dwinovo.numen.rdd.fail;

/**
 * 恢复出口（P2.0）。四选一，对应 GPT/主人对齐的失败诊断四出口。
 */
public enum RecoveryOutcome {
    /** 可恢复：死亡但有备用装备+基地坐标 → 回基地取装备、恢复任务，不重规划。 */
    RECOVER,
    /** 部分失败：补准备/换策略后继续原目标（交 Planner 出方案）。 */
    REPAIR,
    /** 目标性破坏才重规划（真正改变计划）。 */
    REPLAN,
    /** 确定代码问题 → 走自变异流水线。 */
    SELF_COMPILE
}
