package com.dwinovo.numen.worldstate.core.model;

/**
 * 观测有多旧。<b>TTL 只说明数据有多旧，不保证 TTL 内世界没变化</b>——
 * 真正要取物/交易/完成任务前，务必再刷新，执行工具也仍要检查前置条件。
 */
public enum Freshness {

    /** 在主题 TTL 内，且是本次进程直接采集的。 */
    CURRENT,
    /** 超过主题 TTL。 */
    STALE,
    /** 重启后从磁盘载入的动态观测，尚未经过本轮刷新。 */
    HISTORICAL,
    /** 根本没有观测。 */
    UNKNOWN;

    public boolean trustworthy() {
        return this == CURRENT;
    }
}
