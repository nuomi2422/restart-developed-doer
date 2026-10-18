package com.dwinovo.numen.rdd.side;

/**
 * 周期支线模板规格（<b>只预留接口，本轮不实现农田逻辑</b>）。
 *
 * <p>未来"每两三游戏日收割/补种"应是多步硬编码支线，由这里按游戏日节拍触发；
 * 是否逾期补做由 {@link MissedPolicy} 决定。
 */
public record RecurringSideTaskSpec(
        String scheduleId,
        String templateId,
        int intervalGameDays,
        long anchorGameDay,
        MissedPolicy missedPolicy,
        boolean enabled) {

    public enum MissedPolicy {
        /** 错过就跳过这一拍。 */
        SKIP,
        /** 下一拍补做。 */
        CATCH_UP
    }

    public boolean dueOn(long gameDay) {
        return enabled
                && intervalGameDays > 0
                && gameDay >= anchorGameDay
                && Math.floorMod(gameDay - anchorGameDay, intervalGameDays) == 0;
    }
}
