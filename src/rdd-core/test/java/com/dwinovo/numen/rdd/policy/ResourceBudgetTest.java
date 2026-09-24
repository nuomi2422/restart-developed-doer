package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** P2.1 资源储备预算：按风险级别算缺口。 */
class ResourceBudgetTest {

    @Test void normalHasNoRequirement() {
        assertTrue(ResourceBudget.satisfied(RiskLevel.NORMAL, Map.of()));
        assertTrue(ResourceBudget.requiredFor(RiskLevel.NORMAL).isEmpty());
    }

    @Test void netherRequiresTwoSetsDiamondArmorAndPotions() {
        var need = ResourceBudget.requiredFor(RiskLevel.NETHER);
        assertEquals(2, need.get("minecraft:diamond_chestplate"));
        assertEquals(3, need.get("minecraft:fire_resistance_potion"));
        // 只有一套甲 + 没药水 → 报缺口
        var missing = ResourceBudget.missingFor(RiskLevel.NETHER,
                Map.of("minecraft:diamond_chestplate", 1, "minecraft:torch", 16));
        assertTrue(missing.stream().anyMatch(s -> s.contains("diamond_chestplate") && s.contains("need 2")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("fire_resistance_potion")));
        assertFalse(ResourceBudget.satisfied(RiskLevel.NETHER, Map.of("minecraft:diamond_chestplate", 1)));
    }

    @Test void satisfiedWhenAllPresent() {
        var available = Map.of(
                "minecraft:diamond_helmet", 2, "minecraft:diamond_chestplate", 2,
                "minecraft:diamond_leggings", 2, "minecraft:diamond_boots", 2,
                "minecraft:fire_resistance_potion", 3, "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
        assertTrue(ResourceBudget.satisfied(RiskLevel.NETHER, available));
    }
}
