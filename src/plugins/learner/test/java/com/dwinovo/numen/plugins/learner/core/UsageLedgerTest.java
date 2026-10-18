package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用账本的离线检查（S3，2026-10-05）。
 *
 * <p>重点守两条「踩过才知道」的规则：
 * <ol>
 *   <li><b>被呈现后成功 ≠ 有效性证据</b>（时间先后不是因果，否则账本自我印证）；</li>
 *   <li><b>取消后迟到的成功不许翻转结论</b>（AC-B19 同款病）。</li>
 * </ol>
 */
class UsageLedgerTest {

    private static final UUID C = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "usage-" + n + "-" + System.nanoTime());
    }

    @Test
    void append_recordsPhaseChain() {
        UsageLedger ul = new UsageLedger(tmp("a"));
        ul.append("AC_SCRIPT-c1-m1", "AC_SCRIPT", "hp_guard", UsageLedger.Phase.PRESENTED,
                UsageLedger.Outcome.UNKNOWN, "摆给同伴看了", "learner_usage");
        ul.append("AC_SCRIPT-c1-m1", "AC_SCRIPT", "hp_guard", UsageLedger.Phase.ADOPTED,
                UsageLedger.Outcome.UNKNOWN, "", "acx_approve");
        ul.append("AC_SCRIPT-c1-m1", "AC_SCRIPT", "hp_guard", UsageLedger.Phase.EXECUTED,
                UsageLedger.Outcome.UNKNOWN, "跑了 v1", "acx", C.toString());
        UsageLedger.Entry r = ul.append("AC_SCRIPT-c1-m1", "AC_SCRIPT", "hp_guard",
                UsageLedger.Phase.RESULT, UsageLedger.Outcome.SUCCESS, "跑完了", "acx", C.toString());

        assertEquals(UsageLedger.Phase.RESULT, r.phase());
        assertEquals(UsageLedger.Outcome.SUCCESS, r.outcome());
        UsageLedger.Entry latest = ul.latestOf("AC_SCRIPT-c1-m1");
        assertNotNull(latest);
        assertEquals(UsageLedger.Phase.RESULT, latest.phase());
        assertEquals(1, ul.conclusions().size(), "同一产物只留一条结论");
    }

    @Test
    void successAlone_isCandidateAssociation_notValidityEvidence() {
        // ★ 核心规则：跑成功了**不等于**有效
        UsageLedger ul = new UsageLedger(tmp("b"));
        ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.RESULT, UsageLedger.Outcome.SUCCESS, "", "acx", C.toString());

        List<UsageLedger.Entry> cands = ul.associationCandidates();
        assertEquals(1, cands.size(), "成功的产物应进「候选关联」清单等人工审");
        assertFalse(cands.get(0).validityClaimed(),
                "★ 没显式声明就不许算有效证据 —— 时间上的先后不是因果");

        // ★ 传本同伴 id：现在 promptBlock 必须显式给同伴，否则会退化成「把共享账本全喂出去」
        String block = UsageLedger.promptBlock(ul, C);
        assertTrue(block.contains("不等于有效"),
                "回喂给模型的措辞必须说明「成功≠有效」，否则模型会当成已验证: " + block);
    }

    @Test
    void claimValidity_requiresReasonAndSuccess() {
        UsageLedger ul = new UsageLedger(tmp("c"));
        ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.RESULT, UsageLedger.Outcome.FAIL, "炸了", "acx", C.toString());

        // 失败的不许声明有效
        assertThrows(IllegalStateException.class, () -> ul.claimValidity("a1", "我觉得有效", "me"));
        // 没理由的不许声明
        ul.append("a2", "AC_SCRIPT", "y", UsageLedger.Phase.RESULT, UsageLedger.Outcome.SUCCESS, "", "acx", C.toString());
        var e = assertThrows(IllegalArgumentException.class, () -> ul.claimValidity("a2", "  ", "me"));
        assertTrue(e.getMessage().contains("理由"), "要说清是缺理由: " + e.getMessage());
    }

    @Test
    void claimValidity_thenItIsClaimed() {
        UsageLedger ul = new UsageLedger(tmp("d"));
        ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.RESULT, UsageLedger.Outcome.SUCCESS, "", "acx", C.toString());
        ul.claimValidity("a1", "实机连跑 5 次都对，脚本作者本人确认", "owner");
        UsageLedger.Entry latest = ul.latestOf("a1");
        assertTrue(latest.validityClaimed(), "显式声明后应标上");
        assertEquals(0, ul.associationCandidates().size(), "声明过的不该再留在待审清单里");
    }

    @Test
    void lateSuccessAfterCancel_doesNotFlipOutcome() {
        // ★ AC-B19 同款病的预防：取消后迟到的成功不许把结论翻回 SUCCESS
        UsageLedger ul = new UsageLedger(tmp("e"));
        ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.RESULT, UsageLedger.Outcome.CANCELED,
                "玩家取消了", "acx_cancel");
        UsageLedger.Entry late = ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "迟到的回执", "acx", C.toString());

        assertEquals(UsageLedger.Outcome.CANCELED, late.outcome(),
                "★ 迟到成功必须仍按取消记账 —— 翻回去就是 AC-B19 那个 bug");
        assertEquals(UsageLedger.Outcome.CANCELED, ul.latestOf("a1").outcome());
        assertEquals(0, ul.associationCandidates().size(), "取消的产物不许进候选关联清单");
    }

    @Test
    void phaseCannotGoBackwards() {
        UsageLedger ul = new UsageLedger(tmp("f"));
        ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.EXECUTED, UsageLedger.Outcome.UNKNOWN, "", "acx", C.toString());
        UsageLedger.LateArrival e = assertThrows(UsageLedger.LateArrival.class,
                () -> ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.PRESENTED,
                        UsageLedger.Outcome.UNKNOWN, "迟到的呈现", "x"));
        assertEquals(UsageLedger.Phase.EXECUTED, e.seen);
        assertEquals(UsageLedger.Phase.PRESENTED, e.arriving);
    }

    @Test
    void nonResultPhase_cannotCarryOutcome() {
        UsageLedger ul = new UsageLedger(tmp("g"));
        assertThrows(IllegalArgumentException.class,
                () -> ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.PRESENTED,
                        UsageLedger.Outcome.SUCCESS, "", "x"));
    }

    @Test
    void blankArtifactId_isRejected() {
        UsageLedger ul = new UsageLedger(tmp("h"));
        assertThrows(IllegalArgumentException.class,
                () -> ul.append("  ", "AC_SCRIPT", "x", UsageLedger.Phase.PRESENTED,
                        UsageLedger.Outcome.UNKNOWN, "", "x"));
    }
}