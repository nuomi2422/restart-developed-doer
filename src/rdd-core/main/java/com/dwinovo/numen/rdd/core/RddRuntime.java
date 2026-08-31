package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;

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

    public void startCurrent() {
        chain.startCurrent();
        publish("subtask_started", Map.of("goal", chain.currentPrimary().id(), "subtask", chain.currentSubtask().id()));
    }

    public boolean applyHardCoded(String subtaskId, boolean satisfied) {
        boolean completed = chain.applyHardCodedResult(subtaskId, satisfied);
        publish("subtask_detection", Map.of("subtask", subtaskId, "mode", "HARD_CODED", "satisfied", satisfied, "completed", completed));
        if (completed) publish("subtask_completed", Map.of("subtask", subtaskId));
        return completed;
    }

    public void applySupervisor(SupervisorDecision decision) {
        chain.applySupervisorDecision(decision);
        publish("supervisor_decision", Map.of("target", decision.targetNodeId(), "decision", decision.type().name(), "reason", decision.reason()));
    }

    private void publish(String type, Map<String, ?> data) {
        // The pure JVM core intentionally has no monitor dependency. Host adapters may observe this facade.
    }
}
