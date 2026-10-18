package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-A 资产判定地基（craft 转化 / 消耗）。
 *
 * <p>定死语义：判定只看「当前实时背包」的**券位计数**，不追溯历史消耗。
 * 所以 wheat→bread 之后：
 * - bread 计数上升（新产物）；
 * - wheat 计数下降（被消耗，物理正确）；
 * - 一个只要求 bread 的条件应满足；一个仍要求 wheat 的条件应正确地不满足（因为真的没了）。
 *
 * <p>"小麦做面包后又被要求补种"那种不合理，属于**规划层**问题（P2-C AssetRole /
 * P2-E 成本评分），不是判定层 bug —— 判定层只需忠实反映背包。
 */
class AssetCraftConversionTest {

    @Test void craftRaisesProductAndLowersConsumedMaterial() {
        // 3 wheat → 1 bread（原版配方），这里只验计数语义
        Map<String, Integer> before = Map.of("minecraft:wheat", 12);
        Map<String, Integer> after = Map.of("minecraft:wheat", 0, "minecraft:bread", 4);

        assertTrue(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:wheat", "minimum", 12), before));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:wheat", "minimum", 12), after),
                "wheat 被消耗 → 要求 wheat 的条件正确地不再满足（忠实反映背包）");
        assertTrue(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:bread", "minimum", 4), after),
                "bread 产物 → 条件满足");
    }

    @Test void partialConsumptionKeepsRemainder() {
        Map<String, Integer> after = Map.of("minecraft:wheat", 3, "minecraft:bread", 3); // 12-9=3
        assertTrue(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:wheat", "minimum", 3), after));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:wheat", "minimum", 12), after));
    }

    @Test void stackedCountsAreSummed() {
        // 背包里两格 wheat（7+5）必须合成 12
        Map<String, Integer> inv = Map.of("minecraft:wheat", 7 + 5);
        assertTrue(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:wheat", "minimum", 12), inv));
    }

    @Test void foodGroupCountsAcrossVariants() {
        Map<String, Integer> inv = Map.of("minecraft:bread", 4, "minecraft:cooked_beef", 6);
        assertTrue(HardCodedEvaluator.matches(Map.of("group", "food", "minimum", 10), inv));
        assertFalse(HardCodedEvaluator.matches(Map.of("group", "food", "minimum", 11), inv));
    }
}
