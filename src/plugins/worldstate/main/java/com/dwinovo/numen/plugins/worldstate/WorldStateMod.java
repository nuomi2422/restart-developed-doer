package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/**
 * 世界观测模块的模组入口。构造期把 {@link WorldStatePlugin} 挂进 Numen 引擎。
 *
 * <p>{@link #service()} 是给别的模块（settlement / rdd / 后续牧场交易）的<b>只读</b>入口，
 * 沿用 settlement 的先例，避免为了查一次动物数量而依赖整个实现。
 */
@Mod("worldstate")
public final class WorldStateMod {

    private static volatile WorldStateService SERVICE;

    public WorldStateMod() {
        NumenPlugins.register(new WorldStatePlugin());
    }

    static void bindService(WorldStateService service) {
        SERVICE = service;
    }

    public static WorldStateService service() {
        return SERVICE;
    }
}
