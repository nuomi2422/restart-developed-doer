package com.dwinovo.numen.worldstate.core.model;

/**
 * 本次采集<b>覆盖了多少请求范围</b>——回答「到底检查了多少」。
 *
 * <p>这是把「读不到」和「没有」分开的关键：只有 {@link #COMPLETE} 才能据以断言"这里一个都没有"。
 * {@link #PARTIAL} 只说明"在能看到的范围内观察到 N 个"，不能当成总数。
 */
public enum Coverage {

    /** 请求范围被完整检查过。只有它能证明"空"。 */
    COMPLETE,
    /** 只检查了部分范围（区块未加载 / 被预算截断），观察到的是下限，不是总数。 */
    PARTIAL,
    /** 目标范围完全未加载，本次没有采集到任何东西。 */
    UNLOADED;

    /** 只有完整覆盖才允许把"0"当作"确认没有"。 */
    public boolean canProveAbsence() {
        return this == COMPLETE;
    }
}
