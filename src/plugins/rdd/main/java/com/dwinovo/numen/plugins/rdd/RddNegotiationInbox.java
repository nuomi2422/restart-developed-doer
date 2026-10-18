package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.fail.TaskNegotiation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervisor ↔ Numen 双向协商的**指挥官侧接收器**（人工批准 · 2026-09-25）。
 *
 * <p>士兵（Numen 内置 AI）通过 {@code report_task_concern} 工具上报结构化回执
 * （ACCEPT/REJECT/COUNTER）；本类把它落成事件，并由 {@link RddDetector} 在下一 tick
 * 据此**改单或触发重规划**（不是无视——这正是之前缺的"相互交流"）。
 *
 * <p>指挥官动作（对 REJECT/COUNTER）：
 * <ul>
 *   <li>有 suggestion → 把"原命令 + 士兵理由 + 士兵建议"交给重规划（协商）；</li>
 *   <li>无 suggestion → 直接触发重规划（改单）；</li>
 *   <li>预算耗尽 → 回落停车守望（沿用 P4 重规划预算）。</li>
 * </ul>
 * 纯逻辑登记（线程安全），不直接碰 Minecraft。
 */
final class RddNegotiationInbox {

    private RddNegotiationInbox() {}

    /** 每个同伴最近一条待处理的士兵回执（REJECT/COUNTER）；ACCEPT 立即清空。 */
    private static final Map<java.util.UUID, TaskNegotiation> PENDING = new ConcurrentHashMap<>();

    static void accept(java.util.UUID companionId, TaskNegotiation n) {
        if (companionId == null || n == null) return;
        RddMonitor.publish("task_negotiation", n.toEventData(companionId.toString()));
        // 入队条件必须与 RddDetector.tickNegotiation 的消费条件**同时**满足。
        // 2026-09-29 实测踩过：只改了消费侧（认 isPauseRequest），这里仍只判 needsSupervisorAction
        // → PAUSE 落进 else 分支被当场删除，事件还发了（看着像成功），但指挥官永远收不到。
        if (n.needsSupervisorAction() || n.isPauseRequest()) {
            PENDING.put(companionId, n);
        } else {
            PENDING.remove(companionId);   // ACCEPT：只是回执，无需指挥官动作，清掉免得残留
        }
    }

    /** 取出并清除待处理回执（指挥官处理入口）。 */
    static TaskNegotiation takePending(java.util.UUID companionId) {
        return companionId == null ? null : PENDING.remove(companionId);
    }

    /** 只读查看（不消费）。 */
    static TaskNegotiation peek(java.util.UUID companionId) {
        return companionId == null ? null : PENDING.get(companionId);
    }

    static void clear(java.util.UUID companionId) {
        if (companionId != null) PENDING.remove(companionId);
    }

    /**
     * Server stopped：队列里全是上一个世界的回执，一个都不能带到新世界。
     *
     * <p>2026-09-30 深审 R05 补：原先只有按同伴清，跨世界时残留会作用到新世界的链上。
     */
    static void clearAll() {
        PENDING.clear();
    }

    /** 渲染"协商上下文"给重规划用：原命令 + 士兵理由 + 士兵建议。 */
    static String negotiationHint(TaskNegotiation n, Subtask current) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder("【士兵回执（协商）】\n");
        sb.append("- 原命令：").append(current == null ? "?" : current.description()).append("\n");
        sb.append("- 回执：").append(n.kind().name()).append("\n");
        if (!n.reason().isEmpty()) sb.append("- 理由：").append(n.reason()).append("\n");
        if (n.hasSuggestion()) sb.append("- 士兵建议：").append(n.suggestion()).append("\n");
        sb.append("请据此改单或给出可执行的替代方案（不要无视士兵的反馈）。");
        return sb.toString();
    }
}
