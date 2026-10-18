package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 登记基地基准网格：把一整块整平地皮定为基地的固定基准（原点/朝向/每格边长/行列数），
 * 之后 {@code settlement_map} 默认就按它出图，设施按中心落格。
 */
public final class SettlementBaseTool implements NumenTool {

    private final SettlementService service;

    public SettlementBaseTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_base"; }

    @Override public String description() {
        return "登记/查看基地的基准网格。action=set：用 origin_x/origin_z（网格最小角，minX/minZ）、"
                + "floor_y、cols/rows（列/行数）、cell_size（每格边长，默认 5）登记为一整块基地基准；"
                + "不给 origin 则用你当前站位推。action=show：查看已登记的基准。"
                + "登记后 settlement_map 默认按它出图。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "set=登记 / show=查看", "set", "show")
                .optionalInteger("origin_x", "网格最小角 minX（不给则用你站位附近）", -30000000, 30000000)
                .optionalInteger("origin_z", "网格最小角 minZ", -30000000, 30000000)
                .optionalInteger("floor_y", "基准平面 Y（不给则用你脚下）", -64, 320)
                .optionalInteger("cols", "列数，默认 5", 1, 26)
                .optionalInteger("rows", "行数，默认 5", 1, 26)
                .optionalInteger("cell_size", "每格边长，默认 5", 1, 16)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            String action = args.has("action") ? args.get("action").getAsString() : "show";
            if ("show".equals(action)) {
                Optional<SettlementService.BaseGrid> b = service.base();
                if (b.isEmpty()) {
                    reply.accept(TaskResult.ok("no base grid registered yet").toJson());
                    return;
                }
                SettlementService.BaseGrid g = b.get();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("dimension", g.dimension());
                data.put("origin", g.originX() + "," + g.floorY() + "," + g.originZ());
                data.put("cols", g.cols());
                data.put("rows", g.rows());
                data.put("cell_size", g.cellSize());
                reply.accept(TaskResult.ok("base grid: origin=(" + g.originX() + "," + g.floorY() + ","
                        + g.originZ() + ") " + g.cols() + "x" + g.rows() + " cell=" + g.cellSize(), data).toJson());
                return;
            }
            if (!"set".equals(action)) {
                reply.accept(TaskResult.fail("settlement_base: action must be set or show").toJson());
                return;
            }
            int cell = args.has("cell_size") ? clamp(args.get("cell_size").getAsInt(), 1, 16) : 5;
            int cols = args.has("cols") ? clamp(args.get("cols").getAsInt(), 1, 26) : 5;
            int rows = args.has("rows") ? clamp(args.get("rows").getAsInt(), 1, 26) : 5;
            int floorY = args.has("floor_y") ? args.get("floor_y").getAsInt() : self.blockPosition().getY() - 1;
            int originX = args.has("origin_x") ? args.get("origin_x").getAsInt()
                    : Math.floorDiv(self.blockPosition().getX(), cell) * cell;
            int originZ = args.has("origin_z") ? args.get("origin_z").getAsInt()
                    : Math.floorDiv(self.blockPosition().getZ(), cell) * cell;
            String dimension = self.serverLevel().dimension().location().toString();

            SettlementService.BaseGrid grid = new SettlementService.BaseGrid(
                    dimension, originX, floorY, originZ, cols, rows, cell);
            service.setBase(grid);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dimension", dimension);
            data.put("origin", originX + "," + floorY + "," + originZ);
            data.put("cols", cols);
            data.put("rows", rows);
            data.put("cell_size", cell);
            reply.accept(TaskResult.ok("base grid registered: origin=(" + originX + "," + floorY + "," + originZ
                    + ") " + cols + "x" + rows + " cell=" + cell, data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_base error: " + ex.getMessage()).toJson());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
