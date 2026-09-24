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

    public Map<String, Object> snapshot() { return chain.snapshot(); }

    public void startCurrent() {
        chain.startCurrent();
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
