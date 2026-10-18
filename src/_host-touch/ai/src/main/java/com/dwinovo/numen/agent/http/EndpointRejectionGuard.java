package com.dwinovo.numen.agent.http;

/**
 * 「端点<b>连续拒收请求</b>」的判据（纯策略：零依赖、无时钟、可离线单测）。
 *
 * <p><b>为什么需要它</b>（2026-10-06 实机）：
 * 某一局里同伴连续 4 个回合全部以 <b>HTTP 400</b> 收场 —— 请求体 36~50KB、15 个工具、
 * 结构合法、字符干净，而<b>响应 body 是空的</b>（正常的 400 会带原因），
 * 同一 payload 重试<b>同样 400</b>（确定性，不是抖动）。两个模型名（新旧）都中招，
 * 所以不是模型名的问题。
 *
 * <p>真正把「一次 400」放大成「卡 16 分钟」的是<b>监督侧的接线</b>：
 * {@code EntityAgentLoop.endpointProblem()} 只认「没绑 provider / 没 key」，
 * 端点一直拒收时它返回 {@code null} ⇒ BrainGate 一直报「可以开轮」⇒
 * 每一轮催工都触发一次注定失败的调用，最后烧光 3 次重规划预算。
 * <b>拿一个坏掉的脑子去催一个坏掉的脑子，催不动，还把预算烧光了。</b>
 *
 * <p><b>判据口径</b>：
 * <ul>
 *   <li>只认 <b>4xx 且非 429</b>：4xx = 请求本身不被接受（重发同样形状必然再被拒）；
 *       429 是「现在不行、待会儿行」，不算故障；5xx / 网络类由传输层自己退避。</li>
 *   <li>数的是<b>回合</b>不是<b>次</b>：一个回合里有多步工具循环，
 *       计「次」会把同一回合的尝试 + 重试算两次，阈值就失去意义。</li>
 *   <li><b>冷却期一过自动放行</b>：端点拒收常常是 payload 相关、下一轮就可能好了，
 *       永久锁死会把「本来已经恢复」的同伴按死。</li>
 * </ul>
 *
 * <p>线程安全：方法都是 {@code synchronized}（客户端主线程 + 传输线程都会碰它）。
 */
public final class EndpointRejectionGuard {

    /** 连续多少个回合被拒后判「正在拒收」。 */
    public static final int DEFAULT_MAX_TURNS = 3;

    /** 判「正在拒收」的冷却期：超过这么久没有再被拒就放行。 */
    public static final long DEFAULT_COOLDOWN_MS = 5 * 60_000L;

    private final int maxTurns;
    private final long cooldownMs;

    private int turns;
    private long lastAtMs;
    private int lastStatus;
    private boolean ownerTold;

    public EndpointRejectionGuard() {
        this(DEFAULT_MAX_TURNS, DEFAULT_COOLDOWN_MS);
    }

    /**
     * @param maxTurns   连续多少个回合被拒后判「正在拒收」；{@code < 1} 收敛成 1（不会变成「永不触发」）
     * @param cooldownMs 冷却期；{@code 0} 的语义是「<b>不设冷却</b>」⇒ {@link #rejecting} 恒为
     *                   {@code false}（所以生产别传 0）
     */
    public EndpointRejectionGuard(int maxTurns, long cooldownMs) {
        this.maxTurns = Math.max(1, maxTurns);
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    /**
     * 记一次「被端点拒收的回合」。
     *
     * @param httpStatus 该次失败对应的 HTTP 状态码；非 HTTP 故障传 {@code -1}
     * @param nowMs      当前时刻（毫秒）
     * @return 真的记入了（即 4xx 且非 429）→ {@code true}
     */
    public synchronized boolean noteTurnFailure(int httpStatus, long nowMs) {
        if (httpStatus < 400 || httpStatus >= 500 || httpStatus == 429) {
            return false;
        }
        turns++;
        lastStatus = httpStatus;
        lastAtMs = nowMs;
        return true;
    }

    /** 一回合成功 ⇒ 清零。熔断只针对「连续」被拒，不针对「历史上被拒过」。 */
    public synchronized void clear() {
        turns = 0;
        lastStatus = 0;
        ownerTold = false;
    }

    /**
     * 「这一轮拒收期里，已经跟主人说过一次了吗」——说过了返回 {@code false}。
     *
     * <p>为什么要这个：拒收期里每来一个唤醒事件都会再查一次（这是有意的，冷却期要能自动放行），
     * 但<b>提醒只该出现一次</b>，否则主人会看到同一条告警刷屏。
     * 一次拒收期 = 从「跨过阈值」到「某回合成功（{@link #clear}）」。
     */
    public synchronized boolean markOwnerTold() {
        if (ownerTold) {
            return false;
        }
        ownerTold = true;
        return true;
    }

    /** 现在是不是「正在连续拒收」（冷却期一过返回 {@code false}）。 */
    public synchronized boolean rejecting(long nowMs) {
        if (turns < maxTurns) {
            return false;
        }
        return nowMs - lastAtMs < cooldownMs;
    }

    /** 连续被拒的回合数。 */
    public synchronized int consecutiveTurns() {
        return turns;
    }

    /** 最近一次被拒的 HTTP 状态码（0 = 没有被拒过）。只用于把话说清楚。 */
    public synchronized int lastStatus() {
        return lastStatus;
    }
}
