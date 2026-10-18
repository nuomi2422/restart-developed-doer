package com.dwinovo.numen.rdd.policy;

/**
 * 背包空间提醒（Backpack Space）——「快满了」要主动说，不能等它捡不起来再报错。
 *
 * <h2>为什么单独做这一层</h2>
 * 用户 2026-09-28 点名：<b>「背包满是非常大的问题…他老是背包满，虽然说能够识别，但我觉得可以加一个提示」</b>。
 * 现状是：背包满了之后，采集/拾取类任务会失败或空转（{@code MovementPlacement} 里那句
 * 「背包满还没耗材」、{@code FishCompanionTask} 的满包失败路径都说明它<b>事后</b>知道），
 * 但**没有人在它满之前提醒它去卸货**——于是它一路挖到满、再一路失败。
 *
 * <p>纯 JVM、可单测：这里只回答「该不该提醒」，读背包与发 nudge 交给宿主。
 */
public final class BackpackSpace {

    private BackpackSpace() {}

    /**
     * 剩余可拾取格数到这个数就提醒。
     *
     * <p>取 3 而不是 0：留 3 格给它跑完手里这一趟（挖一簇矿、捡一波掉落），
     * 到 0 才报就等于要它当场丢了，反而更乱。用户要的是"要满了就给个提醒"。
     */
    public static final int WARN_FREE_SLOTS = 3;

    /** 剩余格数是否已到该提醒的程度。 */
    public static boolean nearFull(int freeSlots) {
        return freeSlots <= WARN_FREE_SLOTS;
    }

    /**
     * 该不该真的发这条提醒。
     *
     * <p>防刷屏（本项目有前科：等待类事件每 tick 重发，一次卡死 90+ 条淹掉监测台）：
     * 只在**首次接近满**、或**比上次更紧**、或**距上次提醒已过冷却**时才发。
     *
     * @param freeSlots      现在剩余格数
     * @param lastWarnedAt   上次提醒时刻（游戏刻；0 表示从未）
     * @param lastWarnedFree 上次提醒时的剩余格数（-1 表示从未）
     * @param nowTick        当前游戏刻
     * @param cooldownTicks  冷却刻数
     */
    public static boolean shouldWarn(int freeSlots, long lastWarnedAt, int lastWarnedFree,
                                     long nowTick, long cooldownTicks) {
        if (!nearFull(freeSlots)) {
            return false;
        }
        if (lastWarnedAt <= 0) {
            return true;                            // 首次
        }
        if (freeSlots < lastWarnedFree) {
            return true;                            // 更紧了，值得再说一次
        }
        return nowTick - lastWarnedAt >= cooldownTicks;
    }

    /** 提醒文案：说清"还剩几格 + 会有什么后果 + 该干什么"，别只说"背包满了"。 */
    public static String warnMessage(int freeSlots, int mainSlots) {
        int used = Math.max(0, mainSlots - freeSlots);
        return "⚠ 背包快满了（" + used + "/" + mainSlots + "，只剩 " + freeSlots
                + " 格）—— 再满就捡不到掉落物、采集会失败。"
                + "先去基地箱子用 transfer 把不用的东西存一批，再继续挖/采。";
    }
}
