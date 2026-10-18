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
                + "The seven structured fields (mechanism, preconditions, failure_conditions, "
                + "observable_signal, derivation, efficiency, evidence) are optional too, but they are "
                + "what later recall and verification actually read — pass every one the learner gave you, "
                + "verbatim and unlabelled. "
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
                // ★ 七个结构化槽位（键名与 ExperienceEntry.toJson() 逐字一致，见 45 号文档 §2「七字段」）。
                //   2026-10-04 之前这里**没有这七项** ⇒ plugins/learner 的 ExperienceDraft 明明把原值
                //   写进了 experience_draft.entry，Gson 在 fromJson 时静默丢掉，落库的条目七个槽位全空。
                //   症状是「合并不抹」那条测试全绿而库里没有一条七项填齐 —— 值从草稿到 store 这一段是断的。
                .optionalString("mechanism", "The world rule behind this lesson (why it works).")
                .optionalString("preconditions", "When this lesson may be used.")
                .optionalString("failure_conditions", "When this lesson will hurt you.")
                .optionalString("observable_signal", "A criterion you can SEE in the world.")
                .optionalString("derivation", "The causal chain from signal to action.")
                .optionalString("efficiency", "How much cheaper this is than the naive way.")
                .optionalString("evidence", "Where this came from (event chain / original quotes).")
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
                    // ★ 七个槽位必须真的搬进 builder：schema 收了它们、Input 收了它们，
                    //   这里不搬就是「参数合法但值原地消失」—— 而 Gson 对多出来的字段从不报错。
                    .mechanism(nz(in.mechanism()))
                    .preconditions(nz(in.preconditions()))
                    .failureConditions(nz(in.failure_conditions()))
                    .observableSignal(nz(in.observable_signal()))
                    .derivation(nz(in.derivation()))
                    .efficiency(nz(in.efficiency()))
                    .evidence(nz(in.evidence()))
                    .triggerStrings(in.trigger_strings() == null ? List.of() : in.trigger_strings())
                    .toolNames(in.tool_names() == null ? List.of() : in.tool_names())
                    .tags(in.tags() == null ? List.of() : in.tags())
                    .priority(in.priority() <= 0 ? 50 : in.priority())
                    .build();

            ExperienceEntry stored = ExperiencePlugin.memory(companion.getUUID()).learn(entry);
            // ★ 回执与埋点都报 seven_filled：「七个槽位进没进来」必须从外部看得见，
            //   否则「我传了七项」与「我一个都没传」在观测面完全同形 —— 那正是本条缺陷藏了这么久的原因。
            //   计数住在 ExperienceEntry.sevenFieldsFilled()（experience-core，纯 JVM 可单测），
            //   本模块的工具类测试 classpath 没有 NumenTool，所以不在这里自己数一遍。
            int filled = stored.sevenFieldsFilled();
            ExperienceMonitor.publish("learned", Map.of(
                    "id", stored.id(), "type", stored.type().name(),
                    "title", stored.title(), "maturity", stored.maturity().name(),
                    "seven_filled", filled));
            reply.accept(TaskResult.ok("experience recorded", Map.of(
                    "id", stored.id(),
                    "type", stored.type().name(),
                    "maturity", stored.maturity().name(),
                    "verified_count", stored.verifiedCount(),
                    "seven_filled", filled,
                    "seven_complete", filled == 7)).toJson());
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

    /**
     * 组件名用下划线（{@code root_cause} / {@code failure_conditions} …）是刻意的：
     * Gson 按**字段名**映射，键名必须与 {@code ExperienceEntry.toJson()} 逐字一致，
     * 也就是与 {@code ExperienceDraft} 产出的 {@code entry} 对象逐字一致 ——
     * 学习者交来的七个槽位因此可以原样转发，不必改名（改名 = 又一处需要同步的地方）。
     */
    private record Input(String type, String title, String description, String rationale,
                         String root_cause, String recommended_response,
                         String mechanism, String preconditions, String failure_conditions,
                         String observable_signal, String derivation, String efficiency, String evidence,
                         List<String> trigger_strings, List<String> tool_names,
                         List<String> tags, int priority) {}
}
