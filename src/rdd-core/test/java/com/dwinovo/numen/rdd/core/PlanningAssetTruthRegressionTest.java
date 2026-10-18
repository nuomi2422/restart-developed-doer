package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-A 资产判定地基（回归/集成）。
 *
 * <p>决策（2026-09-25 人工批准）：<b>背包资产不持久化</b>；规划真相 = 最近一次实时扫描。
 * 注册表只作 world_ 事实源；其 inventory_scan 条目最多是"提示"，绝不能把实时扫描到的持有
 * 误判成"没有"（否则依赖门/重规划会把已有装备的人重新规划回铁器时代）。
 *
 * <p>这一组专堵"跨组件交互 bug"：单测各自过测不出，必须组合 AssetRegistry + PlanningAssetSnapshot。
 */
class PlanningAssetTruthRegressionTest {

    private static void inv(AssetRegistry r, String id, Number count) {
        r.apply(new Observation("o-" + id, "inventory_scan", "test", "world", 1L, Map.of("count", count)),
                id, AssetScope.GLOBAL, null);
    }

    /** 集成回归：死亡失效后重新规划 → 快照仍应反映真实持有资产（核心，防跨组件交互复发）。 */
    @Test void afterDeathInvalidationPlanningStillSeesRealHeldAssets() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:diamond_chestplate", 1);
        inv(r, "minecraft:iron_sword", 1);
        r.invalidateByType("inventory_scan");          // 死亡：注册表全标 INVALID

        // 复活后：实时扫描（truth）= 背包里真的又有铁套（或从未丢）
        Map<String, Integer> liveScan = Map.of(
                "minecraft:diamond_chestplate", 1,
                "minecraft:iron_sword", 1,
                "minecraft:wheat", 20);

        var snap = PlanningAssetSnapshot.from(liveScan, r);
        assertEquals(1, snap.availableCounts().get("minecraft:diamond_chestplate"),
                "死亡失效后重规划不得把真实持有的装备当没有");
        assertEquals(1, snap.availableCounts().get("minecraft:iron_sword"));
        assertEquals(20, snap.availableCounts().get("minecraft:wheat"));
    }

    /** 注册表里没有 inventory_scan 条目（现实：不落盘）时，也必须以实时扫描为真相。 */
    @Test void registryWithoutInventoryEntriesStillUsesLiveScan() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new Observation("v", "world_village", "test", "world", 1L, Map.of()),
                "world:village", AssetScope.GLOBAL, null);   // 只有 world 资产
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:wheat", 12), r);
        assertEquals(12, snap.availableCounts().get("minecraft:wheat"), "缺 inventory_scan 时用实时扫描");
    }

    /** world 资产不得混进背包持有计数。 */
    @Test void worldAssetsNeverPolluteInventoryCounts() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new Observation("v", "world_village", "test", "world", 1L, Map.of("villagers", 1)),
                "world:village", AssetScope.GLOBAL, null);
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 4), r);
        assertEquals(4, snap.availableCounts().get("minecraft:bread"));
        assertFalse(snap.availableCounts().containsKey("world:village"));
    }

    /**
     * 死亡 invalidate hook 的验收断言（埋点工单）：DEATH → 旧背包声明全部失效 → 下一次
     * PlanningSnapshot 里<b>已丢失</b>的旧声明绝不能再出现。区别于
     * {@code afterDeathInvalidationPlanningStillSeesRealHeldAssets}：那条测"仍在身上"的不能丢，
     * 这条测"死掉没捡回"的必须消失。
     */
    @Test void afterDeathLostClaimsNeverAppearInNextPlan() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:diamond_chestplate", 1);
        inv(r, "minecraft:iron_sword", 1);
        r.invalidateByType("inventory_scan");          // 死亡：注册表全标 INVALID（含没捡回的那件）

        // 复活后实时扫描：铁剑捡回了，钻石胸甲没捡回（真实世界已失）
        Map<String, Integer> liveScan = Map.of("minecraft:iron_sword", 1, "minecraft:wheat", 20);

        var snap = PlanningAssetSnapshot.from(liveScan, 9000L, r);
        assertTrue(snap.availableCounts().containsKey("minecraft:iron_sword"), "真的持有的要保留");
        assertFalse(snap.availableCounts().containsKey("minecraft:diamond_chestplate"),
                "死亡丢失且未重新观测的旧声明绝不能再进下一次规划快照");
        assertTrue(snap.lostIds().contains("minecraft:diamond_chestplate"), "丢失项只许作为 lost 提示");
    }
}
