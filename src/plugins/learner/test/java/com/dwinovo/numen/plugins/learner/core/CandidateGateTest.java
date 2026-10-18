package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CandidateGate} 的判据测试（E1：入队判定，59 §4.1 的 T1–T7）。
 *
 * <p><b>钉的是槽位不是措辞</b>：断言落在「哪一条判据、什么状态、为什么」上，
 * 不是「返回的字符串里出现过某个词」。
 */
class CandidateGateTest {

    private static CandidateGate.Status statusOf(CandidateGate.Decision d, String id) {
        for (CandidateGate.Trigger t : d.triggers()) {
            if (t.id().equals(id)) {
                return t.status();
            }
        }
        throw new AssertionError("no trigger " + id + " in " + d.triggers());
    }

    private static String whyOf(CandidateGate.Decision d, String id) {
        for (CandidateGate.Trigger t : d.triggers()) {
            if (t.id().equals(id)) {
                return t.why();
            }
        }
        throw new AssertionError("no trigger " + id + " in " + d.triggers());
    }

    @Test
    void aDeathIsT2AndQueues() {
        CandidateGate.Decision d = CandidateGate.evaluate("death",
                Map.of("task", "mine stone", "deathCauseId", "genericKill"), 0);
        assertTrue(d.enqueue(), "death must be an intake trigger (59 T2): " + d.reason());
        assertEquals("T2", d.primaryTrigger());
        assertEquals(CandidateGate.Status.MATCH, statusOf(d, "T2"));
        assertEquals(CandidateGate.Status.NO_MATCH, statusOf(d, "T3"), "first sighting is not yet a pattern");
    }

    /**
     * 「饿死」与「被打死」必须分开判：B6 之前的 {@code kindOf} 把两者压成同一个 death 家族，
     * 而 RddPlugin:684-696 特意拆成两个 type 就是因为归因方向相反。
     */
    @Test
    void starvationDeathIsDistinguishedFromBeingKilled() {
        CandidateGate.Decision starving = CandidateGate.evaluate("starvation_death",
                Map.of("deathKind", "starvation"), 0);
        CandidateGate.Decision killed = CandidateGate.evaluate("death",
                Map.of("deathAttacker", "zombie"), 0);
        assertTrue(starving.enqueue());
        assertTrue(killed.enqueue());
        assertFalse(whyOf(starving, "T2").equals(whyOf(killed, "T2")),
                "the two death types must not share one explanation");
    }

    @Test
    void theSecondSightingOfTheSameFingerprintIsT3() {
        String obs = "death|deathcauseid=generickill";
        CandidateGate.Decision first = CandidateGate.evaluate("death", Map.of("deathCauseId", "genericKill"), 0);
        assertEquals(CandidateGate.Status.NO_MATCH, statusOf(first, "T3"));
        CandidateGate.Decision second = CandidateGate.evaluate("death", Map.of("deathCauseId", "genericKill"), 1);
        assertEquals(CandidateGate.Status.MATCH, statusOf(second, "T3"));
        assertTrue(second.enqueue());
        // 主触发仍是 T2（按编号序先命中），T3 是附加的「这是模式不是意外」
        assertEquals("T2", second.primaryTrigger());
        assertTrue(whyOf(second, "T3").contains("2 次"), "the count must be reported: " + whyOf(second, "T3"));
    }

    /** 59 §4.2：不为琐碎单步入队。事件名叫 resource_waste 不等于真的浪费了东西。 */
    @Test
    void resourceWasteWithNothingWastedDoesNotQueue() {
        CandidateGate.Decision d = CandidateGate.evaluate("resource_waste",
                Map.of("gatheredBeyondMinimum", "0", "minimumAlreadyMet", "true"), 0);
        assertFalse(d.enqueue(), "zero waste is not a loss: " + d.reason());
        assertEquals(CandidateGate.Status.NO_MATCH, statusOf(d, "T2"));
    }

    @Test
    void resourceWasteWithARealAmountQueues() {
        CandidateGate.Decision d = CandidateGate.evaluate("resource_waste",
                Map.of("gatheredBeyondMinimum", "17"), 0);
        assertTrue(d.enqueue());
        assertEquals(CandidateGate.Status.MATCH, statusOf(d, "T2"));
    }

    /**
     * 钉住<b>生产里的真实形状</b>：{@code RddSurplus} 把数写在 {@code context} 里，
     * 经 flatten 后键是带点路径的 camelCase {@code context.gatheredBeyondMinimum}。
     * 只按扁平键名 {@code gatheredBeyondMinimum} 查会永远读不到 ⇒ T2 永远 NO_MATCH，
     * 而测试还绿着（正是本项目最贵的那种错）。所以两种形状都要测。
     */
    @Test
    void theWastedAmountIsFoundUnderItsDottedPathToo() {
        CandidateGate.Decision dotted = CandidateGate.evaluate("resource_waste",
                Map.of("context.gatheredBeyondMinimum", "17"), 0);
        assertTrue(dotted.enqueue(), "dotted path must be recognised: " + dotted.reason());
        CandidateGate.Decision dottedZero = CandidateGate.evaluate("resource_waste",
                Map.of("context.gatheredBeyondMinimum", "0"), 0);
        assertFalse(dottedZero.enqueue(), "zero waste is not a loss even on the dotted path");
    }

    /** 「检测器被改了」不是同伴的教训，入队只会污染队列。 */
    @Test
    void instrumentationChangeIsNotACandidate() {
        CandidateGate.Decision d = CandidateGate.evaluate("instrumentation_change", Map.of(), 0);
        assertFalse(d.enqueue(), d.reason());
        assertEquals(CandidateGate.Status.NO_MATCH, statusOf(d, "T2"));
    }

    /** 认不出来的类型不许编一个看起来对的家族（同 FeedbackChannel.kindOf 的纪律）。 */
    @Test
    void anUnknownEventTypeIsUndecidableRatherThanGuessed() {
        CandidateGate.Decision d = CandidateGate.evaluate("brand_new_kind", Map.of(), 0);
        assertFalse(d.enqueue());
        assertEquals(CandidateGate.Status.UNDECIDABLE, statusOf(d, "T2"));
        assertTrue(whyOf(d, "T2").contains("brand_new_kind"), "must name the type it did not recognise");
    }

    /**
     * ★ 五条程序判不了的必须<b>每次都</b>报 UNDECIDABLE。
     * 只报 MATCH 的门会把「我没查」显示成「查过了」——那是本工程最贵的错。
     */
    @Test
    void theFiveUndecidableRulesAreNeverSilentlyPassed() {
        CandidateGate.Decision d = CandidateGate.evaluate("death", Map.of("task", "mine"), 0);
        for (String id : List.of("T1", "T4", "T5", "T6", "T7")) {
            assertEquals(CandidateGate.Status.UNDECIDABLE, statusOf(d, id), id + " must stay undecidable");
            assertNotNull(whyOf(d, id));
            assertFalse(whyOf(d, id).isBlank(), id + " must say who could decide it");
        }
    }

    @Test
    void everyDecisionReportsAllSevenRulesSoNoneCanBeSkipped() {
        CandidateGate.Decision d = CandidateGate.evaluate("death", Map.of(), 0);
        assertEquals(7, d.triggers().size());
        assertEquals(List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7"),
                d.triggers().stream().map(CandidateGate.Trigger::id).toList());
    }

    @Test
    void explainSeparatesMatchFromUndecidable() {
        CandidateGate.Decision d = CandidateGate.evaluate("death", Map.of(), 0);
        var o = d.explain();
        assertTrue(o.get("enqueue").getAsBoolean());
        assertEquals("T2", o.get("primary_trigger").getAsString());
        assertEquals(1, o.get("match").getAsInt());
        assertEquals(5, o.get("undecidable").getAsInt());
        assertNotNull(o.get("undecidable_means").getAsString());
    }

    /**
     * 指纹不能宽到「所有死亡算同一类」——那会让一次意外立刻变成模式。
     * 也没有归因字段时<b>不</b>退回「同类型即同一个」。
     */
    @Test
    void fingerprintSeparatesCausesAndDoesNotCollapseOnMissingFields() {
        String a = CandidateGate.fingerprint("death", Map.of("deathCauseId", "genericKill"));
        String b = CandidateGate.fingerprint("death", Map.of("deathCauseId", "zombie_attack"));
        String c = CandidateGate.fingerprint("death", Map.of());
        assertFalse(a.equals(b), "different causes are different problems: a=" + a + " b=" + b);
        assertTrue(c.startsWith("death"), "type is always the prefix: " + c);
        assertFalse(a.equals(c), "no attribution field must not collapse into 'same as any death'");
        assertEquals(a, CandidateGate.fingerprint("DEATH", Map.of("deathCauseId", "genericKill")),
                "fingerprint must be case-insensitive");
    }

    /**
     * 生产里的键是 <b>camelCase 点路径</b>（{@code context.deathAttacker}）。
     * 按小写扁平名查会永远读不到 ⇒ 指纹全塌成 {@code "death"}，
     * 于是「被怪打死两次」与「两次不同的死」被判成同一个问题（T3 误报）。
     * 只测扁平键名的话，这个 bug 会和测试一起绿。
     */
    @Test
    void fingerprintSeesDottedCamelCaseKeysTheWayTheRealEventCarriesThem() {
        String flat = CandidateGate.fingerprint("death", Map.of("deathAttacker", "zombie"));
        String dotted = CandidateGate.fingerprint("death", Map.of("context.deathAttacker", "zombie"));
        assertEquals(flat, dotted, "the prefix must not make it a different problem");
        String zombie = CandidateGate.fingerprint("death", Map.of("context.deathAttacker", "zombie"));
        String player = CandidateGate.fingerprint("death", Map.of("context.deathAttacker", "player"));
        assertFalse(zombie.equals(player),
                "被怪打死与被玩家打死归因方向相反（RddPlugin:684-696），不能共用一个指纹");
    }

    @Test
    void aMissingTypeDecidesNothingRatherThanQueueing() {
        CandidateGate.Decision d = CandidateGate.evaluate(null, Map.of("task", "mine"), 0);
        assertFalse(d.enqueue());
        assertEquals(CandidateGate.Status.UNDECIDABLE, statusOf(d, "T2"));
        assertNull(d.primaryTrigger());
    }

    @Test
    void summaryOfCountsRealDecisionsNotAnEmptyList() {
        var s = CandidateGate.summaryOf(List.of(
                CandidateGate.evaluate("death", Map.of(), 0),
                CandidateGate.evaluate("resource_waste", Map.of("gatheredBeyondMinimum", "0"), 0),
                CandidateGate.evaluate("death", Map.of(), 1)));
        assertEquals(3, s.get("decisions"));
        assertEquals(2, s.get("enqueue_recommended"));
        assertTrue(((Map<?, ?>) s.get("trigger_first_why")).containsKey("T2"));
        assertTrue(((Map<?, ?>) s.get("trigger_first_why")).containsKey("T3"));
    }
}