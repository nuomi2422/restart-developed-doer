package com.dwinovo.numen.rdd.side;

/** 当前执行槽归谁：主线（原 TaskChain）还是某条支线。 */
public enum TaskScope {
    MAINLINE,
    SIDE
}
