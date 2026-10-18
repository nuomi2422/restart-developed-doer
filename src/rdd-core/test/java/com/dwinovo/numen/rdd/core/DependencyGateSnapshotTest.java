package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-A 依赖门口径修正（接线回归）。
 *
 * <p>核心：依赖门必须读「实时扫描（PlanningAssetSnapshot）」，而不是「注册表 usableCounts()」——
 * 后者只含 inventory_scan，而背包不落盘 → 恒空 → 背包明明够也不判定（死锁）。
 */
class DependencyGateSnapshotTest {

    private static TaskChain chainWithWait(String assetKey, int minimum) {
        Goal goal = new Goal("g", "test goal", List.of(
                new PrimaryGoal("p0", "stage needs " + assetKey,
                        List.of(Subtask.hardCoded("s0", "get it",
                                Map.of("asset_key", assetKey, "minimum", minimum))),
                        List.of(new AssetRequirement(assetKey, minimum)), false)));
        return new TaskChain(goal);
    }

    /** 验收1：背包有 iron_ingot×10，Registry 为空 → wait_for 通过。 */
    @Test void liveScanAloneSatisfiesItemWaitFor() {
        TaskChain chain = chainWithWait("minecraft:iron_ingot", 10);
        // 实时扫描：有 10 个；注册表：空（背包不落盘）
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(
                Map.of("minecraft:iron_ingot", 10), new AssetRegistry());

        assertTrue(chain.activateCurrentWithSnapshot(snap),
                "实时扫描够 → 依赖门应通过（旧注册表口会恒 false）");
    }

    /** 验收1b：实时扫描不够 → 拒绝（不能瞎放行）。 */
    @Test void liveScanInsufficientStillBlocks() {
        TaskChain chain = chainWithWait("minecraft:iron_ingot", 10);
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(
                Map.of("minecraft:iron_ingot", 3), new AssetRegistry());
        assertFalse(chain.activateCurrentWithSnapshot(snap));
    }

    /** 回归对照：旧注册表口在"背包不落盘"时确实恒 false（证明修复的必要性）。 */
    @Test void registryGateIsBlindToBackpack() {
        TaskChain chain = chainWithWait("minecraft:iron_ingot", 10);
        AssetRegistry empty = new AssetRegistry();
        assertFalse(chain.activateCurrentWithRegistry(empty),
                "注册表没有 inventory_scan → 旧口对物品类 wait_for 恒 false（这就是死锁根因）");
    }

    /** 验收2：只有 world 资产、无物品 → 物品类 wait_for 仍失败（不互相污染）。 */
    @Test void worldAssetDoesNotSatisfyItemWaitFor() {
        TaskChain chain = chainWithWait("minecraft:iron_ingot", 10);
        AssetRegistry r = new AssetRegistry();
        r.apply(new com.dwinovo.numen.rdd.api.Observation("o", "world_village", "t", "w", 1L, Map.of()),
                "world:village", com.dwinovo.numen.rdd.api.AssetScope.GLOBAL, null);
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(Map.of(), r);
        assertFalse(chain.activateCurrentWithSnapshot(snap), "世界资产不得充当背包物品");
    }

    /** 无 wait_for 的阶段：总是可激活（不受依赖门影响）。 */
    @Test void noWaitForActivatesAlways() {
        Goal goal = new Goal("g", "goal", List.of(
                new PrimaryGoal("p0", "plain stage",
                        List.of(Subtask.hardCoded("s0", "x", Map.of("asset_key", "minecraft:dirt", "minimum", 1))),
                        List.of(), false)));
        TaskChain chain = new TaskChain(goal);
        assertTrue(chain.activateCurrentWithSnapshot(PlanningAssetSnapshot.from(Map.of(), new AssetRegistry())));
    }

    /** null 快照：Safety-first，拒绝（不误放行）。 */
    @Test void nullSnapshotBlocks() {
        TaskChain chain = chainWithWait("minecraft:iron_ingot", 1);
        assertFalse(chain.activateCurrentWithSnapshot(null));
    }
}
