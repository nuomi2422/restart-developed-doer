package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡死监督：判断"看起来在跑但实际没动"，并决定拍醒 / 恢复 / 升级失败。
 *
 * <p>核心是<b>资产指纹</b>（背包计数 + 方块位置 + 容器内容 + 身体进度）。
 * 连续 {@link #STALL_AFTER_TICKS} 次检测指纹不变 → 判 STALLED 并拍醒将军；
 * 拍醒后给 {@link #STALL_RESPONSE_TICKS} 的响应窗；窗口过了仍不动 → 换措辞再拍；
 * 拍 {@link #MAX_NUDGES} 次无效 → 判失败（Level 1 恢复兜底，绝不伪造完成）。
 *
 * <p>顺带累计"当前二级卡死次数"，达 {@link #CAPABILITY_GAP_AFTER_STALLS} 即发能力缺口信号。
 *
 * <p>只读世界真身；状态只增不减地绑在当前二级上（二级换了才重置）。
 */
final class RddStallWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RddStallWatcher.class);

    /** 卡死监督：资产指纹（背包+位置）连续多少次检测无变化即判 STALLED（1 次/秒）。
     *  取 15 而非 5：AI 的 LLM 轮次（DeepSeek 思考 + 工具链）可能要 10~15 秒，
     *  太短会把"正在思考/刚起步"误判成卡死。 */
    private static final int STALL_AFTER_TICKS = RddStallPolicy.IDLE_GRACE_CHECKS;
    /** 拍醒后的响应窗口：STALLED 后给 AI 这么长时间行动（资产变化则恢复），
     *  仍未动才 re-nudge / 升级。避免"拍完不到 2 秒就判失败"。 */
    private static final int STALL_RESPONSE_TICKS = 25;
    /** 拍醒上限：超过则判失败（Level 1 恢复兜底）。 */
    private static final int MAX_NUDGES = 2;
    /** Level 3：当前二级累计卡死多少次即判定能力不足（AI 反复拍醒仍无法达成目标资产）。 */
    private static final int CAPABILITY_GAP_AFTER_STALLS = 3;

    private final RddFurnaceWatch furnaceWatch;

    /** 卡死监督状态：记录每个同伴当前二级的资产指纹与未变化计数。 */
    private final Map<UUID, StallState> stalls = new ConcurrentHashMap<>();

    /**
     * 卡死监督状态。<b>两个指纹分开放</b>，因为它们回答的是两个不同的问题。
     *
     * <ul>
     *   <li>{@code windowFingerprint}（精确坐标）：喂给 {@link RddStallPolicy#check} 的
     *       <b>无进展窗口</b> —— <b>走路本身算进展</b>，否则正在赶路的同伴会被误判成卡死
     *       （2026-09-30 定的口径，保留不动）。</li>
     *   <li>{@code stateFingerprint}（8 格粗桶）：给 STALLED 期间判「<b>真</b>进展」用。
     *       同伴在两点之间来回走时坐标每 tick 都变，用精确坐标会让 {@link #handleStalled}
     *       的「变了 → resumeFromStalled + 计数归零」被反复触发，响应窗和 {@link #MAX_NUDGES}
     *       被无限重置 —— 2026-10-03 实测：同一条催工话术在 16:49~17:05 一字不变地刷了 42 次，
     *       永远走不到判失败那一步（粗桶口径与 {@link #parkedFingerprint} 一致）。</li>
     * </ul>
     */
    private record StallState(String subtaskId, String windowFingerprint, String stateFingerprint,
                              int unchangedTicks, int nudges) {}

    /**
     * 「同一状态 × 同一类催工只发一次」的记录（uuid → 已发过的键集合）。
     *
     * <p>2026-10-01 只把去重门挂在 LLM 空转那一处，实测**完全挡不住刷屏**：
     * {@code check.stalled()} 和 {@link #handleStalled} 两个分支各自每轮照发，
     * 而键又是<b>精确坐标</b> —— 同伴原地挪一格就换键，去重永远命中不了。
     * 2026-10-03 三处一起改：键换成抗抖动的 {@link #stateKey}、加<b>类别前缀</b>
     * （三类催工的"一次"各算各的，否则 escalate 会被 stall 的记录顶掉）。
     */
    private final Map<UUID, Set<String>> nudgedStates = new ConcurrentHashMap<>();

    private static String nudgeKey(String kind, String stateKey) {
        return kind + "|" + stateKey;
    }

    /** 这一类催工在这个状态下是不是已经花过钱了。 */
    private boolean alreadyNudgedFor(java.util.UUID companionId, String kind, String stateKey) {
        Set<String> sent = nudgedStates.get(companionId);
        return sent != null && sent.contains(nudgeKey(kind, stateKey));
    }

    private void markNudgedFor(java.util.UUID companionId, String kind, String stateKey) {
        Set<String> sent = nudgedStates.computeIfAbsent(companionId,
                k -> ConcurrentHashMap.newKeySet());
        // 键是"状态"的函数，状态随同伴跑动不断变；不封顶就会无界增长（38号页同类教训）。
        if (sent.size() >= NUDGE_KEY_CAP) {
            sent.clear();
        }
        sent.add(nudgeKey(kind, stateKey));
    }

    /** 已发过的催工键上限：超了整组清掉（宁可偶尔多拍一次，也不泄漏）。 */
    private static final int NUDGE_KEY_CAP = 64;

    /**
     * 发一条催工，并<b>只在真的注入出去之后</b>记「这个状态 × 这一类已经拍过」。
     *
     * <p><b>为什么不许先记再发</b>（2026-10-06 核实）：{@link RddPlugin#nudge} 在
     * 监督暂停 / 3 分钟总闸 / 参数无效时都返回 {@code false}，而原实现<b>在调用它之前</b>
     * 就 {@code markNudgedFor} ⇒ <b>一次也没发出去，却把这一状态永久判成「已提醒」</b>。
     * 实测形态：判据 10 秒就报 → 总闸 180 秒挡下 → 该状态从此不再提醒
     * （{@code nudge_throttled} 15 次 vs {@code supervisor_output} 9 次）。
     *
     * <p><b>为什么不只是「去掉 mark」</b>：{@code track()} 每个检查周期都跑，
     * 去掉 mark 会在总闸窗口内每个周期都调一次 {@link RddPlugin#nudge}，
     * 把 {@code nudge_throttled} 刷成周期一条。所以先问只读的
     * {@link RddPlugin#nudgeAllowed}：<b>放行才发送、发送成功才记账</b>；
     * 闸没放行 ⇒ 不发送也不记账，下个周期自然重试。
     *
     * @return 真的注入出去了 → {@code true}
     */
    private boolean nudgeOnce(java.util.UUID companionId, String kind, String stateKey, String message) {
        if (alreadyNudgedFor(companionId, kind, stateKey)) {
            return false;
        }
        if (!RddPlugin.nudgeAllowed(companionId)) {
            return false;
        }
        if (!RddPlugin.nudge(companionId, message)) {
            // 闸放行了却仍失败（参数无效/注入抛异常）⇒ 不记账，下个周期重试
            return false;
        }
        markNudgedFor(companionId, kind, stateKey);
        return true;
    }

    /** Level 3 卡死累计：当前二级累计卡死次数（AI 反复拍醒仍无目标资产进展 → 能力不足）。 */
    private final Map<UUID, StallCount> stallCounts = new ConcurrentHashMap<>();

    private record StallCount(String subtaskId, int total) {}

    RddStallWatcher(RddFurnaceWatch furnaceWatch) {
        this.furnaceWatch = furnaceWatch;
    }

    /**
     * 卡死检测：资产指纹连续 {@link #STALL_AFTER_TICKS} 次无变化
     * → markStalled + 拍醒（nudge）。返回 true 表示本次判定卡死，上层停止推进。
     */
    boolean track(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (!RddPlugin.supervisionEnabled()) {
            return false; // 空转止血：暂停监督不做卡死检测/拍醒/能力升级，资产推进照常
        }
        RddStallPolicy.Observation observation = observe(ap, rt, current);
        String fp = observation.fingerprint();
        String stateKey = stateKey(ap, observation);
        StallState st = stalls.get(ap.getUUID());
        if (st == null || !st.subtaskId().equals(current.id())) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, stateKey, 0, 0));
            return false;
        }
        RddStallPolicy.Check check = RddStallPolicy.check(st.windowFingerprint(), st.unchangedTicks(), observation);
        if (check.changed()) {
            // Assets, container output, cooking, or body work counters changed.
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, stateKey, 0, 0));
            return false;
        }
        int unchanged = check.unchanged();
        // LLM 在飞快照（2026-09-30 盲点①）：纯时间阈值分不清「卡住」与「慢」
        // （实测空转 35.6s < 有效 44.0s），所以先问宿主「它是不是正在飞」再决定拍不拍。
        // stale（在飞超上界 = future 挂死/被取消）当不在飞用，但**只告警一次**
        // （不去重的话 track() 每秒发一条，挂死几小时就是几万条日志 —— Codex 审稿 P1-4）。
        com.dwinovo.numen.agent.llm.LlmActivity.Snapshot llm =
                com.dwinovo.numen.agent.llm.LlmActivity.snapshot(ap.getUUID().toString());
        boolean llmInFlight = llm.inFlight() && !llm.stale();
        if (llm.staleUnreported()) {
            RddMonitor.publish("llm_activity_stale", Map.of(
                    "companionId", ap.getUUID().toString(), "subtask", current.id(),
                    "phase", String.valueOf(llm.phase()),
                    "inFlightCount", llm.inFlightCount(),
                    "oldestInFlightMs", llm.oldestInFlightNanos() / 1_000_000L,
                    "action", "treat as not-in-flight so supervision stays alive"));
            com.dwinovo.numen.agent.llm.LlmActivity.markStaleReported(
                    ap.getUUID().toString(), llm.oldestInFlightToken());
        }
        // LLM 空转拍醒（2026-10-01 重做）。用户指出："太高频又花钱，而且并不是真真正正的监督，
        // 检测也不正确；我进游戏了它又完成任务了" —— 三个问题分别对应下面三条：
        //
        //  ① 太高频 → 阈值 10 → 20（LLM_IDLE_NUDGE_AFTER_CHECKS），真正空转是 35.6s，
        //     10~35s 那段基本是在拍"想得慢"，白花钱还把模型从正确轨道拽下来。
        //  ② 不是真监督 → 新增**响应闸**（shouldNudgeLlmIdleAfterResponse）：
        //     连续 N 轮"模型回答完了但零工具调用"才是真空转。判定只读已完成的响应，**零成本**。
        //  ③ 不看效果 → 同一批（同一 subtask 同一指纹）**只拍一次**，拍完等它 45 秒，
        //     没动作就直接跳到 STALLED 判定，不再重复花钱。
        boolean noToolCallStreak = RddStallPolicy.shouldNudgeLlmIdleAfterResponse(
                llm.consecutiveNoToolCallResponses(), observation.source(), llmInFlight);
        boolean timeBasedIdle = RddStallPolicy.shouldNudgeLlmIdle(unchanged, observation.source(), llmInFlight);

        if ((noToolCallStreak || timeBasedIdle) && !alreadyNudgedFor(ap.getUUID(), "idle", stateKey)) {
            // 话术三条铁律（2026-09-29 GLM 审稿后重写，旧版实测无效）：
            //  1) 必须**点名当前子目标**——泛泛的「调用一个工具」是 content-free 紧迫感，
            //     模型按 recency 服从它 → 挑任意工具 → 覆盖原计划。旧版就是这么把同伴逼去 build 的。
            //  2) 必须给**合规的不作为出口**（BLOCKED）——否则「现在正确地什么都不做」无法表达，
            //     耐心会被转成随机动作；尤其"等规划器批"时拍醒会产生**未授权动作**，比卡住更糟。
            //  3) 必须**允许报错**——工具被拒是契约失败，不是注意力不集中，催只会加剧幻觉。
            //
            // ★ 2026-10-06：记账不再在发送之前 —— 走 nudgeOnce（闸放行 + 注入成功才记）。
            //   原来先 mark 后 nudge，总闸一挡就「一次没发、永不再发」。
            boolean idleSent = nudgeOnce(ap.getUUID(), "idle", stateKey,
                    "当前子目标「" + current.description() + "」"
                    + (noToolCallStreak
                       ? "你已经连着 " + llm.consecutiveNoToolCallResponses() + " 轮只回话、一个工具都没调"
                       : "已连续 " + RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS + " 秒没有任何工具调用")
                    + "。请按下面**三选一**回，不要空转："
                    + "① 立刻为这个子目标调一个工具（先 scan_nearby_entities / rdd_get_inventory 核对真实情况，"
                    + "再选 mine/collect_items/build/interact_at 中**真正对应本目标**的那个）；"
                    + "② 如果做不到，用 report_task_concern 上报，kind=PAUSE 并写明原因"
                    + "（能做到别的办法就用 COUNTER + suggestion，别用 PAUSE 顶替）；"
                    + "③ 如果任务本身已经完成或没必要做，直接说明，不必调工具。");
            // 只有真的发出去了才记这条监测事件 —— 否则闸没放行时它会每个周期重发一遍（刷屏）。
            if (idleSent) {
                // Map.of 最多支持 10 对，这里字段多于 10 → 用 LinkedHashMap（监测台按插入序渲染）
                Map<String, Object> idleData = new LinkedHashMap<>();
                idleData.put("companionId", ap.getUUID().toString());
                idleData.put("subtask", current.id());
                idleData.put("trigger", noToolCallStreak ? "no_toolcall_response_streak" : "time_based_idle");
                idleData.put("noToolCallResponses", llm.consecutiveNoToolCallResponses());
                idleData.put("unchangedChecks", unchanged);
                idleData.put("reason", noToolCallStreak
                        ? "model answered " + llm.consecutiveNoToolCallResponses()
                          + " consecutive turns with zero tool calls"
                        : "no tool call for " + RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS + " checks");
                idleData.put("source", observation.source());
                idleData.put("llmKnown", llm.known());
                idleData.put("llmInFlight", llmInFlight);
                idleData.put("llmInFlightCount", llm.inFlightCount());
                idleData.put("llmLastFinish", String.valueOf(llm.lastFinish()));
                idleData.put("llmLastToolCalls", llm.lastToolCalls());
                idleData.put("action", "nudge-once-per-fingerprint; no repeat spend without progress");
                RddMonitor.publish("llm_idle_stall", idleData);
            }
        }
        // 死任务兜底：source=body_task:* 说明「车在动」，但身体进度一个 tick 都没前进。
        // 上面的 idle 判据不管 body_task（正常干活），check.stalled() 又要等 120 秒 busy 窗口 ——
        // 真的死掉了就会<b>一条拍醒都没有</b>。这里在 busy 窗口耗尽之前先拍一次。
        // 同状态只拍一次（类别 "dead"），配合 RddPlugin 的 3 分钟总闸。
        boolean deadTask = RddStallPolicy.isDeadTask(unchanged, observation.source());
        if (deadTask && !alreadyNudgedFor(ap.getUUID(), "dead", stateKey)) {
            // ★ 2026-10-06：记账挪到 nudgeOnce（闸放行 + 注入成功才记）。
            boolean deadSent = nudgeOnce(ap.getUUID(), "dead", stateKey,
                    "你正在跑「" + current.description() + "」但**进度一个都没前进**"
                    + "（已经 " + unchanged + " 秒）。别重复发同一个工具请求："
                    + "① 先 rdd_get_inventory / scan_nearby_entities 核对真实情况；"
                    + "② 报告工具终态没回来/目标到不了，就用 report_task_concern 上报"
                    + "（kind=BLOCKED 或 PAUSE，写清卡在哪一步）；"
                    + "③ 换一条完全不同的做法，不要原样重试。");
            if (deadSent) {
                Map<String, Object> deadData = new LinkedHashMap<>();
                deadData.put("companionId", ap.getUUID().toString());
                deadData.put("subtask", current.id());
                deadData.put("source", observation.source());
                deadData.put("unchangedChecks", unchanged);
                deadData.put("graceLimitSeconds", check.limit());
                deadData.put("reason", "busy but body progress frozen (dead task)");
                deadData.put("action", "nudge-once-per-state; busy grace limit is "
                        + RddStallPolicy.WORK_GRACE_CHECKS + "s");
                RddMonitor.publish("dead_task_stall", deadData);
            }
        }
        if (observation.waiting() && unchanged >= STALL_AFTER_TICKS
                && unchanged % STALL_AFTER_TICKS == 0 && !check.stalled()) {
            RddMonitor.publish("subtask_work_wait", Map.of(
                    "companionId", ap.getUUID().toString(), "subtask", current.id(),
                    "source", observation.source(), "unchangedChecks", unchanged,
                    "graceRemainingSeconds", check.remaining(), "graceLimitSeconds", check.limit()));
        }
        if (check.stalled()) {
            // Repeated stalls are an observation, not proof of missing software capability.
            StallCount sc = stallCounts.get(ap.getUUID());
            int total = (sc != null && sc.subtaskId().equals(current.id())) ? sc.total() + 1 : 1;
            stallCounts.put(ap.getUUID(), new StallCount(current.id(), total));
            if (total >= CAPABILITY_GAP_AFTER_STALLS) {
                // 类别前缀 "escalate"：这一类的一次独立算，不被下面 "stall" 的记录顶掉。
                // ★ 2026-10-06：记账走 nudgeOnce（闸放行 + 注入成功才记）。
                nudgeOnce(ap.getUUID(), "escalate", stateKey, "这个目标反复没有进展。先核对真实工具结果、附近资源、路径、装备和模型连接；不要仅凭重复失败推断缺软件工具，只有确认能力缺口后再考虑自编译。");
                RddMonitor.publish("subtask_stall_escalated", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(), "failureClass", "UNKNOWN",
                        "reason", "repeated stalls (" + total + "); cause requires evidence"));
                stallCounts.remove(ap.getUUID());
            }
            rt.chain().markStalled(current.id(), "observed work unchanged for " + check.limit() + " checks");
            // ★ 这一句是 2026-10-03 实测最大的刷屏源（42 次，话术一字不变）。同一状态下只发一次：
            // 没真进展就别把同一句话说第二遍，状态机照样往下走（响应窗 / 判失败不受影响）。
            // ★ 2026-10-06：记账走 nudgeOnce —— 闸放行且真的注入出去才记；
            //   被总闸挡下时**不记**，下一个检查周期（闸开了）会重试，而不是永久沉默。
            nudgeOnce(ap.getUUID(), "stall", stateKey,
                    "你的目标「" + current.description() + "」还在，但可见资产、容器生产和身体进度在观察窗口内没有变化。请核对真实工具结果、材料和生产条件，再决定下一步。");
            RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(),
                    "reason", "observed work unchanged", "source", observation.source(),
                    "graceRemainingSeconds", 0, "graceLimitSeconds", check.limit()));
            // 重置响应窗计数：从 STALLED 起给 AI STALL_RESPONSE_TICKS 秒响应时间
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, stateKey, 0, st.nudges() + 1));
            return true;
        }
        stalls.put(ap.getUUID(), new StallState(current.id(), fp, stateKey, unchanged, st.nudges()));
        return false;
    }

    /**
     * STALLED 监督恢复：行为（资产/位置）恢复 → 回 RUNNING；仍在拍醒期 → 换措辞再拍；
     * 多次拍醒无效 → 判失败（Level 1 恢复兜底）。
     */
    void handleStalled(NumenPlayer ap, RddRuntime rt, Subtask current) {
        // ★ 用抗抖动状态键（8 格粗桶），不用精确坐标 —— 见 StallState.stateFingerprint 的注释。
        //   原来用精确坐标时，同伴原地挪一格就算「真进展」→ resume + 计数归零 → 拍醒上限
        //   永远触发不到 → 同一条话术无限重播（2026-10-03 实测 42 次）。
        // observe() 只调一次：它有副作用（furnaceWatch.track 在记账），调两次等于记两次。
        RddStallPolicy.Observation observation = observe(ap, rt, current);
        String stateKey = stateKey(ap, observation);
        String windowFp = observation.fingerprint();
        StallState st = stalls.get(ap.getUUID());
        if (!RddPlugin.supervisionEnabled()) {
            // 空转止血：暂停监督。AI 自己动了 → 回 RUNNING；否则保持卡住标记，绝不拍醒/绝不判失败。
            if (st != null && st.subtaskId().equals(current.id())
                    && st.stateFingerprint().equals(stateKey)) {
                return; // 仍冻结：挂起不动，不打扰 AI
            }
            rt.chain().resumeFromStalled(current.id());
            RddMonitor.publish("subtask_resumed", Map.of("subtask", current.id(), "reason", "progress while supervision paused"));
            stalls.put(ap.getUUID(), new StallState(current.id(), windowFp, stateKey, 0, 0));
            return;
        }
        if (st == null) {
            stalls.put(ap.getUUID(), new StallState(current.id(), windowFp, stateKey, 0, 1));
            // ★ 2026-10-06：走 nudgeOnce —— 闸没放行就不发（原来直接调 nudge，
            //   被挡时只会在监测台留一条 nudge_throttled，提醒本身丢了）。
            nudgeOnce(ap.getUUID(), "stalled_entry", stateKey, "你卡住了吗？缺什么工具或材料？");
            return;
        }
        if (!st.stateFingerprint().equals(stateKey)) {
            // Real production can recover a stalled task without moving items into inventory yet.
            rt.chain().resumeFromStalled(current.id());
            RddMonitor.publish("subtask_resumed", Map.of("subtask", current.id()));
            stalls.put(ap.getUUID(), new StallState(current.id(), windowFp, stateKey, 0, 0));
            return;
        }
        // 资产仍无变化：先给 AI 一个响应窗口，窗口内不打扰（AI 可能正在思考/规划）
        if (st.unchangedTicks() + 1 < STALL_RESPONSE_TICKS) {
            stalls.put(ap.getUUID(), new StallState(current.id(), st.windowFingerprint(), stateKey,
                    st.unchangedTicks() + 1, st.nudges()));
            return;
        }
        if (st.nudges() >= MAX_NUDGES) {
            // 多次拍醒无效 → Level 1 恢复：判失败（不伪造完成）
            rt.chain().markFailed(current.id(), "stalled after " + MAX_NUDGES + " nudges without progress");
            RddMonitor.publish("subtask_failed", Map.of("subtask", current.id(), "reason", "stalled after nudges"));
            stalls.remove(ap.getUUID());
            LOG.warn("[rdd] 二级目标卡死升级失败: {}", current.id());
            return;
        }
        // 响应窗口已过、资产仍无变化 → 换措辞再拍一次
        // 2026-10-01 RL-19（38号v3.4 §1）：这里原来写的是「如果缺工具，现在就调 selfcompile_request」。
        // **点名教唆本身就是漏洞** —— 干活 AI 是照着 nudge 学的，nudge 点了工具名就等于在派活，
        // 而 RL-19 要求游戏内 AI 只能往待办目录写一条（唯一的合法 caller 是**外层**，不是游戏内任何 AI）。
        // → 改成指向待办目录。
        // 2026-10-03：同一状态下这一类只发一次（类别前缀 "renudge"，与 "stall" 各算各的一次机会）。
        //
        // ★ 2026-10-06：改成 nudgeOnce —— 只有闸放行且真的注入出去才记「这一类花过了」。
        //   原来先 mark 再发（还被外层 alreadyNudgedFor 提前 return），被总闸挡下时
        //   这一次机会**白花掉**：计数照样推进到判失败，提醒却一次没送到。
        //   两条分支的 `stalls.put(...)` 本来就完全一样，所以合并成一条。
        if (nudgeOnce(ap.getUUID(), "renudge", stateKey, "你还没动。告诉我你卡在哪一步？"
                + "如果缺工具或能力，把「缺什么 + 你试过什么 + 你所处环境的快照」"
                + "用 learner_note 写一条待办，学习者会看到；**不要自己请求代码变更**。")) {
            RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(), "reason", "still stalled, re-nudge"));
        }
        // 计数照走（无论这次有没有真的发出去）—— 保证 MAX_NUDGES 能推到判失败，
        // 而不是卡成「永远不花钱也永远不判失败」的死循环。
        stalls.put(ap.getUUID(), new StallState(current.id(), st.windowFingerprint(), stateKey,
                0, st.nudges() + 1));
    }

    /**
     * STALLED 期间判「<b>真</b>进展」用的状态键：背包 + <b>8 格区域桶</b> + 容器/熔炉/身体进度。
     *
     * <p>与 {@link #fingerprint} 的唯一区别就是坐标那一项（精确 → 粗桶），理由见
     * {@link StallState#stateFingerprint}。粗桶口径与 {@link #parkedFingerprint} 完全一致，
     * 不另造第二套。
     */
    private String stateKey(NumenPlayer ap, RddStallPolicy.Observation observation) {
        String inv = RddDetector.countInventory(ap).toString();
        var pos = ap.blockPosition();
        return inv + "|" + RddStallPolicy.parkedBucket(pos.getX(), pos.getY(), pos.getZ())
                + "|work=" + observation.workProgress();
    }

    /** 当前二级的资产指纹（背包计数 + 位置），供卡死监督与停车守望共用。 */
    String fingerprint(NumenPlayer ap) {
        String inv = RddDetector.countInventory(ap).toString();
        var pos = ap.blockPosition();
        return inv + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 停车守望专用的"抗抖动"进展指纹（背包 + 8 格区域桶），与 {@link #fingerprint} 分开。
     *
     * <p>为什么单独一套（2026-09-27 实机教训）：原指纹含**精确坐标**，AI 在两点之间横跳
     * （寻路打转/到不了目标）时坐标每 tick 都变 → "无进展"窗口被无限重置 → 催工永不触发，
     * 表现为"它自己一直重复走、任务永远完不成、也没人拍醒"。这里把坐标量化成 8 格粗桶：
     * 区域内抖动/横跳不再重置窗口（照常催工），只有真换区域才算有新进展。
     *
     * <p>不影响卡死检测：{@link RddStallPolicy} 那条路径继续用精确坐标（走路本身算进展）。
     */
    String parkedFingerprint(NumenPlayer ap) {
        String inv = RddDetector.countInventory(ap).toString();
        var pos = ap.blockPosition();
        return inv + "|" + RddStallPolicy.parkedBucket(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Server-thread observation only. Fuel/elapsed/deadline/task-id changes are not work progress. */
    RddStallPolicy.Observation observe(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        RddFurnaceWatch.FurnaceWatch watch = furnaceWatch.track(ap, rt, current);
        AbstractContainerMenu menu = ap.containerMenu;
        StringBuilder progress = new StringBuilder();
        if (menu != null && menu != ap.inventoryMenu) RddFurnaceWatch.appendContainer(progress, menu, ap);
        boolean production = false;
        if (watch != null) {
            if (watch.menu() != menu) RddFurnaceWatch.appendContainer(progress, watch.menu(), ap);
            // Vanilla getBurnProgress is cooking progress; getLitProgress is only fuel countdown.
            progress.append("|cooking=").append(watch.menu().getBurnProgress());
            production = watch.menu().isLit() && !watch.menu().getSlot(0).getItem().isEmpty();
        }
        var body = CompanionTickDispatcher.currentTaskFor(uuid);
        boolean active = body != null && !body.getState().isTerminal();
        if (active) progress.append("|body=").append(body.getToolName()).append(':').append(body.describe());
        String source = production ? "furnace_production" : active ? "body_task:" + body.publicId() : "idle";
        return new RddStallPolicy.Observation(fingerprint(ap), progress.toString(), production || active, source);
    }

    /** 清掉某同伴的全部卡死监督状态（完成/失败/跳过时调用）。 */
    void clear(UUID uuid) {
        stalls.remove(uuid);
        stallCounts.remove(uuid);
        // 2026-10-03：原来只清这两个，nudgedFingerprints 成了纯泄漏（换二级也不清）。
        nudgedStates.remove(uuid);
    }

    void clearAll() {
        stalls.clear();
        stallCounts.clear();
        nudgedStates.clear();
    }
}
