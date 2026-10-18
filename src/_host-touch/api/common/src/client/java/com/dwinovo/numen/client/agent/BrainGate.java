package com.dwinovo.numen.client.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这一轮为什么开不起来」的唯一口径 —— 纯判定、纯读数，不碰任何 Minecraft 类型。
 *
 * <h2>为什么要有这个类（B12 实机逼出来的）</h2>
 * 2026-10-03 实机排查「内脑为什么不派活」时发现：{@code EntityAgentLoop.tryStartTurn}
 * 的每一道拒绝都只写进游戏日志（{@code LOG.debug/info/warn}），而游戏日志在
 * {@code launch-mc.ps1} 脱离启动下是 <b>0 字节</b>（stdout 随父进程一起没了）。
 * 于是「AI 没动」这件事从外部<b>一个字都问不出来</b>，只能靠猜 ——
 * 本次先后误判成「MCP 抢了驾驶席」和「内脑死着没解冻」两次。
 * <p>判定逻辑本身没有错，错在<b>只有它自己知道</b>。
 *
 * <h2>口径纪律</h2>
 * <ul>
 *   <li><b>顺序必须与 {@code tryStartTurn} 逐行一致</b>。这里是<b>描述</b>那串
 *       {@code if} 的唯一出处；改一处不改另一处，读数就会开始说谎 ——
 *       而这份读数的全部价值就是「不说谎」。</li>
 *   <li><b>忠实复刻，包括看起来像 bug 的那部分</b>：{@link #autoCompactArmed} 在
 *       {@code modelWindow} 取不到时照样返回 true，因为生产代码此刻也照样
 *       「武装」。把它在这里改成 false 会让读数比现实好看一点 ——
 *       那正是本工程最贵的那类假绿。<b>窗口不可知这件事本身另算一条问题</b>
 *       （{@link #problems}），不靠改判定来掩盖。</li>
 * </ul>
 *
 * <p>形状照 {@code ExperienceQualityGate} / {@code CandidateGate}：纯函数 +
 * 一个 {@code toMap} 读数口 + 单出口枚举，便于单测直接覆盖。
 */
public final class BrainGate {

    private BrainGate() {}

    /** 与 {@code EntityAgentLoop.AUTO_COMPACT_BUFFER_TOKENS} 同值；改一处必须改两处。 */
    public static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;

    /** 与 {@code EntityAgentLoop.MIN_COMPACT_MESSAGES} 同值。 */
    public static final int MIN_COMPACT_MESSAGES = 8;

    /** 与 {@code EntityAgentLoop.MAX_COMPACT_FAILURES} 同值。 */
    public static final int MAX_COMPACT_FAILURES = 3;

    /**
     * 一轮开不起来的<b>原因</b>。id 是给机器读的（埋点/面板），name 是给人读的。
     * 顺序 = 判定顺序，<b>不要重排</b>：重排会让「第一个原因」变得没有意义
     * （同时命中两道闸时，只有最靠前那道是真的挡住了）。
     */
    public enum Blocker {
        /** 可以开轮。 */
        NONE("none", "可以开轮"),
        /** 身体没了，等定时复活（{@code onEntityDied} → {@code onRespawned}）。 */
        DEAD("dead", "身体不在了，等复活"),
        /** 外接大脑在驾驶（{@code McpMode.driving()}）。 */
        EXTERNALLY_DRIVEN("externally_driven", "外接大脑在驾驶"),
        /** 主人叫停 / 可恢复失败 / 端点被封（{@code AgentTurnPause}）。 */
        TURN_PAUSE("turn_pause", "这一轮被叫停"),
        /** 上一次请求还在飞。 */
        AWAITING_LLM("awaiting_llm_response", "上一次请求还在飞"),
        /** 正在整理记忆。 */
        COMPACTING("compacting", "正在整理记忆"),
        /** 有工具调用没回来。 */
        TOOLS_BUSY("tool_calls_outstanding", "有工具调用没回来"),
        /** 排空之后对话仍空（没有可发的东西）。 */
        CONVERSATION_EMPTY("conversation_empty", "没有可发的东西"),
        /** 模型绑定/端点有问题（会顺带把 {@code turnPause} 封成 BLOCKED）。 */
        ENDPOINT("endpoint_problem", "模型端点有问题"),
        /** 自动整理闸门武装：这一轮会被拿去整理，而不是拿去思考。 */
        AUTO_COMPACT_ARMED("auto_compact_armed", "自动整理闸门武装，这一轮不开工");

        private final String id;
        private final String label;

        Blocker(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** 机器读：埋点与面板用这个。 */
        public String id() {
            return id;
        }

        /** 人读：这个原因意味着什么。 */
        public String label() {
            return label;
        }
    }

    /**
     * 判定所需的全部输入。全部是<b>已经算好的标量</b> —— 判定本身不许去碰
     * {@code convo} / {@code queue} / {@code dispatcher}，否则它就没法单测。
     *
     * @param dead {@code EntityAgentLoop.dead}：身体没了，等复活
     * @param externallyDriven {@code McpMode.driving()}：外脑在驾驶
     * @param turnPause {@code AgentTurnPause.name()}；{@code "NONE"} 表示没被叫停
     * @param awaitingLlmResponse 上一次 LLM 响应是否还在飞
     * @param compacting 是否正在整理记忆
     * @param toolsBusy 是否有工具调用没回来
     * @param conversationMsgs 排空之后的对话条数（{@code convo.snapshot().size()}）
     * @param endpointProblem {@code endpointProblem()} 的原文；{@code null}/空 = 没绑问题
     * @param contextTokens 上一轮真实用量，或本地估算
     * @param modelWindow 模型上下文窗口；<b>0 = 取不到绑定</b>（不是「窗口为零」）
     * @param compactFailures 整理连续失败次数
     * @param externalTakes 本会话里<b>外脑取走过事件的总次数</b>（B16 之后是「从只读
     *        tap 取走几批」，不再意味着内脑被抢）。这是本读数新增的第 11 个维度，理由见
     *        {@link #problems} 里那条 {@code external_event_takeover} 回归警报。
     * @param msSinceLastExternalTake 距上次被外脑取走事件过了多少毫秒；<b>-1 = 本会话一次都没被取走</b>
     *        （不是「刚被取走」——这两种不能混，混了下游会把「从来没发生过」读成「正在发生」）
     * @param externalTakeConsumesQueue 外脑取件走的是<b>消费型</b>接口（从内脑那份队列里
     *        {@code takeWhile}）还是<b>非消费型 tap</b>（读只读镜像）。B16 修完之后应当
     *        <b>永远是 false</b> —— 这条留着的唯一目的是<b>回归警报</b>：谁再把取件改回
     *        {@code queue.takeWhile}，读数会立刻开口，而不是静默回到「内脑被饿死且 blocker=none」
     */
    public record State(boolean dead,
                        boolean externallyDriven,
                        String turnPause,
                        boolean awaitingLlmResponse,
                        boolean compacting,
                        boolean toolsBusy,
                        int conversationMsgs,
                        String endpointProblem,
                        long contextTokens,
                        int modelWindow,
                        int compactFailures,
                        long externalTakes,
                        long msSinceLastExternalTake,
                        boolean externalTakeConsumesQueue) {

        /**
         * 八道闸全开的干净状态，测判定顺序时拿它当基线。
         *
         * <p>★ {@code conversationMsgs} 必须给一个<b>高于 {@link #MIN_COMPACT_MESSAGES}</b>
         * 的值（这里 20），否则这个「全开」基线自己就被自动整理闸挡住 ——
         * 我第一版给的是 4，结果六条测试一起红，而红的理由是「基线起手就错」，
         * 不是被测代码有问题。基线不干净，后面每一条断言都在替一个错的起点说话。
         *
         * <p>★ 同理 {@code externalTakes} 给 0 且 {@code externalTakeConsumesQueue} 给
         * {@code false}：这个基线代表「没人来抢、通道是非消费型的」，给了别的值它就会带
         * problem 出来，基线就不干净了。
         */
        public static State open() {
            return new State(false, false, "NONE", false, false, false, 20, null,
                    1_000L, 1_000_000, 0, 0, -1L, false);
        }

        /** 改一个字段造新状态（record 没有 withX，全部走这里，免得十几行复制粘贴）。 */
        public State with(String field, Object value) {
            return new State(
                    field.equals("dead") ? truthy(value) : dead,
                    field.equals("externallyDriven") ? truthy(value) : externallyDriven,
                    field.equals("turnPause") ? String.valueOf(value) : turnPause,
                    field.equals("awaitingLlmResponse") ? truthy(value) : awaitingLlmResponse,
                    field.equals("compacting") ? truthy(value) : compacting,
                    field.equals("toolsBusy") ? truthy(value) : toolsBusy,
                    field.equals("conversationMsgs") ? ((Number) value).intValue() : conversationMsgs,
                    field.equals("endpointProblem") ? (String) value : endpointProblem,
                    field.equals("contextTokens") ? ((Number) value).longValue() : contextTokens,
                    field.equals("modelWindow") ? ((Number) value).intValue() : modelWindow,
                    field.equals("compactFailures") ? ((Number) value).intValue() : compactFailures,
                    field.equals("externalTakes") ? ((Number) value).longValue() : externalTakes,
                    field.equals("msSinceLastExternalTake") ? ((Number) value).longValue() : msSinceLastExternalTake,
                    field.equals("externalTakeConsumesQueue") ? truthy(value) : externalTakeConsumesQueue);
        }

        private static boolean truthy(Object v) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
        }
    }

    /**
     * 自动整理闸门此刻是否武装。<b>忠实复刻生产判定</b>，包括
     * {@code modelWindow == 0}（取不到绑定）时阈值变成负数、恒成立这件事。
     *
     * <p>⚠️ 取不到窗口时这不是「窗口很小」，而是「<b>没人知道窗口多大</b>」。
     * 阈值 {@code 0 - 13000} 让任何 context 都够格触发整理，于是每一轮都被拿去
     * 整理、永远轮不到真正的思考 —— 而整理那条路不写 {@code ai.jsonl}，
     * 外面看见的就是「她一直活着，就是不动」。真判据见 {@link #problems}。
     */
    public static boolean autoCompactArmed(State s) {
        return s.contextTokens() >= (long) s.modelWindow() - AUTO_COMPACT_BUFFER_TOKENS
                && s.conversationMsgs() >= MIN_COMPACT_MESSAGES
                && s.compactFailures() < MAX_COMPACT_FAILURES;
    }

    /**
     * 第一道挡住这一轮的闸。<b>顺序 = {@code tryStartTurn} 的顺序</b>：
     * 停牌 → 叫停 → 等响应 → 整理中 → 工具在飞 → 对话空 → 端点 → 自动整理武装。
     *
     * <p>注意<b>队列空不在这里</b>：{@code drainInbox()} 在空队列时只是「没东西可取」
     * 并直接返回 false，它从来不是一道拒绝闸。把它算成闸会让读数凭空多一种原因。
     */
    public static Blocker first(State s) {
        if (s.dead()) return Blocker.DEAD;
        if (s.externallyDriven()) return Blocker.EXTERNALLY_DRIVEN;
        if (s.turnPause() != null && !"NONE".equalsIgnoreCase(s.turnPause().trim())) {
            return Blocker.TURN_PAUSE;
        }
        if (s.awaitingLlmResponse()) return Blocker.AWAITING_LLM;
        if (s.compacting()) return Blocker.COMPACTING;
        if (s.toolsBusy()) return Blocker.TOOLS_BUSY;
        if (s.conversationMsgs() <= 0) return Blocker.CONVERSATION_EMPTY;
        if (s.endpointProblem() != null && !s.endpointProblem().isBlank()) {
            return Blocker.ENDPOINT;
        }
        if (autoCompactArmed(s)) return Blocker.AUTO_COMPACT_ARMED;
        return Blocker.NONE;
    }

    /**
     * 读数口：门禁结果 + 它依赖的全部输入。
     *
     * <p>★ <b>输入必须一起给出去</b>，只给一个 {@code blocker} 等于让读数无法自证：
     * 「被自动整理挡住」和「被端点挡住」在下游看来都是「没动」，没有输入就分不开。
     */
    public static Map<String, Object> readout(State s) {
        Blocker b = first(s);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocker", b.id());
        m.put("blocker_label", b.label());
        m.put("dead", s.dead());
        m.put("externally_driven", s.externallyDriven());
        m.put("turn_pause", s.turnPause());
        m.put("awaiting_llm_response", s.awaitingLlmResponse());
        m.put("compacting", s.compacting());
        m.put("tool_calls_outstanding", s.toolsBusy());
        m.put("conversation_msgs", s.conversationMsgs());
        m.put("endpoint_problem", s.endpointProblem() == null ? "" : s.endpointProblem());
        m.put("context_tokens", s.contextTokens());
        m.put("model_window", s.modelWindow());
        m.put("model_window_known", s.modelWindow() > 0);
        m.put("auto_compact_threshold",
                (long) s.modelWindow() - AUTO_COMPACT_BUFFER_TOKENS);
        m.put("auto_compact_armed", autoCompactArmed(s));
        m.put("compact_failures", s.compactFailures());
        m.put("compact_failures_budget", MAX_COMPACT_FAILURES);
        // ★ 第 11 个维度：外脑取件。externally_taken 一眼能看出「事件是不是被人拿走了」——
        //   这件事在这份读数里原本完全不存在，于是「内脑没事做」和「内脑被饿着」
        //   在下游看来是同一句话（就是 blocker=none）。2026-10-03 B14 查出来的那条。
        m.put("externally_taken", s.externalTakes() > 0);
        m.put("external_takes", s.externalTakes());
        m.put("ms_since_last_external_take", s.msSinceLastExternalTake());
        m.put("external_take_mode", s.externalTakeConsumesQueue() ? "consumes_inner_queue"
                : "non_consumptive_tap");
        m.put("problems", problems(s));
        return m;
    }

    /**
     * 「这读数里哪些地方本身就不对」—— 与 {@link #first} 刻意分开：
     * {@code first} 说「现在被什么挡住」，{@code problems} 说「这份配置/状态有什么坑」。
     *
     * <p>⚠️ 这里报的每一条都是<b>真会咬人</b>的，不是「建议」。
     */
    public static java.util.List<String> problems(State s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s.modelWindow() <= 0) {
            out.add("model_window_unknown:取不到模型上下文窗口，自动整理阈值变成负数，"
                    + "于是每一轮都满足整理条件（真轮永远开不起来），而整理这条路不写 ai.jsonl");
        }
        if (autoCompactArmed(s) && s.modelWindow() > 0
                && s.contextTokens() >= (long) s.modelWindow() - AUTO_COMPACT_BUFFER_TOKENS
                && s.compactFailures() == 0) {
            out.add("auto_compact_loop_risk:整理连续失败计数为 0 却已武装，"
                    + "整理一旦「成功」就把计数清零并立刻再开一轮，压不下上下文就是死循环");
        }
        if ("BLOCKED".equalsIgnoreCase(String.valueOf(s.turnPause()).trim())) {
            out.add("turn_pause_latched:BLOCKED 只能被显式解除（afterWakeEvent 只清 RECOVERABLE_FAILURE），"
                    + "不会自行恢复");
        }
        // ★★ B16 修完之后这一条**不再是常态告警，改成回归警报**。
        //   旧实现里外脑用消费型接口（`takeEventsForExternal` 调 `queue.takeWhile`，
        //   与内脑 `drainInbox` 字面同一行、同一个 entries、都会 `subList(0,n).clear()`），
        //   于是「内脑安静」与「内脑被饿着」在 blocker 上都是 none，静默饿死不留痕。
        //   现在取件走非消费型 tap（只读镜像），**抢不动内脑** ⇒ 所以平时**不许报**这条，
        //   否则就是一条永远在响的假警报（而假警报会让人忽略真警报，那更坏）。
        //   它只在 `externalTakeConsumesQueue` 为 true 时才响 —— 也就是有人把取件改回
        //   `queue.takeWhile` 的那一刻。
        if (s.externalTakeConsumesQueue()) {
            out.add("external_event_takeover:★ 回归警告 —— 外脑取件又变回消费型接口"
                    + "（从内脑那份队列里 takeWhile）。内脑与外脑共用同一份 takeWhile 队列且无仲裁，"
                    + "所以内脑「长时间没事做」有可能只是「事件被别人拿走了」，"
                    + "而不是它自己不想动。当前累计取走 " + s.externalTakes() + " 次，"
                    + "最后一次在 " + describeAge(s.msSinceLastExternalTake()) + "前");
        }
        return out;
    }

    /** 把「多久前」写成人能一眼看懂的样子；{@code -1}（从没发生过）不许说成「刚刚」。 */
    private static String describeAge(long millis) {
        if (millis < 0) {
            return "（无记录）";
        }
        long sec = millis / 1000L;
        if (sec < 60L) {
            return sec + " 秒";
        }
        long min = sec / 60L;
        if (min < 60L) {
            return min + " 分钟";
        }
        return (min / 60L) + " 小时";
    }
}