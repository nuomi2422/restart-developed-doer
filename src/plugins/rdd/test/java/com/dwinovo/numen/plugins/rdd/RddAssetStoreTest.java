package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RddAssetStoreTest {
    @TempDir Path directory;

    @Test void persistsReusableWorldAssetsButNotEphemeralInventory() throws Exception {
        UUID companion = UUID.randomUUID();
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_base", Map.of("kind", "base", "label", "home",
                        "dimension", "minecraft:overworld", "x", 1, "y", 64, "z", 2,
                        "refresh_policy", "LAZY")),
                "base|overworld|1", AssetScope.GLOBAL, null);
        registry.apply(observation("inventory_scan", Map.of("count", 64)),
                "minecraft:cobblestone", AssetScope.GLOBAL, "s1");

        RddAssetStore.save(directory, companion, registry);
        AssetRegistry restored = RddAssetStore.load(directory, companion);

        assertTrue(restored.get("base|overworld|1").isPresent());
        assertTrue(restored.get("minecraft:cobblestone").isEmpty());
    }

    @Test void contextIsBoundedAndExplainsLazyEvidence() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_machine", Map.of("kind", "machine", "label", "minecraft:furnace",
                        "dimension", "minecraft:overworld", "x", 3, "y", 65, "z", 4,
                        "refresh_policy", "LAZY", "summary", "coal=8")),
                "machine", AssetScope.GLOBAL, null);

        String context = RddAssetContext.render(registry, 600);
        assertTrue(context.contains("<known_world_assets>"));
        assertTrue(context.contains("minecraft:furnace"));
        assertTrue(context.contains("re-check"));
        assertTrue(context.length() <= 600);
    }

    private static Observation observation(String type, Map<String, Object> value) {
        return new Observation("obs-" + type, type, "test", "minecraft:overworld", 1L, value);
    }
}
