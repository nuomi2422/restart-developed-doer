package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.logic.PlatformMath;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 第一版基地网格地图：把己方设施投影成 {@code 5×5} 网格上的文本地图，并按格/按设施查详情。
 *
 * <p>约定：固定原点 + 固定格边长（默认 5）+ 行（北→南，1 起）列（西→东，A 起）编号；
 * 设施按<b>占地覆盖的格</b>落在图上（可占多格）。地图只是登记真源的只读投影。
 */
public final class SettlementMapTool implements NumenTool {

    private static final int DEFAULT_COLS = 10;
    private static final int DEFAULT_ROWS = 10;
    private static final int DEFAULT_CELL = 5;

    private final SettlementService service;

    public SettlementMapTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_map"; }

    @Override public String description() {
        return "看己方基地的网格地图（每格默认 5×5，行列编号，北↑），或按设施 id / 格子（如 A1）查详情。"
                + "用于回答『羊圈在哪格』『入口在哪』『哪个箱子区』。"
                + "给 id 或 cell 则出该设施/格的详情，否则出整张地图。"
                + "可选 anchor_x/anchor_z 指定网格原点，cols/rows/cell_size 调尺寸。只读。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("id", "查某设施的详情（与 cell 二选一）")
                .optionalString("cell", "查某格详情，如 A1（列字母+行号）")
                .optionalInteger("anchor_x", "网格原点 minX（不给则按设施范围推断）", -30000000, 30000000)
                .optionalInteger("anchor_z", "网格原点 minZ（不给则按设施范围推断）", -30000000, 30000000)
                .optionalInteger("cols", "列数，默认 10", 1, 26)
                .optionalInteger("rows", "行数，默认 10", 1, 26)
                .optionalInteger("cell_size", "每格边长，默认 5", 1, 16)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null) {
                reply.accept(TaskResult.fail("settlement_map: no companion").toJson());
                return;
            }
            List<FacilityRecord> mine = owned(self);
            SettlementService.BaseGrid g = service.base().orElse(null);
            String dimension = g != null ? g.dimension()
                    : (mine.isEmpty() ? self.serverLevel().dimension().location().toString() : mine.get(0).dimension());

            int cell = args.has("cell_size") ? clamp(args.get("cell_size").getAsInt(), 1, 16)
                    : (g != null ? g.cellSize() : DEFAULT_CELL);
            int cols = args.has("cols") ? clamp(args.get("cols").getAsInt(), 1, 26)
                    : (g != null ? g.cols() : DEFAULT_COLS);
            int rows = args.has("rows") ? clamp(args.get("rows").getAsInt(), 1, 26)
                    : (g != null ? g.rows() : DEFAULT_ROWS);

            // 原点：显式优先，其次已登记的基地基准，最后按设施范围推断。
            int ax = args.has("anchor_x") ? args.get("anchor_x").getAsInt()
                    : (g != null ? g.originX() : autoAnchorX(mine, cell));
            int az = args.has("anchor_z") ? args.get("anchor_z").getAsInt()
                    : (g != null ? g.originZ() : autoAnchorZ(mine, cell));

            PlatformPlan plan = new PlatformPlan("base", dimension, ax, 0, az,
                    cols, rows, cell, 0, 4, List.of(), List.of());

            // 每格 -> 占用它的设施（首字母）。
            Map<CellKey, FacilityRecord> occupied = new LinkedHashMap<>();
            Map<String, FacilityRecord> letterToFacility = new LinkedHashMap<>();
            char[] letters = "abcdefghijklmnopqrstuvwxyz".toCharArray();
            int li = 0;
            for (FacilityRecord f : mine) {
                String letter = String.valueOf(letters[li % 26]);
                boolean placed = false;
                for (CellKey c : cellsOf(f, plan)) {
                    if (occupied.putIfAbsent(c, f) == null) placed = true;
                }
                if (placed) {
                    letterToFacility.put(letter, f);
                    li++;
                }
            }

            if (args.has("id")) {
                String id = args.get("id").getAsString().toLowerCase(java.util.Locale.ROOT).trim();
                Optional<FacilityRecord> f = service.byId(id);
                if (f.isEmpty()) {
                    reply.accept(TaskResult.fail("settlement_map: no facility '" + id + "'").toJson());
                    return;
                }
                reply.accept(TaskResult.ok(detail(f.get(), plan), Map.of("id", id)).toJson());
                return;
            }
            if (args.has("cell")) {
                CellKey c = parseCell(args.get("cell").getAsString());
                if (c == null) {
                    reply.accept(TaskResult.fail("settlement_map: bad cell (want like A1)").toJson());
                    return;
                }
                FacilityRecord f = occupied.get(c);
                String msg = (cellName(c) + " → " + (f == null ? "未分配" : f.id() + " (" + f.kind().name() + ")"))
                        + "\n" + (f == null ? "" : detail(f, plan));
                reply.accept(TaskResult.ok(msg, Map.of("cell", cellName(c))).toJson());
                return;
            }

            // 整张地图
            StringBuilder sb = new StringBuilder();
            sb.append("基地：").append(dimension).append("  原点=(").append(ax).append(',').append(az).append(')')
                    .append("  每格 ").append(cell).append('×').append(cell).append("  北↑\n     ");
            for (int cx = 0; cx < cols; cx++) {
                sb.append((char) ('A' + cx)).append("  ");
            }
            sb.append('\n');
            for (int cz = 0; cz < rows; cz++) {
                sb.append(String.format("%4d ", cz + 1));
                for (int cx = 0; cx < cols; cx++) {
                    FacilityRecord f = occupied.get(CellKey.of(cx, cz));
                    sb.append(f == null ? '.' : letterOf(letterToFacility, f)).append("  ");
                }
                sb.append('\n');
            }
            sb.append('\n');
            if (letterToFacility.isEmpty()) {
                sb.append("（本区域暂无己方设施）");
            } else {
                for (Map.Entry<String, FacilityRecord> e : letterToFacility.entrySet()) {
                    FacilityRecord f = e.getValue();
                    sb.append(e.getKey()).append("：").append(f.id())
                            .append("  ").append(f.kind().name())
                            .append("  结构=").append(f.structureVerdict())
                            .append("  生产=").append(f.productionState());
                    if (!f.storedItems().isEmpty()) sb.append("  物品种类=").append(f.storedItems().size());
                    sb.append('\n');
                }
            }
            reply.accept(TaskResult.ok(sb.toString(), Map.of("cols", cols, "rows", rows, "cell", cell)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_map error: " + ex.getMessage()).toJson());
        }
    }

    private List<FacilityRecord> owned(NumenPlayer self) {
        String owner = self.getUUID().toString();
        List<FacilityRecord> out = new ArrayList<>();
        for (FacilityRecord f : service.list()) {
            if (owner.equals(f.ownerId())) out.add(f);
        }
        return out;
    }

    private static int autoAnchorX(List<FacilityRecord> mine, int cell) {
        int min = Integer.MAX_VALUE;
        for (FacilityRecord f : mine) min = Math.min(min, f.bounds().minX());
        return min == Integer.MAX_VALUE ? 0 : Math.floorDiv(min, cell) * cell;
    }

    private static int autoAnchorZ(List<FacilityRecord> mine, int cell) {
        int min = Integer.MAX_VALUE;
        for (FacilityRecord f : mine) min = Math.min(min, f.bounds().minZ());
        return min == Integer.MAX_VALUE ? 0 : Math.floorDiv(min, cell) * cell;
    }

    /** 设施占地覆盖的格（把 bounds 的 x/z 逐列逐行映射回格）。 */
    private static Set<CellKey> cellsOf(FacilityRecord f, PlatformPlan plan) {
        Set<CellKey> cells = new LinkedHashSet<>();
        BlockBox b = f.bounds();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                PlatformMath.cellAt(plan, x, z).ifPresent(cells::add);
            }
        }
        return cells;
    }

    private static String letterOf(Map<String, FacilityRecord> letters, FacilityRecord f) {
        for (Map.Entry<String, FacilityRecord> e : letters.entrySet()) {
            if (e.getValue().id().equals(f.id())) return e.getKey();
        }
        return "?";
    }

    private static String detail(FacilityRecord f, PlatformPlan plan) {
        Set<CellKey> cells = cellsOf(f, plan);
        StringBuilder cellsTxt = new StringBuilder();
        for (CellKey c : cells) {
            if (cellsTxt.length() > 0) cellsTxt.append(',');
            cellsTxt.append(cellName(c));
        }
        BlockBox b = f.bounds();
        StringBuilder sb = new StringBuilder();
        sb.append("设施 ").append(f.id()).append(" (").append(f.kind().name()).append(")\n");
        sb.append("维度=").append(f.dimension())
                .append(" 范围=").append(b.minX()).append(',').append(b.minY()).append(',').append(b.minZ())
                .append(" .. ").append(b.maxX()).append(',').append(b.maxY()).append(',').append(b.maxZ()).append('\n');
        sb.append("格子=").append(cellsTxt.length() == 0 ? "(不在当前网格内)" : cellsTxt).append('\n');
        sb.append("结构=").append(f.structureVerdict())
                .append("  生产=").append(f.productionState())
                .append("  保护区=").append(f.zones().size()).append('\n');
        if (f.entranceInside() != null) {
            sb.append("入口内=").append(f.entranceInside().x()).append(',').append(f.entranceInside().y())
                    .append(',').append(f.entranceInside().z()).append(' ');
        }
        if (f.entranceOutside() != null) {
            sb.append("入口外=").append(f.entranceOutside().x()).append(',').append(f.entranceOutside().y())
                    .append(',').append(f.entranceOutside().z());
        }
        if (!f.storedItems().isEmpty()) {
            sb.append("\n登记物品=").append(f.storedItems());
        }
        return sb.toString();
    }

    private static String cellName(CellKey c) {
        return String.valueOf((char) ('A' + c.cx())) + (c.cz() + 1);
    }

    private static CellKey parseCell(String raw) {
        if (raw == null || raw.length() < 2) return null;
        int cx = Character.toUpperCase(raw.charAt(0)) - 'A';
        if (cx < 0 || cx > 25) return null;
        try {
            int cz = Integer.parseInt(raw.substring(1).trim()) - 1;
            if (cz < 0) return null;
            return CellKey.of(cx, cz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
