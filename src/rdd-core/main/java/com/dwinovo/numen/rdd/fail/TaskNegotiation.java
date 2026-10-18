package com.dwinovo.numen.rdd.fail;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Supervisor ↔ Numen 双向协商协议（人工批准 · 2026-09-25）。
 *
 * <p>从「指挥官单向派单」升级为「士兵可反馈 → 指挥官改单/协商」：
 * <pre>
 *   Supervisor ──派单(current_task+done_when)──▶ Numen
 *   Supervisor ◀──回执(ACCEPT/REJECT/COUNTER)─── Numen
 *   Supervisor ──改单/重规划/接受提议──────────▶ Numen
 * </pre>
 *
 * <p>士兵三种回执（结构化，不自由聊天）：
 * <ul>
 *   <li>{@link Kind#ACCEPT}：接单，正常干；</li>
 *   <li>{@link Kind#REJECT}：命令本身不对（与目标冲突/信息错），附理由；</li>
 *   <li>{@link Kind#COUNTER}：做不到/成本过高，附替代建议（协商）。</li>
 * </ul>
 *
 * <p>指挥官对 REJECT/COUNTER 的处理（由宿主 RddDetector/Planner 执行，本类只定数据）：
 * 接受提议 / 改单 / 触发重规划。纯 JVM、可单测。
 */
public record TaskNegotiation(
        Kind kind,
        String taskId,
        String reason,
        String suggestion) {

    /** 士兵回执类型。 */
    public enum Kind {
        /** 接单。 */
        ACCEPT,
        /** 命令不对（拒绝原命令）。 */
        REJECT,
        /** 做不到/太贵，附替代方案（协商）。 */
        COUNTER,
        /**
         * 暂停：士兵判定当前二级<b>现在做不了，但先别废掉、留着以后开</b>。
         *
         * <p>与 {@link #COUNTER} 差的是<b>意图</b>：COUNTER = 「这样不行，换个做法」→ 走重规划；
         * PAUSE = 「先放着，别白烧我的重试预算，也别判我失败」→ 直接置 {@code PAUSED}，不重规划。
         *
         * <p>2026-09-29 实机依据：一条找村庄的二级在 10:46-10:48 连续
         * {@code stalled → failed → retry(1/2) → stalled → failed → retry(2/2)}，
         * 而它做这件事的前提（附近有村庄）根本不成立 —— 白烧重试预算正是"暂停"要治的病。
         */
        PAUSE
    }

    public TaskNegotiation {
        Objects.requireNonNull(kind, "kind");
        taskId = taskId == null ? "" : taskId.trim();
        reason = reason == null ? "" : reason.trim();
        suggestion = suggestion == null ? "" : suggestion.trim();
    }

    /** 是否需要指挥官介入（改单/协商/重规划）。ACCEPT 不需要。 */
    public boolean needsSupervisorAction() {
        // PAUSE 刻意**不**要军师介入：它不重规划、不换计划，只把当前二级按住。
        // 若把它算进来，RddDetector 会走重规划路径 → PAUSE 退化成 COUNTER，白做。
        return kind == Kind.REJECT || kind == Kind.COUNTER;
    }

    /** 是否是「先放着」类上报（Detector 走 pauseSubtask 分支，不进重规划）。 */
    public boolean isPauseRequest() {
        return kind == Kind.PAUSE;
    }

    public boolean hasSuggestion() {
        return !suggestion.isEmpty();
    }

    /** 渲染给 Supervisor/日志的一行。 */
    public String render() {
        StringBuilder sb = new StringBuilder("[").append(kind.name()).append("] ");
        if (!taskId.isEmpty()) sb.append(taskId).append(" — ");
        if (!reason.isEmpty()) sb.append("reason: ").append(reason).append("; ");
        if (hasSuggestion()) sb.append("suggest: ").append(suggestion);
        return sb.toString().trim();
    }

    public Map<String, Object> toEventData(String companionId) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("companionId", companionId == null ? "" : companionId);
        d.put("kind", kind.name());
        d.put("taskId", taskId);
        d.put("reason", reason);
        d.put("suggestion", suggestion);
        d.put("needsSupervisorAction", needsSupervisorAction());
        d.put("summary", render());
        return d;
    }

    /**
     * 从工具参数解析。
     *
     * <p><b>2026-09-30 深审 R05：未知 kind 不再静默降级成 ACCEPT</b>。
     * 旧行为把 {@code "PAUES"}（拼错）也解析成 ACCEPT，而 ACCEPT 在
     * {@code RddNegotiationInbox.accept()} 里走的是 {@code PENDING.remove()} ——
     * 一次打字错误就会<**把之前合法的待处理 PAUSE/COUNTER 清掉**>，指挥官再也收不到。
     * 探针实测：{@code parse("PAUES", …).kind() == ACCEPT}。
     *
     * <p>现在：kind 为空 → ACCEPT（缺省仍是"接单"，与工具 schema 一致）；
     * kind 非空但无法识别 → 抛 {@link IllegalArgumentException}，
     * 由 {@code RddConcernTool} 转成一次明确的工具失败，让模型重发并看到合法值。
     * 缺字段（taskId/reason/suggestion）仍然容忍，保持原样。
     */
    public static TaskNegotiation parse(String kind, String taskId, String reason, String suggestion) {
        Kind k;
        if (kind == null || kind.isBlank()) {
            k = Kind.ACCEPT;   // 缺省 = 接单（与工具 schema 的默认值一致）
        } else {
            try {
                k = Kind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("unknown kind '" + kind.trim()
                        + "'; expected one of " + kinds());
            }
        }
        return new TaskNegotiation(k, taskId, reason, suggestion);
    }

    /** 合法的 kind 名（供工具 schema / 提示词列出）。 */
    public static List<String> kinds() {
        return List.of(Kind.ACCEPT.name(), Kind.REJECT.name(), Kind.COUNTER.name(), Kind.PAUSE.name());
    }
}
