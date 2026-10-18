package com.dwinovo.numen.worldstate.core.model;

/** 一次刷新（去世界采集）的结局。 */
public enum RefreshStatus {
    /** 采集成功并写入观测。 */
    SUCCESS,
    /** 采集失败（未加载、预算超限、采集器报错等），<b>不清空上一次观测</b>。 */
    FAILED,
    /** 等待超时（结果可能稍后才回来，但调度不再等它）。 */
    TIMED_OUT,
    /** 被取消。 */
    CANCELLED
}
