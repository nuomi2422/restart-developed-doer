package com.dwinovo.numen.plugins.camera.tether;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 绑定 + 牵引：把「玩家」这个客户端概念跟「同伴」这个实体概念焊在一起。
 *
 * <h2>要做两件事</h2>
 * <ol>
 *   <li><b>绑定</b> —— {@link CameraRig#targetName()} 说盯谁就是谁，这里只负责「选谁」。</li>
 *   <li><b>牵引</b> —— 同伴走出客户端渲染距离（=「离开我的加载区域」）时，玩家也过去。
 *       这样「追踪」才闭环：不然镜头只能一直卡在最后机位上。</li>
 * </ol>
 *
 * <h2>为什么传送走 /tp 而不是客户端直接改坐标</h2>
 * 客户端 {@code LocalPlayer} 没有 {@code teleportTo/setPos}；就算有也会被服务端的
 * {@code ServerboundMovePlayerPacket} 校正拉回去（实测症状：玩家瞬移回去又弹回来）。
 * 唯一干净的路是发一条服务端命令 {@code tp @s <目标>}，让服务端权威执行。
 *
 * <h2>为什么要冷却 + 连续丢失判定</h2>
 * 刚传送过去那一瞬间，客户端实体表里可能还没出现新同伴（区块要重收），
 * {@code resolveTarget()} 会连续返回 null 几十个 tick。如果一见到 null 就传送，
 * 就会和「刚传过去→还没加载完→再传一次」打成死循环，一秒刷几十条 /tp 把日志淹掉。
 * 所以：<b>连续丢失 40 tick（2 秒）</b>才判为「真的走了」，且每次传送后 <b>冷却 100 tick</b>。
 *
 * <h2>权限</h2>
 * {@code /tp} 是原版命令，单人游戏里默认需要作弊。拿不到权限就只在状态输出里说清楚，
 * <b>不静默失败</b> —— 否则用户看到「追踪开着但人不动」，完全查不出原因。
 */
public final class CompanionTether {

    /** 连续多少 tick 解析不到目标才判定「真的走出加载区了」。40 tick = 2 秒。 */
    private static final int LOST_TICKS_BEFORE_TP = 40;

    /** 传送后的冷却 tick 数，防止「传过去→还没加载→再传」死循环。 */
    private static final int TP_COOLDOWN_TICKS = 100;

    /** 超过这个距离就主动传过去（只在目标可见时判）。默认 64 格 ≈ 一个区块多一点。 */
    private static volatile double maxDist = 64.0;

    /** 牵引总开关。 */
    private static volatile boolean follow;

    /** 牵引状态行（给 /cam status 看）。 */
    private static volatile String status = "未开启";

    private static int lostTicks;
    private static int cooldownTicks;
    private static boolean warnedNoPermission;

    private CompanionTether() {
    }

    public static boolean follow() {
        return follow;
    }

    public static double maxDist() {
        return maxDist;
    }

    public static String status() {
        return status;
    }

    /** 开关牵引。绑定为空时直接拒绝 —— 没有目标就没有「跟着谁」。 */
    public static String setFollow(boolean on) {
        String t = CameraRig.targetName();
        if (t.isEmpty()) {
            follow = false;
            return "还没绑定同伴。先 /cam bind <名字>。";
        }
        if (on && !canTeleport()) {
            follow = true;
            status = "已开启，但没传送权限（单人游戏要开作弊）—— 只做镜头跟随，不会拉人";
            return status;
        }
        follow = on;
        lostTicks = 0;
        cooldownTicks = 0;
        status = on ? ("已开启：" + t + " 走出加载区就传过去，最远 " + (long) maxDist + " 格")
                : "已关闭";
        return status;
    }

    public static String setMaxDist(double d) {
        maxDist = Math.max(1.0, Math.min(256.0, d));
        return "牵引距离阈值 = " + (long) maxDist + " 格";
    }

    /** 能否执行 /tp。客户端命令的 source 永远 hasPermission==true，所以只能问服务端给不给。 */
    private static boolean canTeleport() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                && (mc.player.hasPermissions(2) || mc.player.isCreative());
    }

    public static void tick() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (!follow) {
            lostTicks = 0;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;   // 聊天界面/暂停菜单开着时别动玩家
        }
        Entity t = CameraRig.resolveTarget();
        if (t != null) {
            lostTicks = 0;
            if (t.distanceTo(mc.player) > maxDist) {
                tpTo((AbstractClientPlayer) t, "超出 " + (long) maxDist + " 格");
            }
            return;
        }
        // 目标不在客户端实体表里。名字对得上才算「走了」，否则只是压根没这个同伴。
        lostTicks++;
        if (lostTicks < LOST_TICKS_BEFORE_TP) {
            return;
        }
        if (cooldownTicks > 0) {
            return;
        }
        AbstractClientPlayer candidate = findByName(CameraRig.targetName());
        if (candidate == null) {
            // 名字都没匹配上：可能是改过名，也可能是它真的被卸载到连客户端实体表都没有。
            // 这时也传 —— 因为「tp @s 名字」是服务端执行，服务端能找到它就会把我拉过去。
            tpByName(CameraRig.targetName(), "已离开客户端加载区");
            return;
        }
        tpTo(candidate, "已离开客户端加载区");
    }

    private static AbstractClientPlayer findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractClientPlayer p
                    && !mc.player.getUUID().equals(p.getUUID())
                    && name.equalsIgnoreCase(p.getName().getString())) {
                return p;
            }
        }
        return null;
    }

    /**
     * 传到目标旁边（偏移 1.5 格，别和它重叠）。
     *
     * <p>用 <b>坐标</b>而不是 <code>tp @s &lt;名字&gt;</code>：按名字传会落在它<b>脚下
     * 正中</b>，万一那个格子是实心（它站在方块顶上的边缘）就可能被挤进墙里。给坐标能
     * 挑一个确定的空位，也顺带让状态行里的数字跟实际落点一致。
     */
    private static void tpTo(AbstractClientPlayer target, String why) {
        Vec3 feet = target.position();
        tpByCoords(feet.x + 1.5, feet.y, feet.z + 1.5, why + " → 已发 /tp 过去");
    }

    /** 目标名都匹配不上时只能按名字传（服务端去找人，服务端认识就行）。 */
    private static void tpByName(String name, String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || name == null || name.isEmpty()) {
            return;
        }
        if (!canTeleport()) {
            reportNoPermission();
            return;
        }
        mc.player.connection.sendCommand("tp @s " + name);
        afterTp(why + " → 已发 /tp @s " + name);
    }

    private static void tpByCoords(double x, double y, double z, String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!canTeleport()) {
            reportNoPermission();
            return;
        }
        mc.player.connection.sendCommand(String.format("tp @s %.1f %.1f %.1f", x, y, z));
        afterTp(String.format("%s → /tp @s %.1f %.1f %.1f", why, x, y, z));
    }

    private static void reportNoPermission() {
        if (warnedNoPermission) {
            return;
        }
        warnedNoPermission = true;
        status = "牵引触发过但没传送权限（单人游戏要开作弊）。"
                + "解法：世界设置里开「作弊」，或 /gamemode creative。";
    }

    private static void afterTp(String what) {
        cooldownTicks = TP_COOLDOWN_TICKS;
        lostTicks = 0;
        warnedNoPermission = false;
        status = what;
    }

    /** 给 /cam status 用的一行摘要。 */
    public static Component describe() {
        return Component.literal(status + " ｜ 目标=" + (CameraRig.targetName().isEmpty()
                ? "(无)" : CameraRig.targetName()));
    }

    /** 让 /cam bind 顺手开牵引：绑定成功但没开牵引的话，多数人的意图就是要跟着。 */
    public static void autoEnableIfFreshlyBound(String previousTarget) {
        if (follow) {
            return;
        }
        String now = CameraRig.targetName();
        if (now.isEmpty() || now.equalsIgnoreCase(previousTarget)) {
            return;
        }
        setFollow(true);
    }
}