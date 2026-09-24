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
}
