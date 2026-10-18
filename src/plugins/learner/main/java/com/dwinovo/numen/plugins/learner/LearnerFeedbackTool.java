package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.FeedbackChannel;
import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_feedback}：<b>只读</b>地读任务链事件流（{@code monitor/rdd.jsonl}），
 * 把「执行结果」交给学习者。
 *
 * <p><b>这是 {@code 38} v3 B14 / v3.2 B22 的实现</b>。三条硬要求：
 * <ol>
 *   <li><b>只读</b> —— 本类只调 {@link FeedbackChannel}，而那个类<b>全类只以 Read 打开文件</b>
 *       （编译期保证，验收 V8 就是 grep 那里有没有写方法）；</li>
 *   <li><b>单向</b> —— 入参只有「从哪读、读到哪」，<b>没有「回给谁」</b>；</li>
 *   <li><b>不注入执行上下文</b> —— 结果只出现在本工具的返回值里，
 *       <b>不进</b> {@code contributeState}（那是给主 AI 的上下文，会变成「叙述变授权」）。</li>
 * </ol>
 *
 * <p><b>⚠️ 本工具是第 4 个工具，但红线不破</b>：{@code 38} B2 约束的是
 * 「<b>不得发身体指令</b>」，不是工具总数。本工具<b>纯只读</b>，
 * {@code tools/list} 里 learner 仍 grep 不到 mine/goto/attack/build。
 *
 * <p><b>拉取式游标</b>：调用方自己带 {@code cursor}（上次拿到的 {@code next_cursor}），
 * 首次传 0。这样学习者能按需取，而不是被推送。
 */
final class LearnerFeedbackTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** rdd.jsonl 的相对路径。<b>刻意不参数化</b> —— 通道只许读这一个已知名（S1 风险）。 */
    private static final String RDD_JSONL = "monitor/rdd.jsonl";

    @Override
    public String name() {
        return "learner_feedback";
    }

    @Override
    public String description() {
        return "READ-ONLY view of what actually happened on the task chain: subtask completed / failed / "
                + "stalled, dependency met, deaths, asset recovery. Use it when you are asked WHY something "
                + "happened, or before you write an experience - do not guess, look. "
                + "Pass the cursor you got last time (0 the first time) to get only what is new. "
                + "This tool never changes the world and never dispatches anything. "
                + "Fields are the source event's own values: a field that is absent means the source event "
                + "did not carry it - it does not mean zero.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("companion", "Companion name or id, from list_companions.")
                .optionalString("cursor", "Cursor returned as next_cursor by the previous call. 0 on the first call.")
                .optionalString("kinds", "Comma-separated kind filter: task, combat, death, asset, planning, other. "
                        + "Omit for all.")
                .optionalInteger("limit", "Max events to return (hard cap "
                        + FeedbackChannel.MAX_LINES_PER_PULL + ").", 1, FeedbackChannel.MAX_LINES_PER_PULL)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            long cursor = parseLong(in == null ? null : in.cursor(), 0L);
            int limit = (int) Math.max(1, Math.min(parseLong(in == null ? null : in.limit(),
                    (long) FeedbackChannel.MAX_LINES_PER_PULL), FeedbackChannel.MAX_LINES_PER_PULL));
            List<String> kinds = parseKinds(in == null ? null : in.kinds());

            Path dir = LearnerPlugin.configDir();
            if (dir == null) {
                reply.accept(TaskResult.fail("learner_feedback: plugin not set up").toJson());
                return;
            }
            Path jsonl = dir.resolve(RDD_JSONL);
            // 存档级代际：存档名 + session.lock mtime。换档 → 代际变 → 旧游标自动失效（v3.2 §2）
            // ⚠️ 这段路径推导**只写一次**，在 FeedbackChannel.generationForConfigDir 里。
            //   2026-10-01 实测教训（改一次只改一半）：configDir=<实例>/config/numen，
            //   要上两级才到 <实例>/saves；当时只改了这里的一处、别处没跟着改，
            //   结果存档名对了但 session.lock 读不到 → generation 变成 "live@-1"。
            String generation = FeedbackChannel.generationForConfigDir(dir);

            var pull = new FeedbackChannel.Pull();
            List<FeedbackEvent> events;
            try {
                events = FeedbackChannel.pull(jsonl, generation, cursor, kinds, pull).events;
            } catch (RuntimeException e) {
                // 通道自己已吞掉坏行；这里只接「文件被换掉/权限变化」这类真异常，如实报
                reply.accept(TaskResult.fail("learner_feedback read failed: " + e.getMessage()).toJson());
                return;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            int cap = Math.min(limit, events.size());
            List<Map<String, Object>> out = new ArrayList<>(cap);
            int payloadChars = 0;
            String stopReason = null;
            for (int i = 0; i < cap; i++) {
                // ⚠️ 2026-10-01 实机踩到：5 条事件的 observation 就有 83496 字符，
                // 撞 MCP 的 16384 字符包上限 → 整条回包被丢弃。
                // 所以这里**再加一道总量闸**，超了就停并如实说 stop_reason=payload_budget。
                Map<String, Object> one = events.get(i).toMap();
                int sz = String.valueOf(one).length();
                if (payloadChars + sz > FeedbackChannel.MAX_OBSERVATION_CHARS) {
                    stopReason = "payload_budget";
                    break;
                }
                payloadChars += sz;
                out.add(one);
            }
            if (out.isEmpty() && !events.isEmpty() && stopReason == null) {
                stopReason = "payload_budget";
            }

            data.put("source", RDD_JSONL);
            // readable=false 是**诚实的失败**（文件不在/读不了），与「读到 0 条」是不同的事
            data.put("readable", pull.readable);
            data.put("generation", generation);
            data.put("events_returned", out.size());
            data.put("events_available", events.size());
            data.put("next_cursor", pull.nextCursor);
            data.put("truncated", pull.truncated);
            // skipped 如实报「坏行被跳过」，不许静默丢（对齐 experience-guard 的写入闸精神）
            data.put("skipped_lines", pull.skipped);
            data.put("events", out);
            if (stopReason != null) {
                data.put("stop_reason", stopReason);
                data.put("payload_budget_chars", FeedbackChannel.MAX_OBSERVATION_CHARS);
                data.put("payload_hint", "narrow it with kinds= (task/combat/death/asset/planning) and a smaller cursor window");
            }
            data.put("note", "observation_summary is intentionally absent in this batch: "
                    + "the learner has not learned to summarise yet (38号v3 B14). "
                    + "observation fields are the source event's own values.");
            if (!pull.readable) {
                data.put("readable_why", "source file not found or unreadable at " + jsonl);
            }
            reply.accept(TaskResult.ok("feedback slice", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("learner_feedback failed: " + ex.getMessage()).toJson());
        }
    }

    private static List<String> parseKinds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static long parseLong(String raw, long dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private record Input(String companion, String cursor, String kinds, String limit) {}
}