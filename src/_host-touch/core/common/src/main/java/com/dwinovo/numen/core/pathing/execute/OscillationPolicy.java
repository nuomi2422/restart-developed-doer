package com.dwinovo.numen.core.pathing.execute;

import java.util.List;

/**
 * 「原地横跳」判据（纯函数，2026-09-28）。
 *
 * <p><b>为什么需要</b>：实机反复出现的浪费形态——同伴在两点之间来回走（最常见于
 * 「走到水边就自动躲开以免淹死 → 侧移 → 又被判定要避水 → 侧移回来」），
 * 表现为长时间零净位移、方向来回反转。路径本身没报错、也没被判卡死，
 * 于是它能这样磨几十分钟，把时间与模型调用一起烧掉。
 *
 * <p><b>判据</b>（只看水平位移，垂直爬塔不算横跳）：
 * <ol>
 *   <li>窗口内至少有 {@code minSamples} 个采样；</li>
 *   <li><b>净位移</b>（首尾直线距离）小于 {@code netThresholdBlocks}；</li>
 *   <li><b>总路程</b>（逐段累加）大于 {@code travelThresholdBlocks}
 *       —— 关键：既走了不少路、却几乎回到原处，这正是"在原地磨"；</li>
 *   <li>方向反转次数（相邻段夹角 ≥ {@code reversalDegrees}）≥ {@code minReversals}。</li>
 * </ol>
 *
 * <p><b>宁可漏报也不误报</b>：真实的长途绕路也会"净位移小、路程大"，
 * 所以再加上"反转次数"这一条；正常绕行是持续朝一个方向推进，
 * 而横跳是方向来回摆动。三条同时成立才判定为横跳。
 */
public final class OscillationPolicy {

    private OscillationPolicy() {
    }

    /**
     * @param samples              按时间升序的水平采样点（x,z），单位格
     * @param minSamples           最少采样数
     * @param netThresholdBlocks   首尾净位移上限（格）
     * @param travelThresholdBlocks 总路程下限（格）
     * @param reversalDegrees      判定为「反向」的相邻段夹角（度）
     * @param minReversals         至少这么多次反向
     */
    public static boolean oscillating(List<double[]> samples, int minSamples, double netThresholdBlocks,
                               double travelThresholdBlocks, double reversalDegrees, int minReversals) {
        if (samples == null || samples.size() < minSamples) {
            return false;
        }
        double[] first = samples.get(0);
        double[] last = samples.get(samples.size() - 1);
        double net = Math.hypot(last[0] - first[0], last[1] - first[1]);
        if (net >= netThresholdBlocks) {
            return false;   // 真的走远了，不是原地
        }
        double travel = 0;
        int reversals = 0;
        double prevHeading = Double.NaN;
        for (int i = 1; i < samples.size(); i++) {
            double[] a = samples.get(i - 1);
            double[] b = samples.get(i);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double seg = Math.hypot(dx, dz);
            if (seg < 1e-6) {
                continue;   // 没动的采样不算方向
            }
            travel += seg;
            double heading = Math.atan2(dx, dz);
            if (!Double.isNaN(prevHeading)) {
                double diff = Math.abs(Math.toDegrees(heading - prevHeading));
                if (diff > 180) {
                    diff = 360 - diff;
                }
                if (diff >= reversalDegrees) {
                    reversals++;
                }
            }
            prevHeading = heading;
        }
        return travel >= travelThresholdBlocks && reversals >= minReversals;
    }
}
