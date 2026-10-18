package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/** Starts only the auditable workspace phase; generation remains a later gated phase. */
public final class SelfCompileRequestTool implements NumenTool {

    private final SelfCompileService service;
    private final SelfCompilePolicy policy;

    public SelfCompileRequestTool(SelfCompileService service) {
        this(service, SelfCompilePolicy.of(false));
    }

    public SelfCompileRequestTool(SelfCompileService service, SelfCompilePolicy policy) {
        this.service = service;
        this.policy = policy == null ? SelfCompilePolicy.of(false) : policy;
    }

    @Override public String name() { return "selfcompile_request"; }

    /**
     * 2026-10-01 RL-19（38号v3.4 §1）：本工具是<b>外层专用</b>。
     *
     * <p>游戏内的 AI（含学习者）<b>不应该</b>调它 —— 它们只该往待办目录写一条
     * （{@code learner_note}），由外层看到待办后自己决定要不要改、怎么改。
     *
     * <p><b>为什么工具仍然保留、只是改描述</b>：删掉它会把<b>唯一合法的 caller（外层）</b>
     * 也堵死 —— {@code run-mutation.ps1} 那条自编译闭环正是靠它起手的。
     * 真正的机制隔离（per-caller 可见性）需要改 {@code NumenApi} 门面 = <b>架构级</b>，
     * 按规矩需用户批准，见 38号v3.4 §2。
     *
     * <p>所以这里的手段是<b>把用途写清楚</b>：即使它在工具清单里可见，读了描述也不该误用。
     */
    @Override public String description() {
        return "【外层专用】为一次受控的 Self-Compile 变异创建隔离工作区并记录需求。只创建审计目录，"
                + "不生成代码、不执行命令、不编译、不部署。"
                + "如果你是在游戏里干活（挖矿/建造/战斗/背包）并且遇到了缺工具或缺能力："
                + "**不要调本工具** —— 请改用 learner_note 写一条待办（现象 + 你试过什么 + 环境快照），"
                + "由外层读到待办后决定要不要改代码、怎么改。";
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
        // ★ RL-19 硬约束（2026-10-01 第 4 轮）：**先查门，再看参数**。
        //   顺序很重要 —— 参数校验放在门后面，门才是唯一出口。
        if (!policy.allowInGameRequest()) {
            SelfCompileMonitor.publish("selfcompile_request",
                    Map.of("state", "DENIED", "reason", "internal-channel"));
            call.complete(SelfCompilePolicy.denialJson());
            return;
        }
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
