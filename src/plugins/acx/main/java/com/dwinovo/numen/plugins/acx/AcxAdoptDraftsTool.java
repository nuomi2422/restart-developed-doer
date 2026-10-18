package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code acx_adopt_drafts}：手工触发一次「学习者 AC 草稿 → 版本库」的采纳（B6/S2）。
 *
 * <p><b>为什么要有这个工具</b>：采纳默认在 {@code ensureReady()} 里已经跑过一次，
 * 但那要等到第一次 {@code acx_*} 调用才发生。学习者刚交完草稿时如果没人碰 acx，
 * 草稿就一直躺在投递箱里；有了这个工具，AI 或人可以立刻把「草稿有没有被接住」问出来 ——
 * <b>「投递了但没被接」是最容易变成哑故障的形态</b>。
 *
 * <p><b>它不做什么</b>：不 approve、不上线、不执行脚本。采纳只是让草稿进版本库并停在
 * GENERATED，上线仍需人工 {@code acx_approve}。
 */
final class AcxAdoptDraftsTool implements NumenTool {

    private final AcxPlugin plugin;

    AcxAdoptDraftsTool(AcxPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "acx_adopt_drafts";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "Adopt pending AC drafts produced by the learner into the ACX version library. "
                + "Returns per-draft results (ADOPTED / REJECTED with reason). "
                + "Adopted drafts land as GENERATED only; going live still requires acx_approve by a human.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        plugin.bindCompanion(companion);
        try {
            // 强制初始化：采纳口是 ensureReady 里建的，没 ready 就等于没有采纳口。
            // 失败在这里如实回执，不静默返回「0 条」。
            plugin.facade();
        } catch (Throwable t) {
            reply.accept(TaskResult.fail("ACX 初始化失败，无法采纳草稿: " + t).toJson());
            return;
        }
        Map<String, Object> report;
        try {
            report = plugin.adoptLearnerDrafts();
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("采纳草稿失败: " + e.getMessage()).toJson());
            return;
        }
        int adopted = toInt(report.get("adopted"));
        int rejected = toInt(report.get("rejected"));
        String msg = adopted > 0
                ? ("已采纳 " + adopted + " 条学习者 AC 草稿（GENERATED，未上线）")
                : ("没有可采纳的学习者 AC 草稿"
                        + (rejected > 0 ? "；本次拒收 " + rejected + " 条" : "")
                        + "（scanned=" + report.get("scanned") + "）");
        reply.accept(TaskResult.ok(msg, report).toJson());
    }

    private static int toInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
