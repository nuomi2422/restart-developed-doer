package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2-C 资产派生/等价：小麦→面包不再被当净损失（战略判断用，不用于硬门）。 */
class AssetDerivationTest {

    @Test void breadCountsWheatEquivalents() {
        // 12 小麦（可做 4 面包）→ 战略上"还能有 4 面包"？
        int eq = AssetDerivation.equivalentCount("minecraft:bread", Map.of("minecraft:wheat", 12));
        assertEquals(12, eq, "1 小麦 ≈ 1 面包（战略等价表）；关键是不为 0、不再重复耕作");
    }

    @Test void directHoldingPlusDerived() {
        Map<String, Integer> inv = Map.of("minecraft:bread", 2, "minecraft:wheat", 6);
        assertEquals(2 + 6, AssetDerivation.equivalentCount("minecraft:bread", inv));
    }

    @Test void logToPlanksEquivalence() {
        int eq = AssetDerivation.equivalentCount("minecraft:oak_planks", Map.of("minecraft:oak_log", 3));
        assertEquals(12, eq, "1 原木 = 4 木板；3 原木 = 12 木板战略等价");
    }

    @Test void unknownTargetReturnsDirectOnly() {
        assertEquals(5, AssetDerivation.equivalentCount("minecraft:diamond", Map.of("minecraft:diamond", 5)));
        assertEquals(0, AssetDerivation.equivalentCount("minecraft:diamond", Map.of("minecraft:wheat", 10)));
    }

    @Test void rulesAreKnown() {
        assertTrue(AssetDerivation.known("minecraft:wheat"));
        assertTrue(AssetDerivation.known("minecraft:oak_log"));
        assertFalse(AssetDerivation.known("minecraft:cobblestone"));
        assertTrue(AssetDerivation.ruleCount() >= 3);
    }

    @Test void nullSafety() {
        assertEquals(0, AssetDerivation.equivalentCount(null, Map.of("minecraft:wheat", 1)));
        assertEquals(0, AssetDerivation.equivalentCount("minecraft:bread", null));
        assertTrue(AssetDerivation.rulesFor(null).isEmpty());
    }
}
