package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddOwnerPause;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_standby}：主人让同伴原地待命 / 解除待命（第一方控制面）。
 *
 * <p><b>与士兵自己上报的 PAUSE 的区别</b>（这是本工具存在的理由）：
 * 士兵 PAUSE = 「我做不了，等条件变」→ 到期会被复评催办；
 * 主人待命 = 「现在就停，等我」→ <b>永不过期、绝不自动恢复</b>，
 * 执行层再怎么上报 COUNTER 也推不翻（{@code RddDetector} 据 owner 标记区别对待）。
 *
 * <p>建这个工具的直接原因：深审发现"主人暂停"原本<b>没有生产入口</b> ——
 * 保护逻辑写好了，却没有任何地方能产生一条 owner 暂停，等于没有。
 */
final class RddStandbyTool implements NumenTool {

    @Override public String name() { return "rdd_standby"; }

    @Override public String description() {
        return "Owner-only: make the companion stand by (stop where it is) or release it."
                + " action='pause' = it stays put and will NOT be pushed by the supervisor,"
                + " NOT auto-resumed, and will NOT act on its own suggestions — only you can"
                + " release it. action='resume' = release it and it continues the current"
                + " task. Use pause when you want it to wait for you (about to rename something,"
                + " building a base, or you just want it to stay put). This is different from"
                + " the companion's own PAUSE, which means 'I cannot do this right now' and"
                + " does get re-evaluated.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "pause = stand by now (only you can release)."
                        + " resume = release the standby and continue.", "pause", "resume")
                .optionalString("note", "Optional human note, e.g. 'renaming the base'.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            String action = args != null && args.has("action") && !args.get("action").isJsonNull()
                    ? args.get("action").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                    : "pause";
            String note = args != null && args.has("note") && !args.get("note").isJsonNull()
                    ? args.get("note").getAsString() : "";
            RddRuntime rt = RddPlugin.runtime(companion.getUUID());
            if (rt == null) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "no active RDD task chain; nothing to stand by on.").toJson());
                return;
            }
            Subtask cur = rt.chain().currentSubtask();
            boolean ok = "resume".equals(action)
                    ? RddOwnerPause.resume(rt)
                    : RddOwnerPause.pause(rt, note);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", action);
            data.put("owner", true);
            data.put("subtask", cur == null ? "" : cur.id());
            data.put("status", rt.chain().currentSubtaskStatus() == null
                    ? "" : rt.chain().currentSubtaskStatus().name());
            if (ok) {
                RddPlugin.clearBody(companion.getUUID());
                RddPlugin.nudge(companion.getUUID(),
                        "resume".equals(action)
                                ? "[主人] 待命解除，继续当前任务「" + cur.description() + "」。"
                                : "[主人] 原地待命。在主人说「继续」之前不要推进这件事。");
                RddMonitor.publish("rdd_standby", Map.of(
                        "companionId", companion.getUUID().toString(),
                        "action", action, "subtask", data.get("subtask"), "note", note));
                reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                        "resume".equals(action) ? "standby released; continuing the current task"
                                : "standing by; only the owner can release it", data).toJson());
            } else {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "resume".equals(action)
                                ? "cannot resume: the current subtask is not paused (status="
                                  + data.get("status") + ")"
                                : "cannot stand by: the current subtask is "
                                  + data.get("status") + " (only RUNNING/PENDING/STALLED can be paused)",
                        data).toJson());
            }
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("rdd_standby failed: " + ex.getMessage()).toJson());
        }
    }
}
