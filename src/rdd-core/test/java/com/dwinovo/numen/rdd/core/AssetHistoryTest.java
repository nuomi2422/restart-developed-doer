package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P2.1 资产历史/恢复语义：Lost ≠ Gone。 */
class AssetHistoryTest {

    @Test void lostKeepsLocationAndSurfacesAsRecoverable() {
        AssetHistory h = new AssetHistory();
        h.recordLost("minecraft:diamond_chestplate", AssetHistory.Purpose.BACKUP_EQUIPMENT, 2,
                "minecraft:overworld", -51, 65, -496, 1000L);

        var e = h.get("minecraft:diamond_chestplate");
        assertEquals(AssetHistory.State.LOST, e.state(), "死亡记 LOST 而非删除");
        assertTrue(e.hasLocation(), "保留最后已知位置 → 可恢复");
        List<AssetHistory.Entry> rec = h.recoverable();
        assertEquals(1, rec.size());
        assertEquals("minecraft:diamond_chestplate", rec.get(0).assetId());
        assertTrue(rec.get(0).render().contains("minecraft:overworld(-51,65,-496)"));
    }

    @Test void currentOverwritesLostWhenReseen() {
        AssetHistory h = new AssetHistory();
        h.recordLost("minecraft:iron_pickaxe", AssetHistory.Purpose.TOOL, 1,
                "minecraft:overworld", 1, 2, 3, 500L);
        h.recordCurrent("minecraft:iron_pickaxe", AssetHistory.Purpose.TOOL, 1,
                "minecraft:overworld", 1, 2, 3, 900L);
        assertEquals(AssetHistory.State.CURRENT, h.get("minecraft:iron_pickaxe").state());
        assertTrue(h.recoverable().isEmpty(), "已拿回 → 不再算可恢复");
    }

    @Test void destroyedIsNotRecoverable() {
        AssetHistory h = new AssetHistory();
        h.recordLost("minecraft:shield", AssetHistory.Purpose.BACKUP_EQUIPMENT, 1,
                "minecraft:overworld", 5, 6, 7, 100L);
        h.recordDestroyed("minecraft:shield", null, 200L);
        assertEquals(AssetHistory.State.DESTROYED, h.get("minecraft:shield").state());
        assertTrue(h.recoverable().isEmpty(), "明确没了 → 不再当恢复线索");
    }

    @Test void recoverableSortedMostRecentFirst() {
        AssetHistory h = new AssetHistory();
        h.recordLost("minecraft:iron_helmet", AssetHistory.Purpose.BACKUP_EQUIPMENT, 1, "w", 1, 1, 1, 100L);
        h.recordLost("minecraft:diamond_boots", AssetHistory.Purpose.BACKUP_EQUIPMENT, 1, "w", 2, 2, 2, 900L);
        List<AssetHistory.Entry> rec = h.recoverable();
        assertEquals("minecraft:diamond_boots", rec.get(0).assetId(), "最近的先");
    }

    @Test void worthRememberingFiltersJunk() {
        assertTrue(AssetHistory.worthRemembering("minecraft:diamond_chestplate", AssetHistory.Purpose.UNKNOWN));
        assertTrue(AssetHistory.worthRemembering("minecraft:iron_pickaxe", AssetHistory.Purpose.UNKNOWN));
        assertTrue(AssetHistory.worthRemembering("minecraft:bread", AssetHistory.Purpose.UNKNOWN));
        assertFalse(AssetHistory.worthRemembering("minecraft:dirt", AssetHistory.Purpose.UNKNOWN));
        assertFalse(AssetHistory.worthRemembering("minecraft:cobblestone", AssetHistory.Purpose.UNKNOWN));
        assertTrue(AssetHistory.worthRemembering("minecraft:dirt", AssetHistory.Purpose.RECOVERY_POINT),
                "显式用途覆盖杂物过滤");
    }

    @Test void nullAndBlankAreIgnored() {
        AssetHistory h = new AssetHistory();
        h.recordLost(null, AssetHistory.Purpose.TOOL, 1, "w", 0, 0, 0, 1L);
        h.recordLost("  ", AssetHistory.Purpose.TOOL, 1, "w", 0, 0, 0, 1L);
        h.record(null);
        assertEquals(0, h.size());
    }

    @Test void jsonRoundTripPreservesEntries() {
        AssetHistory h = new AssetHistory();
        h.recordLost("minecraft:diamond_chestplate", AssetHistory.Purpose.BACKUP_EQUIPMENT, 2,
                "minecraft:overworld", -51, 65, -496, 1234L);
        h.recordCurrent("minecraft:iron_pickaxe", AssetHistory.Purpose.TOOL, 1,
                "minecraft:overworld", 1, 2, 3, 5678L);

        AssetHistory back = AssetHistory.fromJson(h.toJson());
        assertEquals(2, back.size());
        var e = back.get("minecraft:diamond_chestplate");
        assertEquals(AssetHistory.Purpose.BACKUP_EQUIPMENT, e.purpose());
        assertEquals(AssetHistory.State.LOST, e.state());
        assertEquals(2, e.lastCount());
        assertEquals(-51, e.x());
        assertEquals(1234L, e.lastSeenMillis());
        assertEquals(1, back.recoverable().size());
    }

    @Test void badJsonFallsBackToEmpty() {
        assertEquals(0, AssetHistory.fromJson("{not json").size());
        assertEquals(0, AssetHistory.fromJson("[{\"assetId\":\"\"}]").size());
        assertEquals(0, AssetHistory.fromJson(null).size());
    }
}
