package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-A 验收 1-4（数据链闭环）：统一步骤资产感知，杜绝「规划/判定读空快照」。
 *
 * <p>核心不变式（人工批准 A+B+C）：
 * <ul>
 *   <li>实时扫描 = 持有真相；快照必须忠实反映它（验收1/2）。</li>
 *   <li>死亡失效不得残留旧背包（验收3）。</li>
 *   <li>重启后背包靠重新扫描，不依赖旧缓存；世界资产保留（验收4）。</li>
 * </ul>
 */
class AssetSnapshotDataChainTest {

    private static void inv(AssetRegistry r, String id, Number count) {
        r.apply(new com.dwinovo.numen.rdd.api.Observation("o-" + id, "inventory_scan", "t", "w", 1L,
                        Map.of("count", count)),
                id, com.dwinovo.numen.rdd.api.AssetScope.GLOBAL, null);
    }

    /** 验收1：无 Runtime 时 /goal —— 实时扫描到的铁套/钻石镐/黑曜石，快照必须看得到。 */
    @Test void goalSnapshotSeesLiveGearWithoutRuntime() {
        Map<String, Integer> live = Map.of(
                "minecraft:iron_chestplate", 1,
                "minecraft:diamond_pickaxe", 1,
                "minecraft:obsidian", 10);
        // 模拟"无链、注册表空"的 /goal 时刻：唯一输入是实时扫描
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(live, new AssetRegistry());
        assertEquals(1, snap.availableCounts().get("minecraft:iron_chestplate"));
        assertEquals(1, snap.availableCounts().get("minecraft:diamond_pickaxe"));
        assertEquals(10, snap.availableCounts().get("minecraft:obsidian"));
        assertTrue(snap.claims().containsKey("minecraft:iron_chestplate"), "应带 live_scan 声明");
    }

    /** 验收2：运行中物品变化（消耗食物）→ 快照随之变化（第二次扫描覆盖第一次）。 */
    @Test void snapshotTracksInventoryChange() {
        AssetRegistry r = new AssetRegistry();
        PlanningAssetSnapshot before = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 4), r);
        PlanningAssetSnapshot after = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 1), r);
        assertEquals(4, before.availableCounts().get("minecraft:bread"));
        assertEquals(1, after.availableCounts().get("minecraft:bread"), "消耗后快照同步下降");
    }

    /** 验收3：死亡失效后重新扫描 → 快照反映新背包，旧背包不残留（除非真捡回）。 */
    @Test void deathInvalidationDoesNotLeakOldBackpack() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:diamond_chestplate", 1);
        r.invalidateByType("inventory_scan");               // 死亡失效
        // 复活后只捡回铁套，没捡回钻石甲 → 实时扫描只有铁套
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(
                Map.of("minecraft:iron_chestplate", 1), r);
        assertEquals(1, snap.availableCounts().get("minecraft:iron_chestplate"));
        assertFalse(snap.availableCounts().containsKey("minecraft:diamond_chestplate"),
                "未捡回的钻石甲不得出现在下次快照");
        assertTrue(snap.lostIds().contains("minecraft:diamond_chestplate"), "但如实记 lost 线索");
    }

    /** 验收4：重启后背包不依赖旧缓存——空缓存 + 有世界资产 → 背包空、世界资产不受影响。 */
    @Test void restartReliesOnFreshScanNotStaleCache() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new com.dwinovo.numen.rdd.api.Observation("v", "world_base", "t", "w", 1L, Map.of()),
                "world:base", com.dwinovo.numen.rdd.api.AssetScope.GLOBAL, null);
        // 重启后缓存尚未填充
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(Map.of(), r);
        assertTrue(snap.availableCounts().isEmpty(), "背包靠重新扫描，不依赖旧缓存");
        assertFalse(PlanningAssetSnapshot.worldClaims(r).isEmpty(), "世界资产保留");
    }
}
