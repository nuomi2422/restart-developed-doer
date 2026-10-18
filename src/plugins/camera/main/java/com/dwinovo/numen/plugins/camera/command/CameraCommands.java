package com.dwinovo.numen.plugins.camera.command;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;
import com.dwinovo.numen.plugins.camera.move.FastMove;
import com.dwinovo.numen.plugins.camera.rec.VideoRecorder;
import com.dwinovo.numen.plugins.camera.tether.CompanionTether;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /cam} —— 全部功能在游戏内原版聊天框里直接敲。
 *
 * <h2>为什么走客户端 Brigadier 而不是 Numen 自己的聊天命令</h2>
 * Numen 有个 {@code ChatCommands}（拦截聊天输入的斜杠命令），但它<b>只认 Numen 自己
 * 那个 GUI 输入框</b>，原版聊天框不认，而且要传 {@code EntityAgentLoop}。这个模块跟
 * Numen 引擎没有任何依赖（见 {@code CameraRig} 的类注释），挂客户端 Brigadier 才
 * 是干净的：原版聊天框直接能用，还自带 Tab 补全。
 *
 * <h2>为什么子命令全是中文也能用</h2>
 * 全部是 ASCII 字面量。用户敲 {@code /cam mode orbit}（大小写不敏感）就行。
 */
public final class CameraCommands {

    /** Tab 补全：当前客户端能看到的玩家（排除自己）—— 也就是所有同伴。 */
    private static final SuggestionProvider<CommandSourceStack> COMPANIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(visibleCompanions(), builder);

    private CameraCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cam")
                .executes(CameraCommands::help)
                // ── 绑定 / 选择 ───────────────────────────────────────────
                .then(Commands.literal("list").executes(CameraCommands::listCompanions))
                .then(Commands.literal("bind")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(COMPANIONS)
                                .executes(ctx -> bind(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("unbind").executes(ctx -> ok(ctx, CameraRig.setTarget(""))))
                .then(Commands.literal("tp").executes(CameraCommands::tpNow))
                .then(Commands.literal("follow")
                        .executes(ctx -> ok(ctx, CompanionTether.setFollow(true)))
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> ok(ctx, CompanionTether.setFollow(
                                        BoolArgumentType.getBool(ctx, "on"))))))
                .then(Commands.literal("followdist")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1.0, 256.0))
                                .executes(ctx -> ok(ctx, CompanionTether.setMaxDist(
                                        DoubleArgumentType.getDouble(ctx, "blocks"))))))
                // ── 机位 ────────────────────────────────────────────────
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(CameraRig.Mode.values())
                                                .map(Enum::name).collect(Collectors.toList()), b))
                                .executes(CameraCommands::setMode)))
                .then(Commands.literal("dist")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.5, 32.0))
                                .executes(ctx -> ok(ctx, CameraRig.setDistance((float)
                                        DoubleArgumentType.getDouble(ctx, "blocks"))))))
                .then(Commands.literal("height")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(-16.0, 32.0))
                                .executes(ctx -> ok(ctx, CameraRig.setHeight((float)
                                        DoubleArgumentType.getDouble(ctx, "blocks"))))))
                .then(Commands.literal("shoulder")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(-8.0, 8.0))
                                .executes(ctx -> ok(ctx, CameraRig.setShoulder((float)
                                        DoubleArgumentType.getDouble(ctx, "blocks"))))))
                .then(Commands.literal("bearing")
                        .then(Commands.argument("deg", DoubleArgumentType.doubleArg(-360.0, 360.0))
                                .executes(ctx -> ok(ctx, CameraRig.setBearing((float)
                                        DoubleArgumentType.getDouble(ctx, "deg"))))))
                .then(Commands.literal("pitch")
                        .then(Commands.argument("deg", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                .executes(ctx -> ok(ctx, CameraRig.setPitchBias((float)
                                        DoubleArgumentType.getDouble(ctx, "deg"))))))
                .then(Commands.literal("hold")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> ok(ctx, CameraRig.setHoldWhenLost(
                                        BoolArgumentType.getBool(ctx, "on")))))
                        .executes(ctx -> ok(ctx, CameraRig.setHoldWhenLost(true))))
                .then(Commands.literal("lock")
                        .executes(CameraCommands::lockHere)
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(-1.0e7, 1.0e7))
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-4096.0, 4096.0))
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg(-1.0e7, 1.0e7))
                                                .executes(CameraCommands::lockAt)))))
                // ── 移动加速 ────────────────────────────────────────────
                .then(Commands.literal("run")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> ok(ctx, FastMove.setSprint(
                                        BoolArgumentType.getBool(ctx, "on")))))
                        .executes(ctx -> ok(ctx, FastMove.setSprint(true))))
                .then(Commands.literal("fly")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> ok(ctx, FastMove.setFly(
                                        BoolArgumentType.getBool(ctx, "on")))))
                        .executes(ctx -> ok(ctx, FastMove.setFly(true))))
                .then(Commands.literal("speed")
                        .then(Commands.argument("mul", DoubleArgumentType.doubleArg(0.1, 20.0))
                                .executes(ctx -> ok(ctx, FastMove.setFlyMultiplier((float)
                                        DoubleArgumentType.getDouble(ctx, "mul"))))))
                // ── 录像 ────────────────────────────────────────────────
                .then(Commands.literal("rec")
                        .executes(ctx -> ok(ctx, VideoRecorder.status()))
.then(Commands.literal("start")
                                .executes(ctx -> ok(ctx, VideoRecorder.start(10)))
                                .then(Commands.literal("dedupe")
                                        .executes(ctx -> ok(ctx, VideoRecorder.start(10, true))))
                                .then(Commands.argument("fps", IntegerArgumentType.integer(1, 30))
                                        .executes(ctx -> ok(ctx, VideoRecorder.start(
                                                IntegerArgumentType.getInteger(ctx, "fps"))))
                                        .then(Commands.literal("dedupe")
                                                .executes(ctx -> ok(ctx, VideoRecorder.start(
                                                        IntegerArgumentType.getInteger(ctx, "fps"), true))))))
                        .then(Commands.literal("stop").executes(ctx -> ok(ctx, VideoRecorder.stop())))
                        .then(Commands.literal("status").executes(ctx -> ok(ctx, VideoRecorder.status())))
                        .then(Commands.literal("maxframes")
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 5000))
                                        .executes(ctx -> ok(ctx, VideoRecorder.setMaxFrames(
                                                IntegerArgumentType.getInteger(ctx, "n")))))))
                // ── 杂项 ────────────────────────────────────────────────
                .then(Commands.literal("status").executes(CameraCommands::status))
                .then(Commands.literal("save").executes(ctx -> ok(ctx, CameraRig.forceReload())))
                .then(Commands.literal("reload").executes(ctx -> ok(ctx, CameraRig.forceReload())))
                .then(Commands.literal("help").executes(CameraCommands::help)));
    }

    // ── 各子命令实现 ────────────────────────────────────────────────────────

    private static int bind(CommandContext<CommandSourceStack> ctx, String name) {
        String prev = CameraRig.targetName();
        String msg = CameraRig.setTarget(name);
        CameraRig.ensureActiveMode();
        CompanionTether.autoEnableIfFreshlyBound(prev);
        return ok(ctx, msg + " ｜ " + CompanionTether.status());
    }

    private static int listCompanions(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return ok(ctx, "还没进世界。");
        }
        List<String> names = visibleCompanions();
        if (names.isEmpty()) {
            return ok(ctx, "客户端渲染范围内没有别的玩家（同伴走出视距了 = 这里也看不到）。");
        }
        StringBuilder sb = new StringBuilder("可选目标（客户端可见）：");
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractClientPlayer p) || CameraRig.isLocalPlayer(p)) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, " %s(%.0f格)", p.getName().getString(),
                    p.distanceTo(mc.player)));
        }
        return ok(ctx, sb.toString());
    }

    private static int tpNow(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        String t = CameraRig.targetName();
        if (t.isEmpty()) {
            return ok(ctx, "还没绑定。先 /cam bind <名字>。");
        }
        if (mc.player == null) {
            return ok(ctx, "没有本地玩家。");
        }
        if (!(mc.player.hasPermissions(2) || mc.player.isCreative())) {
            return ok(ctx, "没传送权限 —— 单人游戏要开「作弊」（或 /gamemode creative），"
                    + "联机服要 OP。");
        }
        mc.player.connection.sendCommand("tp @s " + t);
        return ok(ctx, "已发 /tp @s " + t + "。");
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "mode");
        CameraRig.Mode m;
        try {
            m = CameraRig.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ok(ctx, "没有这个模式：" + raw + "。可用："
                    + java.util.Arrays.stream(CameraRig.Mode.values())
                            .map(Enum::name).collect(Collectors.joining(" / ")));
        }
        if (m != CameraRig.Mode.OFF && CameraRig.targetName().isEmpty()) {
            return ok(ctx, "还没绑定同伴 —— 没有目标，相机会静默退回原版第一人称。"
                    + "先 /cam bind <名字>（/cam list 看有哪些）。");
        }
        return ok(ctx, CameraRig.setMode(m));
    }

    private static int lockHere(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return ok(ctx, "没有本地玩家。");
        }
        return ok(ctx, CameraRig.setLock(mc.player.getX(), mc.player.getY() + 2.0, mc.player.getZ()));
    }

    private static int lockAt(CommandContext<CommandSourceStack> ctx) {
        return ok(ctx, CameraRig.setLock(
                DoubleArgumentType.getDouble(ctx, "x"),
                DoubleArgumentType.getDouble(ctx, "y"),
                DoubleArgumentType.getDouble(ctx, "z")));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        Entity t = CameraRig.currentTarget();
        String target = CameraRig.targetName();
        String seen;
        if (t == null) {
            seen = "客户端实体表里<b>没有</b>它（超出视距或名字对不上）→ 镜头已退回原版第一人称";
        } else {
            seen = String.format(Locale.ROOT, "在 %.0f 格外（渲染范围内）",
                    t.distanceTo(mc.player));
        }
        String msg = String.format(Locale.ROOT,
                "机位 mode=%s target=%s(%s) dist=%.2f height=%.2f shoulder=%.2f bearing=%.0f° "
                        + "pitch=%+.0f° hold=%b%n牵引 %s%n移动 疾跑=%b 飞行=%b 速度=%.2fx%n录像 %s%n配置 %s",
                CameraRig.mode(), target.isEmpty() ? "(无)" : target, seen,
                CameraRig.distance(), CameraRig.height(), CameraRig.shoulder(),
                CameraRig.bearing(), CameraRig.pitchBias(), CameraRig.holdWhenLost(),
                CompanionTether.status(),
                FastMove.sprinting(), FastMove.flying(), FastMove.flyMultiplier(),
                VideoRecorder.status(),
                CameraRig.configPath());
        return ok(ctx, msg);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        return ok(ctx, """
                /cam —— Numen 相机模块（纯客户端，不依赖 Numen 引擎）
                  bind <名字>        绑定跟拍目标（顺手开牵引、切 ORBIT）   别名：/cam follow 同理
                  unbind             解除绑定
                  list               列出客户端能看到的同伴 + 距离
                  tp                 立刻传到绑定目标身边
                  follow [on|off]    同伴走出加载区/超出阈值就自动传送过来
                  followdist <格>     牵引距离阈值（默认 64）
                  mode <OFF|SHOULDER|ORBIT|LOCK|CINEMATIC>
                  dist/height/shoulder/bearing/pitch <数值>   机位参数
                  hold [on|off]      目标走丢时是否保持最后机位
                  lock [x y z]       钉死机位（不给坐标就钉在玩家头顶上方 2 格）
                  run [on|off]       自动疾跑（前进即满速）
                  fly [on|off]       飞行（每 tick 重申，落地不自关）
                  speed <倍数>       飞行速度（1 = 原版）
                  rec start [fps] [dedupe] / rec stop / rec status / rec maxframes <n>
                  save               重读 cam.json（外部改完文件用）
                  status             当前全部状态""");
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static List<String> visibleCompanions() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) {
            return out;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractClientPlayer p && !CameraRig.isLocalPlayer(p)) {
                out.add(p.getName().getString());
            }
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}