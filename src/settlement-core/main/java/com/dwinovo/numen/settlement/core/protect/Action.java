package com.dwinovo.numen.settlement.core.protect;

/** 对世界的一次改动意图。 */
public enum Action {
    /** 拆除/挖掉。 */
    BREAK,
    /** 在空格/可替换格放置。 */
    PLACE,
    /** 把一个已有的方块换成别的。 */
    REPLACE,
    /** 桶操作（放水/收水/倒岩浆等）。 */
    BUCKET
}
