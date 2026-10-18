package com.dwinovo.numen.core.task.chain;

/**
 * 换气反射"反复抢身体"的纯判据——不碰 Minecraft。
 *
 * <h2>为什么需要它</h2>
 * {@code BreathChain} 是在救命:头在水下、氧气见底,它就把身体抢过去上浮。它做完之后,
 * 被它打断的任务会恢复——<b>然后很可能再做一遍同样的事</b>。用户案例就是这个:
 * 下水捡东西 → 浮上来 → 又下水 → 又浮上来,左右横跳,资产一件没进包。
 *
 * <p>不能靠关掉它来解决(那是唯一的漂浮本能,假玩家没有客户端按跳跃键),能解决的是
 * <b>让反复这件事变成一条认知层看得见的话</b>:同一片水域里短时间内反复上浮,就把
 * "这里做不成,换个地方/换个法子"直接告诉上层,而不是让任务无限次地重试同一个错。
 *
 * <p>注意判据的取向:<b>只报告,不阻断</b>。救命永远优先,这里只负责让循环变得可见。
 */
public final class BreathEpisodeRules {

    private BreathEpisodeRules() {}

    /** 同一片水里连续第几次上浮就值得报。 */
    public static final int REPORT_AFTER_EPISODES = 3;
    /** "同一片水"的判定半径(格,水平面内)。 */
    public static final int AREA_RADIUS = 4;
    /** 两次上浮间隔超过这么久就算"另一次事",不清计数。 */
    public static final int WINDOW_TICKS = 600;

    /** 一次上浮结束后的裁决。 */
    public enum Verdict {
        /** 正常,继续。 */
        OK,
        /** 这片水域反复把我拽上来——告诉上层换个法子,别在这儿重试。 */
        REPORT_REPEATED_RESCUE
    }

    /**
     * 两次上浮算不算"同一片水":水平距离在 {@link #AREA_RADIUS} 内、竖直方向不设限
     * (水面上下都算同一片),且间隔不超过 {@link #WINDOW_TICKS}。
     */
    public static boolean sameArea(int ax, int ay, int az, int bx, int by, int bz, int ticksApart) {
        if (ticksApart > WINDOW_TICKS || ticksApart < 0) {
            return false;
        }
        int dx = ax - bx;
        int dz = az - bz;
        return dx * dx + dz * dz <= AREA_RADIUS * AREA_RADIUS;
    }

    /**
     * 一次上浮结束时:该不该把"反复"这条账报给上层。
     *
     * @param episodesInWindow 计入本窗口的上浮次数(含刚结束的这次)
     */
    public static Verdict onEpisodeEnd(int episodesInWindow) {
        return episodesInWindow >= REPORT_AFTER_EPISODES
                ? Verdict.REPORT_REPEATED_RESCUE
                : Verdict.OK;
    }
}
