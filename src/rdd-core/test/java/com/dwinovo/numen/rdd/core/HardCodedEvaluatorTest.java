package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HardCodedEvaluatorTest {
    @Test void combinesFoodButNotIngredientsOrUnsafeFood() {
        var cond = Map.<String, Object>of("group", "food", "minimum", 16);
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:bread", 10, "minecraft:carrot", 6)));
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:bread", 10, "minecraft:wheat", 64,
                "minecraft:hay_block", 64, "minecraft:poisonous_potato", 64)));
    }
    @Test void woodAndBlocksCombineWithoutCountingUnrelatedEquipment() {
        assertTrue(HardCodedEvaluator.matches(Map.of("group", "wood", "minimum", 5),
                Map.of("minecraft:oak_log", 2, "minecraft:birch_planks", 3)));
        assertTrue(HardCodedEvaluator.matches(Map.of("group", "blocks", "minimum", 5),
                Map.of("minecraft:cobblestone", 2, "minecraft:netherrack", 3)));
        assertFalse(HardCodedEvaluator.matches(Map.of("group", "blocks", "minimum", 5),
                Map.of("minecraft:chest", 8, "minecraft:gravel", 64)));
    }
    @Test void rejectsAmbiguousUnknownAndInvalidGroupConditions() {
        for (var cond : java.util.List.of(Map.of("group", "food", "minimum", 0),
                Map.of("group", "food", "minimum", 0.5), Map.of("group", "unknown", "minimum", 1),
                Map.of("group", "food", "asset_key", "minecraft:bread", "minimum", 1)))
            assertFalse(HardCodedEvaluator.matches(new java.util.HashMap<>(cond), Map.of("minecraft:bread", 64)));
        assertEquals(4294967294L, InventoryGroups.count("food",
                Map.of("minecraft:bread", Integer.MAX_VALUE, "minecraft:carrot", Integer.MAX_VALUE)));
    }
    @Test void matchesWhenCountAtLeastMinimum() {
        Map<String, Object> cond = Map.<String, Object>of("asset_key", "minecraft:oak_log", "minimum", 5);
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:oak_log", 4)));
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:oak_log", 5)));
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:oak_log", 12)));
    }

    @Test void defaultsMinimumToOne() {
        Map<String, Object> cond = Map.<String, Object>of("asset_key", "minecraft:stone");
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:stone", 1)));
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:stone", 3)));
        assertFalse(HardCodedEvaluator.matches(cond, Map.of()));
    }

    @Test void rejectsMissingOrBlankKey() {
        assertFalse(HardCodedEvaluator.matches(Map.of("minimum", 1), Map.of("x", 2)));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "", "minimum", 1), Map.of()));
        assertFalse(HardCodedEvaluator.matches(null, Map.of("x", 1)));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "a", "minimum", 1), null));
    }

    @Test void rejectsNegativeMinimum() {
        Map<String, Object> cond = Map.<String, Object>of("asset_key", "a", "minimum", -1);
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("a", 100)));
    }

    @Test void bareNameFallsBackToVariantFamily() {
        // 2026-10-07 实机：模型写 minecraft:bed，背包里却是 minecraft:white_bed → 二级卡死 STALLED。
        // 裸名兜底：minecraft:bed 按任意 *_bed 加总。
        assertTrue(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:bed", "minimum", 1),
                Map.of("minecraft:white_bed", 1)));
        assertTrue(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:wool", "minimum", 3),
                Map.of("minecraft:white_wool", 2, "minecraft:red_wool", 1)));
        // 精确键在时仍走精确：同一根键不会把不相关族算进来
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:bed", "minimum", 1),
                Map.of("minecraft:white_wool", 5)));
        // 带下划线的具体键 / 非 minecraft 命名空间 / 占位裸键 不兜底
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "minecraft:oak_log", "minimum", 1),
                Map.of("minecraft:birch_log", 1)));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "goal", "minimum", 1), Map.of("x", 1)));
    }
}
