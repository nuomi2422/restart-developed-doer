package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.protect.Decision;
import com.dwinovo.numen.settlement.core.protect.ProtectionRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 位置级保护落到执行层：在方块<b>真被改动之前</b>拦下同伴的动作。
 *
 * <p>用 NeoForge 的 {@link BlockEvent.BreakEvent}/{@link BlockEvent.EntityPlaceEvent}——
 * 它们正是各自动作的取消点，覆盖同伴的挖掘、建造拆除、桶操作等玩家驱动路径。
 * <b>只拦同伴（{@link NumenPlayer}），不拦主人</b>：真人拆自家基地是自己的事。
 *
 * <p>裁决复用 {@link ProtectionRules}，与规划/登记那份规则同源：STRUCTURE 区禁改动，
 * SPACE 区只禁"往通道里放挡路方块"，作业区不设硬禁。临时授权（{@code Grant}）本步还没接，
 * 所以同伴暂时也无法改动已登记设施的结构——这是已知的下一步。
 *
 * <p>每次拒绝都记一条日志（`[settlement] blocked ...`）——静默失效是踩过的坑，
 * 被拦时必须有账可查。
 */
public final class SettlementProtectionListener {

    private static final Logger LOG = LoggerFactory.getLogger("settlement");

    private final SettlementService service;

    public SettlementProtectionListener(SettlementService service) {
        this.service = service;
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof NumenPlayer)) return;
        String dimension = dimensionOf(event.getLevel());
        if (dimension == null) return;
        BlockPos pos = event.getPos();
        FacilityRegistry registry = service.registry();
        Decision decision = ProtectionRules.decideBreak(registry, dimension,
                pos.getX(), pos.getY(), pos.getZ(), service.activeGrants(), System.currentTimeMillis());
        if (decision.denied()) {
            event.setCanceled(true);
            LOG.info("[settlement] blocked BREAK at {} {} (protected facility zone)",
                    dimension, pos.toShortString());
        }
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof NumenPlayer)) return;
        String dimension = dimensionOf(event.getLevel());
        if (dimension == null) return;
        BlockPos pos = event.getPos();
        BlockState state = event.getPlacedBlock();
        boolean obstructs = !state.getCollisionShape(event.getLevel(), pos, CollisionContext.empty()).isEmpty();
        Decision decision = ProtectionRules.decidePlaceObstruction(service.registry(), dimension,
                pos.getX(), pos.getY(), pos.getZ(), obstructs, service.activeGrants(), System.currentTimeMillis());
        if (decision.denied()) {
            event.setCanceled(true);
            LOG.info("[settlement] blocked PLACE at {} {} (protected passage/structure)",
                    dimension, pos.toShortString());
        }
    }

    private static String dimensionOf(LevelAccessor level) {
        if (level instanceof ServerLevel serverLevel) return serverLevel.dimension().location().toString();
        if (level instanceof Level genericLevel) return genericLevel.dimension().location().toString();
        return null;
    }
}
