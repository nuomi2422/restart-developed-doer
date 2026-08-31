package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 门面工具 {@code ac_status}：查询一个 AC 执行会话的状态。如实反映
 * RUNNING / PAUSED / SUCCESS / FAILED 与断点信息，不把“已受理”当完成。
 */
public final class AcStatusTool implements NumenTool {

    private final AcSessions sessions;

    public AcStatusTool(AcSessions sessions) {
        this.sessions = sessions;
    }

    @Override public String name() { return "ac_status"; }
    @Override public String description() {
        return "查询 ac_execute 提交的 AC 执行状态：status(SUCCESS/PAUSED/FAILED/RUNNING)、"
                + "已完成的步骤、当前步骤、错误信息；PAUSED 可用 ac_resume 续跑。";
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
            reply.accept(TaskResult.ok("AC still running", Map.of(
                    "execution_id", id, "ac", session.acName(), "status", "RUNNING")).toJson());
            return;
        }
        ExecutionRecord rec = session.future().join();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("execution_id", id);
        data.put("ac", rec.acName());
        data.put("status", rec.status().name());
        data.put("completed_steps", rec.completedStepIndex());
        data.put("current_step", rec.currentStepId() == null ? "" : rec.currentStepId());
        data.put("message", rec.message() == null ? "" : rec.message());
        if (rec.resume() != null) {
            data.put("resume_attempt", rec.resume().attempt());
            data.put("paused_reason", rec.resume().pausedReason() == null ? "" : rec.resume().pausedReason());
        }
        reply.accept(TaskResult.ok("AC status: " + rec.status().name(), data).toJson());
    }
}
