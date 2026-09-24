package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fail.FailureClassifier;
import com.dwinovo.numen.rdd.fail.FailureContext;
import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.fail.FailureKind;
import com.dwinovo.numen.rdd.fail.RecoveryAction;
import com.dwinovo.numen.rdd.fail.RecoveryDecision;
import com.dwinovo.numen.rdd.fail.RecoveryPlan;
import com.dwinovo.numen.rdd.fail.RecoveryPolicy;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RDD 的硬编码检测驱动器 + 身体执行桥：服务端每 tick 跑一次（节流到 1 秒）。
 *
 * <p>对每个活跃任务链：
 * <ol>
 *   <li>当前二级 PENDING → 自动启动（首个由 sink 启动，后续由这里推进）</li>
 *   <li>二级带 body 且未提交 → 经 {@link ToolRegistry#resolve} 找身体工具直接
 *       {@code onServerCall} 提交（每二级只提交一次）</li>
 *   <li>读<b>真实背包</b>，HARD_CODED 满足 → 推进</li>
 *   <li>身体任务已结束（槽空）但条件未满足 → 重试 ≤{@link #MAX_BODY_RETRIES}，否则 markFailed</li>
 * </ol>
 *
 * <p>只读世界真身，不读 AI 自报。二级全完成 → 简化 Supervisor CONFIRM → 一级完成。
 */
final class RddDetector {
    private static final Logger LOG = LoggerFactory.getLogger(RddDetector.class);
    /** 每多少 tick 检测一次；20 tick = 1 秒。 */
    private static final int TICKS_PER_CHECK = 20;
    /** 身体任务结束但条件未达成时的最大重试次数。 */
    private static final int MAX_BODY_RETRIES = 3;
    /** 卡死监督：资产指纹（背包+位置）连续多少次检测无变化即判 STALLED（1 次/秒）。
     *  取 15 而非 5：AI 的 LLM 轮次（DeepSeek 思考 + 工具链）可能要 10~15 秒，
     *  太短会把"正在思考/刚起步"误判成卡死。 */
    private static final int STALL_AFTER_TICKS = RddStallPolicy.IDLE_GRACE_CHECKS;
    /** 拍醒后的响应窗口：STALLED 后给 AI 这么长时间行动（资产变化则恢复），
     *  仍未动才 re-nudge / 升级。避免"拍完不到 2 秒就判失败"。 */
    private static final int STALL_RESPONSE_TICKS = 25;
    /** 拍醒上限：超过则判失败（Level 1 恢复兜底）。 */
    private static final int MAX_NUDGES = 2;
    /** Level 2 重试：失败的当前二级最多自动重跑次数（每次让 AI 换策略再试）。 */
    private static final int MAX_SUBTASK_RETRIES = 2;
    /** Level 3：当前二级累计卡死多少次即判定能力不足（AI 反复拍醒仍无法达成目标资产）。 */
    private static final int CAPABILITY_GAP_AFTER_STALLS = 3;
    /** 资产提前触发：单个 tick 内最多连跳过多少个"资产已满足"的二级（防极端长链死循环）。 */
    private static final int MAX_INSTANT_PASS = 32;
    /**
     * 停车守望：停车（{@code gapParked}）本意是"别再自动重试烧 token"，但 tickRuntime 在 FAILED
     * 分支就 return，trackStall 只在 RUNNING 跑——结果停车 = 永远没人拍醒，AI 结束当前回合后
     * 无人喂目标即永久静默，且外部看不见（实测冻结 15 分钟零活动）。这里补一层低频率守望。
     * 连续这么多次检测（1 次/秒）资产指纹无变化才再拍一次。
     */
    private static final int PARKED_WATCH_CHECKS = 300;
    /** 同一个停车二级最多再拍醒几次；之后只发可见事件，不再烧模型调用。 */
    private static final int MAX_PARKED_NUDGES = 3;
    private static final Gson GSON = new Gson();

    /** 卡死监督状态：记录每个同伴当前二级的资产指纹与未变化计数。 */
    private final Map<UUID, StallState> stalls = new ConcurrentHashMap<>();

    private record StallState(String subtaskId, String fingerprint, int unchangedTicks, int nudges) {}

    /** Keep the last real furnace observation when its GUI closes during cooking. */
    private final Map<UUID, FurnaceWatch> furnaces = new ConcurrentHashMap<>();
    private record FurnaceWatch(TaskChain chain, String subtaskId, AbstractFurnaceMenu menu, BlockEntity block) {}

    /** Level 2 重试计数：绑定当前二级（二级变了才重置），避免被误清。 */
    private final Map<UUID, RetryState> retries = new ConcurrentHashMap<>();
    private record RetryState(String subtaskId, int count) {}
    /** Level 3 卡死累计：当前二级累计卡死次数（AI 反复拍醒仍无目标资产进展 → 能力不足）。 */
    private final Map<UUID, StallCount> stallCounts = new ConcurrentHashMap<>();
    private record StallCount(String subtaskId, int total) {}
    /** 已达重试上限被"停车"为 FAILED 的二级（uuid→subtaskId）。停车后不再自动重试/nudge，只留资产检测。 */
    private final Map<UUID, String> gapParked = new ConcurrentHashMap<>();
    /** 停车守望状态（uuid→指纹/未变化计数/已拍醒次数）：停车也不能静默死掉。 */
    private final Map<UUID, ParkedWatch> parkedWatches = new ConcurrentHashMap<>();
    private record ParkedWatch(String subtaskId, String fingerprint, int unchanged, int nudges) {}
    private final RddSurplus surplus = new RddSurplus();

    private int tickCounter;
    /** 资产 populate 节流：每 5 次检测（约 5 秒）把背包物品写进 AssetRegistry。 */
    private int assetTick;
    /** 世界资产只观察已加载范围；每 30 秒一次，避免把“资产库”变成全图扫描器。 */
    private int worldAssetTick;

    RddDetector() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    furnaces.clear();
                    stalls.clear();
                    retries.clear();
                    stallCounts.clear();
                    gapParked.clear();
                    parkedWatches.clear();
                    surplus.clear();
                    tickCounter = 0;
                    assetTick = 0;
                    worldAssetTick = 0;
                });
    }

    void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++tickCounter < TICKS_PER_CHECK) {
            return;
        }
        tickCounter = 0;
        assetTick = (assetTick + 1) % 5;
        worldAssetTick = (worldAssetTick + 1) % 30;
        // 空转止血：每次心跳刷新监督开关（flag 文件由监测台/人写，pause=停拍醒）
        RddPlugin.refreshSupervisionFlag();
        // 重启恢复：磁盘有任务但内存无 → 加载为 RddRuntime（幂等，RECOVERING）
        RddPlugin.restoreRuntimes();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof NumenPlayer ap)) {
                continue;
            }
            RddRuntime rt = RddPlugin.runtime(ap.getUUID());
            if (rt == null) {
                continue;
            }
            tickRuntime(ap, rt);
        }
        // 持久化：保存活跃任务链（1 秒一次，文件小，原子写）
        RddPlugin.saveRuntimes();
    }

    private void tickRuntime(NumenPlayer ap, RddRuntime rt) {
        try {
            TaskChain chain = rt.chain();
            PrimaryGoalStatus ps = chain.primaryStatus();
            // 真实背包先扫+缓存（规划注入数据源）；缓存是女仆属性，收尾/监督态也照常更新。
            Map<String, Integer> counts = countInventory(ap);
            surplus.inspect(ap, chain, counts);
            RddPlugin.cacheInventory(ap.getUUID(), counts);
            // Observe even parked/failed/unexpanded chains. A control-state early
            // return must not make the monitoring station appear frozen.
            if (assetTick == 0) populateAssets(ap, rt, chain.currentSubtask(), counts);
            if (worldAssetTick == 0) {
                RddWorldAssetObserver.Result observed = RddWorldAssetObserver.observe(ap, rt.assets());
                RddPlugin.saveAssets(ap.getUUID());
                RddPlugin.publishAssetSnapshot(ap.getUUID(), "lazy_world_observation", observed);
            }
            // 监督/收尾态不由检测驱动。
            if (ps == PrimaryGoalStatus.AWAITING_SUPERVISOR
                    || ps == PrimaryGoalStatus.REPLANNING
                    || ps == PrimaryGoalStatus.COMPLETED
                    || ps == PrimaryGoalStatus.FAILED) {
                return;
            }
            // P0-4 重启恢复：在途链恢复为 RECOVERING 后不许静默瞒报续跑——先发恢复事件，
            // 再按真实资产检测照常推进（资产已满足会走 early-achievement 直接验收；
            // 未满足就恢复监督/卡死/重试，与 FAILED 的"真实资产推进照常"哲学一致）。
            if (ps == PrimaryGoalStatus.RECOVERING) {
                RddPlugin.publishTaskSnapshot(ap.getUUID(), "chain_recovering");
                RddMonitor.publish("chain_recovering", Map.of(
                        "companionId", ap.getUUID().toString(),
                        "primary", chain.currentPrimary().id(),
                        "reason", "in-flight chain restored after restart; resuming under live observation"));
                rt.resumeFromRecovering();
            }
            // 刚推进到新的当前一级(PENDING/WAITING)：先过依赖门(wait_for)，没过就保持 WAITING 不派给 AI。
            if (ps == PrimaryGoalStatus.PENDING || ps == PrimaryGoalStatus.WAITING) {
                // 观察行(懒边界)：当前一级到达但未展开 -> 上报目标驱动器(展开权持有者)，Detector 绝不自己展开/调 LLM。
                if (chain.currentPrimary().unexpanded()) {
                    RddGoalDriver.needExpansion(ap.getUUID());
                    RddPlugin.reportExpansionNeeded(ap.getUUID(),
                            chain.currentPrimary().id(), chain.currentPrimary().description());
                    return;
                }
                if (!rt.activateCurrentFromRegistry()) {
                    RddMonitor.publish("primary_waiting", Map.of(
                            "primary", chain.currentPrimary().id(),
                            "reason", "dependency assets not present"));
                    RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_dependency_waiting");
                    return;
                }
                RddMonitor.publish("dependency_met", Map.of("primary", chain.currentPrimary().id()));
            }
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            // 自动启动当前二级（首个由 sink 启动，推进后由这里继续；AI_ASSISTED 也在内）。
            if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                rt.startCurrent();
            }
            SubtaskStatus status = chain.currentSubtaskStatus();
            // STALLED → 监督恢复分支：行为恢复则回到 RUNNING，多次拍醒无效则升级失败。
            if (status == SubtaskStatus.STALLED) {
                handleStalled(ap, rt, chain.currentSubtask());
                return;
            }
            // FAILED → 先重跑真实资产检测：条件此刻已满足(如 AI 失败后自己攒够) → 直接验收推进
            //（含暂停期，对齐增2"只停主动干预、真实资产检测+推进照常"；FAILED 只表"那次尝试失败"非"目标未达成"）。
            // applyHardCodedResult 要求 RUNNING → 先 FAILED→PENDING(retry)→RUNNING(start) 再走正常完成路径。
            // 仍未满足 → Level 2 局部恢复：预算内自动重跑该二级（AI 换策略再试）。
            if (status == SubtaskStatus.FAILED) {
                Subtask cur = chain.currentSubtask();
                if (cur.detectionMode() == DetectionMode.HARD_CODED
                        && conditionMatches(ap, cur, counts)) {
                    RddMonitor.publish("early_achievement", Map.of(
                            "subtask", cur.id(), "description", cur.description(),
                            "reason", "FAILED but assets now satisfied, completed on real inventory"));
                    rt.chain().retrySubtask(cur.id()); // FAILED→PENDING（仅 FAILED 可 retry）
                    rt.startCurrent();                 // PENDING→RUNNING
                    completeSubtask(ap, rt, cur);      // applyHardCoded(RUNNING)→COMPLETED+推进
                    return;
                }
                handleSubtaskFailure(ap, rt, cur);
                return;
            }
            if (status != SubtaskStatus.RUNNING) {
                return;
            }
            // ==== 资产提前触发(EarlyAchievement)：资产已满足的硬编码二级不拍醒/不派身体，直接过，
            // 同 tick 连跳一串已满足的二级；到一级边界自动过依赖门进入下一级。 ====
            int guard = 0;
            while (chain.primaryStatus() == PrimaryGoalStatus.ACTIVE && guard++ < MAX_INSTANT_PASS) {
                if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                    if (chain.currentSubtask().detectionMode() != DetectionMode.HARD_CODED) {
                        break;
                    }
                    rt.startCurrent();
                }
                if (chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                    break;
                }
                Subtask cur = chain.currentSubtask();
                if (cur.detectionMode() != DetectionMode.HARD_CODED) {
                    break;
                }
                if (!conditionMatches(ap, cur, counts)) {
                    break;
                }
                RddMonitor.publish("early_achievement", Map.of(
                        "subtask", cur.id(), "description", cur.description(),
                        "reason", "assets already present, skipped AI execution"));
                completeSubtask(ap, rt, cur);
                if (chain.currentSubtask() == cur && chain.primaryStatus() == PrimaryGoalStatus.ACTIVE) return;
                if (chain.primaryStatus() == PrimaryGoalStatus.PENDING) {
                    // 一级全完成被 CONFIRM → 进入下一级：下一级可能未展开(懒边界)→ 只上报驱动器，绝不 activate(会抛)
                    if (chain.currentPrimary().unexpanded()) {
                        RddGoalDriver.needExpansion(ap.getUUID());
                        RddPlugin.reportExpansionNeeded(ap.getUUID(),
                                chain.currentPrimary().id(), chain.currentPrimary().description());
                        break;
                    }
                    if (rt.activateCurrentFromRegistry()) {
                        RddMonitor.publish("dependency_met", Map.of("primary", chain.currentPrimary().id()));
                    } else {
                        RddMonitor.publish("primary_waiting", Map.of(
                                "primary", chain.currentPrimary().id(),
                                "reason", "dependency assets not present"));
                        RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_dependency_waiting");
                        break;
                    }
                }
                counts = countInventory(ap);
            }
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            Subtask current = chain.currentSubtask();
            // AI_ASSISTED 二级交还 AI 驱动，不做卡死监督（保持原语义）。
            if (current.detectionMode() != DetectionMode.HARD_CODED) {
                return;
            }
            if (chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                return;
            }
            // 资产 populate：把背包物品写进 AssetRegistry（节流），rdd_status 据此报真实资产。
            // 卡死监督：资产指纹（背包+位置）连续未变化 → 判 STALLED 并拍醒将军。
            // 空转止血：暂停监督 → 跳过卡死检测与身体驱动(不 nudge / 不自动重派)，只留资产检测推进。
            if (RddPlugin.supervisionEnabled() && trackStall(ap, rt, current)) {
                return;
            }
            // assist 协助模式下暂停自动工具提交(防双驾驶):工具执行交还 NUMEN 内置 AI,
            // RDD 只保留资产检测 / 目标完成判定 / 异常提醒。
            if (RddPlugin.supervisionEnabled() && RddPlugin.bodySubmissionEnabled()) {
                maybeSubmitBody(ap, current);
            }
            if (conditionMatches(ap, current, counts)) {
                completeSubtask(ap, rt, current);
                return;
            }
            maybeRetryOrFail(ap, rt, current);
        } catch (RuntimeException e) {
            // 检测失败不能拖垮服务端 tick。
            LOG.warn("[rdd] 检测 tick 异常: {}", e.toString());
        } finally {
            if (assetTick == 0) RddPlugin.publishTaskSnapshot(ap.getUUID(), "periodic_observation");
        }
    }

    /**
     * 卡死检测：资产指纹（背包计数+位置）连续 {@link #STALL_AFTER_TICKS} 次无变化
     * → markStalled + 拍醒（nudge）。返回 true 表示本次判定卡死，上层停止推进。
     */
    private boolean trackStall(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (!RddPlugin.supervisionEnabled()) {
            return false; // 空转止血：暂停监督不做卡死检测/拍醒/能力升级，资产推进照常
        }
        RddStallPolicy.Observation observation = observeWork(ap, rt, current);
        String fp = observation.fingerprint();
        StallState st = stalls.get(ap.getUUID());
        if (st == null || !st.subtaskId().equals(current.id())) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return false;
        }
        RddStallPolicy.Check check = RddStallPolicy.check(st.fingerprint(), st.unchangedTicks(), observation);
        if (check.changed()) {
            // Assets, container output, cooking, or body work counters changed.
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return false;
        }
        int unchanged = check.unchanged();
        if (observation.waiting() && unchanged >= STALL_AFTER_TICKS
                && unchanged % STALL_AFTER_TICKS == 0 && !check.stalled()) {
            RddMonitor.publish("subtask_work_wait", Map.of(
                    "companionId", ap.getUUID().toString(), "subtask", current.id(),
                    "source", observation.source(), "unchangedChecks", unchanged,
                    "graceRemainingSeconds", check.remaining(), "graceLimitSeconds", check.limit()));
        }
        if (check.stalled()) {
            // Repeated stalls are an observation, not proof of missing software capability.
            StallCount sc = stallCounts.get(ap.getUUID());
            int total = (sc != null && sc.subtaskId().equals(current.id())) ? sc.total() + 1 : 1;
            stallCounts.put(ap.getUUID(), new StallCount(current.id(), total));
            if (total >= CAPABILITY_GAP_AFTER_STALLS) {
                RddPlugin.nudge(ap.getUUID(), "这个目标反复没有进展。先核对真实工具结果、附近资源、路径、装备和模型连接；不要仅凭重复失败推断缺软件工具，只有确认能力缺口后再考虑自编译。");
                RddMonitor.publish("subtask_stall_escalated", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(), "failureClass", "UNKNOWN",
                        "reason", "repeated stalls (" + total + "); cause requires evidence"));
                stallCounts.remove(ap.getUUID());
            }
            rt.chain().markStalled(current.id(), "observed work unchanged for " + check.limit() + " checks");
            RddPlugin.nudge(ap.getUUID(), "你的目标「" + current.description() + "」还在，但可见资产、容器生产和身体进度在观察窗口内没有变化。请核对真实工具结果、材料和生产条件，再决定下一步。");
            RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(),
                    "reason", "observed work unchanged", "source", observation.source(),
                    "graceRemainingSeconds", 0, "graceLimitSeconds", check.limit()));
            // 重置响应窗计数：从 STALLED 起给 AI STALL_RESPONSE_TICKS 秒响应时间
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, st.nudges() + 1));
            return true;
        }
        stalls.put(ap.getUUID(), new StallState(current.id(), fp, unchanged, st.nudges()));
        return false;
    }

    /**
     * STALLED 监督恢复：行为（资产/位置）恢复 → 回 RUNNING；仍在拍醒期 → 换措辞再拍；
     * 多次拍醒无效 → 判失败（Level 1 恢复兜底）。
     */
    private void handleStalled(NumenPlayer ap, RddRuntime rt, Subtask current) {
        String fp = observeWork(ap, rt, current).fingerprint();
        StallState st = stalls.get(ap.getUUID());
        if (!RddPlugin.supervisionEnabled()) {
            // 空转止血：暂停监督。AI 自己动了 → 回 RUNNING；否则保持卡住标记，绝不拍醒/绝不判失败。
            if (st != null && st.subtaskId().equals(current.id()) && st.fingerprint().equals(fp)) {
                return; // 仍冻结：挂起不动，不打扰 AI
            }
            rt.chain().resumeFromStalled(current.id());
            RddMonitor.publish("subtask_resumed", Map.of("subtask", current.id(), "reason", "progress while supervision paused"));
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return;
        }
        if (st == null) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 1));
            RddPlugin.nudge(ap.getUUID(), "你卡住了吗？缺什么工具或材料？");
            return;
        }
        if (!st.fingerprint().equals(fp)) {
            // Real production can recover a stalled task without moving items into inventory yet.
            rt.chain().resumeFromStalled(current.id());
            RddMonitor.publish("subtask_resumed", Map.of("subtask", current.id()));
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return;
        }
        // 资产仍无变化：先给 AI 一个响应窗口，窗口内不打扰（AI 可能正在思考/规划）
        if (st.unchangedTicks() + 1 < STALL_RESPONSE_TICKS) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, st.unchangedTicks() + 1, st.nudges()));
            return;
        }
        if (st.nudges() >= MAX_NUDGES) {
            // 多次拍醒无效 → Level 1 恢复：判失败（不伪造完成）
            rt.chain().markFailed(current.id(), "stalled after " + MAX_NUDGES + " nudges without progress");
            RddMonitor.publish("subtask_failed", Map.of("subtask", current.id(), "reason", "stalled after nudges"));
            stalls.remove(ap.getUUID());
            LOG.warn("[rdd] 二级目标卡死升级失败: {}", current.id());
            return;
        }
        // 响应窗口已过、资产仍无变化 → 换措辞再拍一次
        RddPlugin.nudge(ap.getUUID(), "你还没动。告诉我你卡在哪一步？如果缺工具，现在就调 selfcompile_request。");
        RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(), "reason", "still stalled, re-nudge"));
        stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, st.nudges() + 1));
    }

    /** FAILED 二级的 Level 2 局部恢复：预算内重置重跑 + 拍醒提示换策略；预算耗尽 → Level 3。 */
    private void handleSubtaskFailure(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (!RddPlugin.supervisionEnabled()) {
            return; // 空转止血：暂停监督不自动重跑/不引导自编译，保持 FAILED 等主人
        }
        RetryState rs = retries.get(ap.getUUID());
        int n = (rs != null && rs.subtaskId().equals(current.id())) ? rs.count() : 0;
        if (n >= MAX_SUBTASK_RETRIES) {
            if (RddOptionalFood.canSkip(current, countInventory(ap))
                    && CompanionTickDispatcher.currentTaskFor(ap.getUUID()) == null) {
                String reason = "optional food unavailable or unreachable; continue mainline";
                rt.skipSubtask(current.id(), reason);
                RddPlugin.clearBody(ap.getUUID());
                retries.remove(ap.getUUID());
                gapParked.remove(ap.getUUID());
                parkedWatches.remove(ap.getUUID());
                stalls.remove(ap.getUUID());
                stallCounts.remove(ap.getUUID());
                furnaces.remove(ap.getUUID());
                RddPlugin.finishResolvedPrimary(ap.getUUID(), rt);
                RddMonitor.publish("subtask_skipped", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(), "reason", reason));
                RddPlugin.publishTaskSnapshot(ap.getUUID(), "optional_food_skipped");
                return;
            }
            // Exhausted retries do not identify the cause. Report once, then keep this subtask
            // "停车"为 FAILED：不 retrySubtask、也不清 retries（cap 清零会进 FAILED→重试→RUNNING→
            // body 重派→失败 的无限循环，每次 nudge 都会触发额外模型调用）。停车后只留真实资产检测——
            // AI 或主人真攒够资产，由 FAILED 分支的 early_achievement 自动验收推进，不堵恢复路径。
            if (!current.id().equals(gapParked.get(ap.getUUID()))) {
                gapParked.put(ap.getUUID(), current.id());
                parkedWatches.remove(ap.getUUID());
                // P2.0 接线：把“原因未分类的耗尽失败”交给硬分类器，按出口给可审计事件 + 定向提醒。
                // 仍只停车、不自动改链状态（REPLAN 交上层/主人决定），避免无限循环。
                Map<String, Integer> inv = countInventory(ap);
                FailureEvent fe = FailureEvent.of(current.id(), rt.chain().currentPrimary().id(),
                        FailureKind.UNKNOWN, "auto-retries exhausted; root cause not established");
                FailureContext fc = new FailureContext(hasBackupEquipment(inv), hasBase(rt), true, false);
                RecoveryDecision rd = FailureClassifier.classify(fe, fc);
                // P3：把出口落成“恢复动作 + 步骤骨架”（具体方案仍交 AI/Planner）
                List<String> gaps = ResourceBudget.missingFor(
                        RiskGate.levelForText(current.description()), inv);
                RecoveryPlan plan = RecoveryPolicy.plan(rd, gaps);
                RddPlugin.nudge(ap.getUUID(), nudgeForPlan(plan, current.description()));
                RddMonitor.publish("subtask_parked", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(),
                        "failureClass", fe.kind().name(), "recovery", rd.outcome().name(),
                        "action", plan.action().name(), "auto", rd.auto(), "reason", rd.reason()));
                if (plan.action() == RecoveryAction.REQUEST_REPLAN
                        && RddPlugin.requestReplan(ap.getUUID(), fe.reason())) {
                    // P4：转入重规划流程（进入 REPLANNING + 带真实状态重分解），不再 parking 守望
                    return;
                }
                // 预算耗尽等导致未能重规划 → 回落停车守望（下面继续）
            }
            watchParked(ap, rt, current);
            return;
        }
        retries.put(ap.getUUID(), new RetryState(current.id(), n + 1));
        rt.chain().retrySubtask(current.id());
        rt.startCurrent();
        RddPlugin.nudge(ap.getUUID(), "这个目标（" + current.description() + "）失败了，再试一次。换个策略：检查材料、换工具、或换位置。");
        RddMonitor.publish("subtask_retry", Map.of("subtask", current.id(), "retry", n + 1, "max", MAX_SUBTASK_RETRIES));
    }


    /** 是否有“备用装备”：至少两件铁/钻石胸甲（一件在穿 + 一件备用）——保守近似。 */
    private static boolean hasBackupEquipment(Map<String, Integer> inv) {
        int chest = inv.getOrDefault("minecraft:iron_chestplate", 0)
                + inv.getOrDefault("minecraft:diamond_chestplate", 0);
        return chest >= 2;
    }

    /** 是否已有基地（world_base 观测）。 */
    private static boolean hasBase(RddRuntime rt) {
        try {
            return rt.assets().snapshot().stream()
                    .anyMatch(e -> "world_base".equals(e.observation().type()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 按恢复计划给定向提醒（含动作与有序步骤；不改链状态，REPLAN 交主人/上层）。 */
    private static String nudgeForPlan(RecoveryPlan plan, String desc) {
        StringBuilder sb = new StringBuilder("「" + desc + "」失败诊断：" + plan.action().name() + "。");
        if (!plan.steps().isEmpty()) {
            sb.append("按顺序：").append(String.join(" → ", plan.steps())).append("。");
        }
        if (plan.action() == RecoveryAction.REQUEST_REPLAN) {
            sb.append("（原地重试无效，报告卡点请主人重下 /goal）");
        }
        return sb.toString();
    }

    /**
     * 停车守望：停车不等于可以静默死掉。低频看资产指纹，长期无变化就再拍一次；
     * 拍醒预算耗尽后只发可见事件（{@code subtask_parked_silent}），把"需要人或外部介入"
     * 明确暴露出来，而不是让整条链无声冻结。
     */
    private void watchParked(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        String fp = observeWork(ap, rt, current).fingerprint();
        ParkedWatch w = parkedWatches.get(uuid);
        if (w == null || !w.subtaskId().equals(current.id())) {
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, 0));
            return;
        }
        if (!w.fingerprint().equals(fp)) {
            // 仍有变化（AI 自己在推进 / 资产真被攒够）→ 重置窗口，不打扰
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, w.nudges()));
            return;
        }
        int unchanged = w.unchanged() + 1;
        if (unchanged < PARKED_WATCH_CHECKS) {
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, unchanged, w.nudges()));
            return;
        }
        int nudged = w.nudges() + 1;
        parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, nudged));
        if (w.nudges() >= MAX_PARKED_NUDGES) {
            if (w.nudges() == MAX_PARKED_NUDGES) {
                RddMonitor.publish("subtask_parked_silent", Map.of(
                        "companionId", uuid.toString(), "subtask", current.id(),
                        "reason", "parked and unchanged after " + MAX_PARKED_NUDGES
                                + " nudges; needs external intervention"));
            }
            return; // 预算耗尽：只报一次，不再烧模型调用
        }
        RddPlugin.nudge(uuid, "你的目标「" + current.description() + "」还在，但停车后一直没被判出进展。"
                + "先确认真实卡点：查看工具终态、附近资源、路径和装备；"
                + "方向不对就换一条路（换地点、换材料来源、换工具），不要原样重复。");
        RddMonitor.publish("subtask_parked_renudge", Map.of(
                "companionId", uuid.toString(), "subtask", current.id(),
                "nudge", nudged, "max", MAX_PARKED_NUDGES, "reason", "parked with no progress"));
    }

    /** 把背包物品写进 AssetRegistry（GLOBAL 作用域，来源=当前二级）。观测证据：inventory_scan。 */
    private void populateAssets(NumenPlayer ap, RddRuntime rt, Subtask current, Map<String, Integer> counts) {
        try {
            String envId = ap.level().dimension().location().toString();
            Map<String, Integer> observed = new HashMap<>(counts);
            // A consumed item is an observed zero, not a permanently held asset.
            for (var entry : rt.assets().snapshot()) {
                if ("inventory_scan".equals(entry.observation().type())) observed.putIfAbsent(entry.assetId(), 0);
            }
            for (Map.Entry<String, Integer> e : observed.entrySet()) {
                Map<String, Object> value = new HashMap<>();
                value.put("count", e.getValue());
                Observation obs = new Observation(
                        "obs-" + e.getKey().hashCode() + "-" + System.nanoTime(),
                        "inventory_scan", "rdd_detector", envId,
                        System.currentTimeMillis(), value);
                rt.assets().apply(obs, e.getKey(), AssetScope.GLOBAL, current == null ? null : current.id());
            }
        } catch (RuntimeException ex) {
            // 资产 populate 失败不影响检测主流程
            LOG.warn("[rdd] 资产 populate 异常: {}", ex.toString());
        }
    }

    /** 资产指纹：背包物品计数 + 方块位置。卡死检测据此判断行为是否在变。 */
    private String fingerprint(NumenPlayer ap) {
        String inv = countInventory(ap).toString();
        var pos = ap.blockPosition();
        return inv + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Server-thread observation only. Fuel/elapsed/deadline/task-id changes are not work progress. */
    private RddStallPolicy.Observation observeWork(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        FurnaceWatch watch = furnaces.get(uuid);
        if (watch != null && (watch.chain() != rt.chain() || !watch.subtaskId().equals(current.id())
                || watch.block().isRemoved() || watch.block().getLevel() != ap.level())) {
            furnaces.remove(uuid);
            watch = null;
        }
        AbstractContainerMenu menu = ap.containerMenu;
        if (menu instanceof AbstractFurnaceMenu furnace && !menu.slots.isEmpty()
                && menu.slots.getFirst().container instanceof BlockEntity block) {
            watch = new FurnaceWatch(rt.chain(), current.id(), furnace, block);
            furnaces.put(uuid, watch);
        }
        StringBuilder progress = new StringBuilder();
        if (menu != null && menu != ap.inventoryMenu) appendContainer(progress, menu, ap);
        boolean production = false;
        if (watch != null) {
            if (watch.menu() != menu) appendContainer(progress, watch.menu(), ap);
            // Vanilla getBurnProgress is cooking progress; getLitProgress is only fuel countdown.
            progress.append("|cooking=").append(watch.menu().getBurnProgress());
            production = watch.menu().isLit() && !watch.menu().getSlot(0).getItem().isEmpty();
        }
        var body = CompanionTickDispatcher.currentTaskFor(uuid);
        boolean active = body != null && !body.getState().isTerminal();
        if (active) progress.append("|body=").append(body.getToolName()).append(':').append(body.describe());
        String source = production ? "furnace_production" : active ? "body_task:" + body.publicId() : "idle";
        return new RddStallPolicy.Observation(fingerprint(ap), progress.toString(), production || active, source);
    }

    private static void appendContainer(StringBuilder progress, AbstractContainerMenu menu, NumenPlayer ap) {
        progress.append("|container=").append(menu.getClass().getName());
        for (var slot : menu.slots) {
            if (slot.container == ap.getInventory()) continue;
            // Burning fuel does not prove that a recipe is producing anything.
            if (menu instanceof AbstractFurnaceMenu && slot.index == AbstractFurnaceMenu.FUEL_SLOT) continue;
            ItemStack item = slot.getItem();
            progress.append('|').append(slot.index).append(':')
                    .append(BuiltInRegistries.ITEM.getKey(item.getItem())).append('=').append(item.getCount());
        }
    }

    /** 当前二级带 body 且还没提交过 → 提交一次。 */
    private void maybeSubmitBody(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        if (body == null) {
            return;
        }
        var state = RddPlugin.bodyState(ap.getUUID());
        if (state != null && state.subtaskId().equals(current.id())) {
            return;
        }
        submitBody(ap, current);
        RddPlugin.rememberBody(ap.getUUID(), current.id(), 1);
    }

    /** 直接调身体工具的服务端实现（同 tick 线程，安全）；工具内部走 TaskDispatch.setTask。
     *
     *  <p>入口分三路：
     *  <ol>
     *    <li>{@code task_type} 在 RDD 词表/别名内（mine/craft/equip_item/collect_items，含旧臆造名
     *        mine_block/equip）→ {@link RddBodyTools} 翻译成真实工具参数（mine 要 block_ids 数组 + deepslate 变体）；</li>
     *    <li>词表外但是真实注册工具（外部 rdd_submit/监测台显式指名驱动）→ 原样派发（操作者负责参数契约）；</li>
     *    <li>都不是（规划层臆造名）→ 响亮 {@code subtask_capability_gap}，绝不再静默"只检测不执行"空转。</li>
     *  </ol> */
    private void submitBody(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        String raw = body.taskType();
        String canonical = RddBodyTools.canonical(raw);
        if (canonical != null) {
            NumenTool tool = ToolRegistry.resolve(canonical);
            if (tool == null) {
                LOG.error("[rdd] 规范身体工具 {} 未注册（插件与注册表脱节）", canonical);
                RddMonitor.publish("subtask_capability_gap", Map.of(
                        "subtask", current.id(), "reason", "canonical body tool unregistered: " + canonical));
                return;
            }
            Object condMin = current.condition().get("minimum");
            Integer min = condMin instanceof Number num ? num.intValue() : null;
            JsonObject realArgs = RddBodyTools.buildArgs(canonical, body.args(), min);
            if (realArgs == null) {
                LOG.error("[rdd] body {} 的 args 无法翻译成 {} 参数: {}", current.id(), canonical, body.args());
                RddMonitor.publish("subtask_capability_gap", Map.of(
                        "subtask", current.id(), "reason", "untranslatable args for " + canonical));
                return;
            }
            dispatchBody(ap, current, canonical, tool, realArgs, body);
            return;
        }
        NumenTool explicit = ToolRegistry.resolve(raw);
        if (explicit == null) {
            LOG.error("[rdd] body 工具名 {} 不在 RDD 词表 {} 也非真实注册工具 —— 规划层臆造，"
                    + "该二级只做资产检测不身体执行", raw, RddBodyTools.SUPPORTED);
            RddMonitor.publish("subtask_capability_gap", Map.of(
                    "subtask", current.id(), "reason", "unsupported body tool name: " + raw));
            return;
        }
        // 外部显式指名驱动任意真实工具：原样透传参数。
        JsonObject passthrough = new JsonObject();
        if (body.args() != null) {
            body.args().forEach((k, v) -> passthrough.add(k, GSON.toJsonTree(v)));
        }
        dispatchBody(ap, current, raw, explicit, passthrough, body);
    }

    private void dispatchBody(NumenPlayer ap, Subtask current, String toolName, NumenTool tool,
                              JsonObject callArgs, BodyInstruction body) {
        try {
            tool.onServerCall(RddPlugin.nextBodyCallId(), callArgs, ap, reply -> { });
            LOG.info("[rdd] 已提交身体任务 {} -> {} {}", current.id(), toolName, callArgs);
            RddMonitor.publish("body_submitted", Map.of(
                    "subtask", current.id(), "task_type", toolName, "args", body.args()));
        } catch (RuntimeException e) {
            LOG.warn("[rdd] 提交身体任务失败 {}: {}", toolName, e.toString());
            RddMonitor.publish("body_submit_failed", Map.of(
                    "subtask", current.id(), "task_type", toolName, "error", String.valueOf(e)));
        }
    }

    /** 世界真身满足条件 → 推进；二级全完成 → 简化 Supervisor CONFIRM。 */
    private void completeSubtask(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (surplus.hold(ap, rt.chain(), current, countInventory(ap))) return;
        boolean completed = rt.applyHardCoded(current.id(), true);
        RddPlugin.clearBody(ap.getUUID());
        retries.remove(ap.getUUID());
        stallCounts.remove(ap.getUUID());
        gapParked.remove(ap.getUUID());
        parkedWatches.remove(ap.getUUID());
        furnaces.remove(ap.getUUID());
        if (!completed) {
            return;
        }
        LOG.info("[rdd] 二级目标完成: {} ({})", current.id(), current.description());
        RddMonitor.publish("subtask_completed", Map.of(
                "subtask", current.id(), "description", current.description(), "condition", current.condition(),
                "companionId", ap.getUUID().toString(), "evidenceSource",
                com.dwinovo.numen.rdd.core.WorldFactConditions.knownType(current.condition().get("type"))
                        ? "server_world_fact" : "server_inventory"));
        RddPlugin.publishTaskSnapshot(ap.getUUID(), "subtask_completed");
        TaskChain chain = rt.chain();
        // P0 辅助留档：二级完成细节（不用于恢复，供后续 Context/Planner 参考）
        RddPlugin.recordSubtaskFact(ap.getUUID(), chain.goal(), chain.currentPrimary().description(), current.description());
        if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
            PrimaryGoal completedPrimary = chain.currentPrimary();
            rt.applySupervisor(new SupervisorDecision(
                    SupervisorDecisionType.CONFIRM,
                    chain.currentPrimary().id(),
                    "all hard-coded conditions met in the real world"));
            // P0：把"该阶段已完成"记为可靠事实（跨重绑/跨重启继承，防已达成阶段被重跑）
            RddPlugin.recordStageFact(ap.getUUID(), chain.goal(), completedPrimary.description());
            LOG.info("[rdd] 一级目标完成: {}", completedPrimary.description());
            RddMonitor.publish("goal_completed", Map.of(
                    "goal", completedPrimary.id(), "description", completedPrimary.description(),
                    "companionId", ap.getUUID().toString()));
            RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_completed");
            RddPlugin.clearBody(ap.getUUID());
        }
    }

    /** 给当前二级派过身体、身体任务已不在位、条件仍未满足 → 重试或判失败。 */
    private void maybeRetryOrFail(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (current.body() == null) {
            return;
        }
        var state = RddPlugin.bodyState(ap.getUUID());
        if (state == null || !state.subtaskId().equals(current.id())) {
            return; // 没给这个二级派过身体
        }
        // 身体任务还占着当前槽 = 还在跑，不判。
        if (CompanionTickDispatcher.currentTaskFor(ap.getUUID()) != null) {
            return;
        }
        // assist 协助模式下 RDD 不自动提交工具(防双驾驶):重试也跳过,直接判失败提醒。
        // 空转止血：暂停监督 → 不重派也不判失败，清掉身体状态静置等主人。
        if (!RddPlugin.supervisionEnabled()) {
            RddPlugin.clearBody(ap.getUUID());
            return;
        }
        if (state.submitCount() < MAX_BODY_RETRIES && RddPlugin.bodySubmissionEnabled()) {
            RddPlugin.rememberBody(ap.getUUID(), current.id(), state.submitCount() + 1);
            submitBody(ap, current);
            LOG.info("[rdd] 身体任务结束未达成，重试 {} 次: {}", state.submitCount() + 1, current.id());
            RddMonitor.publish("subtask_retry", Map.of(
                    "subtask", current.id(), "retry", state.submitCount() + 1, "max", MAX_BODY_RETRIES));
        } else {
            rt.chain().markFailed(current.id(), "body task ended without satisfying condition");
            RddPlugin.clearBody(ap.getUUID());
            LOG.warn("[rdd] 二级目标失败（身体任务结束未达成）: {}", current.id());
            RddMonitor.publish("subtask_failed", Map.of(
                    "subtask", current.id(), "reason", "body task ended without satisfying condition"));
        }
    }

    static boolean conditionMatches(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {
        if (task.condition().containsKey("type") && !"inventory".equals(task.condition().get("type")))
            return com.dwinovo.numen.rdd.core.WorldFactConditions.valid(task.condition())
                    && RddWorldFacts.matches(ap, task.condition());
        return HardCodedEvaluator.matches(task.condition(), counts);
    }

    /** 统计背包里每种物品的数量，用命名空间 ID（minecraft:oak_log）作 key。 */
    static Map<String, Integer> countInventory(NumenPlayer ap) {
        Map<String, Integer> counts = new HashMap<>();
        var inv = ap.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(key, stack.getCount(), Integer::sum);
        }
        return counts;
    }
}
