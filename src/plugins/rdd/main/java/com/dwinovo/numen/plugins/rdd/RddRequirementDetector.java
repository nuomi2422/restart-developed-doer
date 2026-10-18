package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.policy.RequirementManifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元件检测 · 一级目标资产要求提取（插件侧接线）。
 *
 * <p>【架构概念 2/4】一级大目标生成时同步产出"需求清单"（{@link RequirementManifest}）。
 * 提取范围：{@code waitFor} 前置 + 各二级 condition 的 {@code asset_key}。
 * <b>group 条件不在此列</b> —— 组计数的语义是"组内件数之和"，由
 * {@code RddFactContext} 的检测通道单独跟踪（本清单只承载逐物品的资产要求）。
 *
 * <p><b>★ goalId 必须是真 {@code Goal.id}</b>：事实库（{@code StageFact.goalId}）用的就是它。
 * 2026-10-06 接线时核实：旧实现把 {@code primary.id}（{@code "primary-<uuid>"}）填进 goalId，
 * 而 {@code RequirementFactAudit} / {@code RequirementAssetAudit} 都按
 * {@code manifest.goalId == fact.goalId} 对账 ⇒ <b>生产里永远 join 不上</b>
 * （单测夹具用了同一 id，所以红不了）。这里修正，并把这个口径写死在签名上。
 *
 * <p>（曾经的 {@code detectAndPublish} 是死代码：生产零调用。已由
 * {@code RddFactContext.tick} 的「只在状态变化时上报」检测取代。）
 */
final class RddRequirementDetector {

    private RddRequirementDetector() {}

    /**
     * 从一级目标提取需求清单。
     *
     * @param goalId 真 {@code Goal.id}；null/空串时回落 {@code primary.id}
     *               （回落只为不崩，调用方应总是给真 id —— 否则对账列会静默失配）
     */
    static RequirementManifest.Manifest forPrimary(PrimaryGoal primary, String goalId) {
        Map<String, RequirementManifest.Requirement> byKey = new LinkedHashMap<>();
        if (primary != null) {
            for (AssetRequirement w : primary.waitFor()) {
                if (w != null && w.assetKey() != null && !w.assetKey().isBlank()) {
                    merge(byKey, w.assetKey(), w.minimum());
                }
            }
            for (Subtask s : primary.subtasks()) {
                Map<String, Object> c = s.condition();
                Object key = c == null ? null : c.get("asset_key");
                if (key instanceof String ks && !ks.isBlank()) {
                    int min = 1;
                    Object m = c.get("minimum");
                    if (m instanceof Number n) min = Math.max(0, n.intValue());
                    merge(byKey, ks, min);
                }
            }
        }
        String gid = goalId == null || goalId.isBlank()
                ? (primary == null ? "unknown" : primary.id())
                : goalId;
        return new RequirementManifest.Manifest(gid, new ArrayList<>(byKey.values()));
    }

    /** 同键取更大的 minimum（同一级的两个二级分别要 2 个和 5 个，清单报 5 个才诚实）。 */
    private static void merge(Map<String, RequirementManifest.Requirement> byKey, String key, int minimum) {
        RequirementManifest.Requirement prev = byKey.get(key);
        if (prev == null || minimum > prev.minimum()) {
            byKey.put(key, new RequirementManifest.Requirement(key, minimum, List.of()));
        }
    }
}
