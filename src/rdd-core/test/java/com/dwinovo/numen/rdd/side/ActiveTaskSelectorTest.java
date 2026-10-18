package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 当前目标选择器 + 回执代次校验。 */
class ActiveTaskSelectorTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private SideTaskEngine engine(RecordingEvents events) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        return engine;
    }

    private ActiveTask.Mainline mainline() {
        return new ActiveTask.Mainline(C, "sub-1", "sub-1", 0L);
    }

    @Test
    void sideWinsWhileActiveThenFallsBackToMainline() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        ActiveTaskSelector selector = new ActiveTaskSelector(engine);

        assertEquals(TaskScope.MAINLINE, selector.select(C, mainline()).scope());
        engine.tick(C, new FakeWorldPort().at(11_800L, 0L), null, false);

        ActiveTask selected = selector.select(C, mainline());
        assertEquals(TaskScope.SIDE, selected.scope());
        assertEquals(engine.current(C).orElseThrow().instanceId(), selected.instanceId());

        engine.cancel(C, "test");
        assertEquals(TaskScope.MAINLINE, selector.select(C, mainline()).scope());
    }

    @Test
    void staleResultIsRejectedAndReported() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        ActiveTaskSelector selector = new ActiveTaskSelector(engine);
        engine.tick(C, new FakeWorldPort().at(11_800L, 0L), null, false);
        long generation = engine.current(C).orElseThrow().generation();

        assertTrue(selector.acceptsResult(C, TaskScope.SIDE,
                engine.current(C).orElseThrow().instanceId(), generation, mainline()));

        assertFalse(selector.acceptsResult(C, TaskScope.SIDE, "wrong-instance", generation, mainline()));
        assertTrue(events.saw("side_task_stale_result"), "实例不匹配必须发 stale_result");

        assertFalse(selector.acceptsResult(C, TaskScope.SIDE,
                engine.current(C).orElseThrow().instanceId(), generation + 1, mainline()));
    }
}
