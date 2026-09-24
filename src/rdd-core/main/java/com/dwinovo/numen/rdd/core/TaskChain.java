package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;

/** Stateful owner of task progress; adapters may only report into this object. */
public final class TaskChain {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // 非 final：懒展开注入二级时需要重建 Goal（把当前未展开一级替换为已展开版本）。
    private Goal goal;
    private final Map<String, SubtaskStatus> statuses = new LinkedHashMap<>();
    private final Map<String, String> skipReasons = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    private int primaryIndex;
    private int subtaskIndex;
    private PrimaryGoalStatus primaryStatus = PrimaryGoalStatus.PENDING;
    /** 当前二级最近一次绑定的宿主执行实例 id（P0-3 执行身份）；无执行绑定时为 null。 */
    private String activeExecutionId;
    /** 当前二级最近一次启动的 tick 时间戳（执行审计/重启恢复判定用）；从未启动为 0。 */
    private long lastStartedAtMillis;
    /**
     * P0 完成事实继承层：历史可靠完成过的一级"阶段键"集合（归一化主题，见
     * {@link StageKeyNormalizer}）。命中的一级视为已达成、在 currentPrimary()/推进时跳过；
     * 它<b>不</b>写进 statuses（懒展开一级本无二级，也绝不伪造二级完成）。
     * 全部阶段命中 → 链直接 COMPLETED。跨重绑继承由 {@code CompletedFactStore} 查询后注入。
     */
    private final Set<String> satisfiedStages = new LinkedHashSet<>();

    public TaskChain(Goal goal) {
        this(goal, Set.of());
    }

    /** @param satisfiedStageKeys 历史已完成的阶段键集合（归一键）；命中一级跳过。空集等价旧构造。 */
    public TaskChain(Goal goal, Set<String> satisfiedStageKeys) {
        this.goal = Objects.requireNonNull(goal);
        for (PrimaryGoal primary : goal.primaryGoals()) for (Subtask subtask : primary.subtasks()) statuses.put(subtask.id(), SubtaskStatus.PENDING);
        if (satisfiedStageKeys != null) {
            for (PrimaryGoal primary : goal.primaryGoals()) {
                String key = StageKeyNormalizer.normalize(primary.description());
                if (satisfiedStageKeys.contains(key)) {
                    satisfiedStages.add(key);
                }
            }
        }
        int first = firstUnsatisfiedFrom(0);
        if (first < 0) {
            // 全部阶段都有历史完成事实：链直接终态（一级本无二级，不伪造完成）
            primaryIndex = goal.primaryGoals().size() - 1;
            primaryStatus = PrimaryGoalStatus.COMPLETED;
        } else {
            primaryIndex = first;
            primaryStatus = PrimaryGoalStatus.PENDING;
        }
    }
    public Goal goal() { return goal; }
    public synchronized PrimaryGoalStatus primaryStatus() { return primaryStatus; }

    /** 当前二级的状态；当前一级"已到达但未展开"（无二级可运行）时为 null。 */
    public synchronized SubtaskStatus currentSubtaskStatus() {
        Subtask s = currentSubtask();
        return s == null ? null : statuses.get(s.id());
    }

    /**
     * 当前指针指向的二级目标。当前一级为未展开（懒加载、0 个二级）时返回 null——
     * 链上没有可运行的当前二级，宿主应先把已生成二级注入再激活；null 不是合法可执行节点。
     */
    public synchronized Subtask currentSubtask() {
        PrimaryGoal cur = currentPrimary();
        return cur.unexpanded() ? null : cur.subtasks().get(subtaskIndex);
    }
    public synchronized PrimaryGoal currentPrimary() { return goal.primaryGoals().get(primaryIndex); }

    /** 当前一级的所有前置资产(waitFor)是否都被 counts 满足（无 waitFor → true）。 */
    public synchronized boolean currentPrimaryReady(Map<String, Integer> counts) {
        List<AssetRequirement> wf = currentPrimary().waitFor();
        if (wf.isEmpty()) return true;
        if (counts == null) return false;
        for (AssetRequirement r : wf) {
            if (counts.getOrDefault(r.assetKey(), 0) < r.minimum()) return false;
        }
        return true;
    }

    /**
     * 依赖门接入真实注册表（P0-2）：以 {@link AssetRegistry#usableCounts()} 折叠的
     * 可用资产为准判定当前一级是否已满足前置。只算 OBSERVED 且有 inventory_scan 计数的条目，
     * UNKNOWN/INVALID 状态资产天然不满足依赖门。
     */
    public synchronized boolean currentPrimaryReady(AssetRegistry registry) {
        return currentPrimaryReady(registry == null ? null : registry.usableCounts());
    }

    /**
     * 激活"刚推进到、还没开工"的当前一级（PENDING/WAITING → ACTIVE 并启动其首个二级）。
     * 前置资产未到位 → 置 WAITING 并返回 false（监督方此时不该把它派给 AI，等资产到了再调）。
     */
    public synchronized boolean activateCurrent(Map<String, Integer> counts) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            // 未展开 ≠ 依赖未满足：绝不能把"该展开了"误判成 WAITING(缺资产)，也不得进 ACTIVE。
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        if (!currentPrimaryReady(counts)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
        }
        return true;
    }

    /** 同 {@link #activateCurrent(Map)}，但依赖门以真实注册表为准（P0-2 接线）。 */
    public synchronized boolean activateCurrentWithRegistry(AssetRegistry registry) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        if (!currentPrimaryReady(registry)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
        }
        return true;
    }

    public synchronized void startCurrent() {
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before start: "
                    + currentPrimary().id());
        }
        if (primaryStatus == PrimaryGoalStatus.PENDING) primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (primaryStatus != PrimaryGoalStatus.ACTIVE || statuses.get(currentSubtask().id()) != SubtaskStatus.PENDING) throw new IllegalStateException("current subtask cannot start");
        statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
        attempts.merge(currentSubtask().id(), 1, Integer::sum);
        activeExecutionId = null; // 新一轮执行，旧的宿主执行实例身份作废
        lastStartedAtMillis = System.currentTimeMillis();
    }

    /** P0-3 执行身份：宿主开始执行后把真实执行实例 id 绑到当前二级；重复绑定会置换旧 id。 */
    public synchronized void bindExecution(String subtaskId, String executionId) {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("execution id required");
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        activeExecutionId = executionId;
    }

    /** P0-3 执行身份：当前二级绑定的宿主执行实例 id；未绑定/未运行时为 null。 */
    public synchronized String activeExecutionId() { return activeExecutionId; }
    public synchronized int attempts(String subtaskId) { return attempts.getOrDefault(subtaskId, 0); }

    /**
     * 懒加载唯一合法的注入点：宿主目标驱动器把已为"当前一级"生成的二级集合注入该一级。
     * 保持一级的 id/description/waitFor 与 {@link #primaryStatus} 不变，仅 unexpanded→false；
     * 注入后照常走依赖门/激活/硬检测。当前一级未展开之外的调用一律拒绝（不重写其他任何状态）。
     */
    public synchronized void expandCurrentPrimary(List<Subtask> generated) {
        PrimaryGoal cur = currentPrimary();
        if (isSatisfied(primaryIndex)) {
            // 防御：被完成事实命中的一级必须跳过，绝不能被展开重跑（正常路径 currentPrimary 已跳过）
            throw new IllegalStateException("current primary is fact-satisfied and must be skipped, not expanded: " + cur.id());
        }
        if (!cur.unexpanded()) {
            throw new IllegalStateException("current primary already expanded: " + cur.id());
        }
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may be expanded: " + primaryStatus);
        }
        if (generated == null || generated.isEmpty()) {
            throw new IllegalArgumentException("expansion requires at least one legal subtask");
        }
        Set<String> ids = new HashSet<>();
        for (Subtask s : generated) {
            if (s == null) {
                throw new IllegalArgumentException("null subtask in expansion");
            }
            if (!ids.add(s.id())) {
                throw new IllegalArgumentException("duplicate subtask id in expansion: " + s.id());
            }
            if (statuses.containsKey(s.id())) {
                throw new IllegalArgumentException("subtask id already used in chain: " + s.id());
            }
        }
        List<PrimaryGoal> primaries = new ArrayList<>(goal.primaryGoals());
        primaries.set(primaryIndex,
                new PrimaryGoal(cur.id(), cur.description(), List.copyOf(generated), cur.waitFor(), false));
        goal = new Goal(goal.id(), goal.description(), primaries);
        for (Subtask s : generated) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
        }
        subtaskIndex = 0;
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
                if (primaryIndex + 1 < goal.primaryGoals().size()) {
                    // 推进到下一个"未被完成事实命中"的一级；中间被事实命中的阶段直接跳过。
                    int next = firstUnsatisfiedFrom(primaryIndex + 1);
                    subtaskIndex = 0;
                    if (next < 0) {
                        primaryStatus = PrimaryGoalStatus.COMPLETED;
                    } else {
                        primaryIndex = next;
                        primaryStatus = PrimaryGoalStatus.PENDING;
                    }
                } else {
                    primaryStatus = PrimaryGoalStatus.COMPLETED;
                }
            }
            case REJECT, REPLAN -> primaryStatus = PrimaryGoalStatus.REPLANNING;
            // NEED_MORE_EVIDENCE/DEFER 是"证据不足暂缓"，特意停回 AWAITING_SUPERVISOR：
            // 绝不落到 WAITING——WAITING 是"缺前置资产"语义，二者的退出通道完全不同。
            case NEED_MORE_EVIDENCE, DEFER -> { /* 停在监督等待，等新的 CONFIRM/REJECT/REPLAN */ }
        }
    }

    /** REPLANNING 出口：宿主决定沿用现有二级计划 → 本级二级全量重置 PENDING，回到 PENDING 走依赖门/激活重跑。 */
    public synchronized void resumeFromReplanning() {
        if (primaryStatus != PrimaryGoalStatus.REPLANNING) throw new IllegalStateException("not replanning: " + primaryStatus);
        PrimaryGoal cur = currentPrimary();
        if (cur.unexpanded()) throw new IllegalStateException("unexpanded primary cannot resume replanning: " + cur.id());
        for (Subtask s : cur.subtasks()) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
            skipReasons.remove(s.id());
        }
        subtaskIndex = 0;
        primaryStatus = PrimaryGoalStatus.PENDING;
        clearExecutionMetadata(); // 重跑 = 旧执行身份/启动时间作废
    }

    /**
     * REPLANNING 缺口 P4：从“卡死”进入 REPLANNING 的显式出口。
     *
     * <p>旧链只有 {@code AWAITING_SUPERVISOR + applySupervisorDecision(REPLAN)} 一个入口，
     * 导致生产里 REPLANNING 长期休眠。本方法允许：链 ACTIVE，且**当前二级 FAILED/STALLED**（确有卡死）
     * 时，由宿主/上层显式进入 REPLANNING；之后照旧走 {@link #replaceCurrentSubtasks(List)}（换新计划）
     * 或 {@link #resumeFromReplanning()}（重跑现有）。
     */
    public synchronized void enterReplanningFromStuck(String reason) {
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE primary may enter replanning from stuck: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("unexpanded primary cannot enter replanning from stuck: " + currentPrimary().id());
        }
        Subtask cur = currentSubtask();
        if (cur == null) {
            throw new IllegalStateException("no current subtask to replan");
        }
        SubtaskStatus st = statuses.get(cur.id());
        if (st != SubtaskStatus.FAILED && st != SubtaskStatus.STALLED) {
            throw new IllegalStateException("current subtask is not stuck: " + st);
        }
        primaryStatus = PrimaryGoalStatus.REPLANNING;
        clearExecutionMetadata();
    }

    /**
     * REPLANNING 出口：宿主为当前一级换了新二级计划 → 原位替换成 generated（保留一级
     * id/description/waitFor），旧二级状态作废、新二级全置 PENDING，subtask 指针归零回到 PENDING。
     */
    public synchronized void replaceCurrentSubtasks(List<Subtask> generated) {
        if (primaryStatus != PrimaryGoalStatus.REPLANNING) throw new IllegalStateException("only a REPLANNING primary may have its plan replaced: " + primaryStatus);
        if (generated == null || generated.isEmpty()) throw new IllegalArgumentException("replacement requires at least one legal subtask");
        PrimaryGoal cur = currentPrimary();
        Set<String> ids = new HashSet<>();
        for (Subtask s : generated) {
            if (s == null) throw new IllegalArgumentException("null subtask in replacement");
            if (!ids.add(s.id())) throw new IllegalArgumentException("duplicate subtask id in replacement: " + s.id());
            // 只允许替换当前一级的二级：新 id 不得与其它一级已有的二级冲突。
            if (statuses.containsKey(s.id()) && !wasInPrimary(cur.id(), s.id()))
                throw new IllegalArgumentException("subtask id used outside the replaced primary: " + s.id());
        }
        List<Subtask> old = cur.subtasks();
        Set<String> freed = new HashSet<>();
        for (Subtask s : old) freed.add(s.id());
        List<PrimaryGoal> primaries = new ArrayList<>(goal.primaryGoals());
        primaries.set(primaryIndex, new PrimaryGoal(cur.id(), cur.description(), List.copyOf(generated), cur.waitFor(), false));
        goal = new Goal(goal.id(), goal.description(), primaries);
        for (String freedId : freed) {
            statuses.remove(freedId);
            skipReasons.remove(freedId);
            attempts.remove(freedId);
        }
        for (Subtask s : generated) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
        }
        subtaskIndex = 0;
        primaryStatus = PrimaryGoalStatus.PENDING;
        clearExecutionMetadata(); // 换计划 = 旧执行身份/启动时间作废
    }

    /** RECOVERING 出口：停机恢复后宿主核实当前二级可继续 → 回到 ACTIVE，照常跑监督/完成判定。 */
    public synchronized void resumeFromRecovering() {
        if (primaryStatus != PrimaryGoalStatus.RECOVERING) throw new IllegalStateException("not recovering: " + primaryStatus);
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        clearExecutionMetadata(); // 恢复续跑不是"同一执行"：旧执行身份/启动时间作废
    }

    /** 执行元数据作废（离开在途执行的统一清点）：二级身份与启动时间只属于一个在途执行。 */
    private void clearExecutionMetadata() {
        activeExecutionId = null;
        lastStartedAtMillis = 0;
    }

    /** 查询某一级包含某二级 id（replaceCurrentSubtasks 校验用，避免误删其它一级）。 */
    private boolean wasInPrimary(String primaryId, String subtaskId) {
        for (PrimaryGoal primary : goal.primaryGoals()) {
            if (primary.id().equals(primaryId)) {
                for (Subtask s : primary.subtasks()) if (s.id().equals(subtaskId)) return true;
            }
        }
        return false;
    }

    /** 该一级是否被完成事实命中（归一主题键在 satisfiedStages 中）。 */
    private boolean isSatisfied(int index) {
        return satisfiedStages.contains(StageKeyNormalizer.normalize(goal.primaryGoals().get(index).description()));
    }

    /** 从 start 起找第一个未被完成事实命中的一级下标；全部命中返回 -1。 */
    private int firstUnsatisfiedFrom(int start) {
        for (int i = Math.max(0, start); i < goal.primaryGoals().size(); i++) {
            if (!isSatisfied(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 只读：当前链继承的已完成阶段键集合。 */
    public synchronized Set<String> satisfiedStages() {
        return Set.copyOf(satisfiedStages);
    }

    public synchronized void markFailed(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not running/stalled");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("failure reason required");
        statuses.put(subtaskId, SubtaskStatus.FAILED);
        activeExecutionId = null; // 二级离开 RUNNING：宿主执行身份作废
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

    /**
     * Mark an explicitly optional current step as intentionally skipped and advance.
     * Skipping is different from failure: it is a deliberate replanning outcome and
     * must not trigger the retry/capability-gap loop.
     */
    public synchronized void skipSubtask(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) throw new IllegalStateException("primary not active");
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.FAILED && st != SubtaskStatus.STALLED) {
            throw new IllegalStateException("current subtask cannot be skipped from " + st);
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("skip reason required");
        statuses.put(subtaskId, SubtaskStatus.SKIPPED);
        skipReasons.put(subtaskId, reason);
        advanceOrAwait();
    }

    public synchronized Map<String, SubtaskStatus> subtaskStatuses() {
        return Map.copyOf(statuses);
    }

    /** Read-only structured view for monitoring; it is derived from the state owner. */
    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> primaries = new ArrayList<>();
        for (int p = 0; p < goal.primaryGoals().size(); p++) {
            PrimaryGoal primary = goal.primaryGoals().get(p);
            List<Map<String, Object>> subtasks = new ArrayList<>();
            for (int s = 0; s < primary.subtasks().size(); s++) {
                Subtask subtask = primary.subtasks().get(s);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", subtask.id());
                item.put("description", subtask.description());
                item.put("status", statuses.get(subtask.id()).name());
                if (skipReasons.containsKey(subtask.id())) item.put("skipReason", skipReasons.get(subtask.id()));
                item.put("detectionMode", subtask.detectionMode().name());
                item.put("condition", subtask.condition());
                item.put("current", p == primaryIndex && s == subtaskIndex);
                subtasks.add(item);
            }
            Map<String, Object> primaryView = new LinkedHashMap<>();
            primaryView.put("id", primary.id());
            primaryView.put("description", primary.description());
            primaryView.put("current", p == primaryIndex);
            primaryView.put("unexpanded", primary.unexpanded());
            if (!primary.waitFor().isEmpty()) {
                List<Map<String, Object>> wf = new ArrayList<>();
                for (AssetRequirement r : primary.waitFor()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asset_key", r.assetKey());
                    m.put("minimum", r.minimum());
                    wf.add(m);
                }
                primaryView.put("waitFor", wf);
            }
            primaryView.put("subtasks", subtasks);
            primaries.add(primaryView);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("goalId", goal.id());
        view.put("description", goal.description());
        view.put("satisfiedStages", List.copyOf(satisfiedStages));
        view.put("primaryStatus", primaryStatus.name());
        view.put("primaryIndex", primaryIndex);
        view.put("subtaskIndex", subtaskIndex);
        PrimaryGoal current = currentPrimary();
        view.put("currentPrimaryId", current.id());
        // 当前一级未展开时诚实报"无当前二级"，而不是伪造占位节点。
        view.put("currentSubtaskId", current.unexpanded() ? null : currentSubtask().id());
        if (primaryStatus == PrimaryGoalStatus.WAITING && !current.waitFor().isEmpty()) {
            view.put("waitingFor", currentPrimary().waitFor().stream().map(AssetRequirement::assetKey).toList());
        }
        view.put("primaries", primaries);
        return view;
    }

    /** 导出任务链状态为 JSON（持久化用：goal 结构 + 全部状态 + 当前指针 + 执行元数据）。 */
    public synchronized String toJson() {
        JsonObject o = new JsonObject();
        o.add("goal", GSON.toJsonTree(goal));
        JsonObject st = new JsonObject();
        for (Map.Entry<String, SubtaskStatus> e : statuses.entrySet()) {
            st.addProperty(e.getKey(), e.getValue().name());
        }
        o.add("statuses", st);
        o.add("skipReasons", GSON.toJsonTree(skipReasons));
        o.add("attempts", GSON.toJsonTree(attempts));
        JsonArray sat = new JsonArray();
        for (String k : satisfiedStages) {
            sat.add(k);
        }
        o.add("satisfiedStages", sat);
        o.addProperty("primaryIndex", primaryIndex);
        o.addProperty("subtaskIndex", subtaskIndex);
        o.addProperty("primaryStatus", primaryStatus.name());
        o.addProperty("lastStartedAtMillis", lastStartedAtMillis);
        if (activeExecutionId != null) o.addProperty("activeExecutionId", activeExecutionId);
        return GSON.toJson(o);
    }

    /**
     * 从 JSON 恢复任务链状态（游戏重启后 RECOVERING）。
     *
     * <p>恢复规则（P0-4）：磁盘上正 ACTIVE/RUNNING 的链不等于重启后真的能不告而续——
     * 停机期间的执行身份、身体任务全部作废。这里把「ACTIVE + 当前二级 RUNNING/STALLED」
     * 归一为 RECOVERING，由宿主核实当前二级可继续后再 {@link #resumeFromRecovering()}。
     * 其余状态原样恢复（未开始的 PENDING/WAITING、已停的 AWAITING_SUPERVISOR 等都不算在途执行）。
     */
    public static TaskChain fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonObject goalObj = o.getAsJsonObject("goal");
        // 旧链兼容（A-1 前无 unexpanded 字段）：解析树里给缺该字段的一级补 false=已展开，
        // 不依赖 Gson 对缺失原始类型组件的默认行为。waitFor 等引用组件缺失 → 构造器已置空。
        JsonArray primaries = goalObj.getAsJsonArray("primaryGoals");
        for (JsonElement el : primaries) {
            JsonObject pg = el.getAsJsonObject();
            if (!pg.has("unexpanded")) {
                pg.addProperty("unexpanded", false);
            }
        }
        Goal goal = GSON.fromJson(goalObj, Goal.class);
        TaskChain chain = new TaskChain(goal);
        JsonObject st = o.getAsJsonObject("statuses");
        if (st == null) {
            throw new IllegalArgumentException("restored status table missing");
        }
        chain.statuses.clear();
        for (String k : st.keySet()) {
            chain.statuses.put(k, SubtaskStatus.valueOf(st.get(k).getAsString()));
        }
        chain.primaryIndex = o.get("primaryIndex").getAsInt();
        chain.subtaskIndex = o.get("subtaskIndex").getAsInt();
        chain.primaryStatus = PrimaryGoalStatus.valueOf(o.get("primaryStatus").getAsString());
        if (o.has("skipReasons") && o.get("skipReasons").isJsonObject()) {
            // 全量载入（不做加载期过滤）：skipReason 指向非 SKIPPED/未知二级属于损坏数据，
            // 交由 validateRestoredState 大声拒绝，而不是悄悄丢进 /dev/null。
            for (var entry : o.getAsJsonObject("skipReasons").entrySet()) {
                chain.skipReasons.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        if (o.has("attempts") && o.get("attempts").isJsonObject()) {
            JsonObject att = o.getAsJsonObject("attempts");
            for (String k : att.keySet()) {
                chain.attempts.put(k, att.get(k).getAsInt());
            }
        }
        if (o.has("lastStartedAtMillis")) {
            chain.lastStartedAtMillis = o.get("lastStartedAtMillis").getAsLong();
        }
        // 完成事实继承层（P0）：旧链无此字段 → 空集（向后兼容）
        if (o.has("satisfiedStages") && o.get("satisfiedStages").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("satisfiedStages")) {
                chain.satisfiedStages.add(el.getAsString());
            }
        }
        if (o.has("activeExecutionId") && !o.get("activeExecutionId").isJsonNull()) {
            chain.activeExecutionId = o.get("activeExecutionId").getAsString();
        }
        // 在途执行归一（P0-4）：必须基于恢复后的状态表判，不能基于未恢复的链。
        if (chain.primaryStatus == PrimaryGoalStatus.ACTIVE) {
            Subtask cur = chain.currentSubtask();
            SubtaskStatus curStatus = cur == null ? null : chain.statuses.get(cur.id());
            if (curStatus == SubtaskStatus.RUNNING || curStatus == SubtaskStatus.STALLED) {
                chain.primaryStatus = PrimaryGoalStatus.RECOVERING;
                chain.activeExecutionId = null; // 停机作废：宿主执行实例身份不跨重启
            }
        }
        // 幂等清：已是 RECOVERING（二次重启读到自己刚存的 RECOVERING）也一样作废执行身份，
        // 防"休眠期间宿主编入过 bindExecution 的残留 id"被第三次原样恢复。
        if (chain.primaryStatus == PrimaryGoalStatus.RECOVERING) {
            chain.activeExecutionId = null;
        }
        chain.validateRestoredState();
        return chain;
    }

    private void advanceOrAwait() {
        activeExecutionId = null; // 二级离开 RUNNING：宿主执行身份作废
        if (subtaskIndex + 1 < currentPrimary().subtasks().size()) { subtaskIndex++; return; }
        primaryStatus = PrimaryGoalStatus.AWAITING_SUPERVISOR;
    }
    private void requireCurrent(String id) { if (id == null || currentSubtask() == null || !currentSubtask().id().equals(id)) throw new IllegalArgumentException("stale subtask: " + id); }

    /** Reject corrupt handoff state before it can reach a live executor or monitor. */
    private void validateRestoredState() {
        if (primaryIndex < 0 || primaryIndex >= goal.primaryGoals().size()) {
            throw new IllegalArgumentException("primary index out of range: " + primaryIndex);
        }
        Set<String> expected = new LinkedHashSet<>();
        for (PrimaryGoal primary : goal.primaryGoals()) {
            for (Subtask subtask : primary.subtasks()) {
                if (!expected.add(subtask.id())) {
                    throw new IllegalArgumentException("duplicate subtask id in restored goal: " + subtask.id());
                }
            }
        }
        if (!statuses.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(statuses.keySet());
            Set<String> unknown = new LinkedHashSet<>(statuses.keySet());
            unknown.removeAll(expected);
            throw new IllegalArgumentException("restored status table mismatch; missing=" + missing + ", unknown=" + unknown);
        }
        for (String id : attempts.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored attempts table references unknown subtask: " + id);
            }
        }
        for (String id : skipReasons.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored skipReason references unknown subtask: " + id);
            }
            if (statuses.get(id) != SubtaskStatus.SKIPPED) {
                throw new IllegalArgumentException("restored skipReason on non-skipped subtask: " + id);
            }
        }
        PrimaryGoal current = goal.primaryGoals().get(primaryIndex);
        // 完成事实层自洽：非终态的当前一级不得是被事实命中的阶段（否则永远停在已达成阶段）
        if (primaryStatus != PrimaryGoalStatus.COMPLETED && isSatisfied(primaryIndex)) {
            throw new IllegalArgumentException("current primary is fact-satisfied but chain is not COMPLETED: " + current.id());
        }
        if (current.unexpanded()) {
            if (subtaskIndex != 0) {
                throw new IllegalArgumentException("unexpanded primary must have subtask index 0: " + subtaskIndex);
            }
            if (primaryStatus == PrimaryGoalStatus.ACTIVE) {
                // ACTIVE+未展开 = 永远 return 的静默卡死（当前二级为 null，走状态分支全部落空）。
                throw new IllegalArgumentException("ACTIVE primary cannot be unexpanded; must be PENDING/WAITING until expansion");
            }
        } else if (subtaskIndex < 0 || subtaskIndex >= current.subtasks().size()) {
            throw new IllegalArgumentException("subtask index out of range: " + subtaskIndex);
        }
    }
}
