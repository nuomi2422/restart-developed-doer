package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;

/** Stateful owner of task progress; adapters may only report into this object. */
public final class TaskChain {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Goal goal;
    private final Map<String, SubtaskStatus> statuses = new LinkedHashMap<>();
    private int primaryIndex;
    private int subtaskIndex;
    private PrimaryGoalStatus primaryStatus = PrimaryGoalStatus.PENDING;

    public TaskChain(Goal goal) {
        this.goal = Objects.requireNonNull(goal);
        for (PrimaryGoal primary : goal.primaryGoals()) for (Subtask subtask : primary.subtasks()) statuses.put(subtask.id(), SubtaskStatus.PENDING);
    }
    public Goal goal() { return goal; }
    public synchronized PrimaryGoalStatus primaryStatus() { return primaryStatus; }
    public synchronized SubtaskStatus currentSubtaskStatus() { return statuses.get(currentSubtask().id()); }
    public synchronized Subtask currentSubtask() { return currentPrimary().subtasks().get(subtaskIndex); }
    public synchronized PrimaryGoal currentPrimary() { return goal.primaryGoals().get(primaryIndex); }

    public synchronized void startCurrent() {
        if (primaryStatus == PrimaryGoalStatus.PENDING) primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (primaryStatus != PrimaryGoalStatus.ACTIVE || statuses.get(currentSubtask().id()) != SubtaskStatus.PENDING) throw new IllegalStateException("current subtask cannot start");
        statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
    }

    public synchronized boolean applyHardCodedResult(String subtaskId, boolean satisfied) {
        requireCurrent(subtaskId);
        if (currentSubtask().detectionMode() != DetectionMode.HARD_CODED) throw new IllegalStateException("current subtask is not hard-coded");
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        if (!satisfied) return false;
        statuses.put(subtaskId, SubtaskStatus.COMPLETED);
        advanceOrAwait();
        return true;
    }

    public synchronized boolean applyAiAssistedResult(String subtaskId, String result) {
        requireCurrent(subtaskId);
        if (currentSubtask().detectionMode() != DetectionMode.AI_ASSISTED) throw new IllegalStateException("current subtask is not AI-assisted");
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        Objects.requireNonNull(result);
        if (!"CONFIRMED".equals(result)) return false;
        statuses.put(subtaskId, SubtaskStatus.COMPLETED);
        advanceOrAwait();
        return true;
    }

    public synchronized void applySupervisorDecision(SupervisorDecision decision) {
        Objects.requireNonNull(decision);
        if (primaryStatus != PrimaryGoalStatus.AWAITING_SUPERVISOR || !currentPrimary().id().equals(decision.targetNodeId())) throw new IllegalStateException("supervisor decision does not match current primary goal");
        switch (decision.type()) {
            case CONFIRM -> {
                primaryStatus = PrimaryGoalStatus.COMPLETED;
                if (primaryIndex + 1 < goal.primaryGoals().size()) {
                    primaryIndex++;
                    subtaskIndex = 0;
                    primaryStatus = PrimaryGoalStatus.PENDING;
                }
            }
            case REJECT, REPLAN -> primaryStatus = PrimaryGoalStatus.REPLANNING;
            case NEED_MORE_EVIDENCE, DEFER -> primaryStatus = PrimaryGoalStatus.WAITING;
        }
    }

    public synchronized void markFailed(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not running/stalled");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("failure reason required");
        statuses.put(subtaskId, SubtaskStatus.FAILED);
    }

    /** 监督检测到卡死（资产/工具长时间无变化）→ 置 STALLED，等待外部拍醒或升级。 */
    public synchronized void markStalled(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("stall reason required");
        statuses.put(subtaskId, SubtaskStatus.STALLED);
    }

    /** 监督拍醒后，检测到行为恢复 → 从 STALLED 回到 RUNNING（任务继续）。 */
    public synchronized void resumeFromStalled(String subtaskId) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not stalled");
        statuses.put(subtaskId, SubtaskStatus.RUNNING);
    }

    /** Level 2 局部恢复：失败的当前二级重置为 PENDING，可重新 startCurrent（AI 换策略再试）。 */
    public synchronized void retrySubtask(String subtaskId) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.FAILED) throw new IllegalStateException("only a FAILED subtask may retry");
        statuses.put(subtaskId, SubtaskStatus.PENDING);
    }

    public synchronized Map<String, SubtaskStatus> subtaskStatuses() {
        return Map.copyOf(statuses);
    }

    /** 导出任务链状态为 JSON（持久化用：goal 结构 + 全部状态 + 当前指针）。 */
    public synchronized String toJson() {
        JsonObject o = new JsonObject();
        o.add("goal", GSON.toJsonTree(goal));
        JsonObject st = new JsonObject();
        for (Map.Entry<String, SubtaskStatus> e : statuses.entrySet()) {
            st.addProperty(e.getKey(), e.getValue().name());
        }
        o.add("statuses", st);
        o.addProperty("primaryIndex", primaryIndex);
        o.addProperty("subtaskIndex", subtaskIndex);
        o.addProperty("primaryStatus", primaryStatus.name());
        return GSON.toJson(o);
    }

    /** 从 JSON 恢复任务链状态（游戏重启后 RECOVERING）。 */
    public static TaskChain fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        Goal goal = GSON.fromJson(o.get("goal"), Goal.class);
        TaskChain chain = new TaskChain(goal);
        JsonObject st = o.getAsJsonObject("statuses");
        for (String k : st.keySet()) {
            chain.statuses.put(k, SubtaskStatus.valueOf(st.get(k).getAsString()));
        }
        chain.primaryIndex = o.get("primaryIndex").getAsInt();
        chain.subtaskIndex = o.get("subtaskIndex").getAsInt();
        chain.primaryStatus = PrimaryGoalStatus.valueOf(o.get("primaryStatus").getAsString());
        return chain;
    }

    private void advanceOrAwait() {
        if (subtaskIndex + 1 < currentPrimary().subtasks().size()) { subtaskIndex++; return; }
        primaryStatus = PrimaryGoalStatus.AWAITING_SUPERVISOR;
    }
    private void requireCurrent(String id) { if (id == null || !currentSubtask().id().equals(id)) throw new IllegalArgumentException("stale subtask: " + id); }
}
