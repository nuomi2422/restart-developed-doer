package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssetRegistryTest {
    @Test void keepsObservationHistoryAndUsableProjection() {
        var registry = new AssetRegistry();
        var observation = new Observation("o1", "inventory", "test", "world", 1L, Map.of("count", 3));
        assertTrue(registry.apply(observation, "stone", AssetScope.TASK_BOUND, "s1"));
        assertEquals(1, registry.history().size());
        assertEquals(1, registry.usable().size());
        registry.markUnknown("stone");
        assertTrue(registry.usable().isEmpty());
        registry.apply(observation, "stone", AssetScope.GLOBAL, "s1");
        assertEquals(AssetStatus.OBSERVED, registry.get("stone").orElseThrow().status());
        assertEquals(AssetScope.GLOBAL, registry.get("stone").orElseThrow().scope());
        registry.invalidate("stone");
        assertTrue(registry.history().size() >= 2);
    }

    @Test void currentAssetsRoundTripWithoutPersistingAnUnboundedHistory() {
        var registry = new AssetRegistry();
        for (int i = 0; i < 600; i++) {
            registry.apply(new Observation("o" + i, "world_machine", "test", "world", i,
                    Map.of("count", i)), "machine", AssetScope.GLOBAL, null);
        }

        assertEquals(512, registry.history().size());
        var restored = AssetRegistry.fromJson(registry.toJson());
        var entry = restored.get("machine").orElseThrow();
        assertEquals(599.0, ((Number) entry.observation().value().get("count")).doubleValue());
        assertEquals(AssetStatus.OBSERVED, entry.status());
    }

    @Test void forgetIsIdempotent() {
        var registry = new AssetRegistry();
        registry.apply(new Observation("o", "world_base", "test", "world", 1, Map.of()),
                "base", AssetScope.GLOBAL, null);
        assertTrue(registry.forget("base"));
        assertFalse(registry.forget("base"));
    }

    // Phase 1-3：usableCounts 契约 —— 非整数/负值/NaN/Infinity 一概不算可用，且返回不可变视图。
    @Test void usableCountsRejectsNonIntegerNegativeNaNInfinity() {
        var registry = new AssetRegistry();
        applyInv(registry, "fraction", 1.5);
        applyInv(registry, "negative", -3);
        applyInv(registry, "nan", Double.NaN);
        applyInv(registry, "infinite", Double.POSITIVE_INFINITY);
        applyInv(registry, "good", 4);
        var counts = registry.usableCounts();
        // 只有良好整数算数；其余既不在 count 里、也不该污染
        assertEquals(1, counts.size());
        assertEquals(4, counts.get("good"));
        assertNull(counts.get("fraction"));
        assertNull(counts.get("negative"));
        assertNull(counts.get("nan"));
        assertNull(counts.get("infinite"));
        // 不可变视图：任何写入都必须抛
        assertThrows(UnsupportedOperationException.class, () -> counts.put("hack", 9));
    }

    @Test void usableCountsUnmodifiable() {
        var registry = new AssetRegistry();
        applyInv(registry, "stick", 2);
        assertThrows(UnsupportedOperationException.class, () -> registry.usableCounts().put("x", 1));
    }

    @Test void usableCountsOnlyInventoryScanObserved() {
        var registry = new AssetRegistry();
        registry.apply(new Observation("o", "world_machine", "test", "world", 1, Map.of("count", 5)),
                "machine", AssetScope.GLOBAL, null);
        registry.apply(new Observation("o2", "inventory_scan", "test", "world", 2, Map.of("count", 5)),
                "iron", AssetScope.GLOBAL, null);
        registry.markUnknown("iron");
        assertTrue(registry.usableCounts().isEmpty()); // UNKNOWN 被排除
    }

    private static void applyInv(AssetRegistry registry, String assetId, Number count) {
        registry.apply(new Observation("o-" + assetId, "inventory_scan", "test", "world", System.nanoTime(),
                Map.of("count", count)), assetId, AssetScope.GLOBAL, null);
    }

    // ===== P1：死亡/掉落失效 —— 只失效背包类，不碰世界资产；重新观测可恢复 =====
    @Test void invalidateByTypeMarksOnlyMatchingObservedEntries() {
        var registry = new AssetRegistry();
        applyInv(registry, "iron", 5);                       // inventory_scan OBSERVED
        registry.apply(new Observation("b", "world_base", "test", "world", 1, Map.of()),
                "base", AssetScope.GLOBAL, null);            // world_base OBSERVED（基地不该被死亡抹掉）

        assertEquals(1, registry.invalidateByType("inventory_scan"));
        assertEquals(AssetStatus.INVALID, registry.get("iron").orElseThrow().status());
        assertEquals(AssetStatus.OBSERVED, registry.get("base").orElseThrow().status());
        assertTrue(registry.usableCounts().isEmpty());       // 失效后不再被依赖门算作可用

        assertEquals(0, registry.invalidateByType("inventory_scan")); // 已失效不重复计数

        applyInv(registry, "iron", 2);                       // 重新观测 → 恢复
        assertEquals(AssetStatus.OBSERVED, registry.get("iron").orElseThrow().status());
        assertEquals(2, registry.usableCounts().get("iron"));
    }

    @Test void invalidateByTypeIgnoresBlank() {
        var registry = new AssetRegistry();
        applyInv(registry, "iron", 5);
        assertEquals(0, registry.invalidateByType(null));
        assertEquals(0, registry.invalidateByType("  "));
        assertEquals(AssetStatus.OBSERVED, registry.get("iron").orElseThrow().status());
    }
}
