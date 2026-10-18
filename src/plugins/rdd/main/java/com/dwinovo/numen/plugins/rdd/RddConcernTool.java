package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.fail.TaskNegotiation;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 士兵回执工具（Supervisor ↔ Numen 双向协商 · 人工批准 2026-09-25）。
 *
 * <p>让 Numen 能对当前任务链命令**结构化回应**：接单(ACCEPT) / 命令不对(REJECT) / 做不到或太贵(COUNTER+建议)。
 * 指挥官（RddDetector）收到 REJECT/COUNTER 会**改单或触发重规划**（协商），不再单向无视。
 *
 * <p>读 rdd_status 取 expected_subtask_id，再据实回执。
 */
final class RddConcernTool implements NumenTool {
    @Override public String name() { return "report_task_concern"; }

    @Override public String description() {
        return "对当前 RDD 任务链命令结构化回执，与指挥官(Supervisor)协商。" +
                "kind: ACCEPT=接单照做; REJECT=命令本身不对(与目标冲突/信息错误), 附 reason; " +
                "COUNTER=做不到或成本过高, 附 reason + suggestion(替代方案); " +
                "PAUSE=这件事现在做不了但先别废掉(留着以后开), 附 reason, 不要用 COUNTER 代替。" +
                "当你认为当前 current_task 不合理/不可达/与目标不符时, 用它上报, 不要默默无视。" +
                "选择依据: 你能想到**别的做法**就用 COUNTER; " +
                "你**根本做不到且短期也不会变**(例如要求去 300 格外的地方、要求在末地拿东西)" +
                "就用 PAUSE——COUNTER 会让指挥官换计划并继续消耗你的重试预算, 而 PAUSE 只是把它按住。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("kind", "ACCEPT / REJECT / COUNTER / PAUSE")
                .string("expected_subtask_id", "Current subtask ID from rdd_status.")
                .string("reason", "为什么这样回执（REJECT/COUNTER/PAUSE 必填）。")
                .string("suggestion", "COUNTER 时的替代方案（人话）。")
                .build();
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            String kind = get(args, "kind");
            String taskId = get(args, "expected_subtask_id");
            String reason = get(args, "reason");
            String suggestion = get(args, "suggestion");
            if (kind.isBlank()) throw new IllegalArgumentException("kind required");
            TaskNegotiation n = TaskNegotiation.parse(kind, taskId, reason, suggestion);
            // 校验：回执必须针对当前二级（防张冠李戴）
            RddRuntime rt = RddPlugin.runtime(companion.getUUID());
            if (rt != null && !taskId.isBlank()) {
                Subtask cur = rt.chain().currentSubtask();
                if (cur != null && !cur.id().equals(taskId)) {
                    reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                            "concern rejected: expected_subtask_id mismatch (current=" + cur.id() + ")").toJson());
                    return;
                }
            }
            RddNegotiationInbox.accept(companion.getUUID(), n);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", n.kind().name());
            data.put("needsSupervisorAction", n.needsSupervisorAction());
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    "concern recorded: " + n.render(), data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("concern failed: " + ex.getMessage()).toJson());
        }
    }

    private static String get(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
