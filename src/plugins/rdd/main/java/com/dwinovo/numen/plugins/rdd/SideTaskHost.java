package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.side.MainlineResumeToken;
import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 睡觉支线的宿主接线（2026-10-08，第一版）。
 *
 * <p>通用框架在 rdd-core（{@link SideTaskEngine} + {@link SideTaskWorldPort} + 睡觉类型/触发器）；
 * 这里只做 MC 侧：造世界端口、喂主线恢复令牌、把支线上下文缓存给执行 AI、发监测台事件、落盘/恢复。
 *
 * <p><b>本轮只接睡觉一条支线</b>；框架本身与具体支线无关，加新支线 = 注册新的 type + trigger。
 */
final class SideTaskHost {

    private static final Logger LOG = LoggerFactory.getLogger(SideTaskHost.class);
    private static final SleepSideTaskType SLEEP_TYPE = new SleepSideTaskType();
    private static final SideTaskEngine ENGINE =
            new SideTaskEngine(SideTaskHost::publish, SideTaskHost::validateMainline);
    /** 支线上下文缓存：主线程 tick 写入，上下文构建只读（可能在非主线程被调）。 */
    private static final Map<UUID, String> SIDE_CONTEXT = new ConcurrentHashMap<>();
    /** 支线拍醒的重复间隔（tick，约 4 分钟）：支线 active 期间至少这么久再补一次提醒。 */
    private static final long NUDGE_REPEAT_TICKS = 4L * 60L * 20L;
    private static final Map<UUID, Long> LAST_NUDGE_GAME_TIME = new ConcurrentHashMap<>();
    private static volatile Path sideDir;

    static {
        ENGINE.registerType(SLEEP_TYPE);
        ENGINE.registerTrigger(new SleepSideTaskTrigger());
        SleepWakeWatcher.install();
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> onServerStopped());
    }

    private SideTaskHost() {
    }

    static void setup(Path configDir) {
        sideDir = configDir == null ? null : configDir.resolve("rdd-side");
        restore();
    }

    static boolean isActive(UUID companionId) {
        return companionId != null && ENGINE.isActive(companionId);
    }

    /** 执行 AI 上下文：支线 active 时返回完整 {@code <rdd>…</rdd>}，否则 null。只读缓存。 */
    static String contextFor(UUID companionId) {
        return companionId == null ? null : SIDE_CONTEXT.get(companionId);
    }

    /** 宿主每拍（约 20 tick）调用。 */
    static void tick(MinecraftServer server, NumenPlayer ap) {
        UUID companionId = ap.getUUID();
        try {
            HostWorldPort port = new HostWorldPort(ap);
            boolean wasActive = ENGINE.isActive(companionId);
            if (!wasActive) {
                port.pollWake(companionId); // 无支线时排空陈旧醒来边沿，避免泄漏到下一晚
            }
            MainlineResumeToken token = currentMainlineToken(companionId);
            boolean deathOccupying = RddRepairDispatch.lastRepair(companionId) != null;
            ENGINE.tick(companionId, port, token, deathOccupying);
            boolean nowActive = ENGINE.isActive(companionId);
            refreshContext(companionId, port);
            nudgeIfNeeded(companionId, port, wasActive, nowActive);
            persist();
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 支线 tick 异常 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * 支线监督的"拍醒"：支线刚激活时、以及仍 active 时每约 4 分钟补一次提醒。
     *
     * <p>没有这一步，同伴空闲时不会主动取上下文 → 支线等于没人执行
     * （2026-10-08 用户实测：夜前/夜里都触发了，但同伴毫无反应）。这正是"监督要跟着支线走"。
     * {@link RddPlugin#nudge} 自身还有 3 分钟全局节流兜底，这里再加一层 4 分钟间隔防刷屏。
     */
    private static void nudgeIfNeeded(UUID companionId, SideTaskWorldPort port, boolean wasActive, boolean nowActive) {
        if (!nowActive) {
            LAST_NUDGE_GAME_TIME.remove(companionId);
            return;
        }
        long now = port.gameTime();
        Long last = LAST_NUDGE_GAME_TIME.get(companionId);
        if (wasActive && last != null && now - last < NUDGE_REPEAT_TICKS) {
            return;
        }
        RddPlugin.nudge(companionId, "[睡觉支线] 天黑了，去睡觉——这是你当前最高优先的目标。"
                + "用 scan_blocks 找床（tag #minecraft:beds）→ goto 走到床边 → 调用 sleep。"
                + "白天被原版拒绝不是工具坏了。主线已临时冻结；睡到天亮自然醒、或超时后系统会自动恢复主线。");
        LAST_NUDGE_GAME_TIME.put(companionId, now);
    }

    /** 外部取消（主人暂停/换目标/清目标/同伴移除）。 */
    static void cancel(UUID companionId, String reason) {
        if (companionId == null) {
            return;
        }
        ENGINE.cancel(companionId, reason);
        SIDE_CONTEXT.remove(companionId);
        persist();
    }

    static void onServerStopped() {
        ENGINE.onServerStopped();
        SIDE_CONTEXT.clear();
        SleepWakeWatcher.clearAll();
    }

    // ---- 世界端口 ----

    private static final class HostWorldPort implements SideTaskWorldPort {
        private final NumenPlayer ap;

        HostWorldPort(NumenPlayer ap) {
            this.ap = ap;
        }

        @Override public long gameTime() { return ap.level().getGameTime(); }
        @Override public long dayTime() { return ap.level().getDayTime(); }
        @Override public long gameDay() { return Math.floorDiv(ap.level().getDayTime(), 24_000L); }
        @Override public boolean hasDayNightCycle() { return !ap.level().dimensionType().hasFixedTime(); }
        @Override public String worldId() { return ap.level().dimension().location().toString(); }
        @Override public boolean isSleeping(UUID companionId) { return ap.isSleeping(); }
        @Override public boolean isDay() { return ap.level().isDay(); }
        @Override public Wake pollWake(UUID companionId) { return SleepWakeWatcher.poll(companionId); }
        @Override public boolean inCombat(UUID companionId) { return ap.hurtTime > 0; }
        @Override public boolean bodyBusy(UUID companionId) {
            return com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(companionId) != null;
        }
        @Override public String mainlineObjective(UUID companionId) { return mainlineObjectiveText(companionId); }
    }

    /** 主线目标文本（目标 + 当前一级 + 当前二级描述拼接）：仅用于"主线是否明确要求夜间活动"。 */
    private static String mainlineObjectiveText(UUID companionId) {
        try {
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null || rt.chain() == null || rt.chain().goal() == null) {
                return null;
            }
            TaskChain chain = rt.chain();
            StringBuilder sb = new StringBuilder(chain.goal().description());
            if (chain.currentPrimary() != null) {
                sb.append(' ').append(chain.currentPrimary().description());
            }
            Subtask sub = chain.currentSubtask();
            if (sub != null) {
                sb.append(' ').append(sub.description());
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- 主线恢复令牌 ----

    private static MainlineResumeToken currentMainlineToken(UUID companionId) {
        try {
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return null;
            }
            TaskChain chain = rt.chain();
            if (chain == null || chain.goal() == null) {
                return null;
            }
            Subtask current = chain.currentSubtask();
            return new MainlineResumeToken(companionId, chain.goal().id(),
                    chain.currentPrimary() == null ? null : chain.currentPrimary().id(),
                    current == null ? null : current.id(),
                    chain.planRevision(), chain.planRevision());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static MainlineResumeToken validateMainline(MainlineResumeToken token) {
        if (token == null || token.companionId() == null) {
            return null;
        }
        try {
            RddRuntime rt = RddPlugin.runtime(token.companionId());
            if (rt == null) {
                return null;
            }
            TaskChain chain = rt.chain();
            if (chain == null || chain.goal() == null) {
                return null;
            }
            Subtask current = chain.currentSubtask();
            String primaryId = chain.currentPrimary() == null ? null : chain.currentPrimary().id();
            String subtaskId = current == null ? null : current.id();
            if (!token.matches(chain.goal().id(), primaryId, subtaskId, chain.planRevision())) {
                return null;
            }
            return token;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- 上下文缓存 ----

    private static void refreshContext(UUID companionId, SideTaskWorldPort port) {
        Optional<SideTask> current = ENGINE.current(companionId);
        if (current.isEmpty() || current.get().terminal()) {
            SIDE_CONTEXT.remove(companionId);
            return;
        }
        SideTask task = current.get();
        SideTaskType type = ENGINE.typeOf(task.typeId());
        if (type == null) {
            SIDE_CONTEXT.remove(companionId);
            return;
        }
        String block = "<rdd><enabled>true</enabled><active>true</active>"
                + "<primary_status>SIDE_TASK</primary_status>"
                + "<scope>SIDE</scope>"
                + "<instruction>当前最高优先目标是你下面的支线任务；主线已临时冻结，完成或超时后系统自动恢复主线。</instruction>"
                + type.renderContext(task, port)
                + "</rdd>";
        SIDE_CONTEXT.put(companionId, block);
    }

    // ---- 事件 + 落盘 ----

    private static void publish(String event, Map<String, Object> data) {
        try {
            RddMonitor.publish(event, data);
        } catch (Throwable ignored) {
            // 观测失败不影响支线主流程
        }
    }

    private static void persist() {
        if (sideDir == null) {
            return;
        }
        try {
            Files.createDirectories(sideDir);
            Path tmp = sideDir.resolve("side-tasks.json.tmp");
            Files.writeString(tmp, ENGINE.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, sideDir.resolve("side-tasks.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存支线状态失败: {}", ex.toString());
        }
    }

    private static void restore() {
        if (sideDir == null) {
            return;
        }
        try {
            Path file = sideDir.resolve("side-tasks.json");
            if (!Files.isRegularFile(file)) {
                return;
            }
            ENGINE.restore(Files.readString(file, StandardCharsets.UTF_8));
            LOG.info("[rdd] 恢复支线状态 {} 个", ENGINE.runtime().snapshot().size());
        } catch (Exception ex) {
            LOG.warn("[rdd] 恢复支线状态失败: {}", ex.toString());
        }
    }
}
