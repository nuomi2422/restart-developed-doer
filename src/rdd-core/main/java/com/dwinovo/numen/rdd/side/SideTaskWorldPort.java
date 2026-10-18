package com.dwinovo.numen.rdd.side;

import java.util.UUID;

/**
 * 抽象世界端口：支线类型只看这一层，宿主（MC 侧）提供实现。
 *
 * <p>这是 headless 可测的关键——纯 JVM 测试注入一个假端口即可覆盖"进入睡眠/天亮醒来/
 * 被外力打断/倒计时"全部路径，不必启动游戏。
 */
public interface SideTaskWorldPort {

    /** 醒来这一刻的成因分类。{@code NONE} = 本次评估前没有发生"睡着→醒"的边沿。 */
    enum Wake {
        NONE,
        /** 天亮自然醒（夜过去了）= 正常完成一轮睡眠。 */
        DAYBREAK,
        /** 天没亮就醒 / 被打醒 = 中断。 */
        INTERRUPTED
    }

    /** 世界年龄（tick）：单调递增、不受 {@code /time set} 影响；用于 deadline。 */
    long gameTime();

    /** 昼夜时间（tick）：受 {@code /time set} 影响、驱动日/夜；用于睡眠窗口。 */
    long dayTime();

    long gameDay();

    boolean hasDayNightCycle();

    String worldId();

    boolean isSleeping(UUID companionId);

    boolean isDay();

    /** 消费一次"睡着→醒"边沿；返回 {@link Wake#NONE} 表示没有。每次调用只返回一次。 */
    Wake pollWake(UUID companionId);

    // ---- 决策用事实（默认无此概念，宿主按需实现） ----

    /** 是否正在战斗/受伤（宿主用 {@code hurtTime} 等）。默认 false。 */
    default boolean inCombat(UUID companionId) {
        return false;
    }

    /** 身体是否被别的任务占着（宿主用当前身体任务是否非空）。默认 false。 */
    default boolean bodyBusy(UUID companionId) {
        return false;
    }

    /** 当前主线目标文本（目标/一级/二级描述拼接）；无主线返回 null。用于"主线是否要求夜间活动"。 */
    default String mainlineObjective(UUID companionId) {
        return null;
    }
}
