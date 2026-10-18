package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 登记一处设施里箱子/容器的物品：扫其占地内的容器方块，按物品汇总数量写进登记。
 *
 * <p>只读不搬（不取物、不改箱）；登记的是<b>快照</b>——物品会动，所以要重记就再扫一次。
 * 区块未加载时明确失败（不拿空当"没有"）。
 */
public final class SettlementScanContainersTool implements NumenTool {

    /** 结果里最多回多少种物品。 */
    private static final int MAX_TYPES = 40;

    private final SettlementService service;

    public SettlementScanContainersTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_scan_containers"; }

    @Override public String description() {
        return "登记一处已登记设施里箱子/容器的物品：扫占地内的容器，按物品汇总写进设施登记"
                + "（只读不搬，登记快照；物品变了就再扫一次）。区块未加载会明确失败。"
                + "用 settlement_list / settlement_verify 可看到登记的物品。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "设施 id")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (!args.has("id")) {
                reply.accept(TaskResult.fail("settlement_scan_containers needs 'id'").toJson());
                return;
            }
            String id = args.get("id").getAsString().toLowerCase(java.util.Locale.ROOT).trim();
            Optional<FacilityRecord> found = service.byId(id);
            if (found.isEmpty()) {
                reply.accept(TaskResult.fail("settlement_scan_containers: no facility '" + id + "'").toJson());
                return;
            }
            if (self == null || self.getServer() == null) {
                reply.accept(TaskResult.fail("settlement_scan_containers: no server").toJson());
                return;
            }
            FacilityRecord facility = found.get();
            ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse(facility.dimension()));
            ServerLevel level = self.getServer().getLevel(key);
            if (level == null) {
                reply.accept(TaskResult.fail("settlement_scan_containers: dimension not loaded").toJson());
                return;
            }
            BlockBox b = facility.bounds();
            if (!level.hasChunkAt(new BlockPos(b.minX(), b.minY(), b.minZ()))) {
                reply.accept(TaskResult.fail("settlement_scan_containers: chunk not loaded at facility; go there first").toJson());
                return;
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            int containers = 0;
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        var be = level.getBlockEntity(pos);
                        if (!(be instanceof Container container)) continue;
                        containers++;
                        for (int i = 0; i < container.getContainerSize(); i++) {
                            ItemStack stack = container.getItem(i);
                            if (stack.isEmpty()) continue;
                            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                            counts.merge(itemId, stack.getCount(), Integer::sum);
                        }
                    }
                }
            }
            service.register(facility.withStoredItems(counts));

            Map<String, Object> summary = new LinkedHashMap<>();
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(MAX_TYPES)
                    .forEach(e -> summary.put(e.getKey(), e.getValue()));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("containers", containers);
            data.put("item_types", counts.size());
            data.put("items", summary);
            reply.accept(TaskResult.ok("scanned " + containers + " container(s) in '" + id
                    + "': " + counts.size() + " item type(s)", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_scan_containers error: " + ex.getMessage()).toJson());
        }
    }
}
