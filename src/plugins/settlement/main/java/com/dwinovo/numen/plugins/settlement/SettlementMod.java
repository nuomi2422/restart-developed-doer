package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.protect.GrantLedger;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@Mod("settlement")
public final class SettlementMod {

    private static final Logger LOG = LoggerFactory.getLogger("settlement");
    private static volatile SettlementService SERVICE;
    private final GateWatcher gateWatcher = new GateWatcher();

    public SettlementMod() {
        NumenPlugins.register(new SettlementPlugin());
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    static void bindService(SettlementService service) {
        SERVICE = service;
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        SettlementService service = SERVICE;
        if (server == null || service == null) return;

        long now = System.currentTimeMillis();
        if (service.gatesHoldUntilMs() > 0 && now > service.gatesHoldUntilMs()) {
            LOG.info("[settlement] Sheep entry pause ended, restore auto gate close");
            service.setGatesHeld(0);
        }

        if (!service.list().isEmpty()) {
            try {
                gateWatcher.tick(server, service, server.getTickCount());
            } catch (Throwable t) {
                LOG.warn("[settlement] gate watcher failed: {}", t.toString());
            }
        }

        GrantLedger ledger = service.grantLedger();
        if (ledger.size() == 0) return;

        try {
            for (GrantLedger.OpenGrant open : ledger.openGrants()) {
                String facilityId = open.facilityId();
                FacilityRecord facility = service.byId(facilityId).orElse(null);
                if (facility == null) {
                    ledger.release(facilityId);
                    continue;
                }
                NumenPlayer body = bodyOf(server, facility.ownerId());
                if (body == null) {
                    service.sweepGrants(null);
                    continue;
                }
                TaskRecord current = CompanionTickDispatcher.currentTaskFor(body.getUUID());
                List<String> released = service.sweepGrants(
                        current == null ? null : current.publicId());
                if (!released.isEmpty()) {
                    LOG.info("[settlement] reclaimed construction grants for {} (task now {})",
                            released, current == null ? "none" : current.publicId());
                }
            }
        } catch (Throwable t) {
            LOG.warn("[settlement] grant sweep failed: {}", t.toString());
        }
    }

    private static NumenPlayer bodyOf(MinecraftServer server, String ownerId) {
        try {
            UUID owner = UUID.fromString(ownerId);
            net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(owner);
            return p instanceof NumenPlayer n ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
