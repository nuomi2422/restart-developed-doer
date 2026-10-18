package com.dwinovo.numen.settlement.core.model;

/**
 * 结构可用性（第二层判据）：关键功能条件是否满足——围栏闭合、门可操作、入口能通行。
 *
 * <p><b>UNKNOWN 是一等公民</b>：区块未加载时不允许假装设施消失，也不允许拿旧缓存判"当前可用"。
 * 这种情况必须返回 {@link #UNKNOWN} 并说明原因，保留登记与历史。
 */
public enum Verdict {
    /** 结构条件满足，可投入使用。 */
    USABLE,
    /** 结构条件不满足（施工未收口、入口缺失等）。 */
    NOT_USABLE,
    /** 无法确认（区块未加载、探测不到）。 */
    UNKNOWN
}
