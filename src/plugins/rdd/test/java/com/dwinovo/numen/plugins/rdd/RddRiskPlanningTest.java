package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2.2 规划期风险前置：提示词块 + 高风险阶段自动 wait_for 硬门。 */
class RddRiskPlanningTest {

    @Test void prepHintEmptyForNormal() {
        assertEquals("", RddRiskPlanning.prepHint("做石镐和食物", Map.of()));
    }

    @Test void prepHintListsRequirementsForNether() {
        String hint = RddRiskPlanning.prepHint("进入下界", Map.of());
        assertTrue(hint.contains("风险前置"));
        assertTrue(hint.contains("下界"));
        assertTrue(hint.contains("minecraft:diamond_chestplate"));
        assertTrue(hint.contains("minecraft:fire_resistance_potion"));
        assertTrue(hint.contains("提前"));
    }

    @Test void injectWaitForOnlyIntoRiskyStage() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("做石镐和食物", List.of()),
                new PrimarySpec("进入下界", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertEquals(2, out.size());
        assertTrue(out.get(0).waitFor().isEmpty(), "普通阶段不加门");
        var keys = out.get(1).waitFor().stream().map(AssetRequirement::assetKey).toList();
        assertTrue(keys.contains("minecraft:diamond_chestplate"));
        assertTrue(keys.contains("minecraft:fire_resistance_potion"));
        int chest = out.get(1).waitFor().stream()
                .filter(r -> r.assetKey().equals("minecraft:diamond_chestplate"))
                .findFirst().orElseThrow().minimum();
        assertEquals(2, chest, "下界要求两套钻石甲");
    }

    @Test void injectWaitForMergesWithExistingTakingMax() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("进入下界", List.of(new AssetRequirement("minecraft:torch", 4))));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        int torch = out.get(0).waitFor().stream()
                .filter(r -> r.assetKey().equals("minecraft:torch"))
                .findFirst().orElseThrow().minimum();
        assertEquals(16, torch, "同 key 取较大 minimum");
    }
}
