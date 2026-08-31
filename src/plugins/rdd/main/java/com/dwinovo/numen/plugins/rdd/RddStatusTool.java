package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Small diagnostic/status tool for the RDD host adapter. */
final class RddStatusTool implements NumenTool {
    @Override public String name() { return "rdd_status"; }
    @Override public String description() { return "Read the RDD asset task-chain status. No arguments."; }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        UUID uuid = companion.getUUID();
        if (RddPlugin.decomposing(uuid)) {
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD is decomposing the goal",
                    Map.of("active", false, "decomposing", true)).toJson());
            return;
        }
        RddRuntime runtime = RddPlugin.runtime(uuid);
        if (runtime == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD has no active task chain", Map.of("active", false)).toJson());
            return;
        }
        reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is active", Map.of(
                "active", true,
                "goal", runtime.chain().goal().id(),
                "primary_goal", runtime.chain().currentPrimary().id(),
                "primary_status", runtime.chain().primaryStatus().name(),
                "subtask", runtime.chain().currentSubtask().id(),
                "subtask_status", runtime.chain().currentSubtaskStatus().name(),
                "assets", runtime.assets().snapshot().size())).toJson());
    }
}
