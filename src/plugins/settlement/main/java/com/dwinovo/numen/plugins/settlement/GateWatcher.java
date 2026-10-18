package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.settlement.core.accept.EntityView;
import com.dwinovo.numen.settlement.core.logic.GateKeeper;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 过门自动关门：把同伴在<b>自家设施</b>里走过的、敞着的栅栏门关回去。
 *
 * <p>为什么需要（实测 2026-10-09）：寻路器开门过路用的是原生右键，走过去之后没有
 * 任何东西负责关；新建的羊圈门在同伴穿过后一直是 {@code open=true}，而一个敞着的
 * 门等于圈没围——羊会走出去，"引羊进圈"从根上不成立。
 *
 * <p>安全约束（与 {@link GateKeeper} 同一份策略，这里只做宿主侧取数）：
 * <ul>
 *   <li>只关门、不开门；</li>
 *   <li>门洞里还有实体（她/动物）就不关——关了会把方块塞进身体里；</li>
 *   <li>区块未加载不动手。</li>
 * </ul>
 *
 * <p>扫描范围限定在<b>已登记设施的保护区</b>内：既省开销，也避免她去动别人家的门。
 */
public final class GateWatcher {

    private static final Logger LOG = LoggerFactory.getLogger("settlement");

    /** 每扇门两次尝试之间的最小间隔（刻）。关门是低频动作，不必每 tick 找。 */
    private static final int RETRY_INTERVAL_TICKS = 20;
    /** 同一扇门最多连续尝试几次——防"关了又被人打开"时无限刷日志。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 正在处理的门的重试账：pos.asLong() → 已尝试次数。 */
    private final ConcurrentHashMap<Long, Integer> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> nextTryTick = new ConcurrentHashMap<>();

    private long lastSweepTick = -1;

    /**
     * 每 tick 调一次（宿主在服务端 tick 里）。开销：只在有"刚被同伴动过的门"时才扫描。
     *
     * @return 这一 tick 关掉了几扇门
     */
    public int tick(MinecraftServer server, SettlementService service, long gameTime) {
        if (server == null || service == null) return 0;
        if (gameTime - lastSweepTick < RETRY_INTERVAL_TICKS) return 0;
        lastSweepTick = gameTime;

        int closed = 0;
        for (FacilityRecord facility : service.list()) {
            FacilityKind kind = facility.kind();
            // ★ 牧场/居住的门要关（防动物跑出）；交易所的门【不能自动关】——
            //   用户 2026-10-09 的玩法是"村民夜里自己找床走进来"，门一关村民就进不来了。
            //   （关门时机留给后续"交易站收工"类任务显式做，不在这里自动拍板。）
            if (kind != FacilityKind.PASTURE_SHEEP && kind != FacilityKind.PASTURE_COW
                    && kind != FacilityKind.HOUSE) {
                continue;
            }
            ServerLevel level = levelOf(server, facility.dimension());
            if (level == null) continue;
            closed += sweepFacility(level, facility, gameTime);
        }
        return closed;
    }

    /** 扫一座设施的占地范围，把敞着的门关回去。 */
    private int sweepFacility(ServerLevel level, FacilityRecord facility, long gameTime) {
        // ★ 扫<b>整个设施占地</b>，不是只扫 SPACE（通道）区。
        //   2026-10-09 实机踩到：手工用 settlement_register 登记的设施（如旧 sheep_pen）
        //   只有一个 STRUCTURE 区、没有 SPACE 区，于是它的栅栏门永远不被扫到、一直敞着——
        //   而"门是不是敞着"与登记时怎么划区无关，门就是门。
        //   占地盒有界（设施都是几格到几十格），开销可接受。
        BlockBox box = facility.bounds();
        int closed = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    closed += maybeClose(level, x, y, z, gameTime);
                }
            }
        }
        return closed;
    }

    private int maybeClose(ServerLevel level, int x, int y, int z, long gameTime) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.hasChunkAt(pos)) {
            return 0;   // 未加载不动手
        }
        BlockState state = level.getBlockState(pos);
        boolean isGate = state.getBlock() instanceof FenceGateBlock;
        boolean open = isGate && state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
        if (!isGate || !open) {
            attempts.remove(pos.asLong());
            nextTryTick.remove(pos.asLong());
            return 0;
        }

        long key = pos.asLong();
        Long next = nextTryTick.get(key);
        if (next != null && gameTime < next) {
            return 0;
        }
        int tries = attempts.getOrDefault(key, 0);
        if (tries >= MAX_ATTEMPTS) {
            // 反复被打开：留一次日志说明，然后放手（别无限刷）。
            if (tries == MAX_ATTEMPTS) {
                LOG.info("[settlement] gate at {} stays open after {} attempts; leaving it alone",
                        pos.toShortString(), tries);
                attempts.put(key, tries + 1);
            }
            return 0;
        }

        boolean occupied = GateKeeper.doorwayOccupied(nearbyEntities(level, pos), x, y, z);
        GateKeeper.Decision decision = GateKeeper.decide(true, isGate, open, occupied);
        if (!decision.close()) {
            nextTryTick.put(key, gameTime + RETRY_INTERVAL_TICKS);
            return 0;
        }

        BlockState shut = state.setValue(BlockStateProperties.OPEN, false);
        level.setBlock(pos, shut, Block.UPDATE_ALL);
        attempts.remove(key);
        nextTryTick.remove(key);
        LOG.info("[settlement] auto-closed gate at {} ({})", pos.toShortString(), decision.reason());
        return 1;
    }

    /** 门洞附近（门格 + 正上方一格）的实体投影。 */
    private static List<EntityView> nearbyEntities(ServerLevel level, BlockPos gate) {
        BlockBox b = GateKeeper.doorwayBox(gate.getX(), gate.getY(), gate.getZ());
        AABB area = new AABB(b.minX() - 1.0, b.minY() - 1.0, b.minZ() - 1.0,
                b.maxX() + 2.0, b.maxY() + 2.0, b.maxZ() + 2.0);
        List<EntityView> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, area, ignored -> true)) {
            out.add(new EntityView(
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                    e.getX(), e.getY(), e.getZ()));
        }
        return out;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        if (dimension == null) return null;
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 当前重试账大小（巡检/测试用）。 */
    public int pendingRetries() {
        return attempts.size();
    }

    /** 清空重试账（世界切换/重启时）。 */
    public void clear() {
        attempts.clear();
        nextTryTick.clear();
        lastSweepTick = -1;
    }
}
