package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RddStallPolicyTest {
    private static RddStallPolicy.Observation observation(String work, boolean busy, String source) {
        return new RddStallPolicy.Observation("iron=0|position=1,64,1", work, busy, source);
    }

    private static RddStallPolicy.Check next(RddStallPolicy.Check previous, RddStallPolicy.Observation observation) {
        return RddStallPolicy.check(previous == null ? null : previous.fingerprint(),
                previous == null ? 0 : previous.unchanged(), observation);
    }

    @Test void parkedBucketAbsorbsJitterButSeparatesRealTravel() {
        // 回归（2026-09-27 实机）：AI 在两点之间横跳时，原精确坐标指纹每 tick 变化，
        // "无进展"窗口被无限重置 -> 催工永不触发（表现为重复走且任务永不完成）。
        // 修法是停车守望改用 8 格粗桶：同区域抖动同桶（窗口不被重置），跨区域才算新进展。
        String a = RddStallPolicy.parkedBucket(100, 64, 100);
        assertEquals(a, RddStallPolicy.parkedBucket(100, 64, 101));
        assertEquals(a, RddStallPolicy.parkedBucket(103, 64, 97));
        assertEquals(a, RddStallPolicy.parkedBucket(103, 64, 103));
        assertNotEquals(a, RddStallPolicy.parkedBucket(108, 64, 100));
        assertNotEquals(a, RddStallPolicy.parkedBucket(100, 72, 100));
        // 负坐标也要正确分桶（Math.floorDiv 而非截断）
        assertEquals("-1,0,-1", RddStallPolicy.parkedBucket(-1, 0, -1));
        assertEquals("-2,0,-2", RddStallPolicy.parkedBucket(-9, 0, -9));
    }

    @Test void idleStillEscalatesAfterFifteenUnchangedChecks() {
        var frame = observation("", false, "idle");
        var check = next(null, frame);
        for (int i = 1; i < 15; i++) {
            check = next(check, frame);
            assertFalse(check.stalled());
        }
        assertTrue(next(check, frame).stalled());
    }

    @Test void activeBodyGetsBoundedGraceRatherThanPermanentExemption() {
        var frame = observation("fishing:0/3", true, "body_task:t1");
        var check = next(null, frame);
        for (int i = 1; i < RddStallPolicy.WORK_GRACE_CHECKS; i++) {
            check = next(check, frame);
            assertFalse(check.stalled(), "premature nudge at " + i);
        }
        check = next(check, frame);
        assertTrue(check.stalled());
        assertEquals(0, check.remaining());
    }

    @Test void changingTaskIdentityWithoutWorkCannotRefreshGrace() {
        var check = next(null, observation("fishing:0/3", true, "body_task:t0"));
        for (int i = 1; i <= RddStallPolicy.WORK_GRACE_CHECKS; i++) {
            check = next(check, observation("fishing:0/3", true, "body_task:t" + i));
        }
        assertTrue(check.stalled());
    }

    @Test void furnaceCanProduceForTenMinutesWithoutInventoryChanging() {
        RddStallPolicy.Check check = null;
        for (int second = 0; second < 600; second++) {
            // Material remains in the furnace; completed batches and cooking change, inventory does not.
            check = next(check, observation("output=" + second / 10 + "|cooking=" + second % 10,
                    true, "furnace_production"));
            assertFalse(check.stalled());
        }
    }

    @Test void litButBlockedFurnaceStillEscalatesWhenNothingIsProduced() {
        var frame = observation("input=1|output=64|cooking=0", true, "furnace_production");
        var check = next(null, frame);
        // Fuel countdown is deliberately not part of workProgress; lit alone is not success.
        for (int i = 0; i < RddStallPolicy.WORK_GRACE_CHECKS; i++) check = next(check, frame);
        assertTrue(check.stalled());
    }

    @Test void realContainerOutputOrBodyProgressRestartsObservationWindow() {
        var check = next(null, observation("output=0|body=building:0/20", true, "body_task:t1"));
        for (int i = 0; i < 100; i++) {
            check = next(check, observation("output=0|body=building:0/20", true, "body_task:t1"));
        }
        check = next(check, observation("output=1|body=building:1/20", true, "body_task:t1"));
        assertTrue(check.changed());
        assertEquals(0, check.unchanged());
        assertEquals(RddStallPolicy.WORK_GRACE_CHECKS, check.remaining());
    }

    @Test void endedWorkCannotKeepTheBusyGrace() {
        var check = next(null, observation("output=0", true, "furnace_production"));
        for (int i = 0; i < 20; i++) check = next(check, observation("output=0", true, "furnace_production"));
        check = next(check, observation("output=0", false, "idle"));
        assertTrue(check.stalled());
        assertEquals(RddStallPolicy.IDLE_GRACE_CHECKS, check.limit());
    }

    // ---- LLM 空转提前拍醒（2026-09-29 实机：chat done in 35625ms, tool_calls=[]）----

    @Test void llmIdleFiresOnceAtTheThresholdAndNotBefore() {
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        for (int n = 0; n < threshold - 1; n++) {
            assertFalse(RddStallPolicy.shouldNudgeLlmIdle(n, "idle"), "must stay quiet at " + n);
        }
        assertTrue(RddStallPolicy.shouldNudgeLlmIdle(threshold, "idle"));
        // 一次性：越过阈值后不连发，否则会变成每 tick 刷屏（曾有 subtask_parked_silent 刷 90+ 条的事故）
        assertFalse(RddStallPolicy.shouldNudgeLlmIdle(threshold + 1, "idle"));
        assertFalse(RddStallPolicy.shouldNudgeLlmIdle(threshold * 3, "idle"));
    }

    @Test void llmIdleNeverNudgesWorkThatIsActuallyRunning() {
        // 回归风险：mine/build 这类多步身体任务内部会连跑几十秒且不发新工具调用，
        // 拿 5 秒阈值去拍它等于打断正常工作。判据必须与「有没有进展」分开。
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        for (String busy : new String[]{"body_task:mine", "body_task:build", "furnace_production"}) {
            assertFalse(RddStallPolicy.llmIdle(busy), busy + " must not count as llm-idle");
            for (int n = 0; n <= threshold + 5; n++) {
                assertFalse(RddStallPolicy.shouldNudgeLlmIdle(n, busy), busy + " must never be nudged");
            }
        }
    }

    @Test void llmIdleNudgeIsEarlierThanTheStallGrace() {
        // 提前拍醒的意义就在于早：必须显著早于 IDLE_GRACE_CHECKS，否则等于没加。
        assertTrue(RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS < RddStallPolicy.IDLE_GRACE_CHECKS,
                "必须早于既有 idle 判据，否则这道信号不产生增量");
        // 回归（GLM 2026-09-29 审稿）：实测最短有效轮次是 5.5s，阈值不得低于 6s，
        // 否则对每一轮都拍一次 —— 那是"每轮税"不是检测器。
        assertTrue(RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS >= 6,
                "阈值低于实测最短有效轮次(5.5s)，会对所有回合触发");
        // 诚实边界：纯时间阈值无法区分「空转」与「慢」——实测空转 35.6s 比有效 44.0s 还短。
        // 2026-10-01 已补真正的判据（响应闸 shouldNudgeLlmIdleAfterResponse），
        // 时间阈值退回兜底角色。
        // 这条断言的作用是：若将来有人把阈值往上调到 >= IDLE_GRACE_CHECKS，这里会响
        // ——因为"提前预警"一旦不早于 stalled 判据就失去意义（20 就被它拦下过）。
        assertTrue(RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS < RddStallPolicy.IDLE_GRACE_CHECKS,
                "兜底时间阈值必须早于 stalled 判据，否则提前预警没有意义");
    }

    // ---- 响应闸（2026-10-01 新增）：真正的空转判据 ----

    @Test void responseGateFiresOnConsecutiveZeroToolCallTurns() {
        int n = RddStallPolicy.LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE;
        assertFalse(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n - 1, "idle", false),
                "未到连续轮数不得拍");
        assertTrue(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n, "idle", false),
                "连续 N 轮只回话不调工具 = 真空转");
        assertTrue(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n + 5, "idle", false),
                "超过阈值仍应成立");
    }

    @Test void responseGateNeverFiresWhileLlmIsInFlight() {
        int n = RddStallPolicy.LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE;
        assertFalse(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n + 10, "idle", true),
                "正在飞的那一轮还没资格算空转（长思维链会被误杀）");
    }

    @Test void responseGateIgnoresBusySources() {
        int n = RddStallPolicy.LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE;
        for (String busy : new String[]{"body_task:mine", "body_task:build", "furnace_production"}) {
            assertFalse(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n + 10, busy, false),
                    busy + " 是正常干活，响应闸也不该拍它");
        }
    }

    @Test void responseGateIsIndependentOfTheClock() {
        // 同一个"零工具调用轮数"，不管时钟走了多久都该判空转 —— 这正是它比时间阈值强的地方：
        // 时间阈值永远分不清"想得慢"和"卡住"，响应闸不用看时钟。
        int n = RddStallPolicy.LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE;
        assertTrue(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n, "idle", false));
        assertTrue(RddStallPolicy.shouldNudgeLlmIdleAfterResponse(n, "idle", false));
    }

    @Test void llmIdleNudgeDoesNotChangeTheCheckOutcome() {
        // 契约：这条信号只拍醒，不改状态机 —— 同一串 observation 走 check() 的结果必须与判据无关。
        var a = next(null, observation("", false, "idle"));
        for (int i = 0; i < RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS; i++) {
            a = next(a, observation("", false, "idle"));
        }
        assertEquals(RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS, a.unchanged());
        assertFalse(a.stalled(), "拍醒不等于判卡死：状态机必须仍然认为没到 stalled");
        assertEquals(RddStallPolicy.IDLE_GRACE_CHECKS, a.limit());
    }

    // ---- 在飞闸（2026-09-30 盲点①：纯时间阈值分不清「卡住」与「慢」）----

    @Test void llmInFlightSuppressesTheNudgeAtTheThreshold() {
        // 验收判据①：LLM 在飞 30s、资产零变化 → 绝不能拍醒。
        // 依据：实测「有效但慢」44.0s 比「空转」35.6s 还长，纯时间阈值会误拍正在思考的模型，
        // 而拍醒措辞会覆盖原计划（见 RddStallWatcher 三条话术铁律）。
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        for (int n = 0; n <= threshold + 30; n++) {
            assertFalse(RddStallPolicy.shouldNudgeLlmIdle(n, "idle", true),
                    "在飞时 n=" + n + " 绝不能拍 —— 会把模型从正确轨道上拽下来");
        }
    }

    @Test void llmNotInFlightStillNudgesExactlyAtTheThreshold() {
        // 验收判据②：LLM 已返回（不在飞）→ 必须还能拍。这是加闸不许引入新盲区。
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        assertFalse(RddStallPolicy.shouldNudgeLlmIdle(threshold - 1, "idle", false));
        assertTrue(RddStallPolicy.shouldNudgeLlmIdle(threshold, "idle", false),
                "已落地却不敢拍 = 卡死监督被静默关掉，比误拍更糟");
        assertFalse(RddStallPolicy.shouldNudgeLlmIdle(threshold + 1, "idle", false), "仍然只拍一次");
    }

    @Test void theTwoArgOverloadIsByteForByteTheOldBehaviour() {
        // fail-open 契约：宿主给不出在飞状态时，行为与加闸之前必须逐字相同。
        int threshold = RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS;
        for (int n = 0; n <= threshold + 3; n++) {
            assertEquals(RddStallPolicy.shouldNudgeLlmIdle(n, "idle", false),
                    RddStallPolicy.shouldNudgeLlmIdle(n, "idle"),
                    "n=" + n + " 两参重载必须等于三参的 false 分支");
        }
        for (String busy : new String[]{"body_task:mine", "body_task:build", "furnace_production"}) {
            assertEquals(RddStallPolicy.shouldNudgeLlmIdle(threshold, busy, true),
                    RddStallPolicy.shouldNudgeLlmIdle(threshold, busy),
                    "在飞闸不许碰 source 那一维的既有行为");
        }
    }

    @Test void inFlightGateDoesNotTouchTheStateMachineOutcome() {
        // 加闸只许改「拍不拍」，不许改 check() 的结论。
        var inFlight = next(null, observation("", false, "idle"));
        var idle = next(null, observation("", false, "idle"));
        for (int i = 0; i < RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS; i++) {
            inFlight = next(inFlight, observation("", false, "idle"));
            idle = next(idle, observation("", false, "idle"));
        }
        assertEquals(idle.fingerprint(), inFlight.fingerprint());
        assertEquals(idle.unchanged(), inFlight.unchanged());
        assertEquals(idle.limit(), inFlight.limit());
        assertEquals(idle.stalled(), inFlight.stalled());
    }
}
