package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small host-side facade: owns one chain and publishes observable state, never a second state store. */
public final class RddRuntime {
    public static final String MONITOR_CATEGORY = "rdd";
    private final TaskChain chain;
    private final AssetRegistry assets;

    public RddRuntime(TaskChain chain, AssetRegistry assets) {
        this.chain = Objects.requireNonNull(chain);
        this.assets = Objects.requireNonNull(assets);
    }

    public TaskChain chain() { return chain; }
    public AssetRegistry assets() { return assets; }

    // ---- PAUSED 计时（2026-09-30 深审 R03 修正）----
    //
    // 旧实现三个缺陷，本轮全部修掉（探针实测 firstRead=50 secondRead=0）：
    //  ① pausedTicks() 用 remove() —— 第一次读取就把起点拿走，之后永远返回 0，
    //     "到 60 秒复评"这道闸根本不会触发；
    //  ② 用 System.nanoTime()/20_000_000 当 tick，但游戏是 50ms/tick
    //     （20 tick/s）→ 同一个 1200 数被读成 24 秒，比声称的早 2.5 倍；
    //  ③ 计时只在内存，重启后 PAUSED 状态恢复了、计时全丢，无法续算。
    //
    // 现在：只读不消费（起点由 clearPaused/退出暂停时清），时间基准注入
    // （生产传游戏 tick，测试传假时钟），并随 TaskChain 一起持久化。
    private final Map<String, Long> pausedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记下这一级是何时进入 PAUSED 的（首次进入才记，中途不刷新）。 */
    public void markPaused(String subtaskId, long gameTime) {
        if (subtaskId == null) return;
        pausedAt.putIfAbsent(subtaskId, gameTime);
    }

    /** 清掉计时（恢复/重规划/换二级时调用）。 */
    public void clearPaused(String subtaskId) {
        if (subtaskId != null) pausedAt.remove(subtaskId);
    }

    /**
     * 该级已暂停多少 tick（不在暂停或从未暂停则 0）。
     *
     * <p><b>只读，不消费</b>：暂停期间 Detector 每 tick 都会问一次，用 remove() 的话
     * 第一次问就把起点删了，之后恒为 0（这正是实机两次 PAUSE 卡死的直接原因）。
     * 清起点只有一个入口：{@link #clearPaused}。
     */
    public long pausedTicks(String subtaskId, long nowGameTime) {
        if (subtaskId == null) return 0;
        Long at = pausedAt.get(subtaskId);
        return at == null ? 0 : Math.max(0, nowGameTime - at);
    }

    /** 该级是否已记下暂停起点（供宿主判断"要不要建计时"）。 */
    public boolean hasPausedMark(String subtaskId) {
        return subtaskId != null && pausedAt.containsKey(subtaskId);
    }

    /**
     * 随链一起持久化暂停起点（重启后能续算）。
     *
     * <p>不落盘的后果：重启恢复出的 PAUSED 链，{@link #pausedTicks} 恒为 0 →
     * 复评闸永不触发 → 又变成"暂停了但永远不会有人叫醒它"。
     */
    public Map<String, Long> pausedMarks() {
        return Map.copyOf(pausedAt);
    }

    /** 从持久化恢复暂停起点（{@link #markPaused} 的幂等反面：强制写入）。 */
    public void restorePausedMark(String subtaskId, long gameTime) {
        if (subtaskId == null) return;
        pausedAt.put(subtaskId, gameTime);
    }

    public Map<String, Object> snapshot() { return chain.snapshot(); }

    public void startCurrent() {
        chain.startCurrent();
        publish("subtask_started", Map.of("goal", chain.currentPrimary().id(), "subtask", chain.currentSubtask().id()));
    }

    /**
     * {@link #startCurrent()} + 在同一次迁移里锁 {@code acquire} 基线。
     *
     * <p>2026-10-02 实测事故：{@code rdd_submit} 走无参的 {@link #startCurrent()}，不锁基线 →
     * {@code mode=acquire} 恒 false → 做出来也判不出成功。派活方<b>必须</b>用这个带 counts 的版本。
     */
    public void startCurrentWithCounts(Map<String, Integer> counts) {
        chain.startCurrentWithCounts(counts);
        publish("subtask_started", Map.of("goal", chain.currentPrimary().id(), "subtask", chain.currentSubtask().id()));
    }

    /** 在途执行恢复拍板（P0-4）：RECOVERING → ACTIVE。 */
    public void resumeFromRecovering() {
        chain.resumeFromRecovering();
    }

    /** 激活刚推进到的当前一级；前置资产未到位 → WAITING 并返回 false。 */
    public boolean activateCurrent(Map<String, Integer> counts) {
        return chain.activateCurrent(counts);
    }

    /** 依赖门走真实注册表（P0-2）：用 {@link AssetRegistry#usableCounts()} 判定前置是否就位。 */
    public boolean activateCurrentFromRegistry() {
        return chain.activateCurrentWithRegistry(assets);
    }

    /**
     * 依赖门统一入口（P2-A 修正）：走 {@link PlanningAssetSnapshot}（实时扫描为持有真相）。
     * 背包不落盘，注册表没有 inventory_scan → 旧入口对物品类 wait_for 恒不满足。
     */
    public boolean activateCurrentFromSnapshot(PlanningAssetSnapshot snapshot) {
        return chain.activateCurrentWithSnapshot(snapshot);
    }

    /** 懒展开注入点：宿主目标驱动器把已生成的当前一级二级注入链（core 纯 JVM 不调 LLM）。 */
    public void expandCurrentPrimary(List<Subtask> generated) {
        chain.expandCurrentPrimary(generated);
    }

    public boolean applyHardCoded(String subtaskId, boolean satisfied) {
        boolean completed = chain.applyHardCodedResult(subtaskId, satisfied);
        publish("subtask_detection", Map.of("subtask", subtaskId, "mode", "HARD_CODED", "satisfied", satisfied, "completed", completed));
        if (completed) publish("subtask_completed", Map.of("subtask", subtaskId));
        return completed;
    }

    public void skipSubtask(String subtaskId, String reason) {
        chain.skipSubtask(subtaskId, reason);
        publish("subtask_skipped", Map.of("subtask", subtaskId, "reason", reason));
    }

    public void applySupervisor(SupervisorDecision decision) {
        chain.applySupervisorDecision(decision);
        publish("supervisor_decision", Map.of("target", decision.targetNodeId(), "decision", decision.type().name(), "reason", decision.reason()));
    }

    private void publish(String type, Map<String, ?> data) {
        // The pure JVM core intentionally has no monitor dependency. Host adapters may observe this facade.
    }
}
