package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.policy.AssetSemantics.Importance;
import com.dwinovo.numen.rdd.policy.AssetSemantics.Nature;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.LossImpact;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation;
import com.dwinovo.numen.rdd.policy.RequirementManifest.Manifest;
import com.dwinovo.numen.rdd.policy.RequirementManifest.Requirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 损失影响分析：重要性决定值不值得关注，剩余能力决定需不需要行动。 */
class LossImpactAnalyzerTest {

    private static final String GOAL = "goal-nethers";

    private static Manifest manifest(Requirement... reqs) {
        return new Manifest(GOAL, List.of(reqs));
    }

    private static Map<String, AssetSemantics> importantEquipment(String key, String goal) {
        return Map.of(key, AssetSemantics.declared(key, Importance.IMPORTANT, Nature.EQUIPMENT, goal));
    }

    @Test
    void recoveredLossIsNotPermanent() {
        // 死亡瞬间丢过，但已捡回：净损失为空 → 不报永久损失
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(),
                Map.of("minecraft:diamond_sword", 1),
                List.of(manifest(new Requirement("minecraft:diamond_sword", 1, List.of()))),
                importantEquipment("minecraft:diamond_sword", GOAL),
                true);
        assertEquals(Recommendation.NONE, impact.recommendation());
        assertFalse(impact.importantLoss());
        assertTrue(impact.affectedGoals().isEmpty());
    }

    @Test
    void importantLossWithBackupContinues() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_sword", 1),
                Map.of("minecraft:iron_sword", 1),
                List.of(manifest(new Requirement("minecraft:diamond_sword", 1,
                        List.of("minecraft:iron_sword")))),
                importantEquipment("minecraft:diamond_sword", GOAL),
                true);
        assertEquals(Recommendation.SWAP_BACKUP, impact.recommendation());
        assertTrue(impact.importantLoss());
        assertTrue(impact.backupSufficient(), "备用武器覆盖了需求 → 换装继续");
        assertTrue(impact.affectedGoals().isEmpty());
    }

    @Test
    void capabilityGapNeedsSupplement() {
        // 丢了钻石镐，只有石镐（不是合格替代）→ 能力缺口，需补充
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_pickaxe", 1),
                Map.of("minecraft:stone_pickaxe", 1),
                List.of(manifest(new Requirement("minecraft:diamond_pickaxe", 1, List.of()))),
                importantEquipment("minecraft:diamond_pickaxe", "采钻石"),
                true);
        assertEquals(Recommendation.SUPPLEMENT, impact.recommendation());
        assertTrue(impact.needsAction());
        assertFalse(impact.backupSufficient());
        assertEquals(1, impact.affectedGoals().size());
        assertEquals("minecraft:diamond_pickaxe", impact.affectedGoals().get(0).requirementKey());
    }

    @Test
    void unconfirmedLossAsksToConfirmFirst() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_pickaxe", 1),
                Map.of(),
                List.of(manifest(new Requirement("minecraft:diamond_pickaxe", 1, List.of()))),
                importantEquipment("minecraft:diamond_pickaxe", GOAL),
                false);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertFalse(impact.needsAction(), "尚未确认丢失时不下'永久丢失'结论");
        assertTrue(impact.summary().contains("尚未确认"));
    }

    @Test
    void secondaryLossJustReconfirms() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:stone", 64),
                Map.of(),
                List.of(manifest(new Requirement("minecraft:stone", 32, List.of()))),
                Map.of("minecraft:stone", AssetSemantics.declared("minecraft:stone",
                        Importance.SECONDARY, Nature.MATERIAL, GOAL)),
                true);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertFalse(impact.importantLoss());
    }

    @Test
    void goalCancelledMeansIrrelevantNeedsNothing() {
        // 目标取消后语义标 IRRELEVANT → 丢了也不触发动作
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_sword", 1),
                Map.of(),
                List.of(),
                Map.of("minecraft:diamond_sword", AssetSemantics.declared("minecraft:diamond_sword",
                        Importance.IRRELEVANT, Nature.EQUIPMENT, "")),
                true);
        assertEquals(Recommendation.NONE, impact.recommendation());
        assertFalse(impact.importantLoss());
    }

    @Test
    void normalConsumptionIsNotLoss() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(),
                Map.of("minecraft:bread", 0),
                List.of(manifest(new Requirement("minecraft:bread", 3, List.of()))),
                Map.of(),
                true);
        assertEquals(Recommendation.NONE, impact.recommendation());
    }

    @Test
    void natureGuesserIsConservative() {
        assertEquals(Nature.EQUIPMENT, AssetSemantics.guessNature("minecraft:diamond_pickaxe"));
        assertEquals(Nature.CONSUMABLE, AssetSemantics.guessNature("minecraft:cooked_beef"));
        assertEquals(Nature.MATERIAL, AssetSemantics.guessNature("minecraft:stone"));
    }
}
