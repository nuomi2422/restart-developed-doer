package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.core.AcExecutor;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.ac.bridge.NumenToolBridge;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 门面工具 {@code ac_resume}：对 PAUSED 的 AC 执行选择“继续执行”，从真实断点
 * 续跑（不从头重跑）。身份/断点校验失败会明确报错，不静默续跑。
 */
public final class AcResumeTool implements NumenTool {

    private final AcExecutor executor;
    private final AcSessions sessions;

    public AcResumeTool(AcExecutor executor, AcSessions sessions) {
        this.executor = executor;
        this.sessions = sessions;
    }

    @Override public String name() { return "ac_resume"; }
    @Override public String description() {
        return "继续一个 PAUSED 的 AC 执行：从暂停的那一步重试，保留已完成进度与输入。"
                + "AC 版本或内容变化会被拒绝（需重规划）。";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().string("execution_id", "ac_execute 返回的 execution_id").build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        if (!args.has("execution_id")) {
            reply.accept(TaskResult.fail("缺少必填参数 execution_id").toJson());
            return;
        }
        String id = args.get("execution_id").getAsString();
        var entry = sessions.get(id);
        if (entry.isEmpty()) {
            reply.accept(TaskResult.fail("未知 execution_id: " + id).toJson());
            return;
        }
        AcSessions.SessionEntry session = entry.get();
        if (!session.future().isDone()) {
            reply.accept(TaskResult.fail("AC 仍在运行，无需 resume").toJson());
            return;
        }
        ExecutionRecord paused = session.future().join();
        if (paused.status() != ExecutionRecord.Status.PAUSED) {
            reply.accept(TaskResult.fail("仅 PAUSED 可 resume，当前: " + paused.status()).toJson());
            return;
        }
        ExecutionContext ctx = () -> Map.of(NumenToolBridge.HOST_ENTITY_UUID, companion.getUUID());
        CompletableFuture<ExecutionRecord> next;
        try {
            next = CompletableFuture.supplyAsync(() -> executor.resume(session.ac(), paused, ctx));
        } catch (IllegalArgumentException e) {
            reply.accept(TaskResult.fail("resume 被拒绝: " + e.getMessage()).toJson());
            return;
        }
        sessions.put(new AcSessions.SessionEntry(id, session.acName(), session.ac(), next));
        reply.accept(TaskResult.ok("AC resume requested", Map.of(
                "execution_id", id, "status", "RESUMING")).toJson());
    }
}
