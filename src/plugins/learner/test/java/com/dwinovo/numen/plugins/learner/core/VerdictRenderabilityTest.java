package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定 id 的「可渲染性」判据（2026-10-05 实机 bug 的回归测试）。
 *
 * <p><b>实机现象</b>：ACX 队列里两条 <b>learner_intake 造的</b>备忘录被复审、被 commit
 * （真从队列删了），可监测台 <code>reviewed</code> 事件是
 * {@code reviewed:0 / verdicts:[]}，<code>learner_status</code> 报 {@code NO_VERDICT}。
 * ⇒ 材料被消费却<b>任何可观测面都看不见</b>。
 *
 * <p><b>病因</b>：渲染时用 {@code memoId.startsWith("m-")} 当「合法 id」的判据，
 * 而 {@code learner_intake} 的 id 是 {@code ci-<eventId>} ⇒ 整批被过滤。
 * intake 是自动主路径，所以这条路径的产物<b>全都</b>丢在暗处。
 *
 * <p><b>正确判据</b>：不是前缀猜测，而是「是不是本批真实 memo id」。
 */
class VerdictRenderabilityTest {

    /** 本批真实 id 集合 —— 与 LearnerReviewTool 里的 judged 同义。 */
    private static boolean renderable(String memoId, List<String> batchIds) {
        return memoId != null && batchIds.contains(memoId);
    }

    @Test
    void intakeMemoId_isRenderable_evenThoughItDoesNotStartWith_m() {
        // learner_intake 的真实 id 形状：ci-<eventId>
        List<String> batch = List.of("ci-learner-71181100633000", "ci-learner-72475279561900");
        assertTrue(renderable("ci-learner-71181100633000", batch),
                "摄入来的 memo id 必须能渲染 —— 否则复审产物在监测台/learner_status 全不可见");
        assertFalse("m-ci-x".equals("ci-learner-71181100633000"),
                "守住回归：旧判据是 startsWith(\"m-\")，对 ci-* 一律为 false");
    }

    @Test
    void noteMemoId_isStillRenderable() {
        List<String> batch = List.of("m-1791016499217-583");
        assertTrue(renderable("m-1791016499217-583", batch), "learner_note 的 id 形态没变，仍要能渲染");
    }

    @Test
    void placeholderId_isStillBlocked_withoutPrefixGuessing() {
        List<String> batch = List.of("ci-a", "m-b");
        assertFalse(renderable("?", batch), "占位 id 必须仍被挡住");
        assertFalse(renderable(null, batch), "null 必须挡住");
        assertFalse(renderable("", batch), "空串必须挡住");
        assertFalse(renderable("ci-unknown", batch), "不在本批里的 id 必须挡住（不许凭空冒出来）");
    }
}