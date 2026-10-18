package com.dwinovo.numen.experience.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutcomeCorrelator} 的钉子测试 —— E7 回流链路的「中间那一段」。
 *
 * <p>钉的是<b>四种状态不许互相塌陷</b>：
 * 没结果 / 主人叫停 / 不认识的 status / 真有判据 ——
 * 一旦塌成一种，「查不到」就会被读成「经验没问题」。</p>
 */
class OutcomeCorrelatorTest {

    private static final long T0 = 1_000_000L;

    private static PresentationReceipt.Shown shown(String id, long at) {
        return new PresentationReceipt.Shown(id, "标题-" + id, PresentationReceipt.SURFACE_RECALL_TOOL,
                true, 1.5d, at);
    }

    private static TaskOutcomeLog.Outcome outcome(String status, long finishedAt) {
        return new TaskOutcomeLog.Outcome("t-" + finishedAt, "goto", status,
                TaskOutcomeLog.Outcome.verdictOf(status), finishedAt, "ev-" + finishedAt);
    }

    @Test
    void aSucceededTaskCarriesRealEvidence() {
        List<OutcomeCorrelator.Matched> m = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(outcome("done", T0 + 5_000)));

        assertEquals(1, m.size());
        OutcomeCorrelator.Matched x = m.get(0);
        assertEquals(OutcomeCorrelator.State.OUTCOME_KNOWN, x.state());
        assertEquals("done", x.status());
        assertEquals(TaskOutcomeLog.Verdict.SUCCEEDED.name(), x.outcome());
        assertTrue(x.countsAsEvidence());
        assertEquals(T0 + 5_000, x.finishedAt());
    }

    @Test
    void aFailedTaskCarriesRealEvidenceToo() {
        OutcomeCorrelator.Matched x = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(outcome("interrupted", T0 + 1_000))).get(0);

        assertEquals(OutcomeCorrelator.State.OUTCOME_KNOWN, x.state(),
                "因为死了所以没成 ≠ 没成 —— 它仍是反例");
        assertEquals(TaskOutcomeLog.Verdict.FAILED.name(), x.outcome());
        assertTrue(x.countsAsEvidence());
    }

    // ★ 关键反证：主人叫停既没成也没被证伪。算成失败会加反例、连三次还能降级。
    @Test
    void anOwnerCancelledTaskIsNotRefutation() {
        OutcomeCorrelator.Matched x = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(outcome("stopped", T0 + 1_000))).get(0);

        assertEquals(OutcomeCorrelator.State.CANCELLED_NOT_EVIDENCE, x.state());
        assertFalse(x.countsAsEvidence(), "叫停不许算成反例");
        assertEquals("stopped", x.status(), "状态仍如实报出，不隐藏");
    }

    @Test
    void anUnfinishedTaskMeansNotKnownRatherThanFine() {
        OutcomeCorrelator.Matched x = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of()).get(0);

        assertEquals(OutcomeCorrelator.State.NO_OUTCOME_YET, x.state());
        assertFalse(x.countsAsEvidence());
        assertEquals("", x.status());
        assertEquals(0L, x.finishedAt(), "没有结果时不能编一个时间出来");
    }

    @Test
    void anOutcomeFromBeforeThePresentationIsNotItsResult() {
        // 呈现发生在任务收尾**之后** ⇒ 那次的结局不能说成这条经验被用上了。
        OutcomeCorrelator.Matched x = OutcomeCorrelator.match(
                List.of(shown("exp1", T0 + 10_000)),
                List.of(outcome("failed", T0 + 1_000))).get(0);

        assertEquals(OutcomeCorrelator.State.NO_OUTCOME_YET, x.state());
    }

    @Test
    void aTaskThatFinishedWayTooLateIsNotPaired() {
        OutcomeCorrelator.Matched x = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(outcome("failed", T0 + OutcomeCorrelator.MAX_CORRELATION_GAP_MILLIS + 1)))
                .get(0);

        assertEquals(OutcomeCorrelator.State.NO_OUTCOME_YET, x.state(),
                "隔了半小时的那次任务不是「它被用上的那次」");
    }

    @Test
    void theFirstTaskFinishingAfterThePresentationIsTheOneThatUsedIt() {
        // 内置大脑顺序跑任务：呈现后第一个收尾 = 它被用上的那次。
        List<OutcomeCorrelator.Matched> m = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(
                        outcome("done", T0 + 30_000),
                        outcome("failed", T0 + 2_000),
                        outcome("done", T0 + 9_000)));

        assertEquals(OutcomeCorrelator.State.OUTCOME_KNOWN, m.get(0).state());
        assertEquals(T0 + 2_000, m.get(0).finishedAt(), "入参乱序也要挑对那一次");
    }

    @Test
    void anUnknownStatusNeverBecomesAQuietVerdict() {
        List<OutcomeCorrelator.Matched> m = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(outcome("brand_new_state", T0 + 1_000)));

        assertEquals(1, m.size(), "不认识的状态也要出现在清单里，不许悄悄丢行");
        assertEquals(OutcomeCorrelator.State.UNKNOWN_STATUS, m.get(0).state());
        assertFalse(m.get(0).countsAsEvidence());
        assertEquals("brand_new_state", m.get(0).status());
    }

    @Test
    void eachPendingRowIsMatchedIndependently() {
        List<OutcomeCorrelator.Matched> m = OutcomeCorrelator.match(
                List.of(shown("a", T0), shown("b", T0 + 100_000), shown("c", T0 + 1_000)),
                List.of(outcome("failed", T0 + 2_000)));

        assertEquals(3, m.size(), "呈现几条就得回报几行，不能因为没有结果就少报");
        assertEquals(OutcomeCorrelator.State.OUTCOME_KNOWN, m.get(0).state());
        assertEquals(OutcomeCorrelator.State.NO_OUTCOME_YET, m.get(1).state());
        assertEquals(OutcomeCorrelator.State.OUTCOME_KNOWN, m.get(2).state());
    }

    @Test
    void theReadoutTellsEvidenceApartFromNoise() {
        // 四条呈现分处四个时间窗：一条真判据、一条主人叫停、一条不认识的状态、一条还没收尾。
        // （刻意不给同一时刻的多条呈现 —— 一次呈现只配「它之后第一个收尾的任务」，
        //  所以同一时刻的几条会共享同一个结局，那是设计，不是 bug。）
        List<OutcomeCorrelator.Matched> m = OutcomeCorrelator.match(
                List.of(
                        shown("a", T0),
                        shown("b", T0 + 10_000),
                        shown("c", T0 + 20_000),
                        shown("d", T0 + 30_000)),
                List.of(
                        outcome("done", T0 + 1_000),
                        outcome("stopped", T0 + 11_000),
                        outcome("mystery", T0 + 21_000)));

        Map<String, Object> r = OutcomeCorrelator.readout(m);
        assertEquals(4, r.get("pending_total"));
        assertEquals(1, r.get("outcome_known"));
        assertEquals(1, r.get("outcome_not_yet"));
        assertEquals(1, r.get("cancelled_not_evidence"));
        assertEquals(1, r.get("unknown_status"));
        assertEquals(1, r.get("counts_as_evidence"),
                "只有真判据算证据 —— 叫停与不认识的状态都不算");
        assertEquals("monitor/events.jsonl#task_finished", r.get("source"));
        assertTrue(String.valueOf(r.get("caveat")).contains("MCP"),
                "结构性上限必须随读数一起出去，否则「查不到」会被读成「没问题」");
    }

    @Test
    void severalExperiencesShownDuringOneTaskShareItsSingleOutcome() {
        List<OutcomeCorrelator.Matched> m = OutcomeCorrelator.match(
                List.of(shown("a", T0), shown("b", T0 + 10), shown("c", T0 + 20)),
                List.of(outcome("failed", T0 + 30)));

        assertEquals(3, m.size());
        for (OutcomeCorrelator.Matched x : m) {
            assertEquals(OutcomeCorrelator.State.OUTCOME_KNOWN, x.state());
            assertEquals(T0 + 30, x.finishedAt(), "同一次任务里呈现的几条共享同一个结局");
        }
        assertEquals(3, OutcomeCorrelator.readout(m).get("counts_as_evidence"));
    }

    @Test
    void nullAndEmptyInputsAreHandledWithoutCrashing() {
        assertEquals(0, OutcomeCorrelator.match(null, null).size());
        assertEquals(0, OutcomeCorrelator.match(List.of(), List.of()).size());
        // 空清单的呈现行本身跳过，不许造一条空行出来。
        List<OutcomeCorrelator.Matched> withNull = OutcomeCorrelator.match(
                java.util.Arrays.asList((PresentationReceipt.Shown) null),
                List.of(outcome("done", T0 + 1)));
        assertEquals(0, withNull.size());
        assertEquals(0, OutcomeCorrelator.readout(null).get("pending_total"));
    }

    @Test
    void theRowMapCarriesEverythingNeededToDecide() {
        Map<String, Object> row = OutcomeCorrelator.match(
                List.of(shown("exp1", T0)),
                List.of(outcome("failed", T0 + 2_000))).get(0).toMap();

        assertEquals("exp1", row.get("id"));
        assertEquals("OUTCOME_KNOWN", row.get("outcome_state"));
        assertEquals("failed", row.get("task_status"));
        assertEquals("FAILED", row.get("outcome"));
        assertEquals(Boolean.TRUE, row.get("counts_as_evidence"));
        assertEquals("goto", row.get("task"));
        assertEquals(T0, row.get("presented_at"));
    }
}