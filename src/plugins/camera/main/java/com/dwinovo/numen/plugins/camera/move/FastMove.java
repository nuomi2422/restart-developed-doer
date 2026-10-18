package com.dwinovo.numen.plugins.camera.move;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 移动加速：让「用脚本/AI 驱着玩家走路」这件事不卡。
 *
 * <h2>为什么「卡」</h2>
 * 三层原因叠在一起：
 * <ol>
 *   <li><b>每条指令一次 WebSocket 重连</b>。OpenClaw 的 {@code oc-send.js} 每发一条
 *       命令就 {@code new WebSocket}，一次「走一步」= 4 次连接（取位置→按键→放按键→
 *       再取位置），每次都有握手延迟。</li>
 *   <li><b>按键走二次排队</b>。mod 的 {@code handleKeyDown} 里又包了一次
 *       {@code mc.execute}，而这条命令本身已经在主线程任务里了 → 真正的 setDown
 *       要等下一次 {@code runAllTasks}，每条指令白白多等一拍。</li>
 *   <li><b>默认速度就是走路速度</b>。没人按住疾跑键。</li>
 * </ol>
 * 第 1、2 条属于外部工具链，本模块改不了；<b>第 3 条能一键补上</b>，这就是这里做的事。
 *
 * <h2>疾跑怎么开</h2>
 * 直接按 {@code keySprint} 是<b>假疾跑</b>：原版只在有前进输入时才切疾跑（见
 * {@code LocalPlayer} 里的 sprint 判定，还要满足饥饿&gt;6、不在水里、没在用东西、
 * 没失明）。真正稳定的办法是每 tick 调 {@link LocalPlayer#setSprinting(boolean)}，
 * 让状态本身为真 —— 前进时立刻满速，不用等 7 tick 触发窗口。
 *
 * <h2>飞行必须每 tick 重申</h2>
 * 原版 {@code LocalPlayer} 每 tick 都会检查：<b>站在地上 + abilities.flying + 不是
 * alwaysFlying → 强制把 flying 关掉</b>。所以只 set 一次，落地那一帧就自己关了。
 * 想让飞行保持，必须每 tick 重新断言 {@code flying = true} 并调
 * {@link LocalPlayer#onUpdateAbilities()} 让服务端也认。
 *
 * <p>这是一件<b>只在客户端本地生效</b>的事：服务端仍按 survival 规则算，但飞行包的
 * 落点在客户端，服务端会照单全收（这也是原版双击空格能飞的原因）。单人/局域网均可。
 */
public final class FastMove {

    /** 疾跑开关。 */
    private static volatile boolean sprint;

    /** 飞行开关（每 tick 重申）。 */
    private static volatile boolean fly;

    /** 飞行速度倍数。1.0 = 原版 0.05。 */
    private static volatile float flyMultiplier = 1.0f;

    private FastMove() {
    }

    public static boolean sprinting() {
        return sprint;
    }

    public static boolean flying() {
        return fly;
    }

    public static float flyMultiplier() {
        return flyMultiplier;
    }

    public static String setSprint(boolean on) {
        sprint = on;
        return "自动疾跑 = " + on
                + (on ? "（前进即满速；在水里 / 饥饿 ≤6 / 在用物品时原版会自动退回走路，这是原版规则，不绕）" : "");
    }

    public static String setFly(boolean on) {
        fly = on;
        if (on) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) {
                p.getAbilities().mayfly = true;
            }
        }
        return "飞行 = " + on + (on ? "（每 tick 重申，落地不会自关；关掉前先落到地上）" : "");
    }

    public static String setFlyMultiplier(float mul) {
        flyMultiplier = Math.max(0.1f, Math.min(20.0f, mul));
        return String.format("飞行速度 = %.2fx（原版 %.3f → %.3f）",
                flyMultiplier, 0.05f, 0.05f * flyMultiplier);
    }

    public static void tick() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        if (sprint) {
            // setSprinting 才是真的：keySprint 只是"许可"，还要等原版那 7 tick 窗口。
            p.setSprinting(true);
        }
        if (fly) {
            // 顺序要紧：先给 mayfly 权限，再置 flying，最后通知服务端。少一步都不生效。
            if (!p.getAbilities().mayfly) {
                p.getAbilities().mayfly = true;
            }
            if (!p.getAbilities().flying) {
                p.getAbilities().flying = true;
            }
            float want = 0.05f * flyMultiplier;
            if (p.getAbilities().getFlyingSpeed() != want) {
                p.getAbilities().setFlyingSpeed(want);
            }
            p.onUpdateAbilities();
        }
    }
}