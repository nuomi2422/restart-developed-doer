package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxLimitsSpec;

/**
 * 运行期熔断阈值。默认值见 {@link AcxLimits}，由宿主按需覆盖，
 * 单份 AC 还能用自己的 {@link AcxLimitsSpec} 逐项覆盖（见 {@link #merged}）。
 */
public final class AcxRuntimeLimits {

    private final int maxSteps;
    private final long maxTimeoutMs;
    private final int maxDepth;

    public AcxRuntimeLimits(int maxSteps, long maxTimeoutMs, int maxDepth) {
        this.maxSteps = maxSteps <= 0 ? AcxLimits.DEFAULT_MAX_STEPS : maxSteps;
        this.maxTimeoutMs = maxTimeoutMs <= 0 ? AcxLimits.DEFAULT_MAX_TIMEOUT_MS : maxTimeoutMs;
        this.maxDepth = maxDepth <= 0 ? 8 : maxDepth;
    }

    public static AcxRuntimeLimits defaults() {
        return new AcxRuntimeLimits(
                AcxLimits.DEFAULT_MAX_STEPS,
                AcxLimits.DEFAULT_MAX_TIMEOUT_MS,
                8);
    }

    /**
     * 把「AC 自己声明的上限」叠在「运行时阈值」上，AC 声明的项赢。
     *
     * <p>{@code spec} 为 null 或全空时原样返回 base（同一个对象，不做无谓拷贝）。</p>
     */
    public static AcxRuntimeLimits merged(AcxRuntimeLimits base, AcxLimitsSpec spec) {
        AcxRuntimeLimits b = base == null ? defaults() : base;
        if (spec == null || spec.isEmpty()) {
            return b;
        }
        return new AcxRuntimeLimits(
                spec.maxSteps() == null ? b.maxSteps() : spec.maxSteps(),
                spec.maxTimeoutMs() == null ? b.maxTimeoutMs() : spec.maxTimeoutMs(),
                spec.maxDepth() == null ? b.maxDepth() : spec.maxDepth());
    }

    public int maxSteps() {
        return maxSteps;
    }

    public long maxTimeoutMs() {
        return maxTimeoutMs;
    }

    /** 子 AC 递归层数上限，防 A 调 B 调 A 的间接无限递归。 */
    public int maxDepth() {
        return maxDepth;
    }
}
