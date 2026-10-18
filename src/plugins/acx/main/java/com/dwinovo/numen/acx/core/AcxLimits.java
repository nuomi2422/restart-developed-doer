package com.dwinovo.numen.acx.core;

/**
 * 执行期熔断阈值与默认上限。
 *
 * <p>数值照 DD 版 {@code AcRunner}：{@code DEFAULT_MAX_STEPS = 200}、
 * {@code DEFAULT_MAX_TIMEOUT_MS = 30_000}、{@code max_iters} 默认 20、{@code stagnant_limit} 默认 3。</p>
 *
 * <p>与现有 AC 的关键差别：现有 AC 的 {@code MAX_STEPS = 50} 是<b>发布期</b>静态校验
 * （限制一份 AC 能写多少步），不是运行期熔断。这里的是<b>运行期</b>熔断，
 * 限制一次执行最多走多少步（含递归子 AC 累计）。两者解决不同问题，各留各的。</p>
 */
public final class AcxLimits {

    /** 单次执行的最大总步数，含递归子 AC 累计。 */
    public static final int DEFAULT_MAX_STEPS = 200;

    /** 单次执行的最大 wall-clock 时间（毫秒），防止卡死 MC 主线程。 */
    public static final long DEFAULT_MAX_TIMEOUT_MS = 30_000L;

    /** {@code while} 硬上限。 */
    public static final int DEFAULT_MAX_ITERS = 20;

    /** {@code while} 停滞阈值：连续 N 轮无实质进展即判定此路不通。 */
    public static final int DEFAULT_STAGNANT_LIMIT = 3;

    private AcxLimits() { }
}