package com.dwinovo.numen.agent.llm;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每个同伴的 <b>LLM 在飞快照</b>——只读观测，<b>没有任何控制能力</b>，不依赖 Minecraft。
 *
 * <p><b>为什么要有这个（2026-09-30 实机教训）</b>：卡死监督的 {@code llm_idle_stall}
 * 判据只看「身体/资产有没有变」，<b>看不见模型是不是正在飞</b>。实测分布是
 * 空转 35.6s / 有效 44.0s —— 有效的那次比空转还慢，所以<b>任何纯时间阈值都区分不了
 * 「卡住」与「慢」</b>。在不知道「在飞」的前提下拍醒，<b>误拍正在思考的模型</b>，
 * 而拍醒的措辞会覆盖原计划（见 {@code RddStallWatcher} 的三条话术铁律）。
 *
 * <p><b>为什么不放在 {@link NumenLlmClient} 的字段里</b>：那个 client 是<b>无状态共享</b>的，
 * 一次调用它并不知道自己属于哪个同伴。能带身份的只有 {@link LlmObservation}
 * （构造时就带了 {@code companionId} + {@code phase}），而它已经穿过
 * {@code chatStreaming(..., observation)} 这条唯一通道 —— 所以这里靠 observation 认领身份，
 * <b>不需要改任何调用方</b>。
 *
 * <p><b>★ 为什么是「在飞请求集合 + token 销账」而不是一个布尔（Codex 审稿 P0）</b>：
 * 同一个同伴可以<b>同时</b>有多个请求在飞（{@code execution} / {@code execution_retry} /
 * {@code goal_judging} / {@code compaction}；军师 {@code RddDecomposer} 也会为同一 UUID 发请求）。
 * 单个 boolean 会出这个时序：
 * <ol>
 *   <li>A dispatch → 在飞={A}</li>
 *   <li>B dispatch → 在飞={A,B}</li>
 *   <li>A <b>先</b>落地 → 布尔版会写 {@code inFlight=false}，可 B 其实还在飞</li>
 * </ol>
 * 结果是<b>在阈值处误拍还在思考的 B</b>。{@code ConcurrentHashMap} 只保证单次 get/put 的
 * 可见性，<b>不保证「这次落地属于哪个请求」</b>。所以落地必须<b>按 token 精确销账</b>，
 * 只减自己那一个，一个都不碰别人的。
 *
 * <p><b>为什么用 {@link System#nanoTime()} 而不是 {@code currentTimeMillis()}</b>（Codex 审稿 P1-3）：
 * 墙上时钟会被调时/NTP 回拨。一旦回拨，{@code now - dispatched} 变负 → stale 永不触发
 * → 监督静默失明。时长计算必须走单调时钟。
 *
 * <p><b>为什么自带 stale 上界</b>：如果那个 future 永远不完成（连接挂死、<b>被调用方取消</b>），
 * 在飞集合会永远非空 → 卡死监督被<b>静默关掉</b>，比误拍更糟（没人拍 = 永远卡）。
 * 所以 {@link Snapshot#stale} 给出路：超过 {@link #MAX_INFLIGHT_NANOS} 就不认「在飞」，
 * 宁可误催，不可静默失明。
 */
public final class LlmActivity {

    /** 在飞超过这么久（2 分钟，与传输层单次 header timeout 同量级）就不认「在飞」了。 */
    public static final long MAX_INFLIGHT_NANOS = 120_000_000_000L;

    /**
     * 一次调用的只读快照。
     *
     * @param known              这个同伴是否被观测过（没被观测过 = 没有身份，<b>不敢替它担保</b>）
     * @param inFlight           此刻是否<b>至少有一个</b> LLM 请求在飞
     * @param inFlightCount      在飞请求数（&gt;1 = 这个同伴有并发请求，排查时有用）
     * @param oldestInFlightNanos 最老那个在飞请求已飞多久（单调纳秒）；无在飞时为 0
     * @param phase              最近一次调用的相位（execution / goal_judging / compaction / …）
* @param lastFinish         最近一次落地的 {@code finish_reason}（失败时 null）
     * @param lastToolCalls      最近一次落地带的工具调用数；<b>-1 = 失败落地</b>
     * @param reportedStaleToken 已就「在飞过久」告警过的那个最老在飞 token；0 = 从没告警过
     * @param consecutiveNoToolCallResponses <b>连续「回答完了但零工具调用」的轮数</b>
     *        （2026-10-01 新增）。这是真正能区分「卡住」与「想得慢」的信号：
     *        只看响应内容，不看时钟，所以判定本身<b>零成本</b>（花钱的是拍醒，不是判定）。
     *        任何一次带了工具调用就归零。
     */
    public record Snapshot(boolean known, boolean inFlight, int inFlightCount,
                           long oldestInFlightToken, long oldestInFlightNanos, boolean stale,
                           String phase, String lastFinish, int lastToolCalls, long reportedStaleToken,
                           int consecutiveNoToolCallResponses) {
        public static final Snapshot UNKNOWN =
                new Snapshot(false, false, 0, 0L, 0L, false, null, null, -1, 0L, 0);

        /**
         * 这次「在飞过久」还没告警过 → 该发一条。
         *
         * <p>去重用的是 {@link #oldestInFlightToken}（<b>飞行期间稳定的身份</b>），
         * 不是年龄 —— 年龄每 tick 都在变，拿它去重等于没去重。
         * 不去重的话 {@code RddStallWatcher.track()} 每秒发一条，挂死几小时就是几万条日志。
         */
        public boolean staleUnreported() {
            return stale && oldestInFlightToken != reportedStaleToken;
        }
    }

    private static final class State {
        final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        String phase;
        volatile String lastFinish;
        volatile int lastToolCalls = -1;
        /** 连续「回答完但零工具调用」的轮数；带工具调用即归零。 */
        volatile int consecutiveNoToolCallResponses;
        volatile long reportedStaleToken;

        State(String phase) { this.phase = phase; }
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    /** token -> 发出时刻的单调纳秒。 */
    private static final Map<Long, Long> TOKEN_NANOS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong();

    private LlmActivity() {}

    /**
     * 认领身份：这条观察挂在哪个同伴名下，并发一个<b>本次请求专属的 token</b>。
     *
     * <p><b>返回值必须原样传给 {@link #markSettled}</b> —— 落地时按 token 销账，
     * 才不会误销并发请求里的别人（见类注释的 P0）。
     *
     * @return 本次请求的 token；{@code companionId} 为 null/空时返回 0（无身份，不参与统计）
     */
    public static long markDispatched(String companionId, String phase) {
        if (isBlank(companionId)) return 0L;
        long token = TOKENS.incrementAndGet();
        long now = System.nanoTime();
        TOKEN_NANOS.put(token, now);
        STATES.computeIfAbsent(companionId, id -> new State(phase)).inFlight.add(token);
        return token;
    }

    /**
     * 落地：<b>只销自己那一个 token</b>，别的在飞请求一个都不动。
     *
     * @param token     {@link #markDispatched} 的返回值；0 = 无身份，直接忽略
     * @param finish    {@code finish_reason}；失败时传 null
     * @param toolCalls 本次带的工具调用数；失败时传 -1
     */
    public static void markSettled(String companionId, long token, String finish, int toolCalls) {
        if (isBlank(companionId) || token == 0L) return;
        State st = STATES.get(companionId);
        if (st == null) return; // 已被 forget() 清掉：<b>不重插</b>（否则迟到落地会把行塞回来）
        st.inFlight.remove(token);
        TOKEN_NANOS.remove(token);
        st.lastFinish = finish;
        st.lastToolCalls = toolCalls;
        // 连续「回答完但零工具调用」计数（2026-10-01）：这是真正能区分卡住/想得慢的信号。
        // 只有**成功落地**（toolCalls >= 0）才算数 —— 失败落地不是模型的判断，混进去会虚高。
        if (toolCalls == 0) {
            st.consecutiveNoToolCallResponses++;
        } else if (toolCalls > 0) {
            st.consecutiveNoToolCallResponses = 0;   // 真动手了就归零
        }
    }

    /**
     * 记录「已就这个在飞 token 发过 stale 告警」，供 {@link Snapshot#staleUnreported()} 去重。
     * 不去重的话 {@code RddStallWatcher.track()} 每秒发一次，挂死几小时就是几万条日志。
     *
     * @param oldestInFlightToken {@link Snapshot#oldestInFlightToken()}
     */
    public static void markStaleReported(String companionId, long oldestInFlightToken) {
        if (isBlank(companionId)) return;
        State st = STATES.get(companionId);
        if (st == null) return;
        st.reportedStaleToken = oldestInFlightToken;
    }

    /** 只读快照。没有这个同伴的记录时返回 {@link Snapshot#UNKNOWN}（<b>inFlight=false</b>）。 */
    public static Snapshot snapshot(String companionId) {
        return snapshot(companionId, System.nanoTime());
    }

    /** 同上，但时钟由调用方给 —— 单测要能确定性地跨过 {@link #MAX_INFLIGHT_NANOS}。 */
    public static Snapshot snapshot(String companionId, long nowNanos) {
        if (isBlank(companionId)) return Snapshot.UNKNOWN;
        State st = STATES.get(companionId);
        if (st == null) return Snapshot.UNKNOWN;
        int count = st.inFlight.size();
        if (count == 0) {
            return new Snapshot(true, false, 0, 0L, 0L, false, st.phase,
                    st.lastFinish, st.lastToolCalls, st.reportedStaleToken,
                    st.consecutiveNoToolCallResponses);
        }
        long oldestToken = 0L;
        long oldestAge = 0L;
        for (Long t : st.inFlight) {
            Long born = TOKEN_NANOS.get(t);
            if (born == null) continue;
            long age = nowNanos - born;
            if (age > oldestAge) { oldestAge = age; oldestToken = t; }
        }
        boolean stale = oldestAge > MAX_INFLIGHT_NANOS;
        return new Snapshot(true, true, count, oldestToken, oldestAge, stale, st.phase,
                st.lastFinish, st.lastToolCalls, st.reportedStaleToken,
                st.consecutiveNoToolCallResponses);
    }

    /** 同伴下线/换存档时清掉它的行，别让快照在内存里过夜。 */
    public static void forget(String companionId) {
        if (isBlank(companionId)) return;
        State st = STATES.remove(companionId);
        if (st != null) st.inFlight.clear();
    }

    /** 只给单测用：清空整张表。 */
    static void reset() {
        STATES.clear();
        TOKEN_NANOS.clear();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
