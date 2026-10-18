package com.dwinovo.numen.experience.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「哪条经验被呈现过」与「那次任务后来成没成」对上号 —— E7 回流链路的<b>中间那一段</b>。
 *
 * <p><b>纯函数、零 IO、零状态</b>：两个入参分别来自
 * {@link PresentationReceipt#pendingReports()}（会话内呈现记录）与
 * {@link TaskOutcomeLog#pull}（{@code events.jsonl} 里的任务收尾）。
 * 所以「AI 该回报谁、依据是什么」这件事完全可单测，不需要游戏。</p>
 *
 * <p><b>配对规则（刻意极简）</b>：一次呈现发生在 {@code atMillis}，
 * 就去认<b>它之后第一个结束的任务</b>（{@code finishedAt >= atMillis}）
 * —— 内置大脑一次只跑一个任务槽，所以「呈现之后第一个收尾」就是
 * 「它被用上的那次任务」。找不到、或那个任务结束得太久之后
 * （超过 {@link #MAX_CORRELATION_GAP_MILLIS}），一律报
 * {@link State#NO_OUTCOME_YET}，<b>不猜、不拿上一次任务的结局来顶</b>。</p>
 *
 * <p><b>⚠️ 「没有结果」与「结果不是证据」是三件事，本类用四个状态分开</b>：
 * <ul>
 *   <li>{@link State#NO_OUTCOME_YET} —— 那次任务还没收尾，或者压根不是内置大脑派的
 *       （MCP/外部驱动结构性地不产生 {@code task_finished}）。<b>不是证据，也不是反证。</b></li>
 *   <li>{@link State#CANCELLED_NOT_EVIDENCE} —— 收尾了，但 {@code stopped}＝主人自己叫停。
 *       它既没成也没被证伪；算成失败会给不相干经验加反例，连三次还能降级。</li>
 *   <li>{@link State#UNKNOWN_STATUS} —— 出现了本类不认识的 {@code status} 取值。
 *       新增枚举时必须在这里显式承认，而不是悄悄归到失败。</li>
 *   <li>{@link State#OUTCOME_KNOWN} —— 才有真判据，
 *       {@link TaskOutcomeLog.Verdict#countsAsEvidence()} 为 true。</li>
 * </ul></p>
 *
 * <p><b>本类不做任何写盘决定。</b>它只把「依据」摆出来 ——
 * 回报哪一条仍然由 AI 判断（59 号 D3：发布/判定权不在自动通道）。
 * 同 B8 的纪律：<b>绝不批量一刀切</b>。</p>
 */
public final class OutcomeCorrelator {

    /** 呈现之后多久结束的任务还算「它被用上的那次」。超出就报没有结果。 */
    public static final long MAX_CORRELATION_GAP_MILLIS = 30L * 60L * 1000L;

    /** 提醒 AI 与人：查不到结果不等于没发生。 */
    public static final String CAVEAT =
            "task_finished is only emitted by the built-in brain; MCP/external-driven tasks "
                    + "and death interruptions never appear here. No outcome means NOT KNOWN, "
                    + "not 'the experience was fine'.";

    private OutcomeCorrelator() {
    }

    /** 一条待回报呈现，配上它对应的那次任务结果。 */
    public enum State {
        /** 配到了成/败（两者都算证据）。 */
        OUTCOME_KNOWN,
        /** 还没结束，或不是内置大脑派的任务。 */
        NO_OUTCOME_YET,
        /** 收尾了但主人叫停，不算证据。 */
        CANCELLED_NOT_EVIDENCE,
        /** 出现了不认识的 status。 */
        UNKNOWN_STATUS
    }

    /** 配对结果。 */
    public record Matched(String id, String title, String surface, long presentedAt,
                          State state, String status, String outcome, boolean countsAsEvidence,
                          String taskId, String taskName, long finishedAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("title", title);
            out.put("surface", surface);
            out.put("presented_at", presentedAt);
            out.put("outcome_state", state.name());
            out.put("task_status", status == null ? "" : status);
            out.put("outcome", outcome == null ? "" : outcome);
            out.put("counts_as_evidence", countsAsEvidence);
            out.put("task_id", taskId == null ? "" : taskId);
            out.put("task", taskName == null ? "" : taskName);
            out.put("finished_at", finishedAt);
            return out;
        }
    }

    /** 逐条配对（入参顺序即返回顺序 = 待回报清单的呈现序）。 */
    public static List<Matched> match(List<PresentationReceipt.Shown> pending,
                                      List<TaskOutcomeLog.Outcome> outcomes) {
        List<Matched> out = new ArrayList<>();
        // ★ 必须 new ArrayList<>() 而不是 List.of()：List.of() 不可变，
        //   紧接着 sort() 会抛 UnsupportedOperationException（被单测抓到过一次）。
        List<TaskOutcomeLog.Outcome> sorted = outcomes == null
                ? new ArrayList<>()
                : new ArrayList<>(outcomes);
        sorted.sort(Comparator.comparingLong(TaskOutcomeLog.Outcome::finishedAtMillis));
        for (PresentationReceipt.Shown s : pending == null ? List.<PresentationReceipt.Shown>of() : pending) {
            if (s == null) {
                continue;
            }
            out.add(one(s, firstFinishedAfter(sorted, s.atMillis())));
        }
        return List.copyOf(out);
    }

    /** 汇总读数：四种状态各多少条，以及「其中真能当判据的有几条」。 */
    public static Map<String, Object> readout(List<Matched> matched) {
        int known = 0;
        int evidence = 0;
        int unknown = 0;
        int cancelled = 0;
        int pending = 0;
        for (Matched m : matched == null ? List.<Matched>of() : matched) {
            if (m.state() == State.OUTCOME_KNOWN) {
                known++;
            } else if (m.state() == State.CANCELLED_NOT_EVIDENCE) {
                cancelled++;
            } else if (m.state() == State.UNKNOWN_STATUS) {
                unknown++;
            } else {
                pending++;
            }
            if (m.countsAsEvidence()) {
                evidence++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending_total", matched == null ? 0 : matched.size());
        out.put("outcome_known", known);
        out.put("outcome_not_yet", pending);
        out.put("cancelled_not_evidence", cancelled);
        out.put("unknown_status", unknown);
        out.put("counts_as_evidence", evidence);
        out.put("source", TaskOutcomeLog.RELATIVE_PATH + "#" + TaskOutcomeLog.TYPE_TASK_FINISHED);
        out.put("caveat", CAVEAT);
        return out;
    }

    private static Matched one(PresentationReceipt.Shown s, TaskOutcomeLog.Outcome o) {
        if (o == null) {
            return new Matched(s.id(), s.title(), s.surface(), s.atMillis(),
                    State.NO_OUTCOME_YET, "", "", false, "", "", 0L);
        }
        State state;
        if (o.verdict() == null) {
            state = State.UNKNOWN_STATUS;
        } else if (o.verdict() == TaskOutcomeLog.Verdict.CANCELLED) {
            state = State.CANCELLED_NOT_EVIDENCE;
        } else {
            state = State.OUTCOME_KNOWN;
        }
        return new Matched(s.id(), s.title(), s.surface(), s.atMillis(),
                state, o.status(), o.verdict() == null ? "" : o.verdict().name(),
                state == State.OUTCOME_KNOWN, o.taskId(), o.taskName(), o.finishedAtMillis());
    }

    /** 「呈现之后第一个收尾」；太久的当没有。 */
    private static TaskOutcomeLog.Outcome firstFinishedAfter(List<TaskOutcomeLog.Outcome> sorted, long at) {
        for (TaskOutcomeLog.Outcome o : sorted) {
            long gap = o.finishedAtMillis() - at;
            if (gap >= 0 && gap <= MAX_CORRELATION_GAP_MILLIS) {
                return o;
            }
            if (gap > MAX_CORRELATION_GAP_MILLIS) {
                break;
            }
        }
        return null;
    }
}