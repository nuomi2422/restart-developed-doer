package com.dwinovo.numen.rdd.fail;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * P2-B 任务质量反馈（GPT 外脑建议 · 2026-09-25）：<b>执行层 → 监督层的单向事实通道</b>。
 *
 * <p>现状：Numen 能被执行（nudge 下行）却无法"反驳任务质量"——它知道"等小麦成熟收益太低"
 * 却上报不出去。本记录把这类判断结构化，落进 monitor 事件，供 Supervisor/人重新决策。
 *
 * <p><b>只读上报，不做双向对话</b>（人工裁决）：双向要引入会话状态/回复绑定/超时/权限/冲突解决，
 * 属架构级，先不做。这里只是"事件反馈"。
 *
 * <p>纯 JVM、可单测，不含 Minecraft 类型。
 *
 * @param reason         问题类别
 * @param currentTask    出问题的任务描述（当前二级）
 * @param failedAttempts 已失败尝试次数
 * @param requiredAssets 任务要求资产（key→minimum）
 * @param availableAssets 当前真实可用资产（key→count，来自实时扫描）
 * @param estimatedCost  预估成本/耗时（人话，可空）
 * @param suggestedAction 建议动作（人话，可空）
 */
public record TaskQualityReport(
        Reason reason,
        String currentTask,
        int failedAttempts,
        Map<String, Integer> requiredAssets,
        Map<String, Integer> availableAssets,
        String estimatedCost,
        String suggestedAction) {

    /** 问题类别。枚举是稳定契约，供 Supervisor/监测台过滤。 */
    public enum Reason {
        /** 资源成本过高（收益不抵时间，如等作物生长换少量食物）。 */
        RESOURCE_COST_TOO_HIGH,
        /** 该任务所需的资产/条件实际不可达。 */
        ASSET_UNREACHABLE,
        /** 任务与当前战略目标不符（如主线已是下界，却在补种小麦）。 */
        MISALIGNED_WITH_GOAL,
        /** 疑似规划 bug / 条件永远无法满足。 */
        SUSPECTED_PLAN_DEFECT,
        /** 其它（附说明）。 */
        OTHER
    }

    public TaskQualityReport {
        Objects.requireNonNull(reason, "reason");
        currentTask = currentTask == null ? "" : currentTask.trim();
        failedAttempts = Math.max(0, failedAttempts);
        requiredAssets = requiredAssets == null ? Map.of() : Map.copyOf(requiredAssets);
        availableAssets = availableAssets == null ? Map.of() : Map.copyOf(availableAssets);
        estimatedCost = estimatedCost == null ? "" : estimatedCost.trim();
        suggestedAction = suggestedAction == null ? "" : suggestedAction.trim();
    }

    public boolean hasSuggestion() {
        return !suggestedAction.isEmpty();
    }

    /** 缺哪些必需资产（required 有、available 不足）。纯函数，便于单测与提示词渲染。 */
    public List<String> missingAssets() {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : requiredAssets.entrySet()) {
            int have = availableAssets.getOrDefault(e.getKey(), 0);
            int need = e.getValue() == null ? 0 : e.getValue();
            if (have < need) out.add(e.getKey() + " (need " + need + ", have " + have + ")");
        }
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    /** 渲染成给 Supervisor 看的一行摘要。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(reason.name()).append("] ");
        if (!currentTask.isEmpty()) sb.append(currentTask).append(" — ");
        if (failedAttempts > 0) sb.append("failed ").append(failedAttempts).append("x; ");
        List<String> missing = missingAssets();
        if (!missing.isEmpty()) sb.append("missing: ").append(String.join(", ", missing)).append("; ");
        if (!estimatedCost.isEmpty()) sb.append("cost: ").append(estimatedCost).append("; ");
        if (hasSuggestion()) sb.append("suggest: ").append(suggestedAction);
        return sb.toString().trim();
    }

    /** 转成 monitor 事件的 data map（扁平、字符串安全）。 */
    public Map<String, Object> toEventData(String companionId) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("companionId", companionId == null ? "" : companionId);
        data.put("reason", reason.name());
        data.put("currentTask", currentTask);
        data.put("failedAttempts", failedAttempts);
        data.put("requiredAssets", requiredAssets);
        data.put("availableAssets", availableAssets);
        data.put("estimatedCost", estimatedCost);
        data.put("suggestedAction", suggestedAction);
        data.put("summary", render());
        return data;
    }
}
