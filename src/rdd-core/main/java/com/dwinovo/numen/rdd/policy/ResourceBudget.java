package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 资源储备预算（P2.1）：按 {@link RiskLevel} 给出“进入该风险活动前的最低储备”。
 *
 * <p>解决“规划 AI 没有长期资源观”：不是拿够当前任务，而是按未来风险预留
 * （探索/战斗/死亡恢复都会消耗）。RiskGate（P2.2）据此判“允许/阻止/缺什么”，
 * 与 {@link AssetPurposeStore}（用途/备用）共用。
 *
 * <p>数值是保守基线数据，可调整；键用真实 minecraft 命名空间 ID（mod 兼容由 unknown 键容忍）。
 */
public final class ResourceBudget {

    private static final Map<RiskLevel, Map<String, Integer>> REQUIRED = Map.of(
            RiskLevel.NORMAL, Map.of(),
            RiskLevel.MINING, Map.of(
                    "minecraft:torch", 16,
                    "minecraft:bread", 4),
            RiskLevel.NETHER, Map.of(
                    "minecraft:diamond_helmet", 2,
                    "minecraft:diamond_chestplate", 2,
                    "minecraft:diamond_leggings", 2,
                    "minecraft:diamond_boots", 2,
                    "minecraft:fire_resistance_potion", 3,
                    "minecraft:torch", 16,
                    "minecraft:cooked_beef", 16),
            RiskLevel.END, Map.of(
                    "minecraft:diamond_chestplate", 1,
                    "minecraft:bow", 1,
                    "minecraft:arrow", 32,
                    "minecraft:ender_pearl", 12,
                    "minecraft:water_bucket", 1,
                    "minecraft:cooked_beef", 16,
                    "minecraft:golden_apple", 2)
    );

    private ResourceBudget() {}

    /** 该风险级别要求的最低储备（不可变）。 */
    public static Map<String, Integer> requiredFor(RiskLevel level) {
        return REQUIRED.getOrDefault(level, Map.of());
    }

    /**
     * 相对该风险级别还缺什么：返回 "key need X have Y" 列表（空 = 达标或该级别无要求）。
     * 数值来自真实可用计数（通常 {@code PlanningAssetSnapshot.availableCounts()}）。
     */
    public static List<String> missingFor(RiskLevel level, Map<String, Integer> available) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> need = requiredFor(level);
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int have = available == null ? 0 : available.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) {
                out.add(e.getKey() + " need " + e.getValue() + " have " + have);
            }
        }
        return out;
    }

    /** 是否达标（无缺口）。 */
    public static boolean satisfied(RiskLevel level, Map<String, Integer> available) {
        return missingFor(level, available).isEmpty();
    }
}
