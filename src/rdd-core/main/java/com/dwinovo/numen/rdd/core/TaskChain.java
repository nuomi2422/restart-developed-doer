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

    /** 暂停原因：与 {@link #skipReasons} 对称，但语义相反（留着可开，不是放弃）。 */
    private final Map<String, String> pauseReasons = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    /**
     * {@code mode=acquire} 二级的<b>进入时基线</b>：二级 id → 该时刻各物品的持有量。
     *
     * <p>为什么挂在链上而不是 {@link Subtask} record：{@code Subtask} 是<b>计划规格</b>
     * （由规划器生成、要进 prompt、能被 JSON 序列化）；baseline 是<b>运行时观察事实</b>，
     * 宿主在二级真正激活的那一刻才知道。混进 record 会让"计划"被"执行观察"污染，
     * 并把构造器/JSON/旧存档兼容面一次性放大。
     *
     * <p>为什么必须随链持久化：代次 {@code planRevision} 已经吃过一次"只在宿主内存里 →
     * 重启撞号"的亏（见 {@link #adoptRevisionFromIds}）。baseline 若同样只活在内存，
     * 重启后 {@code acquire} 会拿不到基线 —— 此时 {@link HardCodedEvaluator} 一律判 false，
     * 已完成的二级会在重启后重新变成"没达成"。
     */
    private final Map<String, Map<String, Integer>> acquireBaselines = new LinkedHashMap<>();
    private int primaryIndex;
    private int subtaskIndex;
    /**
     * 计划代次（2026-09-30 深审 R05/codex P1）。
     *
     * <p>用途：让每次生成的二级 id 唯一。宿主生成 id 时拼上它（{@code primary-r{rev}-N}），
     * 于是**上一轮计划留下的任何东西（迟到的协商回执、指标关联、旧 id 引用）都匹配不上新计划**。
     *
     * <p><b>为什么必须存在链里而不是宿主内存</b>（codex 复审抓出）：代次若只活在宿主的 Map，
     * 停服就被清空，而磁盘上的链里 id 仍带着旧 rev —— 重启后第一次重规划从 rev=1 重新开始，
     * 与磁盘上已有的 {@code primary-r1-0} **直接撞号**，隔离形同虚设。
     * 放进 TaskChain 就随 {@code toJson/fromJson} 一起活过重启。
     *
     * <p>单调递增，永不回退。
     */
    private int planRevision;
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

    /** 当前计划代次（只增）。生成二级 id 时拼上它即可保证跨轮次唯一。 */
    public synchronized int planRevision() { return planRevision; }

    /**
     * 主人暂停的原因前缀（2026-09-30 深审/codex P1-2）。
     *
     * <p>约定：凡以它开头的暂停都是<b>主人的明确指令</b>（「原地待命」），
     * 与士兵自己说的 PAUSE（"我做不了，等条件变"）在治理上完全不同：
     * 前者**永不过期、绝不自动恢复**、也不许被执行层的 COUNTER 推翻；
     * 后者到期该催她换办法。
     */
    public static final String OWNER_PAUSE_PREFIX = "owner";

    /** 主人下的暂停（{@link #pauseSubtask} 已校验 reason 非空）。 */
    public static final String OWNER_PAUSE_REASON =
            "owner: paused on request; resume only when the owner says so";

    /** 这次暂停是不是主人下的（供宿主决定要不要被复评/协商打断）。 */
    public synchronized boolean isOwnerPause(String subtaskId) {
        String reason = pauseReasons.get(subtaskId);
        return reason != null && reason.toLowerCase(java.util.Locale.ROOT).startsWith(OWNER_PAUSE_PREFIX);
    }

    /** 推进计划代次，返回新值。宿主在**每次生成/替换二级之前**调它。 */
    public synchronized int nextPlanRevision() { return ++planRevision; }

    /**
     * 从链上<b>实际存在的二级 id</b> 反推代次下限（2026-09-30 深审/codex P1-1）。
     *
     * <p>为什么需要：planRevision 字段是这一轮才加的，**上一版存档里没有**。
     * 而上一版生成的 id 已经是 {@code primary-r1-0} 这样的形状。
     * 若恢复时把代次当成 0，那么重启后第一次重规划又从 rev=1 开始，
     * 生成出与磁盘上<b>一模一样</b>的 id —— 旧回执的隔离就白做了。
     *
     * <p>所以扫一遍现存二级 id，取其中出现过的最大代次作为下限（不存在则保持原值）。
     * 只增不减，永远不会把代次往回拨。
     */
    private void adoptRevisionFromIds() {
        int floor = planRevision;
        for (PrimaryGoal p : goal.primaryGoals()) {
            for (Subtask s : p.subtasks()) {
                int r = revisionOf(s.id());
                if (r > floor) floor = r;
            }
        }
        if (floor != planRevision) {
            planRevision = floor;
        }
    }

    /** 从二级 id 里读出代次；读不出（旧的 {@code -sN} 命名）返回 -1。 */
    private static int revisionOf(String subtaskId) {
        if (subtaskId == null) return -1;
        int at = subtaskId.lastIndexOf("-r");
        if (at < 0) return -1;
        int end = at + 2;
        int i = end;
        while (i < subtaskId.length() && Character.isDigit(subtaskId.charAt(i))) {
            i++;
        }
        if (i == end || i >= subtaskId.length() || subtaskId.charAt(i) != '-') {
            return -1;   // 不是 "-r<数字>-" 形状
        }
        try {
            return Integer.parseInt(subtaskId.substring(end, i));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

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

    /**
     * 当前一级的所有前置资产(waitFor)是否都被 counts 满足（无 waitFor → true）。
     *
     * <p>⚠️ 2026-09-27（用户指示）：**依赖门被临时删除**——一律返回 true，不再用 waitFor 拦一级。
     * 理由：waitFor 一直牵制、影响推进，且逻辑不自洽（中间产物被消耗后门恒不满足 → 静默停）。
     * waitFor 数据仍保留在链上（未删字段 / injectWaitFor 仍写），**需要时可恢复本方法原逻辑**
     * （见 git 历史 / 经验库 issue/waiting-gate-silent-stall-no-supervision）。
     */
    public synchronized boolean currentPrimaryReady(Map<String, Integer> counts) {
        if (!dependencyGateEnabled) {
            return true; // gate off (owner decision 2026-09-27): the chain must not self-lock
        }
        return dependencyGateSatisfied(counts);
    }

    /**
     * 依赖门开关（2026-09-27 用户决定：先关掉，别让链自锁）。
     *
     * <p>做成开关而不是删代码：关掉只是**运行时行为**变了，拦截能力本身与它的契约测试
     * 都还在（测试里 {@link #setDependencyGateEnabled(boolean)} 打开后照样验证"缺料就拦"）。
     * 哪天要恢复门，只要把默认值改回 true 或在启动处打开，能力立刻回来。
     */
    private static volatile boolean dependencyGateEnabled = Boolean.getBoolean("rdd.dependencyGate");

    /** 运行时开关依赖门（生产默认关；单测用它验证拦截能力仍在）。 */
    public static void setDependencyGateEnabled(boolean enabled) {
        dependencyGateEnabled = enabled;
    }

    /** 当前依赖门是否生效（诊断用：监测台/日志可读，避免"为什么没拦"变成灵异事件）。 */
    public static boolean dependencyGateActive() {
        return dependencyGateEnabled;
    }

    /** 依赖门本体（原本的判定逻辑；开关打开时才会被调用）。 */
    private synchronized boolean dependencyGateSatisfied(Map<String, Integer> counts) {
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
            captureAcquireBaseline(currentSubtask().id(),
                    registry == null ? null : registry.usableCounts());   // F5：激活即锁基线
        }
        return true;
    }

    /**
     * 依赖门统一入口（P2-A 修正）：以 {@link PlanningAssetSnapshot} 为准。
     *
     * <p>为什么不是 {@code activateCurrentWithRegistry}：背包资产**不落盘**（P2-A 决策），
     * 注册表里没有 {@code inventory_scan} 条目 → {@code registry.usableCounts()} 恒空 →
     * 任何物品类 wait_for 永不满足（"背包明明有却不判定"）。快照的 {@code availableCounts()}
     * 才是「实时扫描」的持有真相；world 资产仍由注册表另行提供（二级 world 条件走 RddWorldFacts）。
     */
    public synchronized boolean activateCurrentWithSnapshot(PlanningAssetSnapshot snapshot) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        Map<String, Integer> counts = snapshot == null ? null : snapshot.availableCounts();
        if (!currentPrimaryReady(counts)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            statuses.put(currentSubtask().id(), SubtaskStatus.RUNNING);
            captureAcquireBaseline(currentSubtask().id(), counts);   // F5：激活即锁基线
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

    /**
     * 同 {@link #startCurrent()}，但在同一次 PENDING→RUNNING 迁移里额外锁一次 {@code acquire} 基线。
     *
     * <p><b>为什么必须有这个重载（2026-10-02 实测事故）。</b>基线原先只在
     * {@link #activateCurrentWithSnapshot}（和 {@link #activateCurrentWithRegistry}）里拍，
     * 而 {@code rdd_submit} 派活走的是 {@link #startCurrent()} 这条路 —— 那边一次都不拍。
     * 后果：{@code rdd_submit} 建出来的链永远没有基线 →
     * {@link HardCodedEvaluator} 的「无基线 = 未达成」分支让 {@code mode=acquire} <b>恒 false</b> →
     * 合成/采集类任务<b>真的把东西做出来了也判不出成功</b> → 被 {@code RddStallWatcher} 当成卡死 →
     * 判失败并逼模型把整件事重做一遍（实测：13:11:41 合成出石镐，13:12:45 判「stalled after nudges」，
     * 13:13:04 又合成一把，背包里最后有 2 把）。
     *
     * <p>「激活」和「开跑」是同一个"派活之前"的时刻，两条路都必须锁基线。
     * {@link #captureAcquireBaseline} 幂等（已有则不覆盖），所以重复调用安全。
     *
     * @param counts 此刻同伴的持有量；{@code null} 记空表（等价于「什么都有」，
     *               后续 acquire 只能靠净增满足，比缺基线的恒 false 宽松）
     */
    public synchronized void startCurrentWithCounts(Map<String, Integer> counts) {
        startCurrent();
        captureAcquireBaseline(currentSubtask().id(), counts);
    }

    // ---- acquire baseline (mode=acquire 的运行时基线；见字段注释) ----

    /**
     * 捕获某二级的 {@code acquire} 基线（宿主在二级激活、开始派活**之前**调）。
     *
     * <p>幂等：已有基线时**不覆盖**（重试同一个二级不该把基线推到"已经做完"的位置，
     * 否则 retry 后 {@code acquire} 永远判未达成）。
     */
    public synchronized void captureAcquireBaseline(String subtaskId, Map<String, Integer> counts) {
        if (subtaskId == null || acquireBaselines.containsKey(subtaskId)) return;
        acquireBaselines.put(subtaskId, counts == null ? Map.of() : Map.copyOf(counts));
    }

    /** 该二级的基线；没有则 null（宿主据此让 acquire 判未达成，而不是假装达成）。 */
    public synchronized Map<String, Integer> acquireBaseline(String subtaskId) {
        return subtaskId == null ? null : acquireBaselines.get(subtaskId);
    }

    /** 二级离开本轮生命周期（换计划/作废）时连同它的基线一起清掉，避免内存与存档堆积。 */
    private void forgetAcquireBaseline(String subtaskId) {
        acquireBaselines.remove(subtaskId);
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
            pauseReasons.remove(s.id());
            forgetAcquireBaseline(s.id());   // 重跑 = 上一轮的基线作废，否则 acquire 会永远判未达成
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
    /**
     * REPLANNING 入口（协商用）：士兵在**干活途中**（当前二级仍 RUNNING）认为计划不对、
     * 主动上报 COUNTER/REJECT 时走这里。
     *
     * <p><b>为什么要有这个方法</b>（2026-09-29 定位到的真断点）：
     * {@link #enterReplanningFromStuck(String)} 要求当前二级是 FAILED/STALLED，
     * 但士兵唯一合理的上报时机恰恰是 <b>RUNNING</b>（还在干、觉得方向不对）。
     * 于是协商请求走到 {@code RddPlugin.requestReplan} 时被
     * {@code enterReplanningFromStuck} 抛异常挡掉，{@code return false}，
     * 而 {@code soldierHint} 是在那之后才拼装的 —— 结果
     * <b>士兵的建议永远送不到规划器</b>，实机表现为「说了好几次指挥官仍照原计划」。
     * 更糟的是回执已被 {@code RddDetector.tickNegotiation} 先行消费，失败后无法恢复。
     *
     * <p><b>语义选择</b>：这里<b>不</b>把当前二级强转 FAILED（那会伪造一个"失败"事实、
     * 污染达成判定与埋点），而是直接进入 REPLANNING。
     * 当前二级状态在 {@link #replaceCurrentSubtasks(List)} / {@link #resumeFromReplanning()}
     * 时按既有逻辑处理，行为与「从卡死进入」保持一致。
     *
     * @param reason 协商原因（原样进埋点与规划上下文）
     * @throws IllegalStateException 链不处于可重规划状态时（与既有入口一致的失败语义）
     */
    public synchronized void enterReplanningFromNegotiation(String reason) {
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE primary may enter replanning from negotiation: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("unexpanded primary cannot enter replanning from negotiation: " + currentPrimary().id());
        }
        if (currentSubtask() == null) {
            throw new IllegalStateException("no current subtask to replan from negotiation");
        }
        primaryStatus = PrimaryGoalStatus.REPLANNING;
        clearExecutionMetadata();
    }

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
            pauseReasons.remove(freedId);
            attempts.remove(freedId);
            forgetAcquireBaseline(freedId);
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

    /**
     * 暂停当前二级：<b>不开始、留着、以后还能开</b>。
     *
     * <p>与 {@link #skipSubtask} 的关键差别是<b>不推进</b>——skip 立刻 {@code advanceOrAwait()} 走到下一个，
     * 而暂停原地不动：既不消耗重试预算，也不进 FAILED 的能力缺口/失败升级循环。
     * 规划器改主意时用 {@link #resumeFromPaused} 原地开回来。
     *
     * <p>之所以值得单列一个状态而不是复用 STALLED/FAILED：
     * STALLED 会被监督判成卡死并最终 markFailed（记忆库 {@code issue/waiting-gate-silent-stall-no-supervision}：
     * "没进展"与"故意不做"混在一起就会催工、逼它去干本来就不该干的事）；
     * FAILED 会走重试与能力缺口。两者都会破坏"先放着"这个意图。
     * <p>允许从 <b>STALLED</b> 暂停（2026-09-29 实机修正）：士兵察觉"我做不到"的那一刻，
     * 二级通常<b>已经是 STALLED</b>（做不到 → 资产无变化 → 15 秒 grace → 判卡死）。
     * 最初只放行 RUNNING/PENDING，结果实机第一跑就落到
     * {@code subtask_pause_rejected: cannot be paused from STALLED} → 白白退回重规划。
     * 「卡住 + 承认做不到」= 正该被按住，而不是被判失败后继续烧重试预算。
     */
    public synchronized void pauseSubtask(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) throw new IllegalStateException("primary not active");
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.PENDING && st != SubtaskStatus.STALLED) {
            throw new IllegalStateException("current subtask cannot be paused from " + st);
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("pause reason required");
        statuses.put(subtaskId, SubtaskStatus.PAUSED);
        pauseReasons.put(subtaskId, reason);
        clearExecutionMetadata(); // 暂停 = 不再有在途执行，旧执行身份/启动时间作废
    }

    /** 规划器改主意 → 把暂停的当前二级原地开回来（PAUSED → RUNNING，可直接续跑）。 */
    public synchronized void resumeFromPaused(String subtaskId) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.PAUSED) throw new IllegalStateException("current subtask is not paused");
        statuses.put(subtaskId, SubtaskStatus.RUNNING);
        pauseReasons.remove(subtaskId);
    }

    /** 只读：某二级是否处于暂停（监测台/规划器快照用，不改状态）。 */
    public synchronized boolean isPaused(String subtaskId) {
        return statuses.get(subtaskId) == SubtaskStatus.PAUSED;
    }

    public synchronized Map<String, String> pauseReasonsView() {
        return Map.copyOf(pauseReasons);
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
                if (pauseReasons.containsKey(subtask.id())) item.put("pauseReason", pauseReasons.get(subtask.id()));
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
        o.add("pauseReasons", GSON.toJsonTree(pauseReasons));
        o.add("attempts", GSON.toJsonTree(attempts));
        o.add("acquireBaselines", GSON.toJsonTree(acquireBaselines));
        JsonArray sat = new JsonArray();
        for (String k : satisfiedStages) {
            sat.add(k);
        }
        o.add("satisfiedStages", sat);
        o.addProperty("primaryIndex", primaryIndex);
        o.addProperty("subtaskIndex", subtaskIndex);
        o.addProperty("planRevision", planRevision);
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
        // 旧链（无 planRevision 字段）= 0。**但不能真的当 0**：
        // 上一版存档的二级 id 已经是 `primary-r1-0` 这种带代次的形状，
        // 重启后第一次重规划又从 rev=1 开始 → 直接撞号，旧回执又能混进来。
        // 所以从磁盘上**实际存在的二级 id 反推一个下限**（见 adoptRevisionFromIds）。
        chain.planRevision = o.has("planRevision") ? o.get("planRevision").getAsInt() : 0;
        chain.adoptRevisionFromIds();
        chain.primaryStatus = PrimaryGoalStatus.valueOf(o.get("primaryStatus").getAsString());
        if (o.has("skipReasons") && o.get("skipReasons").isJsonObject()) {
            // 全量载入（不做加载期过滤）：skipReason 指向非 SKIPPED/未知二级属于损坏数据，
            // 交由 validateRestoredState 大声拒绝，而不是悄悄丢进 /dev/null。
            for (var entry : o.getAsJsonObject("skipReasons").entrySet()) {
                chain.skipReasons.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        // 旧存档（PAUSED 引入前写出的 JSON）没有这个键 —— 缺失即空，不算损坏。
        if (o.has("pauseReasons") && o.get("pauseReasons").isJsonObject()) {
            for (var entry : o.getAsJsonObject("pauseReasons").entrySet()) {
                chain.pauseReasons.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        if (o.has("attempts") && o.get("attempts").isJsonObject()) {
            JsonObject att = o.getAsJsonObject("attempts");            for (String k : att.keySet()) {
                chain.attempts.put(k, att.get(k).getAsInt());
            }
        }
        // acquire 基线（2026-09-30）：旧存档没有这个键 = 从没捕获过基线 → 空。
        // 语义后果是"acquire 判未达成"，**不是**损坏：重启后由宿主重新捕获，
        // 而不是拿一个错的基线判出假的完成。
        if (o.has("acquireBaselines") && o.get("acquireBaselines").isJsonObject()) {
            for (var entry : o.getAsJsonObject("acquireBaselines").entrySet()) {
                JsonObject counts = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
                if (counts == null) continue;
                Map<String, Integer> baseline = new LinkedHashMap<>();
                for (var c : counts.entrySet()) {
                    try {
                        baseline.put(c.getKey(), c.getValue().getAsInt());
                    } catch (RuntimeException malformed) {
                        throw new IllegalArgumentException("malformed acquireBaseline for " + entry.getKey(), malformed);
                    }
                }
                chain.acquireBaselines.put(entry.getKey(), Map.copyOf(baseline));
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
        for (String id : pauseReasons.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored pauseReason references unknown subtask: " + id);
            }
            if (statuses.get(id) != SubtaskStatus.PAUSED) {
                throw new IllegalArgumentException("restored pauseReason on non-paused subtask: " + id);
            }
        }
        // 基线表只做"引用完整性"校验：不许指向不存在的二级（那会让基线永远查不到、acquire 卡死）。
        // 注意**不**校验"该二级必须正在跑"——重试/暂停期间基线继续存在是合法的。
        for (String id : acquireBaselines.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored acquireBaseline references unknown subtask: " + id);
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
