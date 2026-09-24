package com.dwinovo.numen.rdd.policy;

/**
 * 风险级别（P2.1/P2.2）：决定“最低储备”的门槛。按活动危险度分档（不是按维度硬编）。
 */
public enum RiskLevel {
    /** 主世界常规（村庄、近处采集）。 */
    NORMAL,
    /** 下矿/洞穴/夜行。 */
    MINING,
    /** 下界。 */
    NETHER,
    /** 末地。 */
    END
}
