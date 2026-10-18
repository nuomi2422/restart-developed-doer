package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每服务端 tick 记录"睡着→醒"边沿（{@link SideTaskWorldPort.Wake}）。
 *
 * <p>支线引擎每 20 tick 才跑，而原版"条件满足即跳夜"可能让 {@code isSleeping()} 只维持
 * 一两 tick —— 按 20 tick 采样会整夜漏判。所以这里按 tick 抓边沿，引擎来取。
 *
 * <p>只读 {@code isSleeping()}，用独立状态表；<b>不碰</b> {@link NumenPlayer#pollWokeUp()}
 * 的内部记账（那是内置大脑在消费的，抢它会偷走主线的醒来事件）。
 */
final class SleepWakeWatcher {

    private static final Map<UUID, Boolean> LAST_SLEEPING = new ConcurrentHashMap<>();
    private static final Map<UUID, SideTaskWorldPort.Wake> PENDING = new ConcurrentHashMap<>();
    private static boolean installed;

    private SleepWakeWatcher() {
    }

    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(SleepWakeWatcher::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Pre event) {
        if (event.getServer() == null) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer ap)) {
                continue;
            }
            boolean now = ap.isSleeping();
            Boolean previous = LAST_SLEEPING.put(ap.getUUID(), now);
            if (previous != null && previous && !now) {
                PENDING.put(ap.getUUID(), ap.level().isDay()
                        ? SideTaskWorldPort.Wake.DAYBREAK
                        : SideTaskWorldPort.Wake.INTERRUPTED);
            }
        }
    }

    /** 消费一次醒来的边沿；没有则为 {@link SideTaskWorldPort.Wake#NONE}。 */
    static SideTaskWorldPort.Wake poll(UUID companionId) {
        SideTaskWorldPort.Wake wake = PENDING.remove(companionId);
        return wake == null ? SideTaskWorldPort.Wake.NONE : wake;
    }

    static void clearAll() {
        LAST_SLEEPING.clear();
        PENDING.clear();
    }
}
