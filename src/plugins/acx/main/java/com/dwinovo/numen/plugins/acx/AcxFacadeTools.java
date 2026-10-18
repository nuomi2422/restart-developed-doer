package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.core.AcxFacade;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ACX 的 8 个 Numen 门面工具薄壳（{@code acx_} 前缀）。
 *
 * <p>这一层只做三件事：绑定同伴 → 把 JsonObject 参数转成 Map → 把 {@link AcxFacade}
 * 的 Map 信封转成 {@link TaskResult}。业务语义全在 {@code acx-core} 的
 * {@link AcxFacade} 里（零 MC 依赖、离线可测）。</p>
 */
public final class AcxFacadeTools {

    private static final Gson GSON = new Gson();

    private AcxFacadeTools() {}

    abstract static class FacadeTool implements NumenTool {

        private final AcxPlugin plugin;
        private final String name;
        private final String description;
        private final Map<String, Object> schema;

        FacadeTool(AcxPlugin plugin, String name, String description, Map<String, Object> schema) {
            this.plugin = plugin;
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return schema;
        }

        @Override
        public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                                 Consumer<String> reply) {
            plugin.bindCompanion(companion);
            AcxFacade facade;
            try {
                facade = plugin.facade();
            } catch (Throwable t) {
                reply.accept(TaskResult.fail("ACX 初始化失败: " + t).toJson());
                return;
            }
            Map<String, Object> req;
            try {
                req = toMap(args);
            } catch (RuntimeException e) {
                reply.accept(TaskResult.fail("参数解析失败: " + e.getMessage()).toJson());
                return;
            }
            Map<String, Object> resp;
            try {
                resp = invoke(facade, req);
            } catch (Throwable t) {
                reply.accept(TaskResult.fail("ACX 门面异常: " + t).toJson());
                return;
            }
            reply.accept(toTaskResult(resp).toJson());
        }

        abstract Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonObject args) {
        Map<String, Object> req = args == null ? new LinkedHashMap<>() : GSON.fromJson(args, Map.class);
        if (req == null) {
            req = new LinkedHashMap<>();
        }
        Object input = req.get("input");
        if (input instanceof String s && !s.isBlank()) {
            // LLM 常把嵌套对象写成 JSON 字符串；统一还原成 Map 给门面
            req.put("input", GSON.fromJson(s, Map.class));
        }
        return req;
    }

    @SuppressWarnings("unchecked")
    private static TaskResult toTaskResult(Map<String, Object> resp) {
        Object msgObj = resp.get("message");
        String message = msgObj == null ? "" : String.valueOf(msgObj);
        Object dataObj = resp.get("data");
        Map<String, Object> data = dataObj instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (Boolean.TRUE.equals(resp.get("success"))) {
            return data == null ? TaskResult.ok(message) : TaskResult.ok(message, data);
        }
        return data == null ? TaskResult.fail(message) : TaskResult.fail(message, data);
    }

    // ── 8 个门面 ────────────────────────────────────────────────────────

    public static final class Execute extends FacadeTool {
        public Execute(AcxPlugin plugin) {
            super(plugin, "acx_execute",
                    "执行一个 ACX 脚本：给 ac_json（完整定义）或 ac_name（已发布版本）二选一，"
                            + "可选 input。立刻回 run_id（已受理≠已完成），用 acx_status 查终态。",
                    Schema.object()
                            .optionalString("ac_json", "AC 定义 JSON（与 ac_name 二选一）")
                            .optionalString("ac_name", "按名执行版本库中的脚本")
                            .optionalString("ac_version", "指定版本（可选，默认 active）")
                            .optionalString("input", "执行输入（JSON 对象或 JSON 字符串）")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.execute(req);
        }
    }

    public static final class Status extends FacadeTool {
        public Status(AcxPlugin plugin) {
            super(plugin, "acx_status",
                    "查一次 ACX 执行的终态：data.state ∈ SUCCESS/PAUSED/FAIL/TIMEOUT，含断点与暂停原因。",
                    Schema.object()
                            .string("run_id", "acx_execute 返回的执行 id")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.status(req);
        }
    }

    public static final class Resume extends FacadeTool {
        public Resume(AcxPlugin plugin) {
            super(plugin, "acx_resume",
                    "续跑 PAUSED 的执行（可带 input 刷新环境事实）。只有 PAUSED 能续；"
                            + "定义版本/指纹不符会被拒绝。",
                    Schema.object()
                            .string("run_id", "acx_execute 返回的执行 id")
                            .optionalString("input", "续跑时刷新的输入（JSON 对象或字符串）")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.resume(req);
        }
    }

    public static final class Cancel extends FacadeTool {
        public Cancel(AcxPlugin plugin) {
            super(plugin, "acx_cancel",
                    "协作式取消一次执行：积木在循环里轮询到请求后收手，终态记 PAUSED（可续跑）。",
                    Schema.object()
                            .string("run_id", "acx_execute 返回的执行 id")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.cancel(req);
        }
    }

    public static final class Publish extends FacadeTool {
        public Publish(AcxPlugin plugin) {
            super(plugin, "acx_publish",
                    "发布一个 ACX 新版本（状态 GENERATED，不生效）。静态校验通过才入版本库，"
                            + "等 acx_approve 人工批准。",
                    Schema.object()
                            .string("ac_json", "AC 定义 JSON")
                            .optionalString("note", "版本备注")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.publish(req);
        }
    }

    public static final class Approve extends FacadeTool {
        public Approve(AcxPlugin plugin) {
            super(plugin, "acx_approve",
                    "人工批准一个已发布版本并切为 active（状态 STABLE）。",
                    Schema.object()
                            .string("name", "AC 名")
                            .string("version", "版本号")
                            .optionalString("approver", "批准人")
                            .optionalString("note", "备注")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.approve(req);
        }
    }

    public static final class Rollback extends FacadeTool {
        public Rollback(AcxPlugin plugin) {
            super(plugin, "acx_rollback",
                    "把 active 切回某个仍为 STABLE 的旧版本。",
                    Schema.object()
                            .string("name", "AC 名")
                            .string("version", "目标版本号")
                            .optionalString("reason", "回滚原因")
                            .build());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.rollback(req);
        }
    }

    public static final class Library extends FacadeTool {
        public Library(AcxPlugin plugin) {
            super(plugin, "acx_library",
                    "版本库只读视图：脚本名与各自当前生效版本。",
                    Schema.none());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.library(req);
        }
    }

    /**
     * {@code acx_blocks}：列出当前真的可用的积木（名字 + 参数 + 输出）。
     *
     * <p><b>为什么要有它</b>（2026-10-06 用户实机验收）：干活 AI 写 AC 时看不到积木清单，
     * 只能照抄提示词里的示例，而那个示例里写着<b>不存在的</b> {@code block:"move"}
     * （真名 {@code goto}）。实测后果：照抄被拒 → 连拒之后学会交「最小可解析空壳」
     * （只读状态、零动作），而那个空壳会真的被执行、真的报 SUCCESS。
     *
     * <p><b>写 AC 之前先调它</b>：清单外的 block 名一律不存在，别猜。
     */
    public static final class Blocks extends FacadeTool {
        public Blocks(AcxPlugin plugin) {
            super(plugin, "acx_blocks",
                    "列出当前真的可用的 AC 积木（名字 / 参数 / 输出字段）以及别名。"
                            + "★ 写 AC 的 step.block 之前先调它：清单外的名字 = 不存在，不要猜、不要自创"
                            + "（会被静态校验拒收）；只放只读积木的「动作脚本」是空壳，也会被拒。",
                    Schema.none());
        }

        @Override
        Map<String, Object> invoke(AcxFacade facade, Map<String, Object> req) {
            return facade.blocks(req);
        }
    }
}
