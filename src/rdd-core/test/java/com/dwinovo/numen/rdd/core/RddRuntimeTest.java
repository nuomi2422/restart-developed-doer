package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddRuntimeTest {
    @Test void publishesThroughFacadeWithoutChangingTaskAuthority() {
        var goal = new Goal("g", "goal", java.util.List.of(new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s", "get stone", Map.of("item", "stone"))))));
        var runtime = new RddRuntime(new TaskChain(goal), new AssetRegistry());
        runtime.startCurrent();
        assertTrue(runtime.applyHardCoded("s", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, runtime.chain().primaryStatus());
    }
}
