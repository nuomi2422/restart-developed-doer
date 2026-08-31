package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把一条“以后该记得怎么做的教训”写进经验记忆。
 *
 * <p>调用时机：从成功或失败中学到有复用价值的东西时——不是每条消息/每次工具调用都写。
 * 只写能回答“以后遇到什么情况、应该怎么想怎么做”的经验。
 */
final class ExperienceLearnTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "experience_learn";
    }

    @Override
    public String description() {
        return "Record a reusable lesson into the maid's experience memory. Call when a success or "
                + "failure teaches something that should change future similar situations. "
                + "Required: type, title, description. Optional: rationale, root_cause, "
                + "recommended_response, trigger_strings, tool_names, tags, priority. "
                + "This is NOT for ordinary chat or single tool calls.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("type", "Experience kind", "EXECUTION", "FAILURE", "TOOL_DEFECT", "WORLD_RELATION", "POLICY")
                .string("title", "Short experience title.")
                .string("description", "What happened / the phenomenon.")
                .optionalString("rationale", "Why this is worth keeping.")
                .optionalString("root_cause", "Root cause of the failure.")
                .optionalString("recommended_response", "What to do next time.")
                .optionalStringArray("trigger_strings", "Words/concepts that should recall this experience.")
                .optionalStringArray("tool_names", "Related tool names.")
                .optionalStringArray("tags", "Topic tags.")
                .optionalInteger("priority", "Severity/importance 1-100 (default 50).", 1, 100)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            if (in == null || in.type() == null || blank(in.title()) || blank(in.description())) {
                reply.accept(TaskResult.fail(
                        "experience_learn requires type, title and description").toJson());
                return;
            }
            ExperienceType type;
            try {
                type = ExperienceType.valueOf(in.type().toUpperCase());
            } catch (IllegalArgumentException ex) {
                reply.accept(TaskResult.fail("unknown type: " + in.type()
                        + " (EXECUTION|FAILURE|TOOL_DEFECT|WORLD_RELATION|POLICY)").toJson());
                return;
            }

            ExperienceEntry entry = ExperienceEntry.builder()
                    .type(type)
                    .title(in.title())
                    .description(in.description())
                    .rationale(nz(in.rationale()))
                    .rootCause(nz(in.root_cause()))
                    .recommendedResponse(nz(in.recommended_response()))
                    .triggerStrings(in.trigger_strings() == null ? List.of() : in.trigger_strings())
                    .toolNames(in.tool_names() == null ? List.of() : in.tool_names())
                    .tags(in.tags() == null ? List.of() : in.tags())
                    .priority(in.priority() <= 0 ? 50 : in.priority())
                    .build();

            ExperienceEntry stored = ExperiencePlugin.memory(companion.getUUID()).learn(entry);
            ExperienceMonitor.publish("learned", Map.of(
                    "id", stored.id(), "type", stored.type().name(),
                    "title", stored.title(), "maturity", stored.maturity().name()));
            reply.accept(TaskResult.ok("experience recorded", Map.of(
                    "id", stored.id(),
                    "type", stored.type().name(),
                    "maturity", stored.maturity().name(),
                    "verified_count", stored.verifiedCount())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_learn failed: " + ex.getMessage()).toJson());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private record Input(String type, String title, String description, String rationale,
                         String root_cause, String recommended_response,
                         List<String> trigger_strings, List<String> tool_names,
                         List<String> tags, int priority) {}
}
