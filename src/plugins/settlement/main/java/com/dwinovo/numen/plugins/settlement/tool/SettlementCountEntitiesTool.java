package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 数一个方块盒内的实体。缺了这个，AC 没法判"圈内已有≥2 只羊"这类区域条件
 * （{@code scan_nearby_entities} 只能按半径、不给区域计数）。
 *
 * <p>{@code type_id} 给命名空间实体 id 就只数该种；不给则按 {@code category}
 * （passive=动物 / all=全部）；区块未加载时明确失败，不拿 0 冒充"没有"。
 */
public final class SettlementCountEntitiesTool implements NumenTool {

    public SettlementCountEntitiesTool() {}

    @Override public String name() { return "count_entities_in_box"; }

    @Override public String description() {
        return "数一个长方体区域内的实体数量。传对角两点的 x1/y1/z1 与 x2/y2/z2；可选 type_id"
                + "（如 minecraft:sheep）只数该种，或 category=passive 数动物 / all 数全部。"
                + "用于判定『圈内已有几只羊』这类区域条件。区块未加载时明确失败。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x1", "盒角1 x", -30000000, 30000000)
                .integer("y1", "盒角1 y", -64, 320)
                .integer("z1", "盒角1 z", -30000000, 30000000)
                .integer("x2", "盒角2 x", -30000000, 30000000)
                .integer("y2", "盒角2 y", -64, 320)
                .integer("z2", "盒角2 z", -30000000, 30000000)
                .optionalString("type_id", "只数该实体种，如 minecraft:sheep（不给则按 category）")
                .optionalEnum("category", "passive=动物 / all=全部（默认 all）", "passive", "all")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("count_entities_in_box: not on a server level").toJson());
                return;
            }
            if (!args.has("x1") || !args.has("y1") || !args.has("z1")
                    || !args.has("x2") || !args.has("y2") || !args.has("z2")) {
                reply.accept(TaskResult.fail("count_entities_in_box needs x1/y1/z1/x2/y2/z2").toJson());
                return;
            }
            int ax = args.get("x1").getAsInt(), ay = args.get("y1").getAsInt(), az = args.get("z1").getAsInt();
            int bx = args.get("x2").getAsInt(), by = args.get("y2").getAsInt(), bz = args.get("z2").getAsInt();
            String typeId = args.has("type_id") && !args.get("type_id").isJsonNull()
                    ? args.get("type_id").getAsString().trim() : null;
            String category = args.has("category") ? args.get("category").getAsString() : "all";

            ServerLevel level = self.serverLevel();
            if (!level.hasChunkAt(new BlockPos(ax, ay, az))) {
                reply.accept(TaskResult.fail("count_entities_in_box: chunk not loaded at " + ax + "," + ay + "," + az).toJson());
                return;
            }
            AABB box = new AABB(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                    Math.max(ax, bx) + 1.0, Math.max(ay, by) + 1.0, Math.max(az, bz) + 1.0);
            int count = 0;
            for (Entity e : level.getEntities((Entity) null, box, ignored -> true)) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (typeId != null && !typeId.equals(id)) continue;
                if (typeId == null && "passive".equals(category) && !(e instanceof Animal)) continue;
                count++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", count);
            data.put("type_id", typeId == null ? ("(category=" + category + ")") : typeId);
            data.put("box", ax + "," + ay + "," + az + " .. " + bx + "," + by + "," + bz);
            reply.accept(TaskResult.ok(count + " entit(ies) in box", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("count_entities_in_box error: " + ex.getMessage()).toJson());
        }
    }
}
