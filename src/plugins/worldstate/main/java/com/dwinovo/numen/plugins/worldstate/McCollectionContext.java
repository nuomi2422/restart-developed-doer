package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * {@link CollectionContext} 的 Minecraft 实现：把服务器带进采集器。
 *
 * <p>核心的 {@code CollectionContext} 刻意不认识世界；这里多一层，让本插件的采集器能拿到
 * {@link ServerLevel} 去只读世界。采集器用 {@link #levelOf} 按维度取已加载的 {@link ServerLevel}，
 * <b>不强制加载任何区块</b>。
 */
public record McCollectionContext(MinecraftServer server, String worldId, long gameTick, long nowMs)
        implements CollectionContext {

    public ServerLevel levelOf(String dimension) {
        if (server == null || dimension == null || dimension.isBlank()) return null;
        try {
            ResourceLocation id = ResourceLocation.parse(dimension);
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            return server.getLevel(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
