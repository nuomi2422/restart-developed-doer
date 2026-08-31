package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RddChainFactoryTest {
    private static final UUID CID = UUID.fromString("c29c5c40-0632-4982-9ab7-d99528d58e17");

    @Test void buildsUsableChain() {
        Goal goal = RddChainFactory.fromObjective(CID, "找 3 颗钻石");
        assertEquals(1, goal.primaryGoals().size());
        PrimaryGoal primary = goal.primaryGoals().get(0);
        assertEquals("找 3 颗钻石", primary.description());
        assertEquals(1, primary.subtasks().size());
        Subtask subtask = primary.subtasks().get(0);
        assertEquals(DetectionMode.HARD_CODED, subtask.detectionMode());
        assertFalse(subtask.condition().isEmpty());
        TaskChain chain = new TaskChain(goal);
        chain.startCurrent();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void trimsAndRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromObjective(CID, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromObjective(CID, null));
        assertEquals("ok", RddChainFactory.fromObjective(CID, "  ok  ").description());
    }

    @Test void capsObjectiveLength() {
        String huge = "x".repeat(5000);
        Goal goal = RddChainFactory.fromObjective(CID, huge);
        assertEquals(RddChainFactory.MAX_OBJECTIVE_CHARS, goal.description().length());
    }

    @Test void fromSpecBuildsMultiSubtaskChain() {
        Goal goal = RddChainFactory.fromSpec(CID, "get materials", List.of(
                new SubtaskSpec("get 5 logs", Map.of("asset_key", "minecraft:oak_log", "minimum", 5),
                        new BodyInstruction("collect_items", Map.of("item", "minecraft:oak_log", "count", 5))),
                new SubtaskSpec("get 3 stone", Map.of("asset_key", "minecraft:stone", "minimum", 3), null)));
        assertEquals(1, goal.primaryGoals().size());
        assertEquals("get materials", goal.description());
        var subtasks = goal.primaryGoals().get(0).subtasks();
        assertEquals(2, subtasks.size());
        Subtask first = subtasks.get(0);
        assertEquals(DetectionMode.HARD_CODED, first.detectionMode());
        assertEquals(5, first.condition().get("minimum"));
        assertNotNull(first.body());
        assertEquals("collect_items", first.body().taskType());
        assertEquals(5, first.body().args().get("count"));
        assertNull(subtasks.get(1).body());
        assertNotEquals(first.id(), subtasks.get(1).id());
    }

    @Test void fromSpecRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromSpec(CID, "g", List.of()));
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromSpec(CID, "g", null));
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromSpec(CID, "   ", List.of(
                new SubtaskSpec("x", Map.of("asset_key", "a", "minimum", 1), null))));
        // 缺 asset_key 无法被硬编码判定
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromSpec(CID, "g", List.of(
                new SubtaskSpec("x", Map.of("minimum", 1), null))));
        // 负数 minimum 永远判不中
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromSpec(CID, "g", List.of(
                new SubtaskSpec("x", Map.of("asset_key", "a", "minimum", -1), null))));
        // 超 MAX_SUBTASKS
        List<SubtaskSpec> tooMany = new ArrayList<>();
        for (int i = 0; i <= RddChainFactory.MAX_SUBTASKS; i++) {
            tooMany.add(new SubtaskSpec("s" + i, Map.of("asset_key", "a", "minimum", 1), null));
        }
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromSpec(CID, "g", tooMany));
    }
}
