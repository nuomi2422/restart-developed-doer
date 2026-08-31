package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.api.StepResult;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AcExecutorTest {

    private static final ExecutionContext CTX = Map::of;

    @Test
    void executesAndRecords() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("step", (p, c) -> StepResult.success(Map.of("value", p.get("value"))));
        var ac = new AcDefinition("demo", List.of(new AcDefinition.AcStep("one", "step", Map.of("value", 1))));
        var record = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, record.status());
        assertEquals(1, record.completedStepIndex());
        assertEquals(1, record.output().get("value"));
    }

    @Test
    void pausedExecutionResumesFromPausedStep() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        var shouldPause = new boolean[]{true};
        var aCalls = new AtomicInteger();
        r.register("a", (p, c) -> { aCalls.incrementAndGet(); return StepResult.success(Map.of("from_a", "done")); });
        r.register("b", (p, c) -> shouldPause[0] ? StepResult.paused("needs approval", Map.of("paused", true)) : StepResult.success(Map.of("resumed", true)));
        r.register("c", (p, c) -> StepResult.success(Map.of()));

        var ac = new AcDefinition("pause-demo", List.of(
                new AcDefinition.AcStep("a", "a", Map.of()),
                new AcDefinition.AcStep("b", "b", Map.of()),
                new AcDefinition.AcStep("c", "c", Map.of())));
        var executor = new AcExecutor(r);
        var paused = executor.execute(ac, Map.of("k", "v"), CTX);

        assertEquals(ExecutionRecord.Status.PAUSED, paused.status());
        assertEquals(1, paused.completedStepIndex());
        assertEquals("b", paused.currentStepId());
        assertEquals(1, aCalls.get());          // a 只执行了一次

        // PAUSED 记录必须携带原始输入与断点
        assertEquals("v", paused.resume().input().get("k"));
        assertEquals("b", paused.resume().resumeStepId());

        shouldPause[0] = false;
        var resumed = executor.resume(ac, paused, CTX);

        assertEquals(ExecutionRecord.Status.SUCCESS, resumed.status());
        assertEquals(3, resumed.completedStepIndex());
        assertEquals(1, aCalls.get());          // resume 不重复已完成步骤 a
        assertEquals("done", resumed.output().get("from_a"));  // 保留此前输出
    }

    @Test
    void resumeRejectsNonPausedRecord() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p, c) -> StepResult.success(Map.of()));
        var ac = new AcDefinition("nop", List.of(new AcDefinition.AcStep("s", "ok", Map.of())));
        var executor = new AcExecutor(r);
        var done = executor.execute(ac, Map.of(), CTX);
        assertThrows(IllegalArgumentException.class, () -> executor.resume(ac, done, CTX));
    }

    @Test
    void resumeRejectsChangedVersion() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        var shouldPause = new boolean[]{true};
        r.register("b", (p, c) -> shouldPause[0] ? StepResult.paused("wait", Map.of()) : StepResult.success(Map.of()));
        var v1 = new AcDefinition("v", "1", List.of(new AcDefinition.AcStep("b", "b", Map.of())));
        var executor = new AcExecutor(r);
        var paused = executor.execute(v1, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.PAUSED, paused.status());

        shouldPause[0] = false;
        var v2 = new AcDefinition("v", "2", List.of(new AcDefinition.AcStep("b", "b", Map.of())));
        var ex = assertThrows(IllegalArgumentException.class, () -> executor.resume(v2, paused, CTX));
        assertTrue(ex.getMessage().contains("version"), ex.getMessage());
    }

    @Test
    void resumeRejectsChangedContentSameVersion() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        var shouldPause = new boolean[]{true};
        r.register("b", (p, c) -> shouldPause[0] ? StepResult.paused("wait", Map.of()) : StepResult.success(Map.of()));
        var original = new AcDefinition("v", "1", List.of(new AcDefinition.AcStep("b", "b", Map.of("target", "iron"))));
        var executor = new AcExecutor(r);
        var paused = executor.execute(original, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.PAUSED, paused.status());

        shouldPause[0] = false;
        // 同 name+version，但步骤参数变了 → fingerprint 变 → 拒绝
        var mutated = new AcDefinition("v", "1", List.of(new AcDefinition.AcStep("b", "b", Map.of("target", "coal"))));
        var ex = assertThrows(IllegalArgumentException.class, () -> executor.resume(mutated, paused, CTX));
        assertTrue(ex.getMessage().contains("fingerprint"), ex.getMessage());
    }

    @Test
    void multiplePausesKeepAttemptChainAndState() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        var state = new ArrayList<String>();
        r.register("a", (p, c) -> StepResult.success(Map.of("mined", 1)));
        r.register("b", (p, c) -> {
            if (state.isEmpty()) { state.add("first-pause"); return StepResult.paused("pause-1", Map.of()); }
            state.add("second-pause"); return StepResult.paused("pause-2", Map.of());
        });
        var ac = new AcDefinition("chain", List.of(
                new AcDefinition.AcStep("a", "a", Map.of()),
                new AcDefinition.AcStep("b", "b", Map.of())));
        var executor = new AcExecutor(r);

        var p1 = executor.execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.PAUSED, p1.status());
        String executionId = p1.resume().executionId();
        assertEquals(0, p1.resume().attempt());
        assertEquals(1, p1.output().get("mined"));

        var p2 = executor.resume(ac, p1, CTX);
        assertEquals(ExecutionRecord.Status.PAUSED, p2.status());
        assertEquals(executionId, p2.resume().executionId());   // 同一次执行
        assertEquals(1, p2.resume().attempt());                 // attempt +1
        assertEquals(1, p2.output().get("mined"));              // 状态保留
    }

    @Test
    void stepThrowingRuntimeExceptionFailsExecutionInsteadOfCrashing() {
        // 多步 AC 中某步抛 RuntimeException（如 transport 工具 NPE）→ 必须降级为 FAILED 步骤，
        // 不能让异常冲出 executeSession 使 future 异常完成（ac_status/ac_resume join() 时炸）。
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p, c) -> StepResult.success(Map.of("done", true)));
        r.register("boom", (p, c) -> { throw new NullPointerException("transport sender NPE"); });
        var ac = new AcDefinition("npe-demo", List.of(
                new AcDefinition.AcStep("a", "ok", Map.of()),
                new AcDefinition.AcStep("b", "boom", Map.of()),
                new AcDefinition.AcStep("c", "ok", Map.of())));
        var record = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.FAILED, record.status());
        assertEquals(1, record.completedStepIndex());
        assertTrue(record.message().contains("boom"), record.message());
    }

    @Test
    void stepOutputWithNullValueDoesNotCrash() {
        // 工具输出含 null value（如 get_self_status 的 "target": null）→ 旧 Map.copyOf 对
        // null value 抛 NPE → execution future 异常完成 → ac_status join() 炸（多步 NPE 根因）。
        DefaultToolRegistry r = new DefaultToolRegistry();
        java.util.Map<String, Object> nullish = new java.util.LinkedHashMap<>();
        nullish.put("target", null);
        nullish.put("hp", 20.0);
        r.register("nullish", (p, c) -> StepResult.success(nullish));
        var ac = new AcDefinition("null-value-demo", List.of(
                new AcDefinition.AcStep("a", "nullish", Map.of()),
                new AcDefinition.AcStep("b", "nullish", Map.of())));
        var record = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, record.status());
        assertEquals(2, record.completedStepIndex());
        assertNull(record.output().get("target"));
    }

    @Test
    void loadsAndValidatesJson() {
        var ac = AcJson.load(new StringReader("{\"name\":\"json\",\"version\":\"2\",\"steps\":[{\"id\":\"s\",\"tool\":\"ok\",\"parameters\":{\"n\":2}}]}"));
        assertEquals("json", ac.name());
        assertEquals("2", ac.version());
        assertEquals("ok", ac.steps().get(0).tool());
        assertThrows(IllegalArgumentException.class, () -> AcJson.load(new StringReader("{\"name\":\"bad\"}")));
    }
}
