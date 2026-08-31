package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 报告一条经验在真实世界是否得到印证。成功会推动成熟度升级
 * （OBSERVED/ATTEMPTED → VERIFIED → GENERALIZED）；失败只加反例，不伪造降级。
 */
final class ExperienceVerifyTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "experience_verify";
    }

    @Override
    public String description() {
        return "Report whether a previously learned experience was confirmed or refuted in the real "
                + "world. Pass the experience id returned by experience_learn or experience_recall. "
                + "success=true confirms it (raises maturity), success=false adds a counterexample.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "The experience id to verify.")
                .bool("success", "true if the experience held up in the real world, false otherwise.")
                .optionalString("note", "Short note on what happened (used as a counterexample on failure).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            if (in == null || in.id() == null || in.id().isBlank() || in.success() == null) {
                reply.accept(TaskResult.fail("experience_verify requires id and success").toJson());
                return;
            }
            ExperienceEntry updated = ExperiencePlugin.memory(companion.getUUID())
                    .recordEvidence(in.id(), in.success(), in.note());
            if (updated == null) {
                reply.accept(TaskResult.fail("no experience with id: " + in.id()).toJson());
                return;
            }
            reply.accept(TaskResult.ok("experience verified", Map.of(
                    "id", updated.id(),
                    "maturity", updated.maturity().name(),
                    "verified_count", updated.verifiedCount(),
                    "counterexamples", updated.counterexamples().size())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_verify failed: " + ex.getMessage()).toJson());
        }
    }

    private record Input(String id, Boolean success, String note) {}
}
