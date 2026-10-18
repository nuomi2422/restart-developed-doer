package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;
import com.dwinovo.numen.settlement.core.model.Verdict;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登记一处设施（以同伴当前站位为中心的正方体范围）。
 *
 * <p>默认在范围四壁记 STRUCTURE 保护区、南侧留一个 SPACE 出入口，内部记为作业区——
 * 这样后续保护/验收都能立刻有据可依。{@code protect=false} 则只登记范围不设保护。
 */
public final class SettlementRegisterTool implements NumenTool {

    private final SettlementService service;

    public SettlementRegisterTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_register"; }

    @Override public String description() {
        return "登记一处『基地设施』并可跨任务/重启复用。用于把选中的村屋、"
                + "羊圈、农田、交易所或箱子区记成自家设施。id 是固定标识（小写字母数字-_，≤64），"
                + "重复调用同一个 id 会覆盖登记。默认以你当前站位为中心；也可给 cx/cy/cz 指定中心"
                + "（登记不在脚下的整片建筑时用）。radius 是范围半径（正方体，默认 5）。"
                + "protect=true（默认）会在四壁记结构保护、南侧留出入口，内部留为作业区。"
                + "登记不等于建好：结构可用/生产条件请用 settlement_verify 另验。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "设施固定标识，如 pasture1 / farm_north")
                .enumStr("kind", "设施用途", "HOUSE", "STORAGE", "PASTURE_SHEEP",
                        "PASTURE_COW", "FARM", "TRADE", "GENERIC")
                .optionalInteger("radius", "范围半径（以中心的正方体），默认 5", 1, 32)
                .optionalInteger("cx", "设施中心 x（不给则用你当前站位）", -30000000, 30000000)
                .optionalInteger("cy", "设施中心 y（不给则用你当前站位）", -64, 320)
                .optionalInteger("cz", "设施中心 z（不给则用你当前站位）", -30000000, 30000000)
                .optionalBool("protect", "是否记四壁结构保护 + 南侧出入口，默认 true")
                .optionalBool("protect_full", "是否把整个范围都设为结构保护（如保险库/箱子房），默认 false。与 protect 同开时以整块为准")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_register: not on a server level").toJson());
                return;
            }
            if (!args.has("id") || !args.has("kind")) {
                reply.accept(TaskResult.fail("settlement_register needs 'id' and 'kind'").toJson());
                return;
            }
            String id = args.get("id").getAsString().toLowerCase(Locale.ROOT).trim();
            FacilityKind kind;
            try {
                kind = FacilityKind.valueOf(args.get("kind").getAsString().toUpperCase(Locale.ROOT).trim());
            } catch (IllegalArgumentException bad) {
                reply.accept(TaskResult.fail("settlement_register: unknown kind").toJson());
                return;
            }
            int radius = args.has("radius") ? args.get("radius").getAsInt() : 5;
            boolean protect = !args.has("protect") || args.get("protect").getAsBoolean();
            boolean protectFull = args.has("protect_full") && args.get("protect_full").getAsBoolean();

            ServerLevel level = self.serverLevel();
            BlockPos center = (args.has("cx") && args.has("cy") && args.has("cz"))
                    ? new BlockPos(args.get("cx").getAsInt(), args.get("cy").getAsInt(), args.get("cz").getAsInt())
                    : self.blockPosition();
            String dimension = level.dimension().location().toString();
            BlockBox bounds = BlockBox.of(
                    center.getX() - radius, center.getY() - 1, center.getZ() - radius,
                    center.getX() + radius, center.getY() + radius, center.getZ() + radius);

            DimAnchor inside = DimAnchor.of(dimension, center.getX(), center.getY(), center.getZ());
            DimAnchor outside = DimAnchor.of(dimension, center.getX(), center.getY(), center.getZ() + 1);

            List<ProtectionZone> zones = !protect ? List.of()
                    : protectFull ? List.of(ProtectionZone.structure(bounds))
                    : ringZones(bounds, dimension, center);
            List<BlockBox> workZones = List.of(bounds.expand(-1, 0, -1));

            FacilityRecord record = new FacilityRecord(
                    id, self.getUUID().toString(), "default", dimension, kind, bounds, 0,
                    null, null, null, zones, workZones, outside, inside,
                    List.of(inside), List.of(), List.of(), Map.of(),
                    ConstructionProgress.unknown(), Verdict.UNKNOWN, ProductionState.UNKNOWN,
                    System.currentTimeMillis(), "registered in-game by settlement_register");
            service.register(record);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("kind", kind.name());
            data.put("dimension", dimension);
            data.put("bounds", bounds.minX() + "," + bounds.minY() + "," + bounds.minZ()
                    + " .. " + bounds.maxX() + "," + bounds.maxY() + "," + bounds.maxZ());
            data.put("protection_zones", zones.size());
            data.put("store", service.file().toString());
            reply.accept(TaskResult.ok("registered facility '" + id + "' (" + kind.name() + ")", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_register error: " + ex.getMessage()).toJson());
        }
    }

    /** 四壁 STRUCTURE（南墙在入口列留缺口）+ 南侧入口 SPACE。墙高 2，footprint 为 1 格。 */
    private static List<ProtectionZone> ringZones(BlockBox b, String dimension, BlockPos entrance) {
        int y0 = b.minY() + 1;
        int y1 = b.minY() + 2;
        List<ProtectionZone> zones = new ArrayList<>();
        zones.add(ProtectionZone.structure(BlockBox.of(b.minX(), y0, b.minZ(), b.maxX(), y1, b.minZ())));
        zones.add(ProtectionZone.structure(BlockBox.of(b.minX(), y0, b.minZ(), b.minX(), y1, b.maxZ())));
        zones.add(ProtectionZone.structure(BlockBox.of(b.maxX(), y0, b.minZ(), b.maxX(), y1, b.maxZ())));
        // 南墙在入口列挖口：通道不能既算结构（禁拆）又算通道（禁堵），否则入口永远打不开。
        int gateX = entrance.getX();
        if (gateX - 1 >= b.minX()) {
            zones.add(ProtectionZone.structure(BlockBox.of(b.minX(), y0, b.maxZ(), gateX - 1, y1, b.maxZ())));
        }
        if (gateX + 1 <= b.maxX()) {
            zones.add(ProtectionZone.structure(BlockBox.of(gateX + 1, y0, b.maxZ(), b.maxX(), y1, b.maxZ())));
        }
        zones.add(ProtectionZone.space(BlockBox.of(gateX, y0, b.maxZ(), gateX, y1, b.maxZ())));
        return zones;
    }
}
