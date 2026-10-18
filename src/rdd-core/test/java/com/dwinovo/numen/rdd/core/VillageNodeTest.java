package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2-D 村庄节点：先事实，不求策略。 */
class VillageNodeTest {

    private static void village(AssetRegistry r, String id, String dim, int x, int y, int z,
                                int villagers, int golems, int chests, int crops, int bookshelves,
                                String state) {
        r.apply(new Observation("o-" + id, "world_village", "test", dim, 1L, Map.of(
                        "dimension", dim, "x", x, "y", y, "z", z, "state", state,
                        "resources", Map.of("villagers", villagers, "iron_golem", golems,
                                "chests", chests, "crops", crops, "bookshelves", bookshelves))),
                id, AssetScope.GLOBAL, null);
    }

    @Test void extractsVillageFacts() {
        AssetRegistry r = new AssetRegistry();
        village(r, "v1", "minecraft:overworld", -148, 73, -403, 1, 0, 1, 24, 0, "SCANNED");
        var nodes = VillageNode.extract(r);
        assertEquals(1, nodes.size());
        var n = nodes.get(0);
        assertEquals(-148, n.x());
        assertEquals(1, n.villagers());
        assertEquals(24, n.crops());
        assertEquals(1, n.chests());
        assertTrue(n.hasUsableResources());
        assertTrue(n.render().contains("village @"));
        assertTrue(n.render().contains("villagers=1"));
    }

    @Test void nonVillageAssetsIgnored() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new Observation("b", "world_base", "test", "w", 1L, Map.of()), "world:base", AssetScope.GLOBAL, null);
        assertTrue(VillageNode.extract(r).isEmpty());
    }

    @Test void searchedFlagFromState() {
        AssetRegistry r = new AssetRegistry();
        village(r, "v1", "w", 1, 1, 1, 2, 0, 3, 0, 0, "SEARCHED");
        assertTrue(VillageNode.extract(r).get(0).searched());
        assertTrue(VillageNode.extract(r).get(0).render().contains("[SEARCHED]"));
    }

    @Test void unsearchedWithResourcesRanksFirst() {
        AssetRegistry r = new AssetRegistry();
        village(r, "v_empty", "w", 1, 1, 1, 0, 0, 0, 0, 0, "SCANNED");       // 无资源
        village(r, "v_rich", "w", 2, 2, 2, 3, 1, 5, 10, 2, "SCANNED");       // 有资源、未搜
        village(r, "v_done", "w", 3, 3, 3, 3, 1, 5, 10, 2, "SEARCHED");      // 有资源、已搜
        var nodes = VillageNode.extract(r);
        assertEquals("v_rich", nodes.get(0).assetId(), "未搜过且有资源的最优先");
    }

    @Test void renderEmptySafe() {
        assertEquals("", VillageNode.render(null));
        assertEquals("", VillageNode.render(java.util.List.of()));
        AssetRegistry r = new AssetRegistry();
        village(r, "v1", "w", 0, 0, 0, 1, 0, 1, 0, 0, "SCANNED");
        assertTrue(VillageNode.render(VillageNode.extract(r)).contains("<known_villages>"));
    }

    @Test void missingResourcesMapIsSafe() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new Observation("o", "world_village", "test", "w", 1L, Map.of("dimension", "w")),
                "v1", AssetScope.GLOBAL, null);
        var n = VillageNode.extract(r).get(0);
        assertEquals(0, n.villagers());
        assertFalse(n.hasUsableResources());
    }
}
