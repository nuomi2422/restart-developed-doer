package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.core.AcAuthoringService;
import com.dwinovo.numen.ac.core.AcExecutor;
import com.dwinovo.numen.ac.core.AcJson;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.ac.bridge.NumenToolBridge;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.StringReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 门面工具 {@code ac_execute}：提交一个 AC（JSON），先同步校验（工具存在+schema），
 * 通过后在后台线程执行并立即回执 execution_id。主 AI 用 ac_status 查状态、
 * ac_resume 续跑暂停。
 *
 * <p>校验失败当场回失败，不让坏 AC 进入执行；已受理 ≠ 完成，状态由
 * ac_status 如实反映。
 */
public final class AcExecuteTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private final AcExecutor executor;
    private final AcAuthoringService authoring;
    private final AcSessions sessions;
    private final Runnable refreshBridge;

    public AcExecuteTool(AcExecutor executor, AcAuthoringService authoring, AcSessions sessions,
                         Runnable refreshBridge) {
        this.executor = executor;
        this.authoring = authoring;
        this.sessions = sessions;
        this.refreshBridge = refreshBridge;
    }

    @Override public String name() { return "ac_execute"; }
    @Override public String description() {
        return "提交一个 AC 脚本(JSON: name/version/steps，每步 id/tool/parameters)执行。"
                + "校验通过后后台运行并返回 execution_id；用 ac_status 查进度、ac_resume 续跑暂停。";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("ac_json", "AC 定义 JSON（与 ac_name 二选一；传了优先用 ac_json）")
                .optionalString("ac_name", "按名执行已发布到 AC 库的脚本（需先用 ac_publish 发布）")
                .optionalString("ac_version", "指定版本（可选，默认最新）")
                .optionalString("input", "执行输入 JSON 字符串（可选）")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        // 惰性桥接：本工具可能比 NumenCore 全量工具注册更早被调用，先同步工具目录
        if (refreshBridge != null) refreshBridge.run();
        AcDefinition ac;
        if (!args.has("ac_json")) {
            // 按名复用：ac_name（+可选 ac_version）从落盘 AC 库加载
            String acName = args.has("ac_name") ? args.get("ac_name").getAsString() : "";
            if (acName.isBlank()) {
                reply.accept(TaskResult.fail("缺少必填参数 ac_json 或 ac_name").toJson());
                return;
            }
            java.util.Optional<AcDefinition> found = args.has("ac_version")
                    ? authoring.load(acName, args.get("ac_version").getAsString())
                    : authoring.loadLatest(acName);
            if (found.isEmpty()) {
                reply.accept(TaskResult.fail("AC 库中找不到: " + acName + "（先用 ac_publish 发布）").toJson());
                return;
            }
            ac = found.get();
        } else {
            String acJson = args.get("ac_json").getAsString();
            var v = authoring.validateJson(acJson);
            if (!v.valid()) {
                reply.accept(TaskResult.fail("AC 校验失败: " + v.reason()).toJson());
                return;
            }
            ac = AcJson.load(new StringReader(acJson));
        }
        Map<String, Object> input = parseInput(args);
        ExecutionContext ctx = () -> Map.of(NumenToolBridge.HOST_ENTITY_UUID, companion.getUUID());

        String executionId = UUID.randomUUID().toString();
        CompletableFuture<ExecutionRecord> future =
                CompletableFuture.supplyAsync(() -> executor.execute(ac, input, ctx));
        sessions.put(new AcSessions.SessionEntry(executionId, ac.name(), ac, future));

        reply.accept(TaskResult.ok("AC submitted, running in background", Map.of(
                "execution_id", executionId,
                "ac", ac.name(),
                "ac_version", ac.version(),
                "status", "RUNNING")).toJson());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInput(JsonObject args) {
        if (!args.has("input")) return Map.of();
        try {
            return (Map<String, Object>) GSON.fromJson(args.get("input").getAsString(), Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
