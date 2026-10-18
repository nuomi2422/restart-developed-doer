package com.dwinovo.numen.acx.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份 AC 自己声明的运行上限，覆盖 {@link AcxLimits} 的全局默认。
 *
 * <p>对应 {@code .ac} 顶层 {@code limits} 对象，可写键：
 * {@code max_steps} / {@code max_timeout_ms} / {@code max_depth}。
 * 值为 {@code null} 表示这一项不覆盖，用全局默认。</p>
 *
 * <p>为什么要有它：全局上限是「护栏」，一份 AC 自己该跑多久是「意图」。
 * 没有这层，想把「10 分钟熔断」放宽的作者只能去改全局，把别的 AC 一起放宽。</p>
 */
public record AcxLimitsSpec(Integer maxSteps, Long maxTimeoutMs, Integer maxDepth) {

    public AcxLimitsSpec {
        // 不做取值校验：解析器（AcxLoader）已经按类型与正数校验过，
        // 这里再校验一次只会让「手写构造」和「文件解析」两套规则漂移。
    }

    /** 三项都不覆盖。 */
    public static AcxLimitsSpec of() {
        return new AcxLimitsSpec(null, null, null);
    }

    /** 从可空三元构造（便于 map 取值后直接塞进来）。 */
    public static AcxLimitsSpec of(Integer maxSteps, Long maxTimeoutMs, Integer maxDepth) {
        return new AcxLimitsSpec(maxSteps, maxTimeoutMs, maxDepth);
    }

    /** 三个键中是否有任何一项要覆盖。 */
    public boolean isEmpty() {
        return maxSteps == null && maxTimeoutMs == null && maxDepth == null;
    }

    /** 只有覆盖项才进指纹，避免「写了空 limits 就换指纹」。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (maxSteps != null) {
            m.put("max_steps", maxSteps);
        }
        if (maxTimeoutMs != null) {
            m.put("max_timeout_ms", maxTimeoutMs);
        }
        if (maxDepth != null) {
            m.put("max_depth", maxDepth);
        }
        return m;
    }

    @Override
    public String toString() {
        return "AcxLimitsSpec" + toMap();
    }
}