package com.dwinovo.numen.plugins.ac.bridge;

import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.StepResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NumenToolBridgeTest {

    private static final ExecutionContext CTX = Map::of;

    private static HostTool tool(Consumer<Consumer<String>> trigger) {
        return new HostTool() {
            @Override public String name() { return "fake"; }
            @Override public void invoke(String callId, String argsJson, java.util.UUID anchorUuid, Consumer<String> done) {
                trigger.accept(done);
            }
        };
    }

    @Test
    void successResultMapsToSuccess() {
        var bridge = new NumenToolBridge(tool(done -> done.accept(
                "{\"success\":true,\"message\":\"done\",\"data\":{\"count\":2}}")));
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.SUCCESS, r.status());
        assertEquals(2L, r.output().get("count"));
    }

    @Test
    void timedOutResultMapsToPaused() {
        var bridge = new NumenToolBridge(tool(done -> done.accept(
                "{\"success\":false,\"message\":\"path blocked\",\"timed_out\":true}")));
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.PAUSED, r.status());
    }

    @Test
    void asyncAcceptedReceiptMapsToPausedNotSuccess() {
        // Numen setTask 长任务受理回执：success=true 但 data.async=true → 不是步骤完成
        var bridge = new NumenToolBridge(tool(done -> done.accept(
                "{\"success\":true,\"message\":\"accepted\",\"data\":{\"task_id\":\"t-1\",\"async\":true,\"standing\":false}}")));
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.PAUSED, r.status());
        assertEquals("t-1", r.output().get("task_id"));
        assertTrue(r.message().contains("accepted"), r.message());
    }

    @Test
    void failedResultMapsToFailed() {
        var bridge = new NumenToolBridge(tool(done -> done.accept(
                "{\"success\":false,\"message\":\"no target\"}")));
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.FAILED, r.status());
        assertEquals("no target", r.message());
    }

    @Test
    void nonJsonResultMapsToFailed() {
        var bridge = new NumenToolBridge(tool(done -> done.accept("not json")));
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("非 JSON"), r.message());
    }

    @Test
    void noReplyTimesOutToPaused() {
        var bridge = new NumenToolBridge(tool(done -> { /* 永不回调 */ }), 50);
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.PAUSED, r.status());
        assertTrue(r.message().contains("timeout"), r.message());
    }

    @Test
    void invokeThrowingMapsToFailedNotCrash() {
        // transport 工具（如 get_self_status 走 ServerToolTransport.ship）在 AC 后台线程
        // 可能直接抛 RuntimeException —— 必须降级 FAILED，绝不把异常漏给调用方
        // （否则整个 execution future 异常完成，ac_status/ac_resume join() 时 NPE）。
        var bridge = new NumenToolBridge(new HostTool() {
            @Override public String name() { return "fake"; }
            @Override public void invoke(String callId, String argsJson, java.util.UUID anchorUuid, Consumer<String> done) {
                throw new NullPointerException("platform sender NPE");
            }
        });
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("invoke threw"), r.message());
    }

    @Test
    void bareJsonWithoutSuccessMapsToSuccess() {
        // 非身体工具直接 complete 自定义 JSON（如 selfcompile_status）→ 视为成功产出数据
        var bridge = new NumenToolBridge(tool(done -> done.accept(
                "{\"module\":\"selfcompile\",\"state\":\"idle\",\"mode\":\"controlled\"}")));
        StepResult r = bridge.execute(Map.of(), CTX);
        assertEquals(StepResult.Status.SUCCESS, r.status());
        assertEquals("idle", r.output().get("state"));
        assertEquals("controlled", r.output().get("mode"));
    }
}
