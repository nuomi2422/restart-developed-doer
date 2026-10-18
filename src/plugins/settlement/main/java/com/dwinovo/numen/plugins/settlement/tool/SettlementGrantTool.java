package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.protect.Grant;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 临时施工授权：让同伴在某设施的作业范围内可以合法改动，范围外照旧被保护拦住。
 *
 * <p>保护本身是对的（同伴不该拆家），但同伴"给自己建完的设施补墙/收割/维修"也需要权限。
 * 这一条把这块缺口补上：<b>显式、时间盒、范围受限</b>。默认只授该设施的作业区（耕作/补种/
 * 维修算合规操作），{@code scope=facility} 才授整块（大修）。
 *
 * <p>它不落盘——重启即失效；也可用 {@code settlement_revoke} 立刻收回。更严格的"随施工任务
 * 生命周期自动起止"需要在 core 的建造任务上挂 facilityId，是后续项。
 */
public final class SettlementGrantTool implements NumenTool {

    private final SettlementService service;

    public SettlementGrantTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_grant"; }

    @Override public String description() {
        return "给自己在一处已登记设施的作业范围内开临时施工/维修权限（保护范围内其余照拦）。"
                + "id 是设施 id；minutes 是有效分钟数（默认 10）；scope=work（默认，只授作业区）"
                + "或 facility（授整块，用于大修）。它<b>不</b>解除持久保护：过期或 settlement_revoke 后立刻恢复全拦。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "设施 id")
                .optionalInteger("minutes", "有效分钟数，默认 10", 1, 600)
                .optionalEnum("scope", "work=只授作业区（默认）；facility=授整块", "work", "facility")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (!args.has("id")) {
                reply.accept(TaskResult.fail("settlement_grant needs 'id'").toJson());
                return;
            }
            String id = args.get("id").getAsString().toLowerCase(java.util.Locale.ROOT).trim();
            Optional<FacilityRecord> found = service.byId(id);
            if (found.isEmpty()) {
                reply.accept(TaskResult.fail("settlement_grant: no facility '" + id + "'").toJson());
                return;
            }
            FacilityRecord facility = found.get();
            int minutes = args.has("minutes") ? args.get("minutes").getAsInt() : 10;
            minutes = Math.max(1, Math.min(600, minutes));
            String scope = args.has("scope") ? args.get("scope").getAsString() : "work";
            long expires = System.currentTimeMillis() + minutes * 60_000L;

            List<Grant> newGrants = new ArrayList<>();
            int boxes;
            if ("facility".equals(scope)) {
                newGrants.add(Grant.expiring(id, facility.dimension(), facility.bounds(), expires));
                boxes = 1;
            } else {
                List<BlockBox> zones = facility.workZones().isEmpty()
                        ? List.of(facility.bounds()) : facility.workZones();
                for (BlockBox zone : zones) {
                    newGrants.add(Grant.expiring(id, facility.dimension(), zone, expires));
                }
                boxes = zones.size();
            }
            service.addGrants(newGrants);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("scope", scope);
            data.put("areas", boxes);
            data.put("expires_at", expires);
            data.put("minutes", minutes);
            reply.accept(TaskResult.ok("granted self work rights on '" + id + "' for " + minutes
                    + " min (scope=" + scope + ")", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_grant error: " + ex.getMessage()).toJson());
        }
    }
}
