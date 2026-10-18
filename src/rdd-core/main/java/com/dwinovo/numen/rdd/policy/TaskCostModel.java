package com.dwinovo.numen.rdd.policy;

import java.util.Map;

/**
 * P2-E 时间/成本评分（GPT 外脑建议 · 2026-09-25）：给规划层一个"值不值得做"的量化参考。
 *
 * <p>动机（用户实测）：任务链没有时间成本概念——把"种小麦等生长"和"进村庄拿现成食物"
 * 当成同等选项，甚至把种田排进主线。真实老玩家会权衡：收益 − 时间 − 风险 + 未来复用。
 *
 * <p>公式（GPT 版）：{@code value = progress_gain - time_cost - risk + future_reuse}。
 * 本类只提供**每类活动的保守估时**与**评分**，纯数据+纯函数，可单测；
 * 不下达指令、不改 TaskChain——只把分数交给规划提示词/Supervisor 参考。
 *
 * <p>时间单位：**相对刻度**（不是真实秒）。刻度越小越省时。数值可调。
 */
public final class TaskCostModel {

    /** 常见活动的相对时间成本（刻度）。保守基线，可调。 */
    public enum Activity {
        /** 拿现成物（村庄箱子/掉落物）：最省。 */
        PICKUP_EXISTING(1),
        /** 击杀动物取肉：较快。 */
        HUNT_ANIMAL(4),
        /** 合成（有材料）：快。 */
        CRAFT(2),
        /** 表面采集（砍树/挖表层石）：中。 */
        GATHER_SURFACE(5),
        /** 下矿采掘（找矿+挖）：慢。 */
        MINE_DEEP(12),
        /** 种田**播种后还需等待生长**：最慢（不可压缩的等待）。 */
        FARM_AND_WAIT(20),
        /** 探索/找结构：不定，取中高。 */
        EXPLORE(10);

        private final int costTicks;
        Activity(int costTicks) { this.costTicks = costTicks; }

        /** 相对时间成本（刻度）。 */
        public int cost() { return costTicks; }
    }

    private TaskCostModel() {}

    /**
     * 评分：{@code value = gain - time - risk + reuse}。
     *
     * @param progressGain 对当前目标的推进（0..100）
     * @param timeCost     时间成本（见 {@link Activity#cost()} 或自定义）
     * @param risk         风险代价（0..100）
     * @param futureReuse  未来复用价值（0..100，如"多做一套备用装备"）
     */
    public static int score(int progressGain, int timeCost, int risk, int futureReuse) {
        return progressGain - Math.max(0, timeCost) - Math.max(0, risk) + Math.max(0, futureReuse);
    }

    /** 用活动类型评分。 */
    public static int score(Activity activity, int progressGain, int risk, int futureReuse) {
        return score(progressGain, activity == null ? 0 : activity.cost(), risk, futureReuse);
    }

    /**
     * 是否"明显不划算"：推进有限、耗时很高、又无未来复用 → 建议换策略。
     * 例：种田等待（FARM_AND_WAIT=20）换少量食物、无复用 → 不划算。
     */
    public static boolean isWasteful(Activity activity, int progressGain, int futureReuse) {
        return score(activity, progressGain, 0, futureReuse) < 0;
    }

    /** 人类可读的时间刻度说明（给提示词）。 */
    public static String explain() {
        StringBuilder sb = new StringBuilder("Relative time cost (smaller = cheaper): ");
        Activity[] all = Activity.values();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(all[i].name().toLowerCase(java.util.Locale.ROOT)).append('=').append(all[i].cost());
        }
        return sb.toString();
    }

    /** 各活动成本快照（不可变，供测试/诊断）。 */
    public static Map<String, Integer> costs() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Activity a : Activity.values()) m.put(a.name(), a.cost());
        return Map.copyOf(m);
    }
}
