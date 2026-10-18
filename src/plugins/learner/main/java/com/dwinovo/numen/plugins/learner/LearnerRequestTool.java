package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.SelfCompileRequests;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_request}：自编译请求的<b>取件口</b>。
 *
 * <p><b>它补的是「没人消费」这条边</b>：请求落到投递箱后，此前既没人领、也没人知道
 * 是否被处理过。现在有明确的列出 / 认领 / 标注三步，且每一步都留痕。
 *
 * <p>★ <b>这个工具不写代码</b>。它只做流程：谁领了、领了做什么、结果如何。
 * 真正的改动由外层工程流按 {@code run-mutation.ps1} 走 ——
 * 理由同 B11：外层已有 MutationId 与门禁，学习者再自动化一条写码路径就是两套并存。
 */
final class LearnerRequestTool implements NumenTool {

    @Override
    public String name() {
        return "learner_request";
    }

    @Override
    public String description() {
        return "自编译请求的取件口：list=看所有请求及认领状态，unclaimed=只看没人领的，"
                + "take=认领（必须给 taken_by），resolve=标注处理完（必须给 note）。"
                + "★ 这里**不写代码、不编译**：改代码由外层工程流按 run-mutation 走。";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "list | unclaimed | take | resolve",
                        "list", "unclaimed", "take", "resolve")
                .optionalString("artifact_id", "Which request. Required for take/resolve.")
                .optionalString("taken_by", "Who takes it. REQUIRED for take — no owner = nobody owns it.")
                .optionalString("note", "What was done / result. REQUIRED for resolve.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String action = str(args, "action", "list").trim().toLowerCase(java.util.Locale.ROOT);
        try {
            var store = LearnerPlugin.selfCompileRequests();
            if (store == null) {
                reply.accept(TaskResult.fail("自编译请求取件口未初始化（插件 setup 没跑完？）").toJson());
                return;
            }
            switch (action) {
                case "list" -> {
                    Map<String, Object> data = new LinkedHashMap<>(
                            SelfCompileRequests.toMap(store.list()));
                    data.put("ack_dir", store.ackDir().toString());
                    reply.accept(TaskResult.ok("自编译请求清单", data).toJson());
                }
                case "unclaimed" -> reply.accept(TaskResult.ok("还没人认领的自编译请求",
                        SelfCompileRequests.toMap(store.unclaimed())).toJson());
                case "take", "resolve" -> {
                    String id = str(args, "artifact_id", "").trim();
                    if (id.isBlank()) {
                        reply.accept(TaskResult.fail(action + " 需要 artifact_id（先 list）").toJson());
                        return;
                    }
                    SelfCompileRequests.AckState st = "take".equals(action)
                            ? SelfCompileRequests.AckState.TAKEN : SelfCompileRequests.AckState.RESOLVED;
                    // 认领人：显式参数优先；否则用同伴 id（服务端取，不接受调用方编造同伴）
                    String by = str(args, "taken_by", "").trim();
                    if (by.isBlank() && companion != null) {
                        by = companion.getUUID().toString();
                    }
                    var r = store.ack(id, by, st, str(args, "note", ""));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("artifact_id", r.artifactId());
                    data.put("state", r.state().name());
                    data.put("taken_by", r.takenBy());
                    data.put("note", r.note());
                    data.put("acked_at", r.ackedAt());
                    data.put("reminder", "★ 认领只是流程标记，**不会**触发任何写码或编译");
                    reply.accept(TaskResult.ok("已" + ("take".equals(action) ? "认领" : "标注完成") + "："
                            + r.artifactId(), data).toJson());
                }
                default -> reply.accept(TaskResult.fail(
                        "action 只认 list/unclaimed/take/resolve，收到: " + action).toJson());
            }
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("learner_request " + action + " 失败: " + e.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String k, String def) {
        if (args == null || !args.has(k) || args.get(k).isJsonNull() || !args.get(k).isJsonPrimitive()) {
            return def;
        }
        return args.get(k).getAsString();
    }
}