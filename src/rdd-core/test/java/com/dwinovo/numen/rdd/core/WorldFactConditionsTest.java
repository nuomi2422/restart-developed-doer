package com.dwinovo.numen.rdd.core;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class WorldFactConditionsTest {
    @Test void validatesExplicitEvidenceOnly() {
        assertTrue(WorldFactConditions.valid(Map.of("type", "base")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "advancement", "advancement", "minecraft:story/mine_diamond")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "structure", "structure", "minecraft:stronghold")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "entity_killed", "entity", "minecraft:ender_dragon", "minimum", 1)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "entity_killed", "entity", "minecraft:ender_dragon", "minimum", 0)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "advancement", "advancement", "not real")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "base", "asset_key", "minecraft:bed")));
        assertFalse(HardCodedEvaluator.matches(Map.of("type", "base", "asset_key", "minecraft:white_bed"), Map.of("minecraft:white_bed", 1)));
    }
}
