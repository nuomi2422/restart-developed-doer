package com.dwinovo.numen.plugins.camera;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;
import com.dwinovo.numen.plugins.camera.command.CameraCommands;
import com.dwinovo.numen.plugins.camera.move.FastMove;
import com.dwinovo.numen.plugins.camera.rec.VideoRecorder;
import com.dwinovo.numen.plugins.camera.tether.CompanionTether;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;

/**
 * numencam 入口：一个只管「镜头在哪 + 我怎么过去」的纯客户端小模块。
 *
 * <h2>模块边界</h2>
 * 这个 mod <b>不</b>注册进 {@code NumenPlugins}，也<b>不</b>依赖 numen 的 api jar ——
 * 它只依赖 Minecraft 客户端 + NeoForge。所以它能被单独删掉、单独部署，Numen 引擎那边
 * 一行都不用改。
 *
 * <h2>每 tick 做什么</h2>
 * <ol>
 *   <li>{@link CameraRig#tick()} —— 每 20 tick 重读一次 cam.json（外部改文件即可换机位）</li>
 *   <li>{@link CompanionTether#tick()} —— 绑定目标走出加载区就把玩家传送过去</li>
 *   <li>{@link FastMove#tick()} —— 疾跑 / 飞行（飞行必须每 tick 重申，原版落地会自关）</li>
 *   <li>{@link VideoRecorder#tick()} —— 帧序列 + 异步 ffmpeg 封盘</li>
 * </ol>
 *
 * <p><b>为什么用 {@code ClientTickEvent.Post} 而不是 EndClientTick</b>：Post 在渲染之后，
 * 此时 {@code getMainRenderTarget()} 里是刚画完的那一帧，录像抓到的才是最新画面。
 */
@Mod(value = CameraMod.MOD_ID, dist = Dist.CLIENT)
public final class CameraMod {

    public static final String MOD_ID = "numencam";

    private static final Logger LOGGER = LogUtils.getLogger();

    public CameraMod() {
        // 客户端命令 + 客户端 tick 都挂游戏总线（不是 modBus）。这是 NeoForge 1.21 的规定 ——
        // 把 IModBusEvent 类的事件（注册表、生命周期那些）挂到游戏总线上会直接炸。
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        LOGGER.info("[numencam] 已装载。/cam help 看用法，cam.json 在 {}",
                CameraRig.configPath());
    }

    private void onRegisterCommands(RegisterClientCommandsEvent event) {
        CameraCommands.register(event.getDispatcher());
        LOGGER.info("[numencam] /cam 已注册");
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 菜单/暂停开着时不做任何会动玩家的事（tp 会被暂停吞掉，疾跑会粘在菜单上）。
        if (mc.screen == null) {
            FastMove.tick();
            CompanionTether.tick();
        }
        CameraRig.tick();
        VideoRecorder.tick();
    }
}