package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** P2.2 风险门：硬判定 + 缺口转准备任务 + 风险级别识别。 */
class RiskGateTest {

    @Test void blocksNetherWhenUnderPrepared() {
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER,
                Map.of("minecraft:diamond_chestplate", 1, "minecraft:torch", 16));
        assertFalse(v.allowed());
        assertTrue(v.missing().stream().anyMatch(s -> s.contains("fire_resistance_potion")));
        assertTrue(v.prepTasks().stream().anyMatch(s -> s.contains("酿造") || s.contains("potion")));
        assertTrue(v.prepTasks().stream().anyMatch(s -> s.contains("临时据点") || s.contains("回退")));
    }

    @Test void allowsNetherWhenPrepared() {
        var ok = Map.of(
                "minecraft:diamond_helmet", 2, "minecraft:diamond_chestplate", 2,
                "minecraft:diamond_leggings", 2, "minecraft:diamond_boots", 2,
                "minecraft:fire_resistance_potion", 3, "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER, ok);
        assertTrue(v.allowed());
        assertTrue(v.missing().isEmpty());
    }

    @Test void normalAlwaysAllows() {
        assertTrue(RiskGate.allows(RiskLevel.NORMAL, Map.of()));
    }

    @Test void levelDetection() {
        assertEquals(RiskLevel.NETHER, RiskGate.levelForDimension("minecraft:the_nether"));
        assertEquals(RiskLevel.END, RiskGate.levelForDimension("minecraft:the_end"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForDimension("minecraft:overworld"));
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("进入下界准备"));
        assertEquals(RiskLevel.END, RiskGate.levelForText("击杀末影龙"));
        assertEquals(RiskLevel.MINING, RiskGate.levelForText("下矿采集钻石"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("做石镐"));
    }

    /** §6③：高风险活动需恢复点（已绑床）；没恢复点即使物资够也阻止。 */
    @Test void highRiskRequiresRecoveryPoint() {
        var ok = Map.of(
                "minecraft:diamond_helmet", 2, "minecraft:diamond_chestplate", 2,
                "minecraft:diamond_leggings", 2, "minecraft:diamond_boots", 2,
                "minecraft:fire_resistance_potion", 3, "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
        var noRecovery = RiskGate.checkWithRecovery(RiskLevel.NETHER, ok, false);
        assertFalse(noRecovery.allowed(), "物资够但没恢复点 → 阻止");
        assertTrue(noRecovery.missing().stream().anyMatch(s -> s.contains("recovery_point")));
        assertTrue(noRecovery.prepTasks().stream().anyMatch(s -> s.contains("绑定重生点") || s.contains("床")));

        var withRecovery = RiskGate.checkWithRecovery(RiskLevel.NETHER, ok, true);
        assertTrue(withRecovery.allowed(), "物资够 + 有恢复点 → 允许");
    }

    @Test void recoveryPointNotRequiredForNormalOrMining() {
        assertTrue(RiskGate.checkWithRecovery(RiskLevel.NORMAL, Map.of(), false).allowed());
        assertTrue(RiskGate.checkWithRecovery(RiskLevel.MINING,
                Map.of("minecraft:torch", 16, "minecraft:bread", 4), false).allowed(),
                "下矿不强制恢复点");
    }
}
