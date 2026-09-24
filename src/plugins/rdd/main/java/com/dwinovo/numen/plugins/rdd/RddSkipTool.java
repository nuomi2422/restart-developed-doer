package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Lets Numen challenge an unreachable optional food step without replacing the whole chain. */
final class RddSkipTool implements NumenTool {
    @Override public String name() { return "rdd_skip_optional"; }
    @Override public String description() {
        return "Skip an unreachable optional FOOD step (any food item, or condition group=food) so a food step never deadlocks the chain. Refuses non-food equipment/progression steps and steps explicitly marked optional=false. Requires idle body. Read rdd_status for expected_subtask_id first. Records SKIPPED, never COMPLETED.";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().string("expected_subtask_id", "Current subtask ID from rdd_status.")
                .string("reason", "Observed reason why this optional food is unavailable.").build();
    }
    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            String reason = args.get("reason").getAsString();
            String expected = args.get("expected_subtask_id").getAsString();
            if (reason.isBlank() || expected.isBlank()) throw new IllegalArgumentException("id and reason required");
            String result = RddPlugin.skipOptionalCurrent(companion.getUUID(), expected, reason);
            reply.accept((result.startsWith("optional food subtask skipped")
                    ? com.dwinovo.numen.task.TaskResult.ok(result, Map.of())
                    : com.dwinovo.numen.task.TaskResult.fail(result)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("skip refused: " + ex.getMessage()).toJson());
        }
    }
}
