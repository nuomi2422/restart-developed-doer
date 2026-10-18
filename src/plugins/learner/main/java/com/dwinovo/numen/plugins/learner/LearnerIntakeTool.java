package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.CandidateGate;
import com.dwinovo.numen.plugins.learner.core.CandidateMemo;
import com.dwinovo.numen.plugins.learner.core.FeedbackChannel;
import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@code learner_intake}：读事件链（{@code monitor/instrumentation.jsonl}），
 * 用 {@link CandidateGate} 判「该不该进候选队列」，把判据成立的转成备忘录入队。
 *
 * <p><b>它填的是 E1 那一环</b>（{@code 60} 号 §3.2）：
 * 「{@code instrumentation.jsonl} 有事件，但『要不要记经验』这个判定<b>不存在</b>」。
 * 过去唯一的决定者是「干活 AI 恰好顺手写了一条」，现在这段判定在代码里、有读数。
 *
 * <p><b>三条不能破的线</b>：
 * <ol>
 *   <li><b>只读事件源</b> —— 读用 {@link FeedbackChannel}（那个类全类只以 Read 打开文件）。
 *       本工具唯一写的目标是<b>学习者自己的</b> {@code learner-memos-&lt;uuid&gt;.json}。</li>
 *   <li><b>不碰世界</b> —— 不发身体指令、不改方块、不派任务（同 {@code learner_feedback} 的红线）。</li>
 *   <li><b>不自己写经验库</b> —— 只把候选放进队列；提炼与落库仍是 AI 调
 *       {@code learner_review} → {@code experience_learn} 的事（{@code 59} D3）。</li>
 * </ol>
 *
 * <p><b>为什么是「AI 调才跑」而不是自动入队</b>：{@code 28} 号 §1 已否决「挂事件自动提炼」，
 * 三条实测理由（引擎侧订阅不到、result 被截到 300 字、要动引擎架构）。
 * 本工具<b>不违反</b>那条决议：被否决的是「自动<b>提炼</b>」，这里自动的只是<b>筛选</b>
 * —— {@code 59} §4.4 原话「大量筛选可以由程序完成，只有筛出来的少量目录才需要 AI 阅读。
 * 这是成本控制的主要来源」。<b>入队仍然要 AI 点一次</b>。
 *
 * <p><b>⚠️ T3（同一问题重复出现）的去重计数只活在本次 JVM 会话里</b>：
 * 重启游戏后从头数（{@link #SEEN} 是静态 map）。跨重启的重复不会被算成 T3 ——
 * 如实标注为 {@code t3_state=session_only}，不假装它是真代际计数。
 */
final class LearnerIntakeTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** instrumentation.jsonl 的相对路径。<b>刻意不参数化</b> —— 同 learner_feedback 的 S1 纪律。 */
    private static final String INSTRUMENTATION_JSONL = "monitor/instrumentation.jsonl";

    /** 单次入队的条数上限（防一次把 64 深的队列顶满）。 */
    private static final int MAX_ENQUEUE_PER_CALL = 16;

    /** 指纹计数表上限：超了就清空（宁可丢计数，也不要长成一坨内存）。 */
    private static final int MAX_SEEN_ENTRIES = 512;

    /** 指纹 → 本次会话内见过的次数。有界；重启即清零（诚实边界，见类注释）。 */
    private static final Map<String, Integer> SEEN = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "learner_intake";
    }

    @Override
    public String description() {
        return "Turn recorded failures into learner candidates, using the T1-T7 intake rules in code "
                + "(not your judgement) - call this when pending_memos is empty but things have been going "
                + "wrong, or before deciding a failure was 'just bad luck'. Reads the instrumentation event "
                + "stream, judges each event, and only the ones a rule can actually decide (a death, a "
                + "failed recovery, a retry loop, an asset mismatch) become memos. "
                + "Pass the cursor you got last time (0 the first time). "
                + "Rules T1/T4/T5/T6/T7 need a human or your own judgement and are reported as "
                + "'undecidable', never silently counted as passed - if one of those is the lesson, "
                + "write it with learner_note yourself. "
                + "This never changes the world and never dispatches anything.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("cursor", "Cursor returned as next_cursor by the previous call. 0 on the first call.")
                .optionalInteger("limit", "Max events to scan this call (hard cap "
                        + FeedbackChannel.MAX_LINES_PER_PULL + ").", 1, FeedbackChannel.MAX_LINES_PER_PULL)
                .optionalInteger("max_enqueue", "Max memos to enqueue this call (hard cap "
                        + MAX_ENQUEUE_PER_CALL + ").", 0, MAX_ENQUEUE_PER_CALL)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            long cursor = parseLong(nz(in == null ? null : in.cursor()), 0L);
            int limit = (int) Math.max(1, Math.min(
                    parseLong(nz(in == null ? null : in.limit()), (long) FeedbackChannel.MAX_LINES_PER_PULL),
                    FeedbackChannel.MAX_LINES_PER_PULL));
            int maxEnqueue = (int) Math.max(0, Math.min(
                    parseLong(nz(in == null ? null : in.max_enqueue()), (long) MAX_ENQUEUE_PER_CALL),
                    MAX_ENQUEUE_PER_CALL));

            Path dir = LearnerPlugin.configDir();
            if (dir == null) {
                reply.accept(TaskResult.fail("learner_intake: plugin not set up").toJson());
                return;
            }
            if (companion == null) {
                reply.accept(TaskResult.fail("learner_intake: no companion on this call").toJson());
                return;
            }
            UUID me = companion.getUUID();

            Path jsonl = dir.resolve(INSTRUMENTATION_JSONL);
            String generation = FeedbackChannel.generationForConfigDir(dir);
            var pull = new FeedbackChannel.Pull();
            List<FeedbackEvent> events;
            try {
                events = FeedbackChannel.pull(jsonl, generation, cursor, null, pull).events;
            } catch (RuntimeException e) {
                reply.accept(TaskResult.fail("learner_intake read failed: " + e.getMessage()).toJson());
                return;
            }

            MemoQueue queue = LearnerPlugin.queue(me);
            int scanned = 0;
            int otherCompanion = 0;
            int unattributed = 0;
            int rejected = 0;
            int enqueued = 0;
            int queueFull = 0;
            int triedBlank = 0;
            List<Map<String, Object>> decisions = new ArrayList<>();
            List<CandidateGate.Decision> gateCalls = new ArrayList<>();
            List<Map<String, Object>> queued = new ArrayList<>();

            for (FeedbackEvent ev : events) {
                if (limit <= 0) {
                    break;
                }
                scanned++;

                // 只喂「这个同伴自己的」事件。AI 的队列是按同伴隔离的，
                // 把别人的死亡写进它的队列 = 凭空造出一条没发生过的经历。
                String owner = companionOf(ev);
                if (owner == null) {
                    unattributed++;
                    continue;
                }
                if (!me.toString().equals(owner)) {
                    otherCompanion++;
                    continue;
                }

                String fp = CandidateGate.fingerprint(ev.sourceType(), ev.observation());
                int seenBefore = SEEN.getOrDefault(fp, 0);
                CandidateGate.Decision d = CandidateGate.evaluate(ev.sourceType(), ev.observation(), seenBefore);
                bumpSeen(fp);
                gateCalls.add(d);

                Map<String, Object> one = new LinkedHashMap<>();
                one.put("event_id", ev.eventId());
                one.put("source_type", ev.sourceType());
                one.put("fingerprint", fp);
                one.put("seen_before", seenBefore);
                one.put("gate", GSON.fromJson(d.explain(), Map.class));

                if (!d.enqueue()) {
                    rejected++;
                    decisions.add(one);
                    continue;
                }
                if (enqueued >= maxEnqueue) {
                    one.put("skipped", "max_enqueue reached this call; pass the cursor again to continue");
                    decisions.add(one);
                    continue;
                }

                Memo memo = CandidateMemo.from(ev, d);
                if (memo.tried().isBlank()) {
                    // 不为过质量门 Q1 而编一句「已尝试」：事件里没有就是没有（B21）。
                    // 代价是这条候选复盘时 Q1 会 FAIL —— 那是诚实的失败，如实计数报出去。
                    triedBlank++;
                }
                String memoId = "ci-" + ev.eventId();
                boolean ok;
                try {
                    ok = queue.append(new Memo(memoId, memo.problem(), memo.stage(), memo.tried(),
                            memo.snapshot(), System.currentTimeMillis()));
                } catch (RuntimeException e) {
                    reply.accept(TaskResult.fail("learner_intake cannot write memo queue: " + e.getMessage()).toJson());
                    return;
                }
                if (!ok) {
                    // 队列满 / 字段太长：不猜是哪一种，两种都如实报（同 LearnerNoteTool 的做法）
                    queueFull++;
                    one.put("not_queued", "queue full (" + MemoQueue.MAX_QUEUE + ") or field too long");
                    decisions.add(one);
                    continue;
                }
                enqueued++;
                one.put("memo_id", memoId);
                one.put("tried_source", memo.tried().isBlank() ? "absent" : "derived_from_event");
                decisions.add(one);
                queued.add(Map.of("memo_id", memoId, "source_type", ev.sourceType(),
                        "primary_trigger", String.valueOf(d.primaryTrigger())));
            }

            int depth;
            try {
                depth = queue.size();
            } catch (RuntimeException e) {
                depth = -1;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", INSTRUMENTATION_JSONL);
            data.put("readable", pull.readable);
            if (!pull.readable) {
                data.put("readable_why", "source file not found or unreadable at " + jsonl);
            }
            data.put("generation", generation);
            data.put("events_scanned", scanned);
            data.put("next_cursor", pull.nextCursor);
            data.put("truncated", pull.truncated);
            data.put("skipped_lines", pull.skipped);
            data.put("other_companion_events", otherCompanion);
            data.put("unattributed_events", unattributed);
            data.put("rejected_by_gate", rejected);
            data.put("enqueued", enqueued);
            data.put("not_queued_queue_full", queueFull);
            data.put("queue_depth", depth);
            data.put("t3_state", "session_only");
            // 这条计数是本工具存在的意义：让人看见「这批候选里有多少条注定过不了 Q1」
            data.put("enqueued_without_tried_evidence", triedBlank);
            if (triedBlank > 0) {
                data.put("q1_warning", triedBlank + " memo(s) have no 'what was tried' evidence, so "
                        + "ExperienceQualityGate Q1 (needs one complete event chain) will FAIL on them. "
                        + "That is reported, not hidden; a human/AI should add a learner_note instead.");
            }
            data.put("candidates", queued);
            data.put("decisions", decisions);
            data.put("gate_summary", CandidateGate.summaryOf(gateCalls));

            // 埋点：不埋就没人知道这条路跑没跑过（本项目的老教训——接口在、永远没人调用 = 门禁形同虚设）
            Map<String, Object> ev2 = new LinkedHashMap<>();
            ev2.put("companion", me.toString());
            ev2.put("source", INSTRUMENTATION_JSONL);
            ev2.put("events_scanned", scanned);
            ev2.put("rejected_by_gate", rejected);
            ev2.put("enqueued", enqueued);
            ev2.put("not_queued_queue_full", queueFull);
            ev2.put("queue_depth", depth);
            ev2.put("enqueued_without_tried_evidence", triedBlank);
            ev2.put("other_companion_events", otherCompanion);
            ev2.put("unattributed_events", unattributed);
            ev2.put("triggers", CandidateGate.summaryOf(gateCalls).get("trigger_first_why"));
            LearnerMonitor.publish("intake", ev2);
            data.put("note", "T1/T4/T5/T6/T7 are undecidable from events and are reported as such; "
                    + "use learner_note for those.");
            reply.accept(TaskResult.ok(enqueued > 0
                    ? "intake: " + enqueued + " candidate(s) queued for learner review"
                    : "intake: no event matched a decidable intake rule", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("learner_intake failed: " + ex.getMessage()).toJson());
        }
    }

    /** 这条事件属于哪个同伴；<b>两个键都认</b>（instrumentation 用 companionId，rdd 侧用 companion）。 */
    private static String companionOf(FeedbackEvent ev) {
        Object v = CandidateMemo.firstPresent(ev.observation(), "companionId");
        if (v == null) {
            v = CandidateMemo.firstPresent(ev.observation(), "companion");
        }
        if (v == null) {
            v = str(ev.subjectRef().get("companion"));
        }
        return str(v);
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static void bumpSeen(String fp) {
        if (SEEN.size() >= MAX_SEEN_ENTRIES) {
            SEEN.clear();
        }
        SEEN.merge(fp, 1, Integer::sum);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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

    private record Input(String cursor, String limit, String max_enqueue) {}
}