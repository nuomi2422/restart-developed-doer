package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B12：<b>「这一轮为什么开不起来」这份读数，本身会不会说谎。</b>
 *
 * <p>为什么给判定写这么多测试：这份读数的全部价值就是「能替代读游戏日志」，
 * 而游戏日志是 0 字节（stdout 随脱离启动一起没了）。也就是说<b>一旦它说谎，
 * 没有任何别的东西能发现</b>——所以这里钉的不是「返回了个非空」，而是
 * <b>顺序、每道闸各自的判据、以及几个已知会咬人的边界</b>。
 */
class BrainGateTest {

    // ---- 判定顺序：同时命中多道闸时，只有最靠前那道算数 ----

    @Test
    void anOpenStateIsNotBlocked() {
        assertEquals(BrainGate.Blocker.NONE, BrainGate.first(BrainGate.State.open()));
    }

    @Test
    void deadOutranksEverythingElse() {
        // 同时停牌 + 外脑驾驶 + 端点坏了 + 自动整理武装：只有最靠前那条算数。
        // 这条钉的是「第一个原因」的意义 —— 下游要靠它决定「等什么」，
        // 若顺序一改，「等主人复活」就会变成「等端点修好」，而两者都不成立。
        BrainGate.State s = new BrainGate.State(true, true, "BLOCKED", true, true, true,
                50, "key missing", 999_999L, 0, 0, 0L, -1L, false);
        assertEquals(BrainGate.Blocker.DEAD, BrainGate.first(s));
    }

    @Test
    void externalDrivingOutranksTheRest() {
        BrainGate.State s = BrainGate.State.open()
                .with("externallyDriven", true)
                .with("endpointProblem", "key missing")
                .with("modelWindow", 0);
        assertEquals(BrainGate.Blocker.EXTERNALLY_DRIVEN, BrainGate.first(s));
    }

    @Test
    void everyTurnPauseValueIsAReasonButNoneIsNot() {
        for (String p : List.of("OWNER_INTERRUPT", "RECOVERABLE_FAILURE", "BLOCKED")) {
            assertEquals(BrainGate.Blocker.TURN_PAUSE,
                    BrainGate.first(BrainGate.State.open().with("turnPause", p)), "pause=" + p);
        }
        assertEquals(BrainGate.Blocker.NONE, BrainGate.first(BrainGate.State.open().with("turnPause", "NONE")));
        // 大小写/空白不该让「没被叫停」被读成「被叫停」——
        // 这是读数，不是判据；判据在生产代码里已经收窄过了。
        assertEquals(BrainGate.Blocker.NONE,
                BrainGate.first(BrainGate.State.open().with("turnPause", " none ")));
    }

    @Test
    void theMiddleGatesAreReportedInTheSameOrderTheyFire() {
        // 判定顺序是 等响应 → 整理中 → 工具在飞，所以同时命中时先报哪一个是<b>有讲究</b>的：
        // 下游照着「等什么」决定要不要重试，顺序一改就会等错的那件事。
        assertEquals(BrainGate.Blocker.AWAITING_LLM,
                BrainGate.first(BrainGate.State.open().with("compacting", true)
                        .with("awaitingLlmResponse", true)));
        assertEquals(BrainGate.Blocker.COMPACTING,
                BrainGate.first(BrainGate.State.open().with("toolsBusy", true).with("compacting", true)));
        assertEquals(BrainGate.Blocker.TOOLS_BUSY,
                BrainGate.first(BrainGate.State.open().with("toolsBusy", true)));
    }

    // ---- 对话空 / 端点 ----

    @Test
    void anEmptyConversationIsARepsonAndNotASilentStop() {
        assertEquals(BrainGate.Blocker.CONVERSATION_EMPTY,
                BrainGate.first(BrainGate.State.open().with("conversationMsgs", 0)));
    }

    @Test
    void aBlankEndpointProblemIsNotAProblem() {
        assertEquals(BrainGate.Blocker.NONE,
                BrainGate.first(BrainGate.State.open().with("endpointProblem", "")));
        assertEquals(BrainGate.Blocker.NONE,
                BrainGate.first(BrainGate.State.open().with("endpointProblem", "   ")));
        assertEquals(BrainGate.Blocker.NONE,
                BrainGate.first(BrainGate.State.open().with("endpointProblem", null)));
        assertEquals(BrainGate.Blocker.ENDPOINT,
                BrainGate.first(BrainGate.State.open().with("endpointProblem", "没给 key")));
    }

    // ---- 自动整理闸门：B12 的正主 ----

    @Test
    void autoCompactOnlyArmsNearTheWindow() {
        // 1M 窗口、离阈值还有 30k ⇒ 不武装。
        assertFalse(BrainGate.autoCompactArmed(
                BrainGate.State.open().with("contextTokens", 957_000L).with("modelWindow", 1_000_000)));
        // 越过阈值 ⇒ 武装。
        assertTrue(BrainGate.autoCompactArmed(
                BrainGate.State.open().with("contextTokens", 987_000L).with("modelWindow", 1_000_000)));
    }

    @Test
    void anUnknownModelWindowArmsTheGateForeverAndSaysSo() {
        // ★ 本次实机踩的那一刀：取不到窗口时阈值是负数，任何 context 都够格。
        BrainGate.State s = BrainGate.State.open().with("modelWindow", 0);
        assertTrue(BrainGate.autoCompactArmed(s), "窗口未知时阈值变负数，生产代码此刻也照样武装");
        assertEquals(BrainGate.Blocker.AUTO_COMPACT_ARMED, BrainGate.first(s));

        // 关键：忠实复刻 ≠ 认可。窗口不可知必须另报一条问题，
        // 否则读数会把「没人知道窗口多大」显示成「窗口很小、所以该整理了」。
        List<String> problems = BrainGate.problems(s);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("model_window_unknown:")),
                "窗口不可知必须自己报出来，实际=" + problems);
    }

    @Test
    void aShortConversationIsNotWorthCompactingEvenAtTheThreshold() {
        assertFalse(BrainGate.autoCompactArmed(
                BrainGate.State.open().with("contextTokens", 999_999L)
                        .with("conversationMsgs", BrainGate.MIN_COMPACT_MESSAGES - 1)));
        assertTrue(BrainGate.autoCompactArmed(
                BrainGate.State.open().with("contextTokens", 999_999L)
                        .with("conversationMsgs", BrainGate.MIN_COMPACT_MESSAGES)));
    }

    @Test
    void theCircuitBreakerStopsCompactingAfterThreeFailures() {
        // 连续失败到上限 ⇒ 闸门放开，让真轮跑起来。
        // 这是唯一能把「整理失败」变成「不再整理」的机制，所以它必须被钉住。
        for (int f = 0; f < BrainGate.MAX_COMPACT_FAILURES; f++) {
            assertTrue(BrainGate.autoCompactArmed(BrainGate.State.open()
                    .with("contextTokens", 999_999L).with("compactFailures", f)), "failures=" + f);
        }
        assertFalse(BrainGate.autoCompactArmed(BrainGate.State.open()
                .with("contextTokens", 999_999L)
                .with("compactFailures", BrainGate.MAX_COMPACT_FAILURES)));
    }

    @Test
    void aCompactionLoopRiskIsCalledOutBeforeItHappens() {
        // 整理「成功」就把 compactFailures 清零并立刻再开一轮 ⇒ 计数为 0 且已武装
        // 就是「压不下上下文就会成环」的前置状态。这条要在它发生之前就报出来。
        BrainGate.State looping = BrainGate.State.open()
                .with("contextTokens", 990_000L).with("compactFailures", 0);
        assertTrue(BrainGate.problems(looping).stream()
                .anyMatch(p -> p.startsWith("auto_compact_loop_risk:")),
                "实际=" + BrainGate.problems(looping));

        // 断路器已经吃到上限 ⇒ 不该再报「成环风险」（那时候闸门根本没武装）。
        assertFalse(BrainGate.problems(BrainGate.State.open()
                .with("contextTokens", 990_000L)
                .with("compactFailures", BrainGate.MAX_COMPACT_FAILURES)).stream()
                .anyMatch(p -> p.startsWith("auto_compact_loop_risk:")));
    }

    @Test
    void aLatchedBlockIsCalledOutBecauseItNeverClearsItself() {
        // AgentTurnPause.afterWakeEvent 只清 RECOVERABLE_FAILURE ⇒ BLOCKED 是永久的。
        // 不报出来的话，读数会显示「被端点挡住」，而实际上主人什么都没做它也不会好。
        assertTrue(BrainGate.problems(BrainGate.State.open().with("turnPause", "BLOCKED")).stream()
                .anyMatch(p -> p.startsWith("turn_pause_latched:")));
        assertFalse(BrainGate.problems(BrainGate.State.open().with("turnPause", "RECOVERABLE_FAILURE")).stream()
                .anyMatch(p -> p.startsWith("turn_pause_latched:")),
                "可恢复失败会被下一次事件唤醒，不属于永久latch");
    }

    @Test
    void aPerfectlyNormalStateReportsNoProblems() {
        // 防「problems 变成许愿池」：一切正常时就该是空的。
        assertEquals(List.of(), BrainGate.problems(BrainGate.State.open()));
    }

    // ---- 读数口：输入必须一起给出去，否则读数无法自证 ----

    @Test
    void theReadoutCarriesTheInputsNotJustTheConclusion() {
        Map<String, Object> m = BrainGate.readout(BrainGate.State.open()
                .with("contextTokens", 990_000L).with("conversationMsgs", 42));

        assertEquals("auto_compact_armed", m.get("blocker"));
        for (String k : List.of("dead", "externally_driven", "turn_pause", "awaiting_llm_response",
                "compacting", "tool_calls_outstanding", "conversation_msgs", "endpoint_problem",
                "context_tokens", "model_window", "model_window_known", "auto_compact_threshold",
                "auto_compact_armed", "compact_failures", "compact_failures_budget", "problems")) {
            assertTrue(m.containsKey(k), "读数缺 " + k + " —— 只有结论没有输入，下游没法自证");
        }
        assertEquals(42, m.get("conversation_msgs"));
        assertEquals(990_000L, m.get("context_tokens"));
        assertEquals(1_000_000L - BrainGate.AUTO_COMPACT_BUFFER_TOKENS, m.get("auto_compact_threshold"));
        assertEquals(Boolean.TRUE, m.get("model_window_known"));
    }

    @Test
    void anUnknownEndpointIsReportedAsEmptyRatherThanNull() {
        // 埋点落进 JSONL 后 null 会变成字段消失；「没绑问题」必须显式写成空串。
        Map<String, Object> m = BrainGate.readout(BrainGate.State.open());
        assertEquals("", m.get("endpoint_problem"));
        assertNotEquals(null, m.get("endpoint_problem"));
    }

    @Test
    void twoDifferentCausesNeverLookTheSameDownstream() {
        // 「被自动整理挡住」和「被端点挡住」在只看 blocker 时都只是「没动」。
        // 这条钉住它们的确给出不同的 id 与不同的输入。
        Map<String, Object> compact = BrainGate.readout(BrainGate.State.open()
                .with("contextTokens", 999_999L).with("modelWindow", 0));
        Map<String, Object> endpoint = BrainGate.readout(BrainGate.State.open()
                .with("endpointProblem", "key missing"));

        assertEquals("auto_compact_armed", compact.get("blocker"));
        assertEquals("endpoint_problem", endpoint.get("blocker"));
        assertEquals(Boolean.FALSE, compact.get("model_window_known"));
        assertEquals(Boolean.TRUE, endpoint.get("model_window_known"));
    }

    @Test
    void everyBlockerHasAStableIdAndALabel() {
        // id 进埋点当键用 ⇒ 改名等于改历史数据的含义，必须先被看见。
        List<String> ids = new ArrayList<>();
        for (BrainGate.Blocker b : BrainGate.Blocker.values()) {
            assertFalse(b.id().isBlank(), b.name() + " 没有 id");
            assertFalse(b.label().isBlank(), b.name() + " 没有 label");
            assertFalse(ids.contains(b.id()), "id 重复：" + b.id());
            ids.add(b.id());
        }
        assertEquals(BrainGate.Blocker.values().length, ids.size());
    }

    // ---- B15:事件被外脑取走（第 11 个维度）----

    /**
     * ★ 本条钉的是 B12 那个读数口<b>最大的一处盲区</b>，以及 B16 的修法边界：
     * 内脑 {@code drainInbox} 与外脑旧实现 {@code takeEventsForExternal} 跑的是字面同一行
     * {@code queue.takeWhile(...)}，同一个 {@code queue}、都是破坏性取走、
     * 链路上没有任何仲裁 ⇒ 静默饿死时 {@code blocker} 仍是 {@code none}。
     *
     * <p>B16 把外脑取件改成<b>非消费型 tap</b>（只读镜像）之后，抢不动内脑了 ⇒
     * {@code external_event_takeover} 就从「常态告警」变成<b>回归警报</b>：
     * 平时<b>必须不响</b>（一条永远在响的假警报会让人忽略真警报，那更坏），
     * 只在有人把取件改回 {@code queue.takeWhile} 时才响。
     */
    @Test
    void theTakeoverAlarmOnlyRingsWhenTheChannelRegressesToConsuming() {
        // B16 之后：非消费型 tap，取过很多次也<b>不许</b>报
        BrainGate.State healthyButBusy = BrainGate.State.open()
                .with("externalTakes", 7L).with("msSinceLastExternalTake", 2_000L)
                .with("externalTakeConsumesQueue", false);
        assertEquals(Boolean.TRUE, BrainGate.readout(healthyButBusy).get("externally_taken"),
                "外脑确实取过东西（这是事实，读数要承认）");
        assertTrue(BrainGate.problems(healthyButBusy).stream()
                        .noneMatch(p -> p.startsWith("external_event_takeover")),
                "★ 非消费型 tap 抢不动内脑 ⇒ 这条不许再报。永远在响的假警报比不报更坏");

        // 回归：有人把取件改回 queue.takeWhile ⇒ 必须立刻开口
        BrainGate.State regressed = BrainGate.State.open()
                .with("externalTakes", 7L).with("msSinceLastExternalTake", 2_000L)
                .with("externalTakeConsumesQueue", true);
        assertTrue(BrainGate.problems(regressed).stream().anyMatch(p -> p.startsWith("external_event_takeover:")),
                "★ 取件一旦退回消费型，这条必须第一时间响 —— 它就是为那一刻存在的");
        assertTrue(String.valueOf(BrainGate.problems(regressed).get(0)).contains("回归警告"),
                "这条现在是一次回归警报，措辞必须让人一眼看出「这不是常态」");

        // blocker 在两种情况下都不变：取件不是一道拒绝闸
        assertEquals("none", BrainGate.readout(healthyButBusy).get("blocker"));
        assertEquals("none", BrainGate.readout(regressed).get("blocker"),
                "★ 取件不是一道闸：闸是开的，只是队列可能被拿空了");
    }

    /** 读数要能自证「现在走的是哪条通道」——只有结论没有模式名，等于让人猜。 */
    @Test
    void theReadoutSaysWhichChannelItIsOn() {
        assertEquals("non_consumptive_tap",
                BrainGate.readout(BrainGate.State.open()).get("external_take_mode"));
        assertEquals("consumes_inner_queue",
                BrainGate.readout(BrainGate.State.open().with("externalTakeConsumesQueue", true))
                        .get("external_take_mode"));
    }

    /**
     * 「从来没被取走过」必须与「刚刚被取走过」分得开。
     *
     * <p>两者都可能被写成 {@code ms_since_last_external_take = 0}，而下游看到 0
     * 会读成「正在被抢」—— 一个从未发生的事被读成正在发生，比不报更坏。
     */
    @Test
    void neverTakenIsNotTheSameAsJustTaken() {
        BrainGate.State never = BrainGate.State.open();
        assertEquals(-1L, never.msSinceLastExternalTake(), "open() 的默认必须是「无记录」而不是 0");
        assertEquals(-1L, BrainGate.readout(never).get("ms_since_last_external_take"));

        BrainGate.State just = BrainGate.State.open()
                .with("externalTakes", 1L).with("msSinceLastExternalTake", 0L)
                .with("externalTakeConsumesQueue", true);
        assertEquals(0L, BrainGate.readout(just).get("ms_since_last_external_take"));
        assertTrue(BrainGate.problems(just).stream()
                        .anyMatch(p -> p.contains("0 秒")),
                "0 毫秒要读成「0 秒」而不是「无记录」");
        assertTrue(BrainGate.problems(never).stream().noneMatch(p -> p.contains("无记录")),
                "没发生过就压根不该报这条，不该出现「无记录」这种措辞");
    }

    /** 距上次取走多久要写成人能一眼看懂的样子，且不许出现「-1 秒」这种话。 */
    @Test
    void theAgeOfTheLastTakeIsReadableAndNeverNegative() {
        java.util.function.LongFunction<String> age = ms -> {
            BrainGate.State s = BrainGate.State.open()
                    .with("externalTakes", 1L).with("msSinceLastExternalTake", ms)
                    .with("externalTakeConsumesQueue", true);
            return String.join("|", BrainGate.problems(s));
        };
        assertTrue(age.apply(900L).contains("0 秒"), "900ms 算 0 秒，不许四舍五入成 1 秒");
        assertTrue(age.apply(125_000L).contains("2 分钟"));
        assertTrue(age.apply(7_200_000L).contains("2 小时"));
        assertFalse(age.apply(-1L).contains("-1 秒"), "★ 「无记录」不许被写成 -1 秒");
    }

    /**
     * 计数只在「真的取到了东西」之后加 —— 长轮询空转不计数。
     *
     * <p>这条钉的是取件那一侧的记账位置（B12 那条假绿的同族问题：信号必须来自
     * 真实动作，不然一个挂着的空闲客户端就能把计数刷成假的）。
     */
    @Test
    void aTakeThatGotNothingDoesNotCountAsATakeover() {
        Map<String, Object> r = BrainGate.readout(BrainGate.State.open());
        assertEquals(0L, r.get("external_takes"));
        assertEquals(Boolean.FALSE, r.get("externally_taken"));
        assertTrue(BrainGate.problems(BrainGate.State.open()).stream()
                .noneMatch(p -> p.contains("external_event_takeover")),
                "空转不得留下任何「被取走」的痕迹");
    }
}