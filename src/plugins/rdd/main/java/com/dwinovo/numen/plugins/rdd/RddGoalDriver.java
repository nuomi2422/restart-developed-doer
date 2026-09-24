package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 宿主目标驱动器——懒展开"展开权"的所有者（2026-09-06 架构红线）。
 *
 * <p>Detector 只观察世界、上报"当前一级到达且未展开"；本驱动器持有展开：调 Stage-B
 * {@link RddDecomposer#decomposeSpecs} 把该级主题懒展开成二级，经
 * {@link RddRuntime#expandCurrentPrimary} 注入链。Detector 绝不调 LLM / 不改规划结构。
 *
 * <p>只做编排、不 God Object：LLM 传输与解析在 RddDecomposer / RddStagePlanner，链改写全走
 * RddRuntime/TaskChain，失败恢复阈值在 {@link RddExpansionPolicy}（纯函数）。
 *
 * <p>失败走 §9：尝试 ≤3（每次带上下文修正）→ COOLDOWN → 预算耗尽 escalate=停靠上报
 * （绝不塞占位假二级 / 绝不伪造完成）。escalate 后停靠，等主人换方向（/goal 重来或清除）。
 */
final class RddGoalDriver {
    private static final Logger LOG = LoggerFactory.getLogger(RddGoalDriver.class);
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /** per-同伴懒展开状态；状态迁移都 synchronized(this)（finish 在 MC 线程、needExpansion 在服务端线程）。 */
    private static final class State {
        String primaryId = "";
        int attempts;
        long cooldownUntil;
        boolean inFlight;
        boolean escalated;
        Object request;
    }

    private RddGoalDriver() {}

    /**
     * Detector 上报入口：当前一级到达且未展开。幂等——在途 / 冷却 / 已 escalate 内直接忽略，
     * 避免服务端每 tick 猛开 LLM。调用方绝不该借此改任何规划结构。
     */
    static void needExpansion(UUID companionId) {
        if (companionId == null || RddPlugin.decomposing(companionId)) {
            return;
        }
        try {
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return;
            }
            TaskChain chain = rt.chain();
            if (!chain.currentPrimary().unexpanded()) {
                return; // 已展开 / 非懒边界
            }
            PrimaryGoal cur = chain.currentPrimary();
            State st = STATES.computeIfAbsent(companionId, k -> new State());
            int attempt;
            boolean fire;
            boolean escalatedNow;
            Object request;
            synchronized (st) {
                String pid = cur.id();
                if (!pid.equals(st.primaryId)) { // 换到新一级 → 重置预算/冷却/在途
                    st.primaryId = pid;
                    st.attempts = 0;
                    st.cooldownUntil = 0;
                    st.inFlight = false;
                    st.escalated = false;
                }
                long now = System.currentTimeMillis();
                fire = RddExpansionPolicy.shouldFire(st.inFlight, st.escalated, st.attempts,
                        st.cooldownUntil, now);
                if (fire) {
                    attempt = st.attempts;
                    st.inFlight = true;
                    st.request = new Object();
                } else {
                    attempt = 0;
                }
                request = st.request;
                // 预算耗尽且还没 escalate → 本次 tick 升 escalate（停靠，只报一次）
                escalatedNow = !st.escalated && RddExpansionPolicy.budgetExhausted(st.attempts);
                if (escalatedNow) {
                    st.escalated = true;
                }
            }
            if (escalatedNow) {
                RddMonitor.publish("expansion_exhausted", Map.of(
                        "primary", cur.id(), "theme", cur.description(),
                        "attempts", RddExpansionPolicy.MAX_EXPAND_ATTEMPTS,
                        "reason", "subtask generation failed after retries; parked until /goal reset"));
                RddPlugin.publishTaskSnapshot(companionId, "expansion_exhausted");
                LOG.warn("[rdd] 一级 {} 懒展开预算耗尽，停靠等主人换方向: {}", cur.id(), cur.description());
                return;
            }
            if (!fire) {
                return; // 冷却中/在途/escalate——静默等待
            }
            RddMonitor.publish("expansion_started", Map.of(
                    "primary", cur.id(), "theme", cur.description(), "attempt", attempt + 1));
            // 线性推进：当前一级之前的所有一级都已达成 → 注入给 Stage-B，避免它倒退重规划已完成的资产。
            List<String> completed = completedStagesBefore(chain);
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                st.inFlight = false;
                return;
            }
            var ticket = RddPlugin.planningTicket(companionId);
            RddDecomposer.decomposeSpecs(companionId, cur.description(), attempt, completed,
                    specs -> RddPlugin.onServer(server, ticket,
                            () -> finish(companionId, rt, st, cur.id(), request, specs)));
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 展开驱动异常: {}", ex.toString());
        }
    }

    /** 线性推进：当前一级之前的所有一级都已完成 → 取其描述作 Stage-B 上下文（防倒退重规划）。 */
    private static List<String> completedStagesBefore(TaskChain chain) {
        try {
            List<PrimaryGoal> primaries = chain.goal().primaryGoals();
            PrimaryGoal cur = chain.currentPrimary();
            List<String> out = new ArrayList<>();
            for (PrimaryGoal p : primaries) {
                if (p == cur) {
                    break; // 从前往后扫到当前级为止：之前的都已达成
                }
                out.add(p.description());
            }
            return out;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** Stage-B 结果回收（MC 线程）：注入或计失败；任何"不可用"都算失败，绝不注入占位假二级。 */
    private static void finish(UUID companionId, RddRuntime rt, State st, String pid,
                               Object request, List<SubtaskSpec> specs) {
        // Validate the captured owner before touching a replacement's inFlight or retry budget.
        if (RddPlugin.runtime(companionId) != rt || STATES.get(companionId) != st
                || !rt.chain().currentPrimary().id().equals(pid)) {
            return;
        }
        TaskChain chain = rt.chain();
        boolean ok = false;
        boolean escalatedNow;
        String failure = null;
        synchronized (st) {
            if (!st.inFlight || st.request != request) return;
            st.inFlight = false;
            if (st.escalated) {
                return; // 已被另一路 escalate（本路结果作废）
            }
            if (specs == null || specs.isEmpty()) {
                failure = "no usable subtasks (empty or legal-but-unexecutable after strict parse)";
            } else {
                try {
                    List<Subtask> subs = new ArrayList<>(specs.size());
                    for (int i = 0; i < specs.size(); i++) {
                        SubtaskSpec sp = specs.get(i);
                        // id 以当前一级 id 为前缀 → 跨级/跨重试全局唯一，不与已展开一级冲突
                        subs.add(Subtask.hardCoded(pid + "-s" + i, sp.description(), sp.condition(), sp.body()));
                    }
                    rt.expandCurrentPrimary(subs); // core 校验：非空/重复/已展开/状态合法
                    ok = true;
                } catch (RuntimeException ex) {
                    failure = "expansion rejected by core: " + ex.getMessage();
                }
            }
            if (ok) {
                st.attempts = 0;
                st.cooldownUntil = 0;
                st.escalated = false;
            } else {
                st.attempts++;
                if (RddExpansionPolicy.budgetExhausted(st.attempts)) {
                    st.escalated = true;
                } else {
                    st.cooldownUntil = System.currentTimeMillis() + RddExpansionPolicy.EXPAND_COOLDOWN_MS;
                }
            }
            escalatedNow = !ok && st.escalated;
        }
        if (ok) {
            RddMonitor.publish("primary_expanded", Map.of(
                    "primary", pid, "theme", chain.currentPrimary().description(),
                    "subtasks", specs == null ? 0 : specs.size()));
            RddPlugin.publishTaskSnapshot(companionId, "primary_expanded");
            RddPlugin.saveRuntimes();
            LOG.info("[rdd] 一级 {} 懒展开 {} 个二级", pid, specs == null ? 0 : specs.size());
        } else {
            LOG.warn("[rdd] 一级 {} 懒展开失败({}): {}", pid, failure, chain.currentPrimary().description());
            RddMonitor.publish("expansion_failed", Map.of(
                    "primary", pid, "theme", chain.currentPrimary().description(),
                    "reason", String.valueOf(failure)));
            RddPlugin.publishTaskSnapshot(companionId, "expansion_failed");
            if (escalatedNow) {
                RddMonitor.publish("expansion_exhausted", Map.of(
                        "primary", pid, "theme", chain.currentPrimary().description(),
                        "attempts", RddExpansionPolicy.MAX_EXPAND_ATTEMPTS,
                        "reason", "subtask generation failed after retries; parked until /goal reset"));
                RddPlugin.publishTaskSnapshot(companionId, "expansion_exhausted");
                LOG.warn("[rdd] 一级 {} 懒展开预算耗尽，停靠等主人换方向", pid);
            }
        }
    }

    /** 清除 per-同伴展开状态（目标被清/重绑时调用）。 */
    static void clear(UUID companionId) {
        if (companionId != null) {
            STATES.remove(companionId);
        }
    }

    /** Server stopped: no in-flight expansion or cooldown belongs to the next world. */
    static void clearAll() {
        STATES.clear();
    }
}
