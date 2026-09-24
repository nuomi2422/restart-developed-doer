package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1.5 唯一规划资产口径合成测试：缓存为底、注册表真相覆盖、失效即移除。 */
class PlanningAssetSnapshotTest {

    private static void inv(AssetRegistry r, String id, Number count) {
        r.apply(new Observation("o-" + id, "inventory_scan", "test", "world", 1L, Map.of("count", count)),
                id, AssetScope.GLOBAL, null);
    }

    @Test void registryTruthOverridesCachedInventory() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:iron_ingot", 2);          // 真实只剩 2
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:iron_ingot", 7), r);
        assertEquals(2, snap.availableCounts().get("minecraft:iron_ingot")); // 注册表覆盖缓存
    }

    @Test void invalidAssetsAreRemovedAndReportedLost() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:diamond_pickaxe", 1);
        r.invalidateByType("inventory_scan");        // 死亡失效
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:diamond_pickaxe", 1), r);
        assertNull(snap.availableCounts().get("minecraft:diamond_pickaxe")); // 不再算持有
        assertEquals(1, snap.lostIds().size());
        assertEquals("minecraft:diamond_pickaxe", snap.lostIds().get(0));
    }

    @Test void unknownAssetsAreRemovedAndReported() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:bread", 3);
        r.markUnknown("minecraft:bread");
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 3), r);
        assertFalse(snap.availableCounts().containsKey("minecraft:bread"));
        assertEquals(1, snap.unknownIds().size());
    }

    @Test void emptyRegistryFallsBackToCached() {
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:oak_log", 4), new AssetRegistry());
        assertEquals(4, snap.availableCounts().get("minecraft:oak_log"));
        assertTrue(snap.lostIds().isEmpty());
    }

    @Test void worldAssetsDoNotEnterInventoryCounts() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new Observation("b", "world_base", "test", "world", 1L, Map.of()),
                "world:base", AssetScope.GLOBAL, null);
        var snap = PlanningAssetSnapshot.from(Map.of(), r);
        assertTrue(snap.availableCounts().isEmpty());
    }

    @Test void nonIntegerObservedCountDoesNotCount() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:dirt", 1.5);
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:dirt", 5), r);
        assertNull(snap.availableCounts().get("minecraft:dirt"));
    }
}
