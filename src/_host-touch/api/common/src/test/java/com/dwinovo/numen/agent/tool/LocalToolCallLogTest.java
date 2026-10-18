package com.dwinovo.numen.agent.tool;

import com.google.gson.JsonObject;
import com.dwinovo.numen.entity.NumenPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「哪些工具在客户端本地执行」这个判据 —— 它决定 {@code tools.jsonl} 里会不会少行。
 *
 * <p>实测背景（2026-10-04，live）：{@code tools.jsonl} 全文件 {@code find_tools} 0 行、
 * {@code todowrite} 0 行，而内脑自己的 {@code chat.jsonl} 里 {@code find_tools} 真调了
 * 118 次。根因就是这 12 个工具覆写了 {@code invoke}、在客户端跑完，
 * 压根不产生 {@code ExecuteToolPayload}。
 *
 * @see LocalToolCallLog
 */
class LocalToolCallLogTest {

    /** 不覆写 invoke ⇒ 默认走 ServerToolTransport.ship ⇒ 服务端那一跳会记。
     *  ★ 刻意**不能是 final**：`inheritanceDoesNotFakeALocalTool` 要继承它，
     *    而 Java 不允许继承 final 类（编译器报的是「无法解析为类」，非常误导）。 */
    private static class ServerSide implements NumenTool {
        @Override public String name() { return "server_side"; }
        @Override public Map<String, Object> parameterSchema() { return Map.of(); }
        @Override public String description() { return "走服务端"; }
    }

    /** 覆写 invoke ⇒ 本地执行 ⇒ 服务端不记，必须由 LocalToolCallLog 补。 */
    private static final class ClientLocal implements NumenTool {
        @Override public String name() { return "client_local"; }
        @Override public Map<String, Object> parameterSchema() { return Map.of(); }
        @Override public String description() { return "本地执行"; }
        @Override public void invoke(ToolCall call) { call.complete("{\"ok\":true}"); }
    }

    @Test
    @DisplayName("覆写了 invoke 的算本地执行，没覆写的不算")
    void overrideOfInvokeIsWhatMakesAToolLocal() {
        assertTrue(LocalToolCallLog.runsLocally(new ClientLocal()),
                "覆写了 invoke ⇒ 必须判定为本地执行，否则 tools.jsonl 会漏行");
        assertFalse(LocalToolCallLog.runsLocally(new ServerSide()),
                "★ 没覆写 invoke 的绝不能判成本地 —— 那一跳服务端已经记了，判错就是重复行");
        assertFalse(LocalToolCallLog.runsLocally(null), "null 必须当「不是本地」，不能抛");
    }

    @Test
    @DisplayName("判据只看 invoke 的声明类，不看这个类碰巧有没有别的方法")
    void theDiscriminatorLooksAtInvokeAndNothingElse() {
        // 这个类覆写了 invoke，也**顺带**覆写了 onServerCall。
        // 判据必须是 invoke —— 如果误判成「不是本地」，那它就彻底消失了。
        class Mixed implements NumenTool {
            @Override public String name() { return "mixed"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public String description() { return "两边都碰"; }
            @Override public void invoke(ToolCall call) { call.complete("{}"); }
            @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, java.util.function.Consumer<String> reply) {
                reply.accept("{}");
            }
        }
        assertTrue(LocalToolCallLog.runsLocally(new Mixed()));
    }

    @Test
    @DisplayName("判据对继承也成立：子类没覆写就仍算服务端")
    void inheritanceDoesNotFakeALocalTool() {
        class Child extends ServerSide {
            @Override public String description() { return "只是换了描述"; }
        }
        assertFalse(LocalToolCallLog.runsLocally(new Child()),
                "★ 子类只改了描述不算本地执行 —— 否则它会被记两次");
    }

    @Test
    @DisplayName("同一类反复判定走缓存，结果必须一致")
    void theReflectionResultIsCachedButStaysCorrect() {
        ClientLocal t = new ClientLocal();
        assertTrue(LocalToolCallLog.runsLocally(t));
        assertTrue(LocalToolCallLog.runsLocally(t), "缓存命中后结果不能变");
    }

    @Test
    @DisplayName("观测面写失败不许影响调用 —— publishCall 对任何东西都不抛")
    void journalingNeverBreaksTheCall() {
        // 这条钉的是「日志写不出来时同伴照样干活」。
        // 本地工具在客户端跑，没有服务端兜底 —— 一行日志写不出来就抛出去的话，
        // 她会因为观测面坏了而停手。
        ClientLocal t = new ClientLocal();
        LocalToolCallLog.publishCall(t, UUID.randomUUID(), "call-1", 10);
        LocalToolCallLog.publishRejected(t, UUID.randomUUID(), "call-2", "没取回定义");
        LocalToolCallLog.publishRejected(null, null, null, null);
        // 能走到这里就是通过：上面任何一步抛异常都会让测试红。
    }
}