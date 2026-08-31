package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.api.NumenApi;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reports that the controlled mutation mode is installed, without mutating code. */
public final class SelfCompileStatusTool implements NumenTool {

    private final SelfCompileService service;

    public SelfCompileStatusTool(SelfCompileService service) {
        this.service = service;
    }

    @Override public String name() { return "selfcompile_status"; }

    @Override public String description() {
        return "查看 Self-Compile 自变异系统当前状态。只读，不生成代码、不编译、不部署。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @Override public void invoke(com.dwinovo.numen.agent.tool.ToolCall call) {
        call.complete(service.status());
    }
}
