package com.dwinovo.numen.rdd.side.sleep;

import com.dwinovo.numen.rdd.side.SideTaskTrigger;

/**
 * 睡觉支线触发器：从"夜前约一分钟"开始，覆盖整个夜晚，每游戏日只开一次。
 *
 * <p>原版夜晚约 {@code dayTime ∈ [13000, 23000)}；{@code 11800} 约等于夜前 1200 tick（60 秒）。
 * 窗口一直开到 {@code 23000}（天亮前）——这样"夜里才登录""黄昏没来得及睡"等情况也能触发，
 * 而不是只认那 60 秒。用游戏 tick（{@code dayTime % 24000}），不用墙钟；无昼夜循环维度不触发。
 */
public final class SleepSideTaskTrigger implements SideTaskTrigger {

    public static final long TRIGGER_DAY_TIME = 11_800L;
    public static final long WINDOW_END_DAY_TIME = 23_000L;

    @Override
    public String typeId() {
        return SleepSideTaskType.TYPE_ID;
    }

    @Override
    public boolean fires(Context ctx) {
        if (!ctx.hasDayNightCycle()) {
            return false;
        }
        long dayTime = Math.floorMod(ctx.dayTime(), 24_000L);
        return dayTime >= TRIGGER_DAY_TIME && dayTime < WINDOW_END_DAY_TIME;
    }
}
