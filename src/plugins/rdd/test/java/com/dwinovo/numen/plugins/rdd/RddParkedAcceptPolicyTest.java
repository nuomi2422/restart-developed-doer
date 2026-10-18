package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 停车态「条件已达成」裁决契约测试（2026-09-28）。
 *
 * <p>实测 bug：食物条件早已满足（小麦 19 >= 12），链却停在 REPLANNING/FAILED 原地不动，
 * 因为验收逻辑挂在 ACTIVE 之后、停车态提前 return。这些用例把"该放行/该继续罚站"的边界钉死。
 */
class RddParkedAcceptPolicyTest {

    @Test void resumesWhenReplanningAndHardConditionAlreadyMet() {
        assertTrue(RddParkedAcceptPolicy.shouldResume(true, true, true, true, false));
    }

    @Test void doesNotResumeWhenConditionNotYetMet() {
        // 最常见：该罚站催工，而不是伪造完成
        assertFalse(RddParkedAcceptPolicy.shouldResume(true, true, true, false, false));
    }

    @Test void doesNotResumeTwiceForSamePrimary() {
        // 防乒乓：回退后下一 tick 若又判"已达成"会 停车<->回退 死循环
        assertFalse(RddParkedAcceptPolicy.shouldResume(true, true, true, true, true));
    }

    @Test void softJudgementIsNeverAutoAccepted() {
        // 软判定要 AI 回合才能出结论，没新证据不许替它宣布完成
        assertFalse(RddParkedAcceptPolicy.shouldResume(true, true, false, true, false));
    }

    @Test void onlyReplanningMayAutoResume() {
        // FAILED / WAITING / AWAITING_SUPERVISOR 交给各自既有分支处理
        assertFalse(RddParkedAcceptPolicy.shouldResume(false, true, true, true, false));
    }

    @Test void noCurrentSubtaskMeansNothingToAccept() {
        assertFalse(RddParkedAcceptPolicy.shouldResume(true, false, true, true, false));
    }
}
