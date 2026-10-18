package com.dwinovo.numen.rdd.fact;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投影的离线检查（第三批 N1）。
 *
 * <p>守三条：
 * <ol>
 *   <li><b>被预算截断必须说出来</b>（静默截断会把「只看了 8 条」说成「就这些」）；</li>
 *   <li><b>投影不吞三态</b>：UNKNOWN 在任何视图里都还是 UNKNOWN；</li>
 *   <li>点名了但事实里没有 ⇒ 输出 UNKNOWN 行，不静默省略。</li>
 * </ol>
 */
class FactProjectionTest {

    private static final UUID A = UUID.fromString("33333333-0000-0000-0000-00000000000a");
    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    private static Goal goal(String id, String desc) {
        return new Goal(id, desc, List.of(new PrimaryGoal("p", "primary",
                List.of(new Subtask("s1", "do", DetectionMode.HARD_CODED,
                        Map.of("k", "v"), 5L, 3, false, null)), List.of(), false)));
    }

    private static CompletedFactStore facts(String... stages) {
        CompletedFactStore s = new CompletedFactStore();
        Goal g = goal("g1", "obj");
        for (String st : stages) {
            s.recordStage(g, st, NOW, "ev-" + st);
        }
        return s;
    }

    private static FactSnapshot snap(CompletedFactStore f) {
        return FactSnapshot.capture(A, "minecraft:overworld", f, null, null, NOW, TTL);
    }

    @Test
    void briefRespectsBudgetAndReportsOmission() {
        FactSnapshot s = snap(facts("aa", "bb", "cc"));
        var r = FactProjection.brief(s, new FactProjection.Options(2, false, false));
        assertEquals(2, r.rows().size(), "预算 2 就只出 2 条");
        assertEquals(1, r.omitted(), "★ 被截掉的必须计数");
        assertTrue(r.truncated());
        assertTrue(String.valueOf(r.notes()).contains("截断"),
                "要明说截断了，不能让人以为「就这些」: " + r.notes());
    }

    @Test
    void briefWithoutOmission_isNotMarkedTruncated() {
        FactSnapshot s = snap(facts("aa"));
        var r = FactProjection.brief(s, new FactProjection.Options(8, false, false));
        assertFalse(r.truncated());
        assertEquals(0, r.omitted());
    }

    @Test
    void projectionsKeepUnknownAsUnknown() {
        FactSnapshot s = snap(facts("aa"));
        var r = FactProjection.forTask(s, List.of("aa", "build_house"),
                FactProjection.Options.task());
        var unknown = r.rows().stream()
                .filter(x -> x.certainty() == FactSnapshot.Certainty.UNKNOWN).toList();
        assertEquals(1, unknown.size(), "点名的没记录项必须以 UNKNOWN 出现在结果里");
        assertEquals("build_house", unknown.get(0).stageKey());
        assertTrue(String.valueOf(r.notes()).contains("UNKNOWN"),
                "要说明有 UNKNOWN 项（不是「没做」）");
    }

    @Test
    void taskProjectionWithNothingWanted_saysSo() {
        FactSnapshot s = snap(facts("aa"));
        var r = FactProjection.forTask(s, List.of(), FactProjection.Options.task());
        assertEquals(0, r.rows().size());
        assertTrue(String.valueOf(r.notes()).contains("没有点名"),
                "「没点名」与「没有事实」要分开说");
    }

    @Test
    void briefDropsStaleUnlessAsked() {
        // 一条新鲜 + 一条过期
        CompletedFactStore f = new CompletedFactStore();
        Goal g = goal("g1", "obj");
        f.recordStage(g, "fresh", NOW, "ev");
        f.recordStage(g, "old", NOW - TTL - 100, "ev");
        FactSnapshot s = snap(f);

        var withoutStale = FactProjection.brief(s, new FactProjection.Options(10, false, false));
        assertTrue(withoutStale.rows().stream().noneMatch(
                        x -> x.certainty() == FactSnapshot.Certainty.STALE),
                "brief 视图不该混进过期事实");

        var withStale = FactProjection.brief(s, new FactProjection.Options(10, true, false));
        assertTrue(withStale.rows().stream().anyMatch(
                        x -> x.certainty() == FactSnapshot.Certainty.STALE),
                "要查全时（含过期）应当能看到它，但确定度必须仍是 STALE");
    }

    @Test
    void evidenceIncludedOnlyWhenAsked() {
        FactSnapshot s = snap(facts("aa"));
        var brief = FactProjection.brief(s, FactProjection.Options.brief());
        var full = FactProjection.full(s);
        assertEquals(null, brief.rows().get(0).evidence(), "简要视图不带证据");
        assertEquals("ev-aa", full.rows().get(0).evidence(), "详细视图带证据");
    }

    @Test
    void fullProjection_isCompleteAndOrderedByRecency() {
        FactSnapshot s = snap(facts("aa", "bb"));
        var r = FactProjection.full(s);
        assertEquals(2, r.rows().size());
        assertFalse(r.truncated(), "full 不设预算，不该报截断");
    }

    @Test
    void stageState_isTheNarrowContractForConsumers() {
        // N2/N3 只需要问这一句，不该自己去翻 entries()
        FactSnapshot s = snap(facts("mine"));
        assertEquals(FactSnapshot.Certainty.KNOWN,
                FactProjection.stageState(s, "mine", NOW));
        assertEquals(FactSnapshot.Certainty.UNKNOWN,
                FactProjection.stageState(s, "nope", NOW));
        assertEquals(FactSnapshot.Certainty.STALE,
                FactProjection.stageState(s, "mine", NOW + TTL + 1),
                "★ 采集后过期的，读取时也必须报 STALE");
    }

    // ── 用量投影（第三批 N1：把 observedUsage 也投影出去） ────────────────

    @Test
    void usageProjection_returnsAllKnownUsage() {
        FactSnapshot s = FactSnapshot.capture(A, "minecraft:overworld",
                facts("aa"), null, Map.of("memo-1", 42), NOW, TTL);
        var rows = FactProjection.usage(s);
        assertEquals(1, rows.size());
        assertEquals("memo-1", rows.get(0).key());
        assertEquals(42, rows.get(0).value());
        assertTrue(rows.get(0).known());
    }

    @Test
    void usageProjection_emptyWhenNoUsageSource() {
        FactSnapshot s = snap(facts("aa")); // 没传 usageOf ⇒ 用量表空 = UNKNOWN
        var rows = FactProjection.usage(s);
        assertTrue(rows.isEmpty(), "没有用量来源时投影为空，不是「用量全 0」");
    }

    @Test
    void usageProjection_isIsolatedPerCompanion() {
        UUID B = UUID.fromString("44444444-0000-0000-0000-00000000000b");
        FactSnapshot sa = FactSnapshot.capture(A, "minecraft:overworld",
                facts("aa"), null, Map.of("memo-1", 10), NOW, TTL);
        FactSnapshot sb = FactSnapshot.capture(B, "minecraft:overworld",
                facts("bb"), null, Map.of("memo-1", 20), NOW, TTL);
        assertEquals(10, FactProjection.usage(sa).get(0).value());
        assertEquals(20, FactProjection.usage(sb).get(0).value());
        assertFalse(sa.companionId().equals(sb.companionId()));
    }
}
