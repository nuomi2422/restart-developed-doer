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
}
