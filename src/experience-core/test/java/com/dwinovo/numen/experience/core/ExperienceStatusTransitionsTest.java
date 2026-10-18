package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ E8：验证状态变化 —— 撤回 / 修订链 / 降级。
 *
 * <p><b>这一批修的真洞（先说清楚为什么值得写这些测试）</b>：
 * 老代码的失败分支只有一句
 * {@code if (old.maturity().level() < ATTEMPTED.level()) maturity = ATTEMPTED;}
 * ⇒ 只有 {@code OBSERVED} 会被降级，{@code VERIFIED}／{@code GENERALIZED}
 * <b>完全不受失败影响</b>。实测后果：一条被证伪 10 次的 {@code GENERALIZED}
 * 经验成熟度永不下降，注入侧照样报 {@code <verified>N</verified>} 当它可信 ——
 * 那是<b>假事实</b>。</p>
 */
class ExperienceStatusTransitionsTest {

    @TempDir
    Path tmp;

    private static ExperienceEntry entry(String title) {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title(title)
                .description("d")
                .build();
    }

    private ExperienceStore store() {
        return ExperienceStore.at(tmp.resolve("exp.jsonl"));
    }

    // ---------- 降级（E8 最核心的那条）----------

    @Test
    void consecutiveFailuresDemoteMaturity() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        // 先攒到 VERIFIED
        s.recordEvidence(id, true, null);
        assertEquals(ExperienceMaturity.VERIFIED, s.all().get(0).maturity());

        // ★ 证伪 3 次（= DEMOTE_AFTER_CONSECUTIVE_FAILURES）必须真的降一级。
        //   修复前这里会一直是 VERIFIED —— 那就是被证伪了还当可信。
        for (int i = 1; i <= ExperienceStore.DEMOTE_AFTER_CONSECUTIVE_FAILURES; i++) {
            s.recordEvidence(id, false, "fail-" + i);
        }
        assertEquals(ExperienceMaturity.ATTEMPTED, s.all().get(0).maturity(),
                "连续证伪 " + ExperienceStore.DEMOTE_AFTER_CONSECUTIVE_FAILURES
                        + " 次后必须降一级，否则被证伪的经验仍被当可信注入");

        assertEquals(0, s.all().get(0).consecutiveFailures(),
                "降级后计数归零（下一轮重新数 3 次）");
    }

    @Test
    void oneOrTwoFailuresDoNotDemote() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.recordEvidence(id, true, null);

        s.recordEvidence(id, false, "f1");
        assertEquals(ExperienceMaturity.VERIFIED, s.all().get(0).maturity(), "一次失败不该降级");
        s.recordEvidence(id, false, "f2");
        assertEquals(ExperienceMaturity.VERIFIED, s.all().get(0).maturity(),
                "两次失败还没到阈值，不该降级");
        assertEquals(2, s.all().get(0).consecutiveFailures(), "连续失败数要如实报出来");
    }

    @Test
    void aSuccessResetsTheConsecutiveCounter() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.recordEvidence(id, true, null);
        s.recordEvidence(id, false, "f1");
        s.recordEvidence(id, false, "f2");
        assertEquals(2, s.all().get(0).consecutiveFailures());

        // ★ 成功一次 ⇒ 连续计数归零。接下来的失败不该跟前面那两次算成「连续 3 次」
        s.recordEvidence(id, true, "recovered");
        assertEquals(0, s.all().get(0).consecutiveFailures());
        s.recordEvidence(id, false, "f3");
        assertEquals(ExperienceMaturity.VERIFIED, s.all().get(0).maturity(),
                "成功之后的第一次失败不够降级（连续被成功打断了）");
    }

    @Test
    void successDoesNotWipeCounterexamples() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.recordEvidence(id, false, "在开阔水域这条不成立");
        s.recordEvidence(id, true, null);

        assertEquals(1, s.all().get(0).counterexamples().size(),
                "成功**不许**清掉反例 —— 那是「在什么条件下不成立」的证据，丢了才是丢证据");
    }

    @Test
    void observedStaysAtAttemptedAfterOneFailure() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        assertEquals(ExperienceMaturity.OBSERVED, s.all().get(0).maturity());
        s.recordEvidence(id, false, "no");
        assertEquals(ExperienceMaturity.ATTEMPTED, s.all().get(0).maturity(),
                "OBSERVED 被证伪一次至少是 ATTEMPTED（老行为，保留）");
    }

    // ---------- 撤回 ----------

    @Test
    void retractMarksInsteadOfDeleting() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.retract(id, "与世界实际不符：那个方块在 1.21 已经改名");

        assertEquals(1, s.size(), "撤回是标记不是删除 —— 条数不该变");
        assertTrue(s.all().get(0).retracted());
        assertFalse(s.usable().isEmpty() && s.usable().size() == 1, "撤回后不该出现在 usable() 里");
        assertTrue(s.usable().stream().noneMatch(e -> e.id().equals(id)), "撤回的条目不在 usable() 里");
    }

    @Test
    void retractRequiresAReason() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        // 撤回而不写理由 = 让别人猜 ⇒ 硬拒
        assertThrows(IllegalArgumentException.class, () -> s.retract(id, "  "));
        assertThrows(IllegalArgumentException.class, () -> s.retract(id, null));
        assertFalse(s.all().get(0).retracted(), "被拒的撤回不该生效");
    }

    @Test
    void retractOfUnknownIdReturnsNullRatherThanFakeSuccess() {
        ExperienceStore s = store();
        s.learn(entry("t"));
        assertNull(s.retract("no-such-id", "理由"), "不认识的 id 必须返回 null，不许静默成功");
    }

    @Test
    void retractIsIdempotentAndCanCorrectItsReason() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.retract(id, "第一次的理由写错了");
        s.retract(id, "更正：真正的原因是版本差异");

        assertEquals(1, s.size());
        assertEquals("更正：真正的原因是版本差异", s.all().get(0).retractedReason(),
                "重复撤回 = 纠正理由，不是第二次撤回，也不是报错");
    }

    @Test
    void reinstateBringsItBack() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.retract(id, "误判");
        s.reinstate(id);
        assertFalse(s.all().get(0).retracted());
        assertTrue(s.usable().stream().anyMatch(e -> e.id().equals(id)));
    }

    @Test
    void retractedEntrySurvivesReload() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        s.retract(id, "原因");

        // ★ 必须真的落盘：换一个 store 实例重读，撤回状态还在才算数
        ExperienceStore reopened = ExperienceStore.at(tmp.resolve("exp.jsonl"));
        assertTrue(reopened.all().get(0).retracted(), "撤回状态必须持久化（重开后仍是已撤回）");
        assertEquals("原因", reopened.all().get(0).retractedReason());
        assertTrue(reopened.usable().stream().noneMatch(e -> e.id().equals(id)));
    }

    // ---------- 修订链 ----------

    @Test
    void supersedeMarksTheOlderEntryAsDead() {
        ExperienceStore s = store();
        String oldId = s.learn(entry("旧说法：要在 y=64 上面放火把")).id();
        String newId = s.learn(entry("新说法：刷怪笼半径内亮度 >7 会停刷")).id();
        assertEquals(2, s.usable().size());

        s.supersede(oldId, newId);

        assertTrue(s.supersededIds().contains(oldId), "旧条目应进入被取代集合");
        assertEquals(1, s.supersededCount());
        assertTrue(s.usable().stream().noneMatch(e -> e.id().equals(oldId)),
                "被取代的条目不该再当可信经验用");
        assertTrue(s.usable().stream().anyMatch(e -> e.id().equals(newId)));
        assertEquals(2, s.size(), "取代是标记，两条都还在（可追溯）");
    }

    @Test
    void supersedeIsRejectedWhenEitherIdIsUnknown() {
        ExperienceStore s = store();
        String newId = s.learn(entry("新")).id();
        assertNull(s.supersede("no-such-old", newId), "指向不存在的旧条目 = 说谎，拒绝");
        assertEquals(0, s.supersededCount());
    }

    @Test
    void anEntryCannotSupersedeItself() {
        ExperienceStore s = store();
        String id = s.learn(entry("t")).id();
        assertThrows(IllegalArgumentException.class, () -> s.supersede(id, id));
    }

    @Test
    void aRetractedEntryCannotSupersede() {
        ExperienceStore s = store();
        String oldId = s.learn(entry("旧")).id();
        String newId = s.learn(entry("新")).id();
        s.retract(newId, "新条目本身被判错了");
        assertNull(s.supersede(oldId, newId), "已撤回的条目不该再宣称取代别人");
        assertEquals(0, s.supersededCount());
    }

    @Test
    void supersedeChainSurvivesReload() {
        ExperienceStore s = store();
        String oldId = s.learn(entry("旧")).id();
        String newId = s.learn(entry("新")).id();
        s.supersede(oldId, newId);

        ExperienceStore reopened = ExperienceStore.at(tmp.resolve("exp.jsonl"));
        assertTrue(reopened.supersededIds().contains(oldId), "supersedes 指针必须落盘");
    }

    // ---------- 可信度读数（注入侧就靠它）----------

    @Test
    void statsCountOnlyUsableEntriesAsTrusted() {
        ExperienceStore s = store();
        String vId = s.learn(entry("会被验证的")).id();
        s.learn(entry("待观察的"));
        s.recordEvidence(vId, true, null);
        assertEquals(1, verifiedViaMemory(s), "撤回前 verified=1");

        s.retract(vId, "撤回它");
        assertEquals(0, verifiedViaMemory(s),
                "★ 撤回后 verified 必须掉到 0 —— 它不再能被算成可信（这是 E8 的核心读数口径）");
        assertEquals(2, totalViaMemory(s), "total 仍是全量 2");
        assertEquals(1, usableViaMemory(s), "usable=1（全量 2 减掉撤回的 1）");
    }

    @Test
    void unusableReasonExplainsWhyAndIsBlankForHealthyEntries() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("t"));
        assertEquals("", e.unusableReason(false), "健康的条目不该有不可信理由");

        ExperienceEntry r = e.retracted("因为世界改版了");
        assertTrue(r.unusableReason(false).contains("已撤回"));
        assertTrue(r.unusableReason(false).contains("因为世界改版了"), "要说清为什么，不只说「已撤回」");

        assertTrue(e.unusableReason(true).contains("取代"), "被取代也要有理由");
    }

    @Test
    void retractedEntryWithoutReasonStillReportsThatItHappened() {
        // 手工构造（绕过 retract 的硬门）时可能没理由 —— 仍要说「已撤回」而不是空
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title("t").description("d")
                .retracted(true).build();
        String why = e.unusableReason(false);
        assertTrue(why.startsWith("已撤回"), why);
        assertTrue(why.contains("没写理由"), "没理由要说「没写理由」，不能装作有理由：" + why);
    }

    // ---- helpers ----

    private static int verifiedViaMemory(ExperienceStore s) {
        return statsOf(s).verified();
    }

    private static int totalViaMemory(ExperienceStore s) {
        return statsOf(s).total();
    }

    private static int usableViaMemory(ExperienceStore s) {
        return statsOf(s).usable();
    }

    private static ExperienceStats statsOf(ExperienceStore s) {
        ExperienceMemory m = ExperienceMemory.at(s.file(), new LexicalExperienceRetriever());
        return m.stats();
    }

    @Test
    void experienceStatsUnusableIsDerivedNotGuessed() {
        ExperienceStats st = new ExperienceStats(22, 1, 0, 19, 2, 1);
        assertEquals(3, st.unusable(), "unusable 由 total-usable 算，不另设字段（免得两份数据打架）");
        assertEquals(19, st.usable());
        assertNotNull(List.of());
    }
}