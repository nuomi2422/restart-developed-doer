package com.dwinovo.numen.worldstate.core.model;

/** 刷新未能执行的原因。<b>失败模式不许静默</b>：调用方必须拿到一个明确原因。 */
public enum FailReason {
    NONE,
    /** 目标维度不可用/不存在。 */
    DIMENSION_MISSING,
    /** 目标范围所在区块未加载（不强制加载，也不拿 0 冒充"没有"）。 */
    CHUNK_UNLOADED,
    /** 请求体积超过采集器声明的预算上限，请缩小范围。 */
    BUDGET_EXCEEDED,
    /** 没有为该主题注册采集器（或该请求参数不支持）。 */
    NO_PROVIDER,
    /** 采集器内部异常。 */
    PROVIDER_ERROR,
    /** 请求参数非法。 */
    INVALID_REQUEST,
    TIMED_OUT,
    CANCELLED
}
