package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge 入口：注册插件 + 挂 RDD 检测的服务端 tick。 */
@Mod("rdd")
public final class RddMod {

    private final RddDetector detector = new RddDetector();

    public RddMod() {
        NumenPlugins.register(new RddPlugin());
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        if (event.getServer() != null) {
            detector.onServerTick(event.getServer());
        }
    }
}
