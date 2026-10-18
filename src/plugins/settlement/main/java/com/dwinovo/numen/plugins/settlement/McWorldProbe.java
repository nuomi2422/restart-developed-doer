package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.settlement.core.accept.EntityView;
import com.dwinovo.numen.settlement.core.accept.WorldProbe;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WorldProbe} 的 Minecraft 实现：跨维度读已加载区块。
 *
 * <p>不强制加载任何区块——{@link #loaded} 为假时上层必须判 UNKNOWN，不是判"没有"。
 */
public final class McWorldProbe implements WorldProbe {

    private final MinecraftServer server;

    public McWorldProbe(MinecraftServer server) {
        this.server = server;
    }

    private ServerLevel levelOf(String dimension) {
        if (server == null || dimension == null) return null;
        try {
            ResourceLocation id = ResourceLocation.parse(dimension);
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean loaded(String dimension, int x, int y, int z) {
        ServerLevel level = levelOf(dimension);
        return level != null && level.hasChunkAt(new BlockPos(x, y, z));
    }

    @Override
    public String blockIdAt(String dimension, int x, int y, int z) {
        ServerLevel level = levelOf(dimension);
        if (level == null) return null;
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
    }

    @Override
    public List<EntityView> entities(String dimension, BlockBox box) {
        ServerLevel level = levelOf(dimension);
        if (level == null) return List.of();
        AABB area = new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0);
        List<EntityView> out = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, area, ignored -> true)) {
            out.add(new EntityView(
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        return out;
    }
}
