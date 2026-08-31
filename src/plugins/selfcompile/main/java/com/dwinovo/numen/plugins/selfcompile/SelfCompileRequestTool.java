package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/** Starts only the auditable workspace phase; generation remains a later gated phase. */
public final class SelfCompileRequestTool implements NumenTool {

    private final SelfCompileService service;

    public SelfCompileRequestTool(SelfCompileService service) {
        this.service = service;
    }

    @Override public String name() { return "selfcompile_request"; }

    @Override public String description() {
        return "为一次受控的 Self-Compile 变异创建隔离工作区并记录需求。只创建审计目录，不生成代码、不执行命令、不编译、不部署。";
    }

    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("requirement", Map.of(
                "type", "string",
                "description", "要补齐或修复的能力，必须是具体、可验证的需求"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("requirement"),
                "additionalProperties", false);
    }

    @Override public void invoke(ToolCall call) {
        try {
            String requirement = call.args().has("requirement")
                    ? call.args().get("requirement").getAsString().trim() : "";
            if (requirement.isBlank()) {
                call.complete("{\"success\":false,\"error\":\"requirement must not be blank\"}");
                return;
            }
            MutationManifest manifest = service.create(requirement);
            SelfCompileMonitor.publish("selfcompile_request",
                    Map.of("mutation_id", manifest.id(),
                            "state", manifest.state().name(),
                            "requirement", requirement));
            call.complete("{\"success\":true,\"id\":\"" + escape(manifest.id())
                    + "\",\"state\":\"" + manifest.state()
                    + "\",\"workspace\":\"" + escape(manifest.workspace()) + "\"}");
        } catch (Exception e) {
            call.complete("{\"success\":false,\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
