package com.dwinovo.numen.settlement.core.model;

/**
 * 生产条件（第三层判据）：使用所需资源是否到位——圈内已有羊、农田已有作物、交易位可交易。
 *
 * <p>空圈可以判 {@link Verdict#USABLE}，但<b>不能判"已有稳定食物来源"</b>。所以生产条件
 * 单独一层，不能并进结构可用性里，否则"建好了"会被误当成"能产出了"。
 *
 * <p>同样保留 {@link #UNKNOWN}：区块未加载时不得用旧缓存判"当前已满足"。
 */
public enum ProductionState {
    /** 生产条件已具备。 */
    READY,
    /** 具备使用条件但资源未到位（空圈、空田）。 */
    NOT_READY,
    /** 无法确认（区块未加载）。 */
    UNKNOWN
}
