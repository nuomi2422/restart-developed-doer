package com.dwinovo.numen.plugins.rdd;

/** Pure supervision policy; activity evidence is separate from merely having a task in flight. */
final class RddStallPolicy {
    static final int IDLE_GRACE_CHECKS = 15;
    /** One check per second. A busy slot alone cannot suppress supervision forever. */
    static final int WORK_GRACE_CHECKS = 120;

    /**
     * LLM 空转提前拍醒：身体<b>一次工具都没调</b>持续到这么多次检测（1 次/秒）就提前喊。
     *
     * <p>为什么要有这条独立于 {@link #IDLE_GRACE_CHECKS} 的更早信号（2026-09-29 实机）：
     * 一次真实死亡后，日志里出现 {@code chat done in 35625ms, finish=stop, tool_calls=[]}——
     * 整整 35 秒模型只在空想、零工具调用；再往后它 {@code find_tools} 查完 {@code collect_items}
     * 却没有重试，反而跑去 {@code build}/{@code decompose_goal}。
     * 而现有 idle 判据要等 {@link #IDLE_GRACE_CHECKS}=15 秒才响，且响完只换措辞重拍，
     * <b>拍完它照样跑偏</b>。
     *
     * <p><b>阈值仍是 10，不能调大</b>（2026-10-01 试过 20，被既有测试正确拦下）：
     * 这条信号的存在意义就是<b>早于</b> {@link #IDLE_GRACE_CHECKS}=15 秒的 stalled 判据，
     * 调到 20 就晚于它、提前预警归零。它<b>不是</b>用来省钱的闸。
     *
     * <p><b>省钱不靠放宽阈值，靠两件事</b>（用户 2026-10-01 指出"太高频又花钱"）：
     * <ol>
     *   <li><b>同指纹只拍一次</b>（见 {@code RddStallWatcher} 的 {@code nudgedFingerprints}）：
     *       拍醒是一次真实 LLM 请求，拍完没效果就不该再拍同一批。</li>
     *   <li><b>主判据换成响应闸</b> {@link #shouldNudgeLlmIdleAfterResponse}：
     *       只看"模型回答完了却零工具调用"这件<b>确定的事实</b>，不看时钟。
     *       判定本身零成本（花钱的是拍醒，不是判定），而且比时间阈值准得多。</li>
     * </ol>
     *
     * <p>诚实边界仍然成立：纯时间阈值分不清「卡住」与「慢」（实测空转 35.6s &lt; 有效 44.0s），
     * 所以它只当**兜底**，主判据是上面的响应闸。
     *
     * <p>为什么只"拍醒"不"改状态"：时间阈值必然有假阳性。
     * 若据此 markStalled，会把"正在思考"误杀成 FAILED/STALLED，
     * 触发重试与失败升级——那才是真伤害。拍醒的代价只是一句话，可以承受。
     */
    static final int LLM_IDLE_NUDGE_AFTER_CHECKS = 10;

    /**
     * 「模型回答了，但这次回答没有任何工具调用」的连续次数上限。
     *
     * <p><b>这才是真正的空转判据</b>（2026-10-01）：时间阈值分不清"卡住"和"慢"
     * （实测空转 35.6s &lt; 有效 44.0s），但<b>响应本身是确定的</b> ——
     * {@code finish=stop && tool_calls=[]} 说明这一轮模型只吐了文本、正事一件没干。
     *
     * <p>取 3：连续三轮"只说话不动手"才是真空转（单轮可能是它在读工具结果/在组织下一步）。
     * 判它比时间阈值便宜得多 —— <b>不需要发任何请求</b>，只是看已完成的响应历史。
     */
    static final int LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE = 3;

    /**
     * 只有 {@code source=idle} 才算「LLM 没在调工具」。
     *
     * <p>{@code body_task:*} 表示它已经在跑一个多步身体任务（walk/mine 内部会连跑几十秒，
     * 中间本就不发新工具调用）——那是<b>正常干活</b>，拿 5 秒去拍它等于打断正常工作。
     * {@code furnace_production} 同样在推进。判据必须与「有没有进展」分开。
     */
    static boolean llmIdle(String source) {
        return "idle".equals(source);
    }

    /**
     * 是否该在这一个检查点拍醒。纯函数便于单测锁住两条边界：
     * 干活的 source 永不触发；空转到阈值的那一次触发（不重复触发）。
     *
     * <p><b>两参重载 = 旧行为</b>（假定宿主给不出「在飞」）。给得出就必须用三参那版。
     */
    static boolean shouldNudgeLlmIdle(int unchanged, String source) {
        return shouldNudgeLlmIdle(unchanged, source, false);
    }

    /**
     * 同上，多一个 {@code llmInFlight} 闸（2026-09-30 加，见统一版 38 号页 §2 盲点①）。
     *
     * <p><b>这个闸为什么必须加</b>：{@link #LLM_IDLE_NUDGE_AFTER_CHECKS} 是<b>纯时间</b>阈值，
     * 而实测分布里空转 35.6s <b>&lt;</b> 有效 44.0s —— 也就是说
     * <b>「有效但慢」比「空转」还慢，纯阈值天生就会误拍正在思考的模型</b>。
     * 拍醒的措辞会覆盖原计划（见 {@code RddStallWatcher} 的三条话术铁律），
     * 所以误拍不是「多喊一句」，是<b>把模型从正确的轨道上拽下来</b>。
     *
     * <p><b>闸是 fail-open 的</b>：宿主给不出在飞状态（{@code llmInFlight=false}）时，
     * 行为与加闸之前<b>逐字相同</b> —— 加闸只允许<b>少</b>误拍，不许引入新误判。
     * 反向的 stale 保护在 {@code LlmActivity.Snapshot.stale} 那一侧：
     * 在飞太久（future 挂死）就不认在飞，<b>宁可误催，不可静默失明</b>。
     */
    static boolean shouldNudgeLlmIdle(int unchanged, String source, boolean llmInFlight) {
        return llmIdle(source) && !llmInFlight && unchanged == LLM_IDLE_NUDGE_AFTER_CHECKS;
    }

    /**
     * <b>真正的空转判据</b>：模型<b>已经回答完了</b>，而这 {@code noToolCallResponses} 轮里
     * 每一次都是 {@code finish=stop && tool_calls=[]}（只吐文本、正事没干）。
     *
     * <p>为什么它比时间阈值可靠（2026-10-01，用户指出"检测不正确、花钱又没效果"）：
     * <ul>
     *   <li>时间阈值只能看到"多久没动"，分不清"在想"和"卡住"（实测空转 35.6s &lt; 有效 44.0s）；</li>
     *   <li>本判据看的是<b>已经落地的响应内容</b>——只要模型每一轮都没调工具，就是空转，
     *       不管它花了 3 秒还是 40 秒；</li>
     *   <li><b>而且它完全不需要发请求</b>：只是读已完成的响应历史，
     *       所以判定本身零成本（花钱的是拍醒，不是判定）。</li>
     * </ul>
     *
     * <p>{@code llmInFlight} 仍是必要条件：正在飞的那一轮<b>还没资格</b>被算进空转
     * （否则长思维链的正常轮次会被误计）。
     *
     * @param noToolCallResponses 连续"回答了但零工具调用"的轮数（宿主从 LlmActivity 累计）
     */
    static boolean shouldNudgeLlmIdleAfterResponse(int noToolCallResponses, String source,
                                                 boolean llmInFlight) {
        return llmIdle(source)          // 跑多步身体任务/熔炉推进中不算空转（同 shouldNudgeLlmIdle）
                && !llmInFlight         // 正在飞的那一轮还没资格被算进空转
                && noToolCallResponses >= LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE;
    }

    /** 两参重载（假定宿主给不出「在飞」）：行为与给得出在飞状态时一致，只是更容易触发。 */
    static boolean shouldNudgeLlmIdleAfterResponse(int noToolCallResponses, boolean llmInFlight) {
        return shouldNudgeLlmIdleAfterResponse(noToolCallResponses, "idle", llmInFlight);
    }

/**
 * 「车没动 + 姿态没动 + 无生产」持续到这么多次检测（1 次/秒）就升级催工（能力缺口方向）。
 *
 * <p><b>为什么这条阈值单独存在</b>（2026-10-03 实测教训）：真正的死任务（车不走、姿态不变、
 * 熔炉没在烤、工具零进展）落不进上面任何一条判据 ——
 * {@link #llmIdle} 认的是 {@code body_task:*}/{@code furnace_production} 那些「正在推进」的
 * source，压根不会触发；{@code check.stalled()} 还要等窗口累积，而 {@code waiting()} 为真时
 * 上限是 {@link #WORK_GRACE_CHECKS}=<b>120 秒</b>。结果就是同伴真的死掉了、连续十几分钟盯着
 * 她看，日志里<b>一条拍醒都没有</b> —— 这才是「检测不正确」的另一半。取 45 秒：远小于 120 秒的
 * busy 窗口，保证「busy 但没进展」在 busy 窗口耗尽<b>之前</b>就被拍一次。
 */
static final int DEAD_TASK_NUDGE_AFTER_CHECKS = 45;

/**
 * 是否是「死任务」：source 是 {@code body_task:*}（车在动）但 <b>身体进度一个 tick 都没前进</b>。
 *
 * <p>这是「busy 但真没进展」的唯一入口：{@code waiting()} 为真时上限 120 秒，
 * 单靠它这条死任务要等两分钟才被拍一次；而 {@link #llmIdle} 又不管 {@code body_task:*}。
 * 取 45 秒：在 busy 窗口耗尽之前先拍一次，钱花在「它真的卡住了」上而不是「白等」。
 */
static boolean isDeadTask(int unchanged, String source) {
    return source != null && source.startsWith("body_task:")
            && unchanged >= DEAD_TASK_NUDGE_AFTER_CHECKS;
}

/**
 * 坐标量化成 8 格粗桶（纯函数，无 MC 依赖，便于单测锁住"区域内抖动同桶、跨区域换桶"）。
 *
 * <p>2026-09-27 实机教训：停车守望原用**精确坐标**判进展，AI 在两点之间横跳（寻路打转、
 * 到不了目标）时坐标每 tick 变化 -> "无进展"窗口被无限重置 -> 催工永不触发，
 * 表现为"它自己一直重复走、任务永远完不成、也没人拍醒"。粗桶让区域内抖动不再重置窗口。
 */
static String parkedBucket(int x, int y, int z) {
        return Math.floorDiv(x, 8) + "," + Math.floorDiv(y, 8) + "," + Math.floorDiv(z, 8);
    }

    record Observation(String assetsAndPosition, String workProgress, boolean waiting, String source) {
        String fingerprint() { return assetsAndPosition + "|work=" + workProgress; }
    }

    record Check(String fingerprint, int unchanged, int limit, boolean changed) {
        boolean stalled() { return unchanged >= limit; }
        int remaining() { return Math.max(0, limit - unchanged); }
    }

    static Check check(String previous, int unchanged, Observation observation) {
        String fingerprint = observation.fingerprint();
        boolean changed = !fingerprint.equals(previous);
        int next = changed ? 0 : unchanged + 1;
        return new Check(fingerprint, next,
                observation.waiting() ? WORK_GRACE_CHECKS : IDLE_GRACE_CHECKS, changed);
    }
}
