package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 列出已登记的全部设施（跨任务/重启保留）。 */
public final class SettlementListTool implements NumenTool {

    /** 一次最多回多少条，防止设施库增长后撑爆结果。 */
    private static final int MAX_ROWS = 64;

    private final SettlementService service;

    public SettlementListTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_list"; }

    @Override public String description() {
        return "列出已登记的基地设施（id / 用途 / 维度 / 范围 / 结构判定 / 生产判定）。"
                + "只读。想知道某个设施现在还能不能用、有没有进入生产，用 settlement_verify 复查。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        List<FacilityRecord> all = service.list();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FacilityRecord f : all) {
            if (rows.size() >= MAX_ROWS) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.id());
            row.put("kind", f.kind().name());
            row.put("dimension", f.dimension());
            row.put("bounds", f.bounds().minX() + "," + f.bounds().minY() + "," + f.bounds().minZ()
                    + " .. " + f.bounds().maxX() + "," + f.bounds().maxY() + "," + f.bounds().maxZ());
            row.put("zones", f.zones().size());
            row.put("structure", f.structureVerdict().name());
            row.put("production", f.productionState().name());
            row.put("stored_types", f.storedItems().size());
            if (!f.storedItems().isEmpty()) {
                row.put("stored_items", f.storedItems());
            }
            row.put("checked_at", f.lastCheckedAtEpochMs());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", all.size());
        data.put("truncated", all.size() > MAX_ROWS);
        data.put("store", service.file().toString());
        data.put("facilities", rows);
        reply.accept(TaskResult.ok(all.isEmpty() ? "no facilities registered yet" : all.size() + " facility(ies)", data).toJson());
    }
}
