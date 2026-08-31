package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.core.AcAuthoringService;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 门面工具 {@code ac_publish}：把一个 AC 定义（JSON）校验后发布到 AC 库（落盘）。
 * 发布后其它请求 / AI 可用 {@code ac_execute} 的 {@code ac_name} 按名复用，不必每次传完整 JSON。
 */
public final class AcPublishTool implements NumenTool {

    private final AcAuthoringService authoring;
    private final Runnable refreshBridge;

    public AcPublishTool(AcAuthoringService authoring, Runnable refreshBridge) {
        this.authoring = authoring;
        this.refreshBridge = refreshBridge;
    }

    @Override public String name() { return "ac_publish"; }
    @Override public String description() {
        return "把一个 AC 脚本定义(JSON: name/version/steps，每步 id/tool/parameters)校验后发布到 AC 库(落盘)。"
                + "之后可用 ac_execute 的 ac_name 参数按名复用。校验失败不写入、不破坏已有版本。";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().string("ac_json", "AC 定义 JSON").build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        // 校验依赖 AC registry 知道 Numen 工具：发布前先惰性桥接（同 ac_execute）
        if (refreshBridge != null) refreshBridge.run();
        if (!args.has("ac_json")) {
            reply.accept(TaskResult.fail("缺少必填参数 ac_json").toJson());
            return;
        }
        try {
            AcDefinition ac = authoring.publishJson(args.get("ac_json").getAsString());
            reply.accept(TaskResult.ok("AC 已发布到库", Map.of(
                    "ac", ac.name(), "ac_version", ac.version(), "steps", ac.steps().size())).toJson());
        } catch (IllegalArgumentException e) {
            reply.accept(TaskResult.fail("AC 发布失败: " + e.getMessage()).toJson());
        }
    }
}
