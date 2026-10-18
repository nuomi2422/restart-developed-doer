package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求 ↔ 持有资产 三方对账的离线检查（第三批 N2 第二步）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>★ {@code UNKNOWN_HELD} 与 {@code BELOW_MINIMUM} 必须分开 ——
 *       「登记表里没这个键」= 不知道，「持有量不足」= 有明确缺口。混起来会让
 *       没扫过背包的世界报出一堆假缺口；</li>
 *   <li>资产键与阶段键<b>不做 join</b>（语义不同），只并列展示并说明；</li>
 *   <li>只读：不改事实、不改资产登记。</li>
 * </ol>
 */
class RequirementAssetAuditTest {

    private static Goal goal(String id, String description) {
        return new Goal(id, description,
                List.of(new PrimaryGoal("p", "primary",
                        List.of(new Subtask("s1", "do", DetectionMode.HARD_CODED,
                                Map.of("k", "v"), 5L, 3, false, null)),
                        List.of(), false)));
    }

    private static RequirementManifest.Manifest manifest(String goalId, String... keys) {
        List<RequirementManifest.Requirement> reqs = new java.util.ArrayList<>();
        for (String k : keys) {
            reqs.add(new RequirementManifest.Requirement(k, 1, List.of()));
        }
        return new RequirementManifest.Manifest(goalId, reqs);
    }

    /**
     * 造一个持有若干资产的登记。
     *
     * <p>★ 必须照 {@code AssetRegistry.usableCounts()} 的真实口径：
     * <ul>
     *   <li>键是 <b>{@code assetId}</b>（不是 observation 的 type）；</li>
     *   <li>只认 {@code type == "inventory_scan"} 的观测；</li>
     *   <li>数量取自 {@code value.count}，且必须是非负整数。</li>
     * </ul>
     * 照着真实口径写夹具，而不是「随便造一个看起来像的」——
     * 后者会造出「测试全绿、实测一条都对不上」的局面。
     */
    private static AssetRegistry registryWith(String... assetIds) {
        AssetRegistry reg = new AssetRegistry();
        for (int i = 0; i < assetIds.length; i++) {
            reg.apply(new Observation("obs-" + i, "inventory_scan", "test", "env-1", 1000L,
                            Map.of("count", 3)),
                    assetIds[i], AssetScope.TASK_BOUND, "node-1");
        }
        return reg;
    }

    @Test
    void heldAboveMinimum_isMeetsMinimum() {
        var r = RequirementAssetAudit.audit(manifest("goal-1", "oak_log"),
                null, registryWith("oak_log"), null);
        assertEquals(1, r.items().size());
        assertEquals(RequirementAssetAudit.Verdict.MEETS_MINIMUM, r.items().get(0).verdict());
        assertEquals(0, r.items().get(0).gap());
    }

    @Test
    void missingKeyInRegistry_isUnknownHeld_notGap() {
        // ★ 核心：登记表里没有这个键 = 不知道，不是「一个都没有」
        var r = RequirementAssetAudit.audit(manifest("goal-1", "diamond"),
                null, registryWith("oak_log"), null);
        assertEquals(RequirementAssetAudit.Verdict.UNKNOWN_HELD, r.items().get(0).verdict());
        assertNull(r.items().get(0).held(), "★ 不知道持有量时 held 必须是 null，不能写 0");
        assertEquals(0, r.items().get(0).gap(), "不知道就没有缺口数字");
    }

    @Test
    void emptyRegistry_isUnknownHeldAndSaysSo() {
        var r = RequirementAssetAudit.audit(manifest("goal-1", "oak_log"), null, new AssetRegistry(), null);
        assertFalse(r.knowsHeld(), "空登记 = 不知道持有量");
        assertEquals(RequirementAssetAudit.Verdict.UNKNOWN_HELD, r.items().get(0).verdict());
        assertTrue(String.valueOf(r.notes()).contains("REGISTRY_EMPTY"),
                "★ 要说清是「登记表空」而不是「什么都没有」: " + r.notes());
    }

    @Test
    void nullRegistry_isUnknownHeldForEverything() {
        var r = RequirementAssetAudit.audit(manifest("goal-1", "oak_log"), null, null, null);
        assertEquals(RequirementAssetAudit.Verdict.UNKNOWN_HELD, r.items().get(0).verdict());
        assertTrue(String.valueOf(r.notes()).contains("NO_ASSET_REGISTRY"));
    }

    @Test
    void factColumnIsNotJoined_butStated() {
        // ★ 资产键 "oak_log" 与事实键 normalize("oak_log")="oaklog" **并不相等**
        //   —— 两者语义就不同（要什么物品 vs 做了什么动作）。
        //   所以即便字面看着像，也不该 join 成功。
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "oak_log", 1000L, "ev");
        var r = RequirementAssetAudit.audit(manifest("goal-1", "oak_log"), f, registryWith("oak_log"), null);
        var item = r.items().get(0);
        assertEquals("NOT_COMPARED", item.factMatchedBy(),
                "★ 资产键与阶段键口径不同，不该因为字面像就当成同一件事");
        assertTrue(String.valueOf(r.notes()).contains("不做 join"),
                "要明说没做 join，读者才不会把两列当成因果: " + r.notes());
    }

    @Test
    void identicalKeyString_isReportedAsExactButStillFlagged() {
        // 键字面完全相同时如实标 EXACT，但仍保留「不做 join」的说明
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "minelog", 1000L, "ev");
        var r = RequirementAssetAudit.audit(manifest("goal-1", "minelog"), f, registryWith("minelog"), null);
        assertEquals("EXACT", r.items().get(0).factMatchedBy());
        assertEquals(RequirementAssetAudit.Verdict.MEETS_MINIMUM,
                r.items().get(0).verdict(), "持有判定只看资产登记，不受事实列影响");
    }

    @Test
    void factsOfOtherGoal_areExcluded() {
        Goal g2 = goal("goal-2", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g2, "oak_log", 1000L, "ev");
        var r = RequirementAssetAudit.audit(manifest("goal-1", "oak_log"), f, registryWith("oak_log"), null);
        assertEquals("NOT_COMPARED", r.items().get(0).factMatchedBy(),
                "别的 goal 的事实不该算作这条需求的覆盖");
    }

    @Test
    void noManifest_isReported() {
        var r = RequirementAssetAudit.audit(null, null, registryWith("oak_log"), null);
        assertEquals(0, r.requirementCount());
        assertTrue(String.valueOf(r.notes()).contains("HAS_NO_MANIFEST"));
    }

    @Test
    void auditDoesNotMutateEitherSide() {
        Goal g = goal("goal-1", "mine");
        CompletedFactStore f = new CompletedFactStore();
        f.recordStage(g, "oak_log", 1000L, "ev");
        AssetRegistry reg = registryWith("oak_log");
        int facts = f.stageCount();
        int held = reg.usableCounts().size();

        RequirementAssetAudit.audit(manifest("goal-1", "oak_log", "diamond"), f, reg, null);

        assertEquals(facts, f.stageCount(), "★ 不许改事实");
        assertEquals(held, reg.usableCounts().size(), "★ 不许改资产登记");
    }
}