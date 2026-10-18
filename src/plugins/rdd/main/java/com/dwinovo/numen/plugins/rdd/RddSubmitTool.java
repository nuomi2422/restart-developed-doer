package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.BodyInstruction;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Creates the first explicit RDD chain from a bounded tool request. */
final class RddSubmitTool implements NumenTool {
    private static final Gson GSON = new Gson();

    @Override public String name() { return "rdd_submit"; }
    @Override public String description() {
        return "Submit a bounded RDD asset task chain. Required: goal, primary_goal, subtask, asset_key, minimum. "
                + "Optional task_type + args also drive the maid's body for this subtask. "
                + "Optional mode decides how asset_key is judged. DEFAULT (omit mode entirely) = hold: "
                + "she must HOLD minimum right now -- a hard reading of her inventory, satisfied the moment "
                + "the item is really there. Use hold for anything she MAKES or PRODUCES (craft/equip/build): "
                + "making the thing IS the achievement, so 'she has it' is the real completion. "
                + "acquire = she must GAIN minimum during this subtask; use it ONLY for collect/farm style "
                + "tasks where she may already be holding a pile ('gather 10 more seeds' would otherwise "
                + "complete instantly when she already holds 33). Do NOT use acquire for craft/produce goals.";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("goal", "Overall goal description.")
                .string("primary_goal", "Current primary goal description.")
                .string("subtask", "Current executable subtask description.")
                .string("asset_key", "Asset key used by the hard-coded observation condition.")
                .integer("minimum", "Minimum observed count required.", 0, Integer.MAX_VALUE)
                .optionalEnum("mode",
                        "How asset_key is judged. OMIT IT unless you truly need acquire: the default hold is a "
                                + "hard inventory reading (count >= minimum) and is right for craft/equip/build "
                                + "goals, where making the thing IS the achievement. "
                                + "acquire counts only the GAIN since this subtask started; use it for collect/farm "
                                + "tasks where she may already hold a pile. Never use acquire for craft/produce.",
                        com.dwinovo.numen.rdd.core.HardCodedEvaluator.MODE_HOLD,
                        com.dwinovo.numen.rdd.core.HardCodedEvaluator.MODE_ACQUIRE)
                .optionalString("task_type", "Optional body tool to drive. RDD 词表内(mine/craft/equip_item/collect_items)会被自动翻译参数; 也可指名任意真实注册 NUMEN 工具原样驱动.")
                .optionalString("args", "Optional JSON object string of body-tool arguments, e.g. {\"item\":\"minecraft:oak_log\",\"count\":5}.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input input = GSON.fromJson(args, Input.class);
            if (input == null || blank(input.goal()) || blank(input.primary_goal()) || blank(input.subtask())
                    || blank(input.asset_key()) || input.minimum() < 0) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "rdd_submit requires goal, primary_goal, subtask, asset_key and non-negative minimum").toJson());
                return;
            }
            // mode 缺省 = hold：不传时行为与本改动前逐字一致。
            // 拼进 condition 前先过一遍 modeOf：非法值显式拒绝，不静默降级成 hold
            // （那正是 7.4#2 假完成那一族 —— 拼错的键悄悄变成另一种语义）。
            var condition = new java.util.LinkedHashMap<String, Object>();
            condition.put("asset_key", input.asset_key());
            condition.put("minimum", input.minimum());
            if (!blank(input.mode())) {
                if (com.dwinovo.numen.rdd.core.HardCodedEvaluator.modeOf(
                        java.util.Map.of("asset_key", input.asset_key(), "mode", input.mode())) == null) {
                    reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                            "rdd_submit mode must be 'hold' or 'acquire', got: " + input.mode()).toJson());
                    return;
                }
                condition.put("mode", input.mode().trim());
            }
            String primaryId = "primary-" + companion.getUUID();
            String subtaskId = "subtask-" + companion.getUUID();
            var subtask = Subtask.hardCoded(subtaskId, input.subtask(), condition, parseBody(input));
            var primary = new com.dwinovo.numen.rdd.api.PrimaryGoal(primaryId, input.primary_goal(),
                    java.util.List.of(subtask));
            var goal = new com.dwinovo.numen.rdd.api.Goal("goal-" + companion.getUUID(), input.goal(),
                    java.util.List.of(primary));
            RddPlugin.bind(companion.getUUID(), goal);
            RddRuntime runtime = RddPlugin.runtime(companion.getUUID());
            // 必须用带 counts 的重载锁 acquire 基线（2026-10-02 实测事故）：
            // 无参 startCurrent() 不锁基线 → mode=acquire 恒 false → 真做出来也判不出成功
            // → 被 RddStallWatcher 当卡死 → 判失败并逼模型重做一遍。详见 TaskChain.startCurrentWithCounts。
            Map<String, Integer> baselineCounts = null;
            try {
                baselineCounts = RddPlugin.planningSnapshot(companion.getUUID()).availableCounts();
            } catch (RuntimeException ignored) {
                // 拿不到背包不阻断派活：基线记空表，判定会退化成"按持有量算"，不会卡死任务
            }
            runtime.startCurrentWithCounts(baselineCounts);
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain accepted and started",
                    Map.of("goal", goal.id(), "primary_goal", primaryId, "subtask", subtaskId,
                            "detection_mode", "HARD_CODED",
                            "task_type", subtask.body() == null ? "" : subtask.body().taskType())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("invalid RDD task chain: " + ex.getMessage()).toJson());
        }
    }

    /** task_type 可选；给了才挂身体指令。args 是 JSON 字符串，坏 JSON 则忽略（工具用缺省参数）。 */
    private static BodyInstruction parseBody(Input input) {
        if (blank(input.task_type())) {
            return null;
        }
        Map<String, Object> argsMap = new LinkedHashMap<>();
        if (!blank(input.args())) {
            try {
                JsonObject parsed = GSON.fromJson(input.args(), JsonObject.class);
                if (parsed != null) {
                    for (var entry : parsed.entrySet()) {
                        argsMap.put(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
                    }
                }
            } catch (RuntimeException ex) {
                // 坏的 args JSON：身体工具会用缺省参数
            }
        }
        return new BodyInstruction(input.task_type(), argsMap);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Input(String goal, String primary_goal, String subtask, String asset_key, int minimum,
                         String mode,
                         String task_type, String args) {}
}
