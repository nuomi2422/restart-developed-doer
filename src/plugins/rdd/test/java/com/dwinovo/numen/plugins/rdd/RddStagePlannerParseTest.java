package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RddStagePlanner.parse 纯容错解析单测（Stage-A 一级清单，批B-2；不触 LLM/网络/MC）。 */
class RddStagePlannerParseTest {

    @Test void parsesStagesWithOptionalWaitFor() {
        String json = """
                {"stages":[
                  {"theme":"石器时代：做石斧石镐备基础材料"},
                  {"theme":"铁器时代：建熔炉炼铁做铁器",
                   "wait_for":[{"asset_key":"minecraft:stone_pickaxe","minimum":1}]}
                ]}""";
        List<PrimarySpec> stages = RddStagePlanner.parse(json);
        assertEquals(2, stages.size());
        assertEquals("石器时代：做石斧石镐备基础材料", stages.get(0).description());
        assertTrue(stages.get(0).waitFor().isEmpty());
        assertEquals(1, stages.get(1).waitFor().size());
        assertEquals("minecraft:stone_pickaxe", stages.get(1).waitFor().get(0).assetKey());
        assertEquals(1, stages.get(1).waitFor().get(0).minimum());
    }

    @Test void dropsUnusableWaitForKeepsStage() {
        // 裸键(永不匹配背包)、大写、负 minimum ->整条门丢弃，但阶段保留（门是增强不是必须）
        String json = """
                {"stages":[
                  {"theme":"石器时代",
                   "wait_for":[{"asset_key":"stone_pickaxe","minimum":1},
                               {"asset_key":"minecraft:Diamond","minimum":1}]},
                  {"theme":"铁器时代",
                   "wait_for":[{"asset_key":"minecraft:iron_ingot","minimum":0}]}
                ]}""";
        List<PrimarySpec> stages = RddStagePlanner.parse(json);
        assertEquals(2, stages.size());
        assertTrue(stages.get(0).waitFor().isEmpty(), "bare/case-wrong keys are unusable ->dropped");
        assertTrue(stages.get(1).waitFor().isEmpty(), "minimum<=0 is not 'already holds' ->dropped");
    }

    @Test void keepsStageWithSomeGoodWaitFor() {
        String json = """
                {"stages":[{"theme":"下界",
                   "wait_for":[{"asset_key":"flint_and_steel","minimum":1},
                               {"asset_key":"minecraft:obsidian","minimum":10}]}]}""";
        List<PrimarySpec> stages = RddStagePlanner.parse(json);
        assertEquals(1, stages.size());
        assertEquals(1, stages.get(0).waitFor().size());
        assertEquals("minecraft:obsidian", stages.get(0).waitFor().get(0).assetKey());
        assertEquals(10, stages.get(0).waitFor().get(0).minimum());
    }

    @Test void dropsThemeLessStagesAndEmptyOrBadInput() {
        assertTrue(RddStagePlanner.parse(null).isEmpty());
        assertTrue(RddStagePlanner.parse("").isEmpty());
        assertTrue(RddStagePlanner.parse("not json {{{").isEmpty());
        assertTrue(RddStagePlanner.parse("{}").isEmpty());
        assertTrue(RddStagePlanner.parse("{\"stages\":[]}").isEmpty());
        String badThemes = """
                {"stages":[
                  {"theme":"  "},
                  {"wait_for":[{"asset_key":"minecraft:stone","minimum":1}]}
                ]}""";
        assertTrue(RddStagePlanner.parse(badThemes).isEmpty());
    }

    @Test void capsAtMaxStages() {
        StringBuilder sb = new StringBuilder("{\"stages\":[");
        for (int i = 0; i < RddChainFactory.MAX_STAGES + 5; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"theme\":\"阶段").append(i).append("\"}");
        }
        sb.append("]}");
        List<PrimarySpec> stages = RddStagePlanner.parse(sb.toString());
        assertEquals(RddChainFactory.MAX_STAGES, stages.size());
        assertEquals("阶段0", stages.get(0).description());
    }
}
