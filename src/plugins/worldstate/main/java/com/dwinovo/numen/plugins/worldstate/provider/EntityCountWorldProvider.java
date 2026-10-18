package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

/**
 * 有界实体计数采集器：范围内的动物/实体计数。<b>只读、不强制加载区块</b>。
 *
 * <h2>三种结局必须分清</h2>
 * <ul>
 *   <li>全部列都已加载 → {@link Coverage#COMPLETE}，此时 {@code count=0} 才是"确认没有"。</li>
 *   <li>部分列未加载 → {@link Coverage#PARTIAL}，观察到的是<b>下限</b>，不能当总数。</li>
 *   <li>一列都没加载 → 抛 {@link FailReason#CHUNK_UNLOADED}，明确失败，<b>不拿 0 冒充</b>。</li>
 * </ul>
 *
 * <p>手里已经有羊圈/牛圈，这个采集器直接服务它们，用来验证世界观测架构不是空架子。
 */
public final class EntityCountWorldProvider implements WorldProvider {

    /** 单次请求的最大体积（方块数）；超了让调用方缩小范围，不在这里硬扫。 */
    private static final long MAX_VOLUME = 32768L;

    @Override public Topic topic() { return Topic.ENTITIES; }

    @Override public String id() { return "entities"; }

    @Override public int dataVersion() { return 1; }

    @Override public long maxVolume() { return MAX_VOLUME; }

    @Override
    public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
        McCollectionContext mc = McCollectionContexts.require(ctx);
        Bounds box = request.bounds();
        if (box == null) {
            throw new ProviderException(FailReason.INVALID_REQUEST, "entities request needs bounds");
        }
        ServerLevel level = mc.levelOf(request.dimension());
        if (level == null) {
            throw new ProviderException(FailReason.DIMENSION_MISSING,
                    "dimension not loaded: " + request.dimension());
        }

        // 覆盖估算：逐列看区块加载状态，不触发加载。锚点 y 取盒底即可（列加载与 y 无关）。
        int scanned = 0;
        int skipped = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                cursor.set(x, box.minY(), z);
                if (level.hasChunkAt(cursor)) scanned++;
                else skipped++;
            }
        }
        if (scanned == 0) {
            throw new ProviderException(FailReason.CHUNK_UNLOADED,
                    "no loaded chunk in " + box.canonical() + "; go there first");
        }

        String[] filter = parseFilter(request.filter());
        String entityType = filter[0];
        String category = filter[1];

        AABB area = new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0);
        int count = 0;
        for (Entity entity : level.getEntities((Entity) null, area, ignored -> true)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (entityType != null) {
                if (!entityType.equals(id)) continue;
            } else if ("passive".equals(category) && !(entity instanceof Animal)) {
                continue;
            }
            count++;
        }

        Coverage coverage = skipped == 0 ? Coverage.COMPLETE : Coverage.PARTIAL;
        EntityCountPayload payload = new EntityCountPayload(request.dimension(), box, entityType,
                entityType == null ? category : null, count, scanned, skipped);
        return ProviderResult.of(payload, coverage);
    }

    /** 解析 {@code type=...} / {@code category=...}，返回 [entityType, category]。 */
    static String[] parseFilter(String filter) {
        String f = filter == null ? "" : filter.trim();
        if (f.startsWith("type=")) {
            return new String[]{f.substring("type=".length()), null};
        }
        if (f.startsWith("category=")) {
            return new String[]{null, f.substring("category=".length())};
        }
        return new String[]{null, "all"};
    }
}
