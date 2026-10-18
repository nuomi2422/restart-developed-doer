package com.dwinovo.numen.experience.core;

/**
 * 经验库统计（监测台 / contributeState / 注入目录共用）。
 *
 * <p><b>口径规则（E8 定，别各自改）</b>：
 * {@link #total} 是<b>全量</b>（含已撤回 / 已被取代的），
 * 而 {@link #verified} / {@link #generalized} 只数 <b>usable</b>
 * —— 一条被撤回的经验不该再被算成「可信」。
 * 于是 {@code total - (verified + generalized)} 这个差值天然包含了不可用的条数，
 * 监测台与注入目录都能一眼看出「库里有 N 条，但只有 M 条能用」。</p>
 *
 * @param total          全量条数
 * @param verified       <b>可用</b>条目里成熟度为 VERIFIED 的条数
 * @param generalized    <b>可用</b>条目里成熟度为 GENERALIZED 的条数
 * @param usable         还能当可信经验用的条数（E8：排除已撤回 / 已被取代）
 * @param retracted      已撤回的条数（E8）
 * @param superseded     已被更新条目取代的条数（E8）
 */
public record ExperienceStats(
        int total,
        int verified,
        int generalized,
        int usable,
        int retracted,
        int superseded
) {

    /** 便捷构造：不关心 E8 明细时用它，明细按 0 填（调用方拿不到就别假装有）。 */
    public ExperienceStats(int total, int verified, int generalized) {
        this(total, verified, generalized, total, 0, 0);
    }

    /** 全量里<b>不能用</b>的条数（撤回 + 被取代）。 */
    public int unusable() {
        return Math.max(0, total - usable);
    }
}