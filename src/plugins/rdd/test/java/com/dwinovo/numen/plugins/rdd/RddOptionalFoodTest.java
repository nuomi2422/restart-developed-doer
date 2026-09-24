package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 新策略：任意食物子步（含 cooked_porkchop/bread）默认可跳过；非食物与显式 required 一律拒绝。 */
class RddOptionalFoodTest {
    @Test void anyFoodIsSkippableUnlessExplicitlyRequired() {
        var carrot = Subtask.hardCoded("s", "get carrots", Map.of("asset_key", "minecraft:carrot"));
        var porkchop = Subtask.hardCoded("p", "cook porkchop", Map.of("asset_key", "minecraft:cooked_porkchop"));
        var bread = Subtask.hardCoded("b", "bread", Map.of("asset_key", "minecraft:bread"));
        var foodGroup = Subtask.hardCoded("g", "food supply", Map.of("group", "food", "minimum", 4));

        // 食物（含旧策略不让跳的烹饪肉/面包）都可跳——这正是解死锁的关键
        assertTrue(RddOptionalFood.canSkip(carrot, Map.of()));
        assertTrue(RddOptionalFood.canSkip(porkchop, Map.of()));
        assertTrue(RddOptionalFood.canSkip(bread, Map.of()));
        assertTrue(RddOptionalFood.canSkip(foodGroup, Map.of()));

        // 非食物不可跳：小麦是原料、工具/装备是进度类
        assertFalse(RddOptionalFood.canSkip(Subtask.hardCoded("w", "wheat", Map.of("asset_key", "minecraft:wheat")), Map.of()));
        assertFalse(RddOptionalFood.canSkip(Subtask.hardCoded("e", "pick", Map.of("asset_key", "minecraft:stone_pickaxe")), Map.of()));

        // 显式 required 否决
        assertFalse(RddOptionalFood.canSkip(
                Subtask.hardCoded("r", "required carrot", Map.of("asset_key", "minecraft:carrot", "optional", false)),
                Map.of("minecraft:bread", 32)));

        assertFalse(RddOptionalFood.canSkip(null, Map.of()));
    }
}
