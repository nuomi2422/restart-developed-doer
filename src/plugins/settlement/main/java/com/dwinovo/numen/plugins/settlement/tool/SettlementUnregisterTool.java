package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** 注销一处设施（保护规则随之失效）。 */
public final class SettlementUnregisterTool implements NumenTool {

    private final SettlementService service;

    public SettlementUnregisterTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_unregister"; }

    @Override public String description() {
        return "注销一处已登记设施，它的保护规则随之失效。id 不存在时明确失败，不静默成功。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "要注销的设施 id")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        if (!args.has("id")) {
            reply.accept(TaskResult.fail("settlement_unregister needs 'id'").toJson());
            return;
        }
        String id = args.get("id").getAsString().toLowerCase(java.util.Locale.ROOT).trim();
        if (service.unregister(id)) {
            reply.accept(TaskResult.ok("unregistered facility '" + id + "'").toJson());
        } else {
            reply.accept(TaskResult.fail("settlement_unregister: no facility '" + id + "'").toJson());
        }
    }
}
