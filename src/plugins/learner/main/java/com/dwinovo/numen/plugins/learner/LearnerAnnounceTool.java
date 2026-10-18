package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.Announce;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_announce}：学习者「说话」的开关与限频（S3 附属，2026-10-05）。
 *
 * <p><b>为什么要有这个工具</b>：用户要「给白马一个可回话的地方」。
 * 一旦能说话，就必须能<b>闭嘴</b> —— 否则出问题时（复审频繁触发、
 * 或者某次拼出了一句胡话）没法止损。
 *
 * <p>所以：开关 + 限频都做成工具可调，且<b>每次改动都回显当前值</b>，
 * 免得「不知道现在是不是开着」变成新的查不到根因的哑故障。
 */
final class LearnerAnnounceTool implements NumenTool {

    @Override
    public String name() {
        return "learner_announce";
    }

    @Override
    public String description() {
        return "学习者回话的开关与限频。学习者复审完会主动说一句（只说事实：学了几条、"
                + "产物落了什么、下游拒了什么），默认开、同伴之间限频。"
                + "用这个工具可以随时闭嘴或调整间隔。";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.RESIDENT;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "status | enable | disable | set_gap",
                        "status", "enable", "disable", "set_gap")
                .optionalInteger("min_gap_seconds",
                        "Minimum seconds between two messages to the owner. 0 = no limit.", 0, 3600)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String action = str(args, "action", "status").trim().toLowerCase(java.util.Locale.ROOT);
        try {
            switch (action) {
                case "enable" -> Announce.setEnabled(true);
                case "disable" -> Announce.setEnabled(false);
                case "set_gap" -> {
                    Integer g = args != null && args.has("min_gap_seconds")
                            && args.get("min_gap_seconds").isJsonPrimitive()
                            ? args.get("min_gap_seconds").getAsInt() : null;
                    if (g == null) {
                        reply.accept(TaskResult.fail("set_gap 需要 min_gap_seconds（0 = 不限频）").toJson());
                        return;
                    }
                    Announce.setMinGapSeconds(g);
                }
                case "status" -> {
                    // 什么都不做，纯查询
                }
                default -> {
                    reply.accept(TaskResult.fail(
                            "action 只认 status/enable/disable/set_gap，收到: " + action).toJson());
                    return;
                }
            }
            // ★ 每次都回显当前值：改了之后必须能确认「现在到底是什么状态」
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", Announce.enabled());
            data.put("min_gap_seconds", Announce.minGapSeconds());
            data.put("scope", "per companion（不同同伴互不影响）");
            data.put("says", "只说事实（学了几条/落了什么/拒了什么），不写自评");
            reply.accept(TaskResult.ok("learner_announce " + action, data).toJson());
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("learner_announce " + action + " 失败: " + e.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String k, String def) {
        if (args == null || !args.has(k) || args.get(k).isJsonNull() || !args.get(k).isJsonPrimitive()) {
            return def;
        }
        return args.get(k).getAsString();
    }
}