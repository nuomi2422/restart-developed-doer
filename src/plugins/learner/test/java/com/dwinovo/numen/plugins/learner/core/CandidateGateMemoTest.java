package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CandidateMemo} 的测试：钉的是「<b>不许编</b>」这条纪律，不是文案。
 */
class CandidateGateMemoTest {

    private static FeedbackEvent event(String type, Map<String, Object> obs) {
        return new FeedbackEvent("e1", "live@1", "2026-10-03T00:00:00Z", type, "death",
                Map.of(), obs, null);
    }

    @Test
    void triedStaysBlankWhenTheEventCarriesNoAttemptEvidence() {
        FeedbackEvent ev = event("death", Map.of("task", "mine stone", "deathCauseId", "genericKill"));
        CandidateGate.Decision d = CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0);
        Memo m = CandidateMemo.from(ev, d);
        assertTrue(m.tried().isBlank(),
                "an event with no attempt info must not get an invented 'what was tried': " + m.tried());
    }

    @Test
    void triedIsFilledOnlyFromWhatTheEventActuallyCarries() {
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("task", "mine stone");
        obs.put("context.attempts", "3");
        FeedbackEvent ev = event("loop_detected", obs);
        CandidateGate.Decision d = CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0);
        Memo m = CandidateMemo.from(ev, d);
        assertTrue(m.tried().contains("3"), m.tried());
        assertEquals("loop_detected", m.stage());
    }

    /** B6 的死亡快照带了「死之前发生过多少条事件」——那是有据可查的「试过什么」。 */
    @Test
    void aDeathTraceCountCountsAsRealAttemptEvidence() {
        FeedbackEvent ev = event("death", Map.of("recent_trace.count", "12"));
        CandidateGate.Decision d = CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0);
        Memo m = CandidateMemo.from(ev, d);
        assertTrue(m.tried().contains("12"), m.tried());
    }

    /**
     * 死亡意味着血量 0，但那仍是<b>推断</b>。
     * 补一个 hp=0 会让 Q3（一次高价值失败/死亡）凭空 PASS —— 门禁一旦靠推断变绿就失去意义。
     */
    @Test
    void noHpIsInventedForADeath() {
        FeedbackEvent ev = event("death", Map.of("task", "mine stone"));
        Memo m = CandidateMemo.from(ev, CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0));
        assertFalse(m.snapshot().contains("hp="), "must not fabricate hp: " + m.snapshot());
        assertNull(ExperienceQualityGate.hpFromSnapshot(m.snapshot()),
                "and the gate must still see hp as unreadable");
    }

    /** 快照是压平后的观测事实 ⇒ Q1 的「有没有事实」这一半天然成立。 */
    @Test
    void theSnapshotCarriesRealFactsSoQ1CanReadIt() {
        FeedbackEvent ev = event("death", Map.of("task", "mine stone", "deathAttacker", "zombie"));
        Memo m = CandidateMemo.from(ev, CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0));
        assertTrue(ExperienceQualityGate.snapshotReadable(m.snapshot()), m.snapshot());
    }

    @Test
    void problemQuotesOnlyWhatTheEventCarried() {
        FeedbackEvent ev = event("death", Map.of("task", "mine stone", "deathAttacker", "zombie"));
        Memo m = CandidateMemo.from(ev, CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0));
        assertTrue(m.problem().contains("mine stone"), m.problem());
        assertTrue(m.problem().contains("zombie"), m.problem());
        assertTrue(m.problem().startsWith("[T2/death]"), "the trigger must be visible on the memo: " + m.problem());
    }

    @Test
    void anEventWithNoAttributionSaysSoRatherThanFillingIt() {
        FeedbackEvent ev = event("recovery_failed", Map.of());
        Memo m = CandidateMemo.from(ev, CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0));
        assertTrue(m.problem().contains("没有带任何归因字段"), m.problem());
    }

    /** 生产里键是 camelCase 点路径；按扁平名取值会永远读不到。 */
    @Test
    void dottedCamelCaseKeysAreReadTheWayTheRealEventCarriesThem() {
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("context.deathAttacker", "player");
        obs.put("task", "mine obsidian");
        FeedbackEvent ev = event("death", obs);
        Memo m = CandidateMemo.from(ev, CandidateGate.evaluate(ev.sourceType(), ev.observation(), 0));
        assertTrue(m.problem().contains("player"), m.problem());
    }
}