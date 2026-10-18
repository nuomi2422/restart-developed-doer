package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 收回临时施工授权：给 id 只收该设施的，不给则收回全部。 */
public final class SettlementRevokeTool implements NumenTool {

    private final SettlementService service;

    public SettlementRevokeTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_revoke"; }

    @Override public String description() {
        return "立刻收回临时施工授权（settlement_grant 开的那把）。给 id 只收该设施的，不给则全部收回。"
                + "收回后持久保护立即恢复全拦。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("id", "只收回该设施的授权；不给则收回全部")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        String id = args.has("id") && !args.get("id").isJsonNull()
                ? args.get("id").getAsString().toLowerCase(java.util.Locale.ROOT).trim() : null;
        int removed = service.clearGrants(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revoked", removed);
        data.put("scope", id == null ? "all" : id);
        reply.accept(TaskResult.ok("revoked " + removed + " work grant(s)", data).toJson());
    }
}
