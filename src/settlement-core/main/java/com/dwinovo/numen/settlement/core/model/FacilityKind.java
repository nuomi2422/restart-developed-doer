package com.dwinovo.numen.settlement.core.model;

/** 设施用途。P0 先覆盖牧场/农田/交易所/居住/仓储，其余走 GENERIC。 */
public enum FacilityKind {
    /** 居住点：床、入口、储物箱。 */
    HOUSE,
    /** 仓储点：箱子群。 */
    STORAGE,
    /** 羊圈。 */
    PASTURE_SHEEP,
    /** 牛圈。 */
    PASTURE_COW,
    /** 农田。 */
    FARM,
    /** 交易所：交易位 + 工作站 + 玩家交互位置。 */
    TRADE,
    /** 其它/未分类设施。 */
    GENERIC;

    public boolean isPasture() {
        return this == PASTURE_SHEEP || this == PASTURE_COW;
    }

    /** 这类设施是否以"产出/饲养"为生产条件（决定验收怎么判）。 */
    public boolean isProductionFacility() {
        return isPasture() || this == FARM || this == TRADE;
    }
}
