package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.fail.FailureKind;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.rdd.replan.ReplanContextBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** NUMEN host adapter for the host-independent RDD task-chain core. */
public final class RddPlugin implements NumenPlugin {
    private static final Logger LOG = LoggerFactory.getLogger(RddPlugin.class);
    private static final Map<UUID, RddRuntime> RUNTIMES = new ConcurrentHashMap<>();
    /** Reusable world assets survive task replacement and are attached to the next task runtime. */
    private static final Map<UUID, AssetRegistry> ASSETS = new ConcurrentHashMap<>();
    private static final Set<UUID> DECOMPOSING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, BodyState> BODY = new ConcurrentHashMap<>();
    private static final AtomicLong BODY_CALLS = new AtomicLong();
    private static final RddCallbackGuard CALLBACKS = new RddCallbackGuard();
    /** 懒边界上报去重：uuid → 最近一次已上报的未展开一级 id（避免每秒刷 expansion_needed）。 */
    private static final Map<UUID, String> LAST_EXPANSION_REPORT = new ConcurrentHashMap<>();
    static {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> clearWorldState());
    }
    /** assist 协助模式下暂停自动工具提交(防双驾驶);默认 true = RDD 可自动提交。 */
    private static volatile boolean bodySubmissionEnabled = true;
    /** 空转止血：是否允许"监督拍醒"主动干预（nudge/自动重试/失败升级/重派身体）。
     *  默认 true。暂停时 Detector 退化为纯观察——真实资产检测推进 + EarlyAchievement 照常，
     *  但绝不 nudge 注入 AI / 自动重试 / 升级判失败（LLM 空转止血）。 */
    private static volatile boolean supervisionEnabled = true;
    /** 开关文件 config/numen/rdd-supervision.flag：内容含 "pause"(或 "0") → 暂停监督。 */
    private static volatile Path supervisionFlag;
    /** 最近一次真实背包快照（Detector 每秒写，uuid→物品ID→数量）。规划注入用；不清除=背包是女仆属性与链无关。 */
    private static final Map<UUID, Map<String, Integer>> LAST_INVENTORY = new ConcurrentHashMap<>();
    /** setup 时保存的插件门面，用于监督拍醒（nudge 注入内置 AI）。 */
    private static volatile NumenApi numenApi;
    /** 任务链持久化目录 config/numen/rdd-tasks（每个同伴一个 <uuid>.json）。 */
    private static volatile Path tasksDir;
    /** Independent world-asset store; clearing a task must not erase a base or known structure. */
    private static volatile Path assetsDir;
    /** P0 完成事实目录 config/numen/rdd-facts（每个同伴一个 <uuid>.json）；跨重绑/跨重启保留。 */
    private static volatile Path factsDir;
    /** 内存完成事实仓库（uuid→store）；磁盘为真身，清世界状态只清内存。 */
    private static final Map<UUID, CompletedFactStore> FACTS = new ConcurrentHashMap<>();
    /** P4 重规划预算：每（同伴|一级）最多自动重规划次数；超限回落停车，防无限烧 LLM。 */
    private static final int MAX_REPLAN_PER_PRIMARY = 3;
    private static final Map<String, Integer> REPLAN_COUNTS = new ConcurrentHashMap<>();
    /** 同伴床边复活偏好：companionId → owner的床位 BlockPos。有值则在复活时 TP 到床旁。 */
    private static final Map<UUID, BlockPos> BED_RESPAWN_PREFERENCE = new ConcurrentHashMap<>();

    record BodyState(String subtaskId, int submitCount) {}

    @Override
    public void setup(NumenApi numen) {
        numenApi = numen;
        tasksDir = numen.configDir().resolve("rdd-tasks");
        assetsDir = numen.configDir().resolve("rdd-assets");
        factsDir = numen.configDir().resolve("rdd-facts");
        supervisionFlag = numen.configDir().resolve("rdd-supervision.flag");
        numen.registerTool(new RddStatusTool());
        numen.registerTool(new RddSubmitTool());
        numen.registerTool(new RddSkipTool());
        numen.registerTool(new RddAssetsTool());
        // 接管 /goal：先同步认领，Stage-A 异步规划；规划期间 NUMEN 原生目标循环让位。
        com.dwinovo.numen.agent.goal.GoalSinks.register((uuid, objective) -> {
            if (uuid == null || objective == null || objective.isBlank()) {
                return false;
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            RddCallbackGuard.Ticket ticket = CALLBACKS.replace(uuid);
            onServer(server, ticket, () -> beginPlanning(server, ticket, objective));
            return true;
        });
        com.dwinovo.numen.agent.goal.GoalSinks.registerClear((uuid, reason) -> {
            if (uuid != null) {
                remove(uuid);
                LOG.info("[rdd] 已接收目标清除请求 {}", uuid);
                return true;
            }
            return false;
        });
        // P1 资产真相层：同伴死亡/掉装备 → 背包类资产立即失效（不再拿旧装备当"还持有"），
        // 规划输入 LAST_INVENTORY 同步清空；世界资产（基地/结构）不受影响，重新观测会恢复 OBSERVED。
        numen.on(CompanionEvent.DEATH, body -> onCompanionDeath(body.getUUID()));
        numen.on(CompanionEvent.REMOVE, body -> LAST_INVENTORY.remove(body.getUUID()));
        // 床边复活偏好：如果该同伴有设定的床位偏好，则在 SPAWN 后将其 TP 到床旁（偏移 0.5 防止卡壁）
        numen.on(CompanionEvent.SPAWN, body -> {
            UUID uuid = body.getUUID();
            if (uuid == null) {
                LOG.warn("[rdd] SPAWN event missing companion UUID");
                return;
            }
            BlockPos bedPos = BED_RESPAWN_PREFERENCE.get(uuid);
            if (bedPos != null) {
                try {
                    // 把同伴移到床的旁边（X/Z 偏移 0.5），Y 保持不变
                    body.moveTo(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5, body.getYRot(), body.getXRot());
                    // 执行一次后清除偏好，避免每次生效都 TP
                    BED_RESPAWN_PREFERENCE.remove(uuid);
                    LOG.info("[rdd] {} 复活后自动 TP 到床旁: {}", uuid, bedPos);
                } catch (Exception e) {
                    LOG.warn("[rdd] 床边复活 TP 失败 {}: {}", uuid, e.toString());
                }
            }
        });
        numen.contributeState(uuid -> {
            String context = renderStateContext(uuid);
            if (!context.equals(LAST_CONTEXT.put(uuid, context))) {
                Map<String, Object> data = observationData(uuid);
                data.put("source", "rdd_state_contributor");
                data.put("target", "numen");
                data.put("context", context);
                RddMonitor.publish("numen_context", data);
            }
            return context;
        });
    }

    /** Model-requested task correction: only explicitly optional food may be skipped. */
    static String skipOptionalCurrent(UUID companionId, String expectedSubtaskId, String reason) {
        RddRuntime runtime = RUNTIMES.get(companionId);
        if (runtime == null || runtime.chain().currentSubtask() == null) return "no active executable RDD subtask";
        Subtask current = runtime.chain().currentSubtask();
        if (!current.id().equals(expectedSubtaskId)) return "refused: stale subtask id; read rdd_status again";
        if (!RddOptionalFood.canSkip(current, lastInventory(companionId)))
            return "refused: only an optional food step (food item or group=food, not marked required) can be skipped";
        if (com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(companionId) != null)
            return "refused: body is busy; wait for the current action to stop";
        runtime.chain().skipSubtask(current.id(), reason == null || reason.isBlank() ? "optional food unavailable" : reason);
        clearBody(companionId);
        finishResolvedPrimary(companionId, runtime);
        publishTaskSnapshot(companionId, "model_requested_optional_skip");
        RddMonitor.publish("subtask_skipped", Map.of("companionId", companionId.toString(),
                "subtask", current.id(), "reason", reason == null ? "optional food unavailable" : reason,
                "source", "numen_model"));
        return "optional food subtask skipped; continue with the next RDD step";
    }

    /** A terminal optional step must not leave the chain parked at AWAITING_SUPERVISOR. */
    static void finishResolvedPrimary(UUID companionId, RddRuntime runtime) {
        var chain = runtime.chain();
        if (chain.primaryStatus() != com.dwinovo.numen.rdd.api.PrimaryGoalStatus.AWAITING_SUPERVISOR) return;
        String primary = chain.currentPrimary().id();
        String primaryDesc = chain.currentPrimary().description();
        runtime.applySupervisor(new com.dwinovo.numen.rdd.api.SupervisorDecision(
                com.dwinovo.numen.rdd.api.SupervisorDecisionType.CONFIRM, primary,
                "required conditions verified; optional omissions remain SKIPPED, not world achievements"));
        recordStageFact(companionId, chain.goal(), primaryDesc);
        RddMonitor.publish("primary_resolved", Map.of("companionId", companionId.toString(), "primary", primary,
                "reason", "verified required steps; optional steps may be skipped"));
    }

    private static final Map<UUID, String> LAST_CONTEXT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Disk handoffs survive; in-flight planning and cached world observations do not. */
    private static void clearWorldState() {
        CALLBACKS.clear(() -> {
            DECOMPOSING.clear();
            RddGoalDriver.clearAll();
            BODY.clear();
            RUNTIMES.clear();
            ASSETS.clear();
            FACTS.clear();
            LAST_CONTEXT.clear();
            LAST_INVENTORY.clear();
            LAST_EXPANSION_REPORT.clear();
        });
    }

    private static void beginPlanning(MinecraftServer server, RddCallbackGuard.Ticket ticket, String objective) {
        UUID uuid = ticket.companionId();
        DECOMPOSING.add(uuid);
        BODY.remove(uuid);
        RddGoalDriver.clear(uuid);
        RddMonitor.publish("supervisor_input", Map.of(
                "companionId", uuid.toString(), "objective", objective,
                "source", "goal_sink", "target", "rdd"));
        RddStagePlanner.planStages(uuid, objective, stages -> onServer(server, ticket, () -> {
            if (!stages.isEmpty()) {
                try {
                    bindCurrent(uuid, RddChainFactory.fromStages(uuid, objective, stages));
                    RddMonitor.publish("goal_staged", Map.of(
                            "companionId", uuid.toString(), "objective", objective, "stages", stages.size()));
                    publishTaskSnapshot(uuid, "goal_staged");
                    // First primary stays unexpanded; Detector reports the boundary to GoalDriver.
                    LOG.info("[rdd] Stage-A 规划 {} 级已绑定 {}:{}", stages.size(), uuid, objective);
                } catch (RuntimeException ex) {
                    LOG.warn("[rdd] Stage-A 装配失败，回落单遍分解: {}", ex.toString());
                    DECOMPOSING.add(uuid);
                    decomposeSinglePass(server, ticket, objective);
                    return;
                }
                DECOMPOSING.remove(uuid);
                return;
            }
            // Fallback belongs to this submission too; a later /goal invalidates both callbacks.
            decomposeSinglePass(server, ticket, objective);
        }));
        LOG.info("[rdd] 接管目标，Stage-A 规划中 {}:{}", uuid, objective);
    }

    /** Exact RDD state block returned to Numen; observation does not own task progress. */
    private static String renderStateContext(UUID uuid) {
            if (DECOMPOSING.contains(uuid)) {
                return withAssets(uuid, "<rdd><enabled>true</enabled><active>false</active><decomposing>true</decomposing></rdd>");
            }
            RddRuntime runtime = RUNTIMES.get(uuid);
            if (runtime == null) {
                return withAssets(uuid, "<rdd><enabled>true</enabled><active>false</active></rdd>");
            }
            TaskChain chain = runtime.chain();
            Subtask current = chain.currentSubtask();
            if (current == null) {
                // 当前一级已到达但未展开(懒加载)：诚实报阶段主题，不伪造可执行节点
                return withAssets(uuid, "<rdd><enabled>true</enabled><active>true</active>"
                        + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                        + "<state>stage_reached_expanding</state>"
                        + "<current_phase>" + escape(chain.currentPrimary().description()) + "</current_phase></rdd>");
            }
            return withAssets(uuid, "<rdd><enabled>true</enabled><active>true</active>"
                    + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                    + "<subtask>" + escape(current.id()) + "</subtask>"
                    + "<current_task>" + escape(current.description()) + "</current_task>"
                    + "<done_when>" + escape(String.valueOf(current.condition())) + "</done_when>"
                    + "<subtask_status>" + chain.currentSubtaskStatus() + "</subtask_status></rdd>");
    }

    private static String withAssets(UUID companionId, String rddContext) {
        String worldAssets = RddAssetContext.render(assets(companionId), 1200);
        return worldAssets.isBlank() ? rddContext : rddContext + "\n" + worldAssets;
    }

    /** Stage-A 退化回落：今天的单遍 decompose -> bind + startCurrent（目标不被吞，行为不劣化）。 */
    private static void decomposeSinglePass(MinecraftServer server, RddCallbackGuard.Ticket ticket, String objective) {
        UUID uuid = ticket.companionId();
        RddDecomposer.decompose(uuid, objective, goal -> onServer(server, ticket, () -> {
            try {
                bindCurrent(uuid, goal);
                RddRuntime runtime = runtime(uuid);
                if (runtime != null) {
                    runtime.startCurrent();
                    publishTaskSnapshot(uuid, "goal_decomposed");
                }
                LOG.info("[rdd] 目标分解完成并启动 {}:{}", uuid, goal.description());
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 分解结果启动失败，保留原生回落: {}", ex.toString());
                removeCurrent(uuid);
            } finally {
                DECOMPOSING.remove(uuid);
            }
        }));
    }

    public static void bind(UUID companionId, Goal goal) {
        if (companionId == null || goal == null) throw new IllegalArgumentException("companion and goal required");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("RDD bind requires server thread");
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        onServer(server, ticket, () -> bindCurrent(companionId, goal));
    }

    /** Apply a planning result without invalidating that result's own goal generation. */
    private static void bindCurrent(UUID companionId, Goal goal) {
        BODY.remove(companionId);
        DECOMPOSING.remove(companionId);
        // 新目标在同一同伴上会复用同样的 primary-<uuid8>-N 命名，必须清掉去重记忆，否则首个懒边界漏报。
        LAST_EXPANSION_REPORT.remove(companionId);
        RddGoalDriver.clear(companionId);
        clearReplanCounts(companionId);   // 新目标 = 新阶段标识，重规划预算清零
        // P0：把该目标血缘下"已可靠完成"的阶段事实注入新链 → 已达成一级直接跳过、不再重复规划
        RUNTIMES.put(companionId, new RddRuntime(
                new TaskChain(goal, facts(companionId).satisfiedStageKeys(goal)), assets(companionId)));
        saveRuntimes();
        publishTaskSnapshot(companionId, "task_bound");
    }

    /**
     * P4：对一个“卡死”的同伴显式发起重规划——进入 REPLANNING，用真实状态（失败事实+资产+完成事实+风险缺口）
     * 重分解当前一级；成功则 {@code replaceCurrentSubtasks} 换新计划，失败/空则 {@code resumeFromReplanning} 回落重跑现有。
     * 这是 REPLANNING 在生产里的**活触发器**（此前只有测试构造 REPLAN）。
     */
    static boolean requestReplan(UUID companionId, String reason) {
        RddRuntime rt = RUNTIMES.get(companionId);
        if (rt == null) return false;
        TaskChain chain = rt.chain();
        // 预算：同一(同伴|一级)最多自动重规划 MAX_REPLAN_PER_PRIMARY 次；超限不再重规划（回落停车）
        String primaryId = chain.currentPrimary().id();
        String key = companionId + "|" + primaryId;
        int used = REPLAN_COUNTS.getOrDefault(key, 0);
        if (used >= MAX_REPLAN_PER_PRIMARY) {
            RddMonitor.publish("replan_exhausted", Map.of(
                    "companionId", companionId.toString(), "primary", primaryId,
                    "attempts", used, "reason", "replan budget exhausted; park instead"));
            LOG.warn("[rdd] 重规划预算耗尽，回落停车 {}:{}", companionId, primaryId);
            return false;
        }
        REPLAN_COUNTS.merge(key, 1, Integer::sum);
        try {
            chain.enterReplanningFromStuck(reason);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 无法进入重规划 {}: {}", companionId, ex.toString());
            return false;
        }
        String theme = chain.currentPrimary().description();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            chain.resumeFromReplanning();
            return true;
        }
        var snap = planningSnapshot(companionId);
        var level = RiskGate.levelForText(theme);
        var gaps = ResourceBudget.missingFor(level, snap.availableCounts());
        Goal goal = chain.goal();
        var ctx = ReplanContextBuilder.build(goal.description(), primaryId,
                chain.currentSubtask() == null ? null : chain.currentSubtask().id(),
                FailureEvent.of(null, primaryId, FailureKind.UNKNOWN, reason == null ? "" : reason),
                new java.util.ArrayList<>(chain.satisfiedStages()),
                snap.availableCounts(), level, gaps, java.util.List.of());
        String hint = ReplanContextBuilder.render(ctx);
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        RddDecomposer.decomposeSpecsWithHint(companionId, theme, 1, java.util.List.of(), hint,
                specs -> onServer(server, ticket, () -> {
                    try {
                        if (specs == null || specs.isEmpty()) {
                            chain.resumeFromReplanning();
                            publishTaskSnapshot(companionId, "replan_fallback_resume");
                            return;
                        }
                        java.util.List<Subtask> subs = new java.util.ArrayList<>();
                        for (int i = 0; i < specs.size(); i++) {
                            SubtaskSpec sp = specs.get(i);
                            subs.add(Subtask.hardCoded(primaryId + "-r" + i, sp.description(), sp.condition(), sp.body()));
                        }
                        chain.replaceCurrentSubtasks(subs);
                        saveRuntimes();
                        publishTaskSnapshot(companionId, "replanned");
                        RddMonitor.publish("replanned", Map.of("companionId", companionId.toString(),
                                "primary", primaryId, "theme", theme, "subtasks", subs.size()));
                    } catch (RuntimeException ex) {
                        LOG.warn("[rdd] 重规划替换失败，回落重跑现有 {}: {}", companionId, ex.toString());
                        try { chain.resumeFromReplanning(); } catch (RuntimeException ignore) { }
                    }
                }));
        return true;
    }

    public static RddRuntime runtime(UUID companionId) {
        return RUNTIMES.get(companionId);
    }

    public static AssetRegistry assets(UUID companionId) {
        if (companionId == null) return new AssetRegistry();
        return ASSETS.computeIfAbsent(companionId, id -> RddAssetStore.load(assetsDir, id));
    }

    /**
     * P1：同伴死亡（含掉装备）→ 立刻让该同伴的背包类资产失效，避免规划/依赖门继续按旧装备放行。
     * 只失效 {@code inventory_scan}，不碰 world_ 基地/结构；下一次背包扫描会把还在身上的重新观测回 OBSERVED。
     */
    private static void onCompanionDeath(UUID companionId) {
        if (companionId == null) return;
        try {
            int lost = assets(companionId).invalidateByType("inventory_scan");
            LAST_INVENTORY.remove(companionId);   // 规划输入立即不再按旧背包
            saveAssets(companionId);              // 失效态落盘，跨重启也保持
            BODY.remove(companionId);
            RddMonitor.publish("companion_assets_invalidated", Map.of(
                    "companionId", companionId.toString(),
                    "reason", "companion_death",
                    "invalidatedInventoryEntries", lost));
            LOG.info("[rdd] 同伴死亡：背包资产失效 {} 项 {}", companionId, lost);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 死亡资产失效处理失败 {}: {}", companionId, ex.toString());
        }
    }

    /** P0 完成事实仓库（磁盘为真身，内存缓存）。 */
    public static CompletedFactStore facts(UUID companionId) {
        if (companionId == null) return new CompletedFactStore();
        return FACTS.computeIfAbsent(companionId, id -> RddFactStore.load(factsDir, id));
    }

    /** 记录"某战略阶段已被可靠完成"并落盘（P0）。失败只记日志，不影响主流程。 */
    static void recordStageFact(UUID companionId, Goal goal, String primaryDescription) {
        if (companionId == null || goal == null || primaryDescription == null) return;
        try {
            CompletedFactStore store = facts(companionId);
            store.recordStage(goal, primaryDescription, System.currentTimeMillis(), "primary confirmed");
            RddFactStore.save(factsDir, companionId, store);
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存完成事实失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 留档一条二级完成细节（辅助，不用于恢复）；随阶段落盘一起持久化。 */
    static void recordSubtaskFact(UUID companionId, Goal goal, String primaryDescription, String subtaskDescription) {
        if (companionId == null || goal == null) return;
        facts(companionId).recordSubtask(goal, primaryDescription, subtaskDescription, System.currentTimeMillis());
    }

    static String planningAssets(UUID companionId) {
        return RddAssetContext.render(assets(companionId), 2000);
    }

    /**
     * P1.5 唯一规划资产口径：缓存背包为底 + 注册表真相覆盖（死亡失效的背包条目移除）。
     * Planner 提示词与 PlanGuard 都从这里取数，不再各读各的缓存/注册表。
     */
    public static com.dwinovo.numen.rdd.core.PlanningAssetSnapshot planningSnapshot(UUID companionId) {
        return com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.from(lastInventory(companionId), assets(companionId));
    }

    /** 清某同伴的重规划预算计数（重绑/清任务时调用）。 */
    private static void clearReplanCounts(UUID companionId) {
        if (companionId == null) return;
        String prefix = companionId + "|";
        REPLAN_COUNTS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 设置某同伴的床边复活偏好（owner 当前的床位）。 */
    public static void setBedRespawnPreference(UUID companionId, BlockPos bedPos) {
        if (companionId == null || bedPos == null) {
            return;
        }
        BED_RESPAWN_PREFERENCE.put(companionId, bedPos);
        LOG.info("[rdd] 设置 {} 的床边复活偏好: {}", companionId, bedPos);
    }

    /** 清除某同伴的床边复活偏好。 */
    public static void clearBedRespawnPreference(UUID companionId) {
        if (companionId == null) return;
        BED_RESPAWN_PREFERENCE.remove(companionId);
        LOG.info("[rdd] 清除 {} 的床边复活偏好", companionId);
    }

    /** 记录某同伴最近一次背包计数（Detector 心跳写）。null/空安全。 */
    public static void cacheInventory(UUID companionId, Map<String, Integer> counts) {
        if (companionId != null) {
            LAST_INVENTORY.put(companionId, counts == null ? Map.of() : Map.copyOf(counts));
        }
    }

    /** 最近一次背包快照（可能为空 = 从未观测到该同伴背包）。不可变。 */
    public static Map<String, Integer> lastInventory(UUID companionId) {
        if (companionId == null) {
            return Map.of();
        }
        return LAST_INVENTORY.getOrDefault(companionId, Map.of());
    }

    public static boolean decomposing(UUID companionId) {
        return companionId != null && DECOMPOSING.contains(companionId);
    }

    public static BodyState bodyState(UUID companionId) {
        return companionId == null ? null : BODY.get(companionId);
    }

    public static void rememberBody(UUID companionId, String subtaskId, int submitCount) {
        if (companionId != null && subtaskId != null) {
            BODY.put(companionId, new BodyState(subtaskId, submitCount));
        }
    }

    public static void clearBody(UUID companionId) {
        if (companionId != null) BODY.remove(companionId);
    }

    public static String nextBodyCallId() {
        return "rdd-" + BODY_CALLS.incrementAndGet();
    }

    public static void remove(UUID companionId) {
        if (companionId == null) return;
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) onServer(server, ticket, () -> removeCurrent(companionId));
    }

    private static void removeCurrent(UUID companionId) {
        if (companionId == null) return;
        try {
            RddTaskHandoff.remove(tasksDir, companionId, () -> {
                DECOMPOSING.remove(companionId);
                RddRuntime previous = RUNTIMES.get(companionId);
                if (previous != null) {
                    publishTaskSnapshot(companionId, "task_removed");
                }
                RUNTIMES.remove(companionId);
                BODY.remove(companionId);
                LAST_CONTEXT.remove(companionId);
                LAST_EXPANSION_REPORT.remove(companionId);
                RddGoalDriver.clear(companionId); // 目标清/重绑 → 丢掉该同伴的懒展开状态
                clearReplanCounts(companionId);
                LOG.info("[rdd] 已清除任务及磁盘交接 {}", companionId);
            });
        } catch (IOException ex) {
            // Do not report a successful removal while restoreRuntimes can still reload the file.
            LOG.error("[rdd] 清除磁盘任务失败，内存任务保留，需处理文件后重试清除 {}: {}", companionId, ex.toString());
            RddMonitor.publish("task_clear_failed", Map.of(
                    "companionId", companionId.toString(), "reason", ex.toString(),
                    "memoryRetained", true));
        }
    }

    static RddCallbackGuard.Ticket planningTicket(UUID companionId) {
        return CALLBACKS.current(companionId);
    }

    /** Revalidate on the original server: an old world's completion never enters a new world. */
    static void onServer(MinecraftServer server, RddCallbackGuard.Ticket ticket, Runnable callback) {
        CALLBACKS.dispatch(ticket, action -> {
            if (server.isSameThread()) action.run();
            else server.execute(action);
        }, () -> {
            if (ServerLifecycleHooks.getCurrentServer() == server) callback.run();
        });
    }

    /** RDD 是否允许自动提交身体工具。assist 协助模式下 false(工具执行交还 NUMEN)。 */
    public static boolean bodySubmissionEnabled() {
        return bodySubmissionEnabled;
    }

    /** 设置 RDD 自动工具提交开关。assist=true 时调用 setBodySubmissionEnabled(false) 防双驾驶。 */
    public static void setBodySubmissionEnabled(boolean on) {
        bodySubmissionEnabled = on;
    }

    /** 监督拍醒是否放行。false = 空转止血：Detector 只观察/推进，不 nudge AI。 */
    public static boolean supervisionEnabled() {
        return supervisionEnabled;
    }

    /** 每次检测心跳(~1s)刷新监督开关：外部(监测台/人)写 config/numen/rdd-supervision.flag=pause 即暂停。 */
    public static void refreshSupervisionFlag() {
        if (supervisionFlag == null) {
            return;
        }
        boolean paused;
        try {
            String content = Files.exists(supervisionFlag)
                    ? Files.readString(supervisionFlag, StandardCharsets.UTF_8).trim() : "";
            // 文件内容 "pause"/"0"/任意非空非 run → 暂停；删文件或写 "run" → 恢复
            paused = !content.isEmpty() && !content.equalsIgnoreCase("run");
        } catch (IOException ex) {
            paused = !supervisionEnabled; // flag 读取失败保持当前状态
        }
        if (paused != !supervisionEnabled) {
            supervisionEnabled = !paused;
            LOG.info("[rdd] 监督{}: flag={}", supervisionEnabled ? "恢复" : "暂停", supervisionFlag);
            RddMonitor.publish("supervision_state", Map.of("supervisionEnabled", supervisionEnabled));
        }
    }

    /** 保存所有活跃任务链到 config/numen/rdd-tasks（原子写 tmp+move）。 */
    public static void saveRuntimes() {
        if (tasksDir == null || RUNTIMES.isEmpty()) return;
        try {
            Files.createDirectories(tasksDir);
            for (Map.Entry<UUID, RddRuntime> e : RUNTIMES.entrySet()) {
                try {
                    Path tmp = tasksDir.resolve(e.getKey() + ".json.tmp");
                    Files.writeString(tmp, e.getValue().chain().toJson(), StandardCharsets.UTF_8);
                    Files.move(tmp, tasksDir.resolve(e.getKey() + ".json"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    LOG.warn("[rdd] 保存任务失败 {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (IOException ex) {
            LOG.warn("[rdd] 创建任务目录失败: {}", ex.toString());
        }
    }

    /** 游戏重启恢复：磁盘有任务但内存没有 → 加载为 RddRuntime（幂等）。 */
    public static void restoreRuntimes() {
        if (tasksDir == null || !Files.isDirectory(tasksDir)) return;
        try (var stream = Files.list(tasksDir)) {
            stream.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                try {
                    UUID uuid = UUID.fromString(f.getFileName().toString().replace(".json", ""));
                    if (RUNTIMES.containsKey(uuid)) return;
                    String json = Files.readString(f, StandardCharsets.UTF_8);
                    TaskChain chain = TaskChain.fromJson(json);
                    RUNTIMES.put(uuid, new RddRuntime(chain, assets(uuid)));
                    // 懒链可能停靠在未展开一级：currentSubtask()=null，报阶段而非 NPE
                    Subtask restored = chain.currentSubtask();
                    String curLabel = restored != null
                            ? restored.id() : ("unexpanded:" + chain.currentPrimary().id());
                    LOG.info("[rdd] 恢复任务链 {}（当前二级 {}）", uuid, curLabel);
                } catch (Exception ex) {
                    LOG.warn("[rdd] 恢复任务失败 {}: {}", f.getFileName(), ex.toString());
                }
            });
        } catch (IOException ex) {
            LOG.warn("[rdd] 扫描任务目录失败: {}", ex.toString());
        }
    }

    static void saveAssets(UUID companionId) {
        if (companionId == null) return;
        try {
            RddAssetStore.save(assetsDir, companionId, assets(companionId));
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存世界资产失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * 卡死监督的"拍醒"：把一句话注入内置 AI（效果和主人亲手打字一样）。
     * RDD 不抢方向盘，只在将军发愣时提醒它——缺工具会让它自己调 selfcompile_request。
     */
    public static void nudge(UUID companionId, String message) {
        if (!supervisionEnabled) {
            // 空转止血：监督暂停时绝不注入内置 AI（兜底闸；调用方也各自判了暂停）
            LOG.info("[rdd] 监督暂停,跳过拍醒: {}", message == null ? "" : message);
            return;
        }
        try {
            if (numenApi != null && companionId != null && message != null && !message.isBlank()) {
                numenApi.enqueue(companionId, message);
                Map<String, Object> data = observationData(companionId);
                data.put("outputId", UUID.randomUUID().toString());
                data.put("companionId", companionId.toString());
                data.put("message", message);
                data.put("source", "supervisor");
                data.put("target", "numen");
                RddRuntime runtime = RUNTIMES.get(companionId);
                if (runtime != null) data.put("taskChain", runtime.snapshot());
                RddMonitor.publish("supervisor_output", data);
                LOG.info("[rdd] nudge {}: {}", companionId, message);
            }
        } catch (RuntimeException e) {
            LOG.warn("[rdd] nudge failed: {}", e.toString());
        }
    }

    /**
     * Emits an observational task-chain snapshot. This never mutates task state.
     *
     * <p>刻意<b>不</b>内嵌全量资产表：单条快照曾因此达 69 KB，其中 88% 是每 5 秒重复的同一批
     * 资产条目（每件还带完整 Observation 对象），实测把 rdd.jsonl 推到 288 MB。资产本体由
     * 30 秒一次的 {@link #publishAssetSnapshot}（{@code RddAssetContext.worldAssets} 渲染形状）
     * 提供，监测台优先读它；这里只留一个计数供页面显示规模。
     */
    public static void publishTaskSnapshot(UUID companionId, String reason) {
        if (companionId == null) return;
        RddRuntime runtime = RUNTIMES.get(companionId);
        if (runtime == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("reason", reason == null ? "state_observed" : reason);
        data.put("taskChain", runtime.snapshot());
        data.put("assetCount", runtime.assets().snapshot().size());
        data.put("taskId", runtime.chain().goal().id());
        data.put("subtaskId", runtime.snapshot().get("currentSubtaskId"));
        RddMonitor.publish("taskchain_snapshot", data);
    }

    /**
     * 懒边界上报（已去重）：当前一级到达但未展开时，Detector 每秒都会走到这个分支，
     * 若不去重就会每秒写一条 expansion_needed + 一份任务链快照。
     * 同一个一级只上报一次；换到新一级才再报。
     */
    static void reportExpansionNeeded(UUID companionId, String primaryId, String theme) {
        if (companionId == null || primaryId == null) {
            return;
        }
        if (primaryId.equals(LAST_EXPANSION_REPORT.put(companionId, primaryId))) {
            return; // 同一一级已上报过
        }
        RddMonitor.publish("expansion_needed", Map.of(
                "primary", primaryId,
                "theme", theme == null ? "" : theme));
        publishTaskSnapshot(companionId, "primary_reached_unexpanded");
    }

    static void publishAssetSnapshot(UUID companionId, String reason, RddWorldAssetObserver.Result observed) {
        if (companionId == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("reason", reason == null ? "lazy_world_observation" : reason);
        data.put("assets", RddAssetContext.worldAssets(assets(companionId)));
        data.put("observed", Map.of("bases", observed.bases(), "structures", observed.structures(),
                "machines", observed.machines(), "entityGroups", observed.entityGroups(), "total", observed.total()));
        data.put("dataFlow", Map.of(
                "source", "loaded_world_and_respawn_base",
                "registry", "rdd-assets/<companion>.json",
                "consumers", java.util.List.of("numen_context", "supervisor_context", "monitoring_station"),
                "refresh", "lazy_30s_no_chunk_force_load"));
        RddMonitor.publish("asset_snapshot", data);
    }

    /** Correlation only; no secondary task state and no reconstructed prompt. */
    static Map<String, Object> observationData(UUID companionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        RddRuntime rt = runtime(companionId);
        if (rt != null) {
            Map<String, Object> snapshot = rt.snapshot();
            data.put("taskId", snapshot.get("goalId"));
            data.put("primaryId", snapshot.get("currentPrimaryId"));
            data.put("subtaskId", snapshot.get("currentSubtaskId"));
        }
        return data;
    }

    static void publishPlanningContext(UUID companionId, String stage, String user, String system,
                                       com.dwinovo.numen.agent.provider.IToolSpec tool) {
        Map<String, Object> data = observationData(companionId);
        data.put("inputId", UUID.randomUUID().toString());
        data.put("source", "rdd_" + stage);
        data.put("target", "supervisor_planner");
        data.put("context", Map.of("system", system, "user", user,
                "tool", tool.name(), "parameters", tool.parameterSchema()));
        RddMonitor.publish("supervisor_context", data);
    }

    /** XML 转义：描述/条件可能含玩家可输入的 < > & ". */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
