package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** P2.1 资产用途/储备映射：事实与用途分离，可 JSON 往返。 */
class AssetPurposeStoreTest {

    @Test void reserveAndQueryDeathRecoveryRole() {
        AssetPurposeStore store = new AssetPurposeStore();
        store.setReserved("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY, 2);
        assertEquals(2, store.reservedOf("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY));
        assertEquals(0, store.reservedOf("minecraft:diamond_chestplate", AssetRole.COMBAT));
        assertFalse(store.hasReserved("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY, 1));
        assertTrue(store.hasReserved("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY, 2));
        assertTrue(store.hasReserved("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY, 5));
    }

    @Test void jsonRoundTrip() {
        AssetPurposeStore store = new AssetPurposeStore();
        store.setReserved("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY, 2);
        store.setReserved("minecraft:bread", AssetRole.FOOD_RESERVE, 16);
        AssetPurposeStore back = AssetPurposeStore.fromJson(store.toJson());
        assertEquals(2, back.reservedOf("minecraft:diamond_chestplate", AssetRole.DEATH_RECOVERY));
        assertEquals(16, back.reservedOf("minecraft:bread", AssetRole.FOOD_RESERVE));
    }

    @Test void defaultsAreZeroAndNegativeClamps() {
        AssetPurposeStore store = new AssetPurposeStore();
        assertEquals(0, store.reservedOf("minecraft:x", AssetRole.UTILITY));
        store.setReserved("minecraft:x", AssetRole.UTILITY, -5);
        assertEquals(0, store.reservedOf("minecraft:x", AssetRole.UTILITY));
    }
}
