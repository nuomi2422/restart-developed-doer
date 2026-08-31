package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HardCodedEvaluatorTest {
    @Test void matchesWhenCountAtLeastMinimum() {
        Map<String, Object> cond = Map.<String, Object>of("asset_key", "minecraft:oak_log", "minimum", 5);
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:oak_log", 4)));
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:oak_log", 5)));
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:oak_log", 12)));
    }

    @Test void defaultsMinimumToOne() {
        Map<String, Object> cond = Map.<String, Object>of("asset_key", "minecraft:stone");
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:stone", 1)));
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:stone", 3)));
        assertFalse(HardCodedEvaluator.matches(cond, Map.of()));
    }

    @Test void rejectsMissingOrBlankKey() {
        assertFalse(HardCodedEvaluator.matches(Map.of("minimum", 1), Map.of("x", 2)));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "", "minimum", 1), Map.of()));
        assertFalse(HardCodedEvaluator.matches(null, Map.of("x", 1)));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "a", "minimum", 1), null));
    }

    @Test void rejectsNegativeMinimum() {
        Map<String, Object> cond = Map.<String, Object>of("asset_key", "a", "minimum", -1);
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("a", 100)));
    }
}
