package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ★ E8：<b>撤回 / 撤销撤回 / 标注取代</b>，以及「哪些条目已经不能用了」的如实清单。
 *
 * <p><b>为什么要有这个工具</b>：E8 只在 store 里加了能力还不够 ——
 * 那些代码没有调用方就等于不存在（这是本项目反复吃过的亏：B5 的
 * {@code rewritten_query} 解析了但零调用方、B2 的映射写完就没人走）。
 * 同伴/发布方需要一个<b>能调用的口</b>，否则「撤回」永远不会被触发。</p>
 *
 * <p><b>⚠️ 三个动作的语义差别很大，别混用</b>：
 * <ul>
 *   <li><b>retract</b> = 「这条<b>根本不该用</b>」（判错了、或与世界实际不符）。
 *       标记而非删除 —— 删了就没有「为什么撤」的证据，而这个项目要的是链路可冻结。
 *       <b>reason 必填且不许空</b>：撤回而不写理由等于让别人猜。</li>
 *   <li><b>reinstate</b> = 误撤回后救回来。</li>
 *   <li><b>supersede</b> = 「新的一条把旧的<b>说法</b>改了」。<b>不要用它表达撤回</b> ——
 *       语义上「旧的被新说法取代」和「旧的是错的」是两回事，混起来会让
 *       检索侧把错的经验当成「只是有更新版本」而继续用。</li>
 * </ul>
 *
 * <p>⚠️ 这个工具<b>没有</b>「永久删除」这个动作：经验库一旦能删，
 * 「这条为什么被删」就没地方查了。需要删时先 retract 并写清理由。</p>
 */
final class ExperienceRetractTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "experience_retract";
    }

    @Override
    public String description() {
        return "Retract an experience that is wrong or does not hold in the real world "
                + "(marks it unusable; does NOT delete it, so the reason stays auditable), "
                + "reinstate a retracted one, or mark that a newer entry supersedes an older one. "
                + "Use action=status to list which entries are currently unusable and why. "
                + "Do NOT use supersede to mean 'this was wrong' - supersede means the wording was "
                + "updated; retract means the knowledge itself is invalid.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("action", "One of: retract | reinstate | supersede | status.")
                .string("id", "The experience id. For 'retract'/'reinstate' it is the target itself; "
                        + "for 'supersede' it is the NEWER entry (the one doing the superseding).")
                .optionalString("reason", "Required for 'retract': why this experience is unusable. "
                        + "Never retract without a reason.")
                .optionalString("supersedes", "Required for 'supersede': the id of the OLD entry "
                        + "that 'id' replaces.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            if (in == null || in.action() == null || in.action().isBlank()) {
                reply.accept(TaskResult.fail("experience_retract requires action").toJson());
                return;
            }
            ExperienceMemory mem = ExperiencePlugin.memory(companion.getUUID());
            String action = in.action().trim().toLowerCase(java.util.Locale.ROOT);

            switch (action) {
                case "status" -> reply.accept(TaskResult.ok("experience status", statusReport(mem)).toJson());
                case "retract" -> reply.accept(TaskResult.ok("experience retracted", retract(mem, in)).toJson());
                case "reinstate" -> reply.accept(TaskResult.ok("experience reinstated", reinstate(mem, in)).toJson());
                case "supersede" -> reply.accept(TaskResult.ok("experience superseded", supersede(mem, in)).toJson());
                default -> reply.accept(TaskResult.fail(
                        "unknown action '" + in.action() + "' (want retract|reinstate|supersede|status)").toJson());
            }
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_retract failed: " + ex.getMessage()).toJson());
        }
    }

    private static Map<String, Object> retract(ExperienceMemory mem, Input in) {
        if (in.id() == null || in.id().isBlank()) {
            return Map.of("ok", false, "error", "retract requires id");
        }
        if (in.reason() == null || in.reason().isBlank()) {
            // 不给理由就不许撤 —— 这条是硬口径，不是建议
            return Map.of("ok", false, "error",
                    "retract requires a non-empty reason: 撤回一条经验而不写理由，等于让别人猜");
        }
        ExperienceEntry e = mem.retract(in.id(), in.reason());
        if (e == null) {
            return Map.of("ok", false, "error", "no experience with id: " + in.id());
        }
        return Map.of("ok", true, "id", e.id(), "retracted", true,
                "reason", e.retractedReason(), "usable_now", mem.usable().size());
    }

    private static Map<String, Object> reinstate(ExperienceMemory mem, Input in) {
        if (in.id() == null || in.id().isBlank()) {
            return Map.of("ok", false, "error", "reinstate requires id");
        }
        ExperienceEntry e = mem.reinstate(in.id());
        if (e == null) {
            return Map.of("ok", false, "error", "no experience with id: " + in.id());
        }
        return Map.of("ok", true, "id", e.id(), "retracted", e.retracted(),
                "usable_now", mem.usable().size());
    }

    private static Map<String, Object> supersede(ExperienceMemory mem, Input in) {
        if (in.id() == null || in.id().isBlank() || in.supersedes() == null || in.supersedes().isBlank()) {
            return Map.of("ok", false, "error", "supersede requires id (newer) and supersedes (older)");
        }
        ExperienceEntry older = mem.supersede(in.supersedes(), in.id());
        if (older == null) {
            return Map.of("ok", false, "error",
                    "supersede failed: older id '" + in.supersedes() + "' not found, or newer is retracted");
        }
        return Map.of("ok", true, "newer", in.id(), "older", older.id(),
                "older_title", older.title(), "superseded_count", mem.supersededCount());
    }

    /** 如实清单：哪些不能用、为什么。空列表也是有效信息（「现在没有不可用的」）。 */
    private static Map<String, Object> statusReport(ExperienceMemory mem) {
        var dead = mem.supersededIds();
        List<Map<String, Object>> unusable = new ArrayList<>();
        for (ExperienceEntry e : mem.all()) {
            String why = e.unusableReason(dead.contains(e.id()));
            if (!why.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.id());
                row.put("title", e.title());
                row.put("maturity", e.maturity() == null ? "?" : e.maturity().name());
                row.put("why", why);
                unusable.add(row);
            }
        }
        return Map.of(
                "total", mem.size(),
                "usable", mem.usable().size(),
                "retracted", mem.retractedCount(),
                "superseded", mem.supersededCount(),
                "unusable_entries", unusable);
    }

    private record Input(String action, String id, String reason, String supersedes) {}
}