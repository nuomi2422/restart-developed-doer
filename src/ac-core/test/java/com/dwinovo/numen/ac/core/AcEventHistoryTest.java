package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.AcEvent;
import com.dwinovo.numen.ac.api.AcEventListener;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.api.StepResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AcEventHistoryTest {

    private static final ExecutionContext CTX = Map::of;

    private static List<AcEvent.Kind> collect(AcExecutor executor, List<AcEvent> all) {
        executor.addEventListener(e -> all.add(e));
        return all.stream().map(AcEvent::kind).toList();
    }

    @Test
    void emitsDeterministicEventSequence() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p, c) -> StepResult.success(Map.of()));
        r.register("bad", (p, c) -> StepResult.failed("boom"));
        var ac = new AcDefinition("seq", List.of(
                new AcDefinition.AcStep("a", "ok", Map.of()),
                new AcDefinition.AcStep("b", "bad", Map.of())));
        var executor = new AcExecutor(r);
        List<AcEvent> events = new ArrayList<>();
        collect(executor, events);

        var rec = executor.execute(ac, Map.of(), CTX);

        assertEquals(ExecutionRecord.Status.FAILED, rec.status());
        assertEquals(List.of(
                AcEvent.Kind.EXECUTION_STARTED,
                AcEvent.Kind.STEP_STARTED,
                AcEvent.Kind.STEP_SUCCEEDED,
                AcEvent.Kind.STEP_STARTED,
                AcEvent.Kind.STEP_FAILED,
                AcEvent.Kind.EXECUTION_FAILED), events.stream().map(AcEvent::kind).toList());
        // 步骤事件携带定位信息
        AcEvent stepFailed = events.get(4);
        assertEquals("b", stepFailed.stepId());
        assertEquals(1, stepFailed.stepIndex());
        assertEquals("bad", stepFailed.tool());
        assertEquals(rec.executionId(), stepFailed.executionId());
    }

    @Test
    void listenerExceptionIsIsolated() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p, c) -> StepResult.success(Map.of()));
        var ac = new AcDefinition("iso", List.of(new AcDefinition.AcStep("a", "ok", Map.of())));
        var executor = new AcExecutor(r);
        executor.addEventListener(e -> { throw new IllegalStateException("listener blew up"); });

        var rec = executor.execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, rec.status());  // 执行不受监听器异常影响
    }

    @Test
    void historyKeepsWholeAttemptChainByExecutionId() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        var shouldPause = new boolean[]{true};
        r.register("b", (p, c) -> shouldPause[0] ? StepResult.paused("wait", Map.of()) : StepResult.success(Map.of()));
        var ac = new AcDefinition("chain", List.of(new AcDefinition.AcStep("b", "b", Map.of())));
        var executor = new AcExecutor(r);

        var p1 = executor.execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.PAUSED, p1.status());
        String executionId = p1.executionId();

        shouldPause[0] = false;
        var done = executor.resume(ac, p1, CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, done.status());

        var chain = executor.recordsByExecutionId(executionId);
        assertEquals(2, chain.size());
        // 同一条执行链，两条 attempt 记录
        assertEquals(executionId, chain.get(0).executionId());
        assertEquals(executionId, chain.get(1).executionId());
        assertEquals(ExecutionRecord.Status.PAUSED, chain.get(0).status());
        assertEquals(0, chain.get(0).resume().attempt());
        assertEquals(ExecutionRecord.Status.SUCCESS, chain.get(1).status());
    }

    @Test
    void boundedHistoryEvictsOldest() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p, c) -> StepResult.success(Map.of()));
        var ac = new AcDefinition("evict", List.of(new AcDefinition.AcStep("a", "ok", Map.of())));
        var executor = new AcExecutor(r, 3);
        for (int i = 0; i < 10; i++) executor.execute(ac, Map.of(), CTX);
        assertEquals(3, executor.records().size());
    }
}
