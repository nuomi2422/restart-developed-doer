package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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
    private static final int STALL_AFTER_TICKS = 15;
    /** 拍醒后的响应窗口：STALLED 后给 AI 这么长时间行动（资产变化则恢复），
     *  仍未动才 re-nudge / 升级。避免"拍完不到 2 秒就判失败"。 */
    private static final int STALL_RESPONSE_TICKS = 25;
    /** 拍醒上限：超过则判失败（Level 1 恢复兜底）。 */
    private static final int MAX_NUDGES = 2;
    /** Level 2 重试：失败的当前二级最多自动重跑次数（每次让 AI 换策略再试）。 */
    private static final int MAX_SUBTASK_RETRIES = 2;
    private static final Gson GSON = new Gson();

    /** 卡死监督状态：记录每个同伴当前二级的资产指纹与未变化计数。 */
    private final Map<UUID, StallState> stalls = new ConcurrentHashMap<>();

    private record StallState(String subtaskId, String fingerprint, int unchangedTicks, int nudges) {}

    /** Level 2 重试计数：每同伴当前二级失败重跑次数。 */
    private final Map<UUID, Integer> retries = new ConcurrentHashMap<>();

    private int tickCounter;
    /** 资产 populate 节流：每 5 次检测（约 5 秒）把背包物品写进 AssetRegistry。 */
    private int assetTick;

    void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++tickCounter < TICKS_PER_CHECK) {
            return;
        }
        tickCounter = 0;
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
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            // 自动启动当前二级：首个由 sink 启动，推进后由这里继续。
            if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                rt.startCurrent();
            }
            Subtask current = chain.currentSubtask();
            if (current.detectionMode() != DetectionMode.HARD_CODED) {
                return;
            }
            SubtaskStatus status = chain.currentSubtaskStatus();
            // STALLED → 监督恢复分支：行为恢复则回到 RUNNING，多次拍醒无效则升级失败。
            if (status == SubtaskStatus.STALLED) {
                handleStalled(ap, rt, current);
                return;
            }
            // FAILED → Level 2 局部恢复：预算内自动重跑该二级（AI 换策略再试）。
            if (status == SubtaskStatus.FAILED) {
                handleSubtaskFailure(ap, rt, current);
                return;
            }
            if (status != SubtaskStatus.RUNNING) {
                return;
            }
            // 卡死监督：资产指纹（背包+位置）连续未变化 → 判 STALLED 并拍醒将军。
            if (trackStall(ap, rt, current)) {
                return;
            }
            // assist 协助模式下暂停自动工具提交(防双驾驶):工具执行交还 NUMEN 内置 AI,
            // RDD 只保留资产检测 / 目标完成判定 / 异常提醒。
            if (RddPlugin.bodySubmissionEnabled()) {
                maybeSubmitBody(ap, current);
            }
            Map<String, Integer> counts = countInventory(ap);
            // 资产 populate：把背包物品写进 AssetRegistry（节流），rdd_status 据此报真实资产。
            if (++assetTick % 5 == 0) {
                populateAssets(ap, rt, current, counts);
            }
            if (HardCodedEvaluator.matches(current.condition(), counts)) {
                completeSubtask(ap, rt, current);
                return;
            }
            maybeRetryOrFail(ap, rt, current);
        } catch (RuntimeException e) {
            // 检测失败不能拖垮服务端 tick。
            LOG.warn("[rdd] 检测 tick 异常: {}", e.toString());
        }
    }

    /**
     * 卡死检测：资产指纹（背包计数+位置）连续 {@link #STALL_AFTER_TICKS} 次无变化
     * → markStalled + 拍醒（nudge）。返回 true 表示本次判定卡死，上层停止推进。
     */
    private boolean trackStall(NumenPlayer ap, RddRuntime rt, Subtask current) {
        String fp = fingerprint(ap);
        StallState st = stalls.get(ap.getUUID());
        if (st == null || !st.subtaskId().equals(current.id())) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return false;
        }
        if (!st.fingerprint().equals(fp)) {
            // 资产/位置变了 = AI 在动 → 清零未变化计数
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return false;
        }
        int unchanged = st.unchangedTicks() + 1;
        if (unchanged >= STALL_AFTER_TICKS) {
            rt.chain().markStalled(current.id(), "asset fingerprint unchanged for " + STALL_AFTER_TICKS + " checks");
            RddPlugin.nudge(ap.getUUID(), "你的目标「" + current.description() + "」还在，但你的背包和位置已经有一段时间没变化了。你卡住了吗？缺什么工具或材料？缺工具就调 selfcompile_request 请求新工具。");
            RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(), "reason", "asset fingerprint unchanged"));
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
        String fp = fingerprint(ap);
        StallState st = stalls.get(ap.getUUID());
        if (st == null) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 1));
            RddPlugin.nudge(ap.getUUID(), "你卡住了吗？缺什么工具或材料？");
            return;
        }
        if (!st.fingerprint().equals(fp)) {
            // AI 被拍醒后恢复行动 → 回到 RUNNING，任务继续
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

    /** FAILED 二级的 Level 2 局部恢复：预算内重置重跑 + 拍醒提示换策略；预算耗尽保持失败。 */
    private void handleSubtaskFailure(NumenPlayer ap, RddRuntime rt, Subtask current) {
        int n = retries.getOrDefault(ap.getUUID(), 0);
        if (n >= MAX_SUBTASK_RETRIES) {
            retries.remove(ap.getUUID());
            return;
        }
        retries.put(ap.getUUID(), n + 1);
        rt.chain().retrySubtask(current.id());
        rt.startCurrent();
        RddPlugin.nudge(ap.getUUID(), "这个目标（" + current.description() + "）失败了，再试一次。换个策略：检查材料、换工具、或换位置。");
        RddMonitor.publish("subtask_retry", Map.of("subtask", current.id(), "retry", n + 1, "max", MAX_SUBTASK_RETRIES));
    }

    /** 把背包物品写进 AssetRegistry（GLOBAL 作用域，来源=当前二级）。观测证据：inventory_scan。 */
    private void populateAssets(NumenPlayer ap, RddRuntime rt, Subtask current, Map<String, Integer> counts) {
        try {
            String envId = ap.level().dimension().location().toString();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                Map<String, Object> value = new HashMap<>();
                value.put("count", e.getValue());
                Observation obs = new Observation(
                        "obs-" + e.getKey().hashCode() + "-" + System.nanoTime(),
                        "inventory_scan", "rdd_detector", envId,
                        System.currentTimeMillis(), value);
                rt.assets().apply(obs, e.getKey(), AssetScope.GLOBAL, current.id());
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

    /** 直接调身体工具的服务端实现（同 tick 线程，安全）；工具内部走 TaskDispatch.setTask。 */
    private void submitBody(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        NumenTool tool = ToolRegistry.resolve(body.taskType());
        if (tool == null) {
            LOG.warn("[rdd] 身体工具 {} 不存在，该二级只检测不执行", body.taskType());
            return;
        }
        JsonObject args = new JsonObject();
        if (body.args() != null) {
            body.args().forEach((k, v) -> args.add(k, GSON.toJsonTree(v)));
        }
        try {
            tool.onServerCall(RddPlugin.nextBodyCallId(), args, ap, reply -> { });
            LOG.info("[rdd] 已提交身体任务 {} -> {} {}", current.id(), body.taskType(), body.args());
            RddMonitor.publish("body_submitted", Map.of(
                    "subtask", current.id(), "task_type", body.taskType(), "args", body.args()));
        } catch (RuntimeException e) {
            LOG.warn("[rdd] 提交身体任务失败 {}: {}", body.taskType(), e.toString());
            RddMonitor.publish("body_submit_failed", Map.of(
                    "subtask", current.id(), "task_type", body.taskType(), "error", String.valueOf(e)));
        }
    }

    /** 世界真身满足条件 → 推进；二级全完成 → 简化 Supervisor CONFIRM。 */
    private void completeSubtask(NumenPlayer ap, RddRuntime rt, Subtask current) {
        boolean completed = rt.applyHardCoded(current.id(), true);
        RddPlugin.clearBody(ap.getUUID());
        retries.remove(ap.getUUID());
        if (!completed) {
            return;
        }
        LOG.info("[rdd] 二级目标完成: {} ({})", current.id(), current.description());
        RddMonitor.publish("subtask_completed", Map.of(
                "subtask", current.id(), "description", current.description()));
        TaskChain chain = rt.chain();
        if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
            rt.applySupervisor(new SupervisorDecision(
                    SupervisorDecisionType.CONFIRM,
                    chain.currentPrimary().id(),
                    "all hard-coded conditions met in the real world"));
            LOG.info("[rdd] 一级目标完成: {}", chain.currentPrimary().description());
            RddMonitor.publish("goal_completed", Map.of(
                    "goal", chain.currentPrimary().id(), "description", chain.currentPrimary().description()));
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

    /** 统计背包里每种物品的数量，用命名空间 ID（minecraft:oak_log）作 key。 */
    private Map<String, Integer> countInventory(NumenPlayer ap) {
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
