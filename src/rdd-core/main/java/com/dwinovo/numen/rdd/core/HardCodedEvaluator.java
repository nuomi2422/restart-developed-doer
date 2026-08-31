package com.dwinovo.numen.rdd.core;

import java.util.Map;

/**
 * 硬编码完成条件的纯 JVM 判定：只回答「给一堆物品计数，condition 满足没有」。
 *
 * <p>condition 形状（与 {@code Subtask.hardCoded} 一致）：
 * <pre>{@code
 * { "asset_key": "minecraft:oak_log", "minimum": 5 }
 * }</pre>
 *
 * <p>判定是纯函数、无副作用、无 IO——真身读背包的工作在宿主侧（{@code RddDetector}），
 * 这一层只做「计数够了没」，好单测。
 */
public final class HardCodedEvaluator {

    private HardCodedEvaluator() {}

    /** 缺省阈值：condition 里没给 minimum 时按 1 计。 */
    public static final int DEFAULT_MINIMUM = 1;

    /**
     * @param condition 二级目标的硬编码条件（{@code asset_key} 必需）
     * @param counts    物品 ID → 数量（宿主从真实环境统计）
     * @return 计数达到/超过 minimum 才算满足；缺 key 或条件畸形返回 {@code false}
     */
    public static boolean matches(Map<String, Object> condition, Map<String, Integer> counts) {
        if (condition == null || counts == null) {
            return false;
        }
        Object assetKey = condition.get("asset_key");
        if (!(assetKey instanceof String key) || key.isBlank()) {
            return false;
        }
        Object minimumObj = condition.get("minimum");
        int minimum = minimumObj instanceof Number n ? n.intValue() : DEFAULT_MINIMUM;
        if (minimum < 0) {
            return false;
        }
        return counts.getOrDefault(key, 0) >= minimum;
    }
}
