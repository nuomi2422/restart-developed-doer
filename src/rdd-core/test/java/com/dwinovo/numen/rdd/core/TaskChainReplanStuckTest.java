package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P4：从卡死进入 REPLANNING 的显式出口（补 REPLANNING 在生产无触发器的问题）。 */
class TaskChainReplanStuckTest {

    private static Goal goal() {
        return new Goal("goal-x", "通关MC",
                List.of(PrimaryGoal.unexpanded("primary-0", "采矿", List.of())));
    }

    private static TaskChain runningChainWithFailedSubtask() {
        TaskChain chain = new TaskChain(goal());
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded("s0", "采矿", Map.of("asset_key", "minecraft:iron_ore"), null)));
        chain.startCurrent();
        chain.markFailed("s0", "挖不到");
        return chain;
    }

    @Test void enterReplanningFromStuckOnlyWhenFailed() {
        TaskChain chain = runningChainWithFailedSubtask();
        assertEquals(SubtaskStatus.FAILED, chain.currentSubtaskStatus());
        chain.enterReplanningFromStuck("挖不到");
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
    }

    @Test void cannotEnterWhenNotStuck() {
        TaskChain chain = new TaskChain(goal());
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded("s0", "采矿", Map.of("asset_key", "minecraft:iron_ore"), null)));
        chain.startCurrent(); // RUNNING, not FAILED
        assertThrows(IllegalStateException.class, () -> chain.enterReplanningFromStuck("x"));
    }

    @Test void replaceSubtasksAfterStuckReplan() {
        TaskChain chain = runningChainWithFailedSubtask();
        chain.enterReplanningFromStuck("挖不到");
        chain.replaceCurrentSubtasks(List.of(
                Subtask.hardCoded("s0-r0", "换矿洞挖", Map.of("asset_key", "minecraft:iron_ore"), null)));
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertEquals("s0-r0", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
    }
}
