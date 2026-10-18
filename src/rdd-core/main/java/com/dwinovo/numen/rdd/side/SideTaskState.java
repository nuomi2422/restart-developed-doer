package com.dwinovo.numen.rdd.side;

/** 支线的通用生命周期状态（与具体支线类型无关）。 */
public enum SideTaskState {
    CREATED,
    ACTIVE,
    COMPLETED,
    /** 超时跳过：不是失败，不触发主线 FAILED / 重规划。 */
    SKIPPED_TIMEOUT,
    /** 取消：主人暂停/换目标/清目标/世界切换等。 */
    CANCELLED
}
