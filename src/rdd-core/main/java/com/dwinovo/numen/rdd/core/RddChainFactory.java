package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从一个自然语言目标（{@code /goal X} 的正文）或一组结构化规格造出 RDD 任务链。
 *
 * <p>两级入口：
 * <ul>
 *   <li>{@link #fromStages}——Stage-A 一级清单：N 个<b>未展开</b>一级(阶段主题)，懒展开多级链的主路径。</li>
 *   <li>{@link #fromSpec}——单遍分解（单一级含全部二级）；{@link #fromObjective} 是其占位封装
 *   （asset_key="goal"），两者都是 Stage-A 失败时的回落路径，保证目标不被吞掉。</li>
 * </ul>
 *
 * <p>纯 JVM，不碰 Minecraft。
 */
public final class RddChainFactory {

    /** 目标正文截断上限，与 {@code GoalState.MAX_OBJECTIVE_CHARS} 对齐。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;
    /** 一次分解最多产出的二级目标数，防 LLM 吐出几十个子步。 */
    public static final int MAX_SUBTASKS = 8;
    /** 一个懒展开链最多的一级阶段数（通关MC十级在此内）。 */
    public static final int MAX_STAGES = 12;

    private RddChainFactory() {}

    /**
     * Stage-A 装配：由一级阶段规格造一个<b>全未展开</b>的多一级 Goal。
     *
     * <p>每个一级 {@code PrimaryGoal.unexpanded(id, 主题, waitFor)}——只带主题与可选的跨级资产门，
     * 不带任何二级；首个一级被进入时由宿主目标驱动器懒展开其二级（spec §9：进入前必须生成
     * ≥1 个合法可执行的二级）。一级 id 稳定可预测（primary-&lt;suffix&gt;-i），二级注入时以此为前缀
     * 保证全局唯一、与已展开一级的 subtask- 前缀不冲突。
     *
     * @param companionId 同伴 UUID，用于生成稳定的节点 id
     * @param objective   目标正文（整条战役的标题，作 Goal.description）
     * @param stages      一级阶段规格；非空且 ≤ {@link #MAX_STAGES}，每份主题非空
     */
    public static Goal fromStages(UUID companionId, String objective, List<PrimarySpec> stages) {
        if (companionId == null) {
            throw new IllegalArgumentException("companionId required");
        }
        String obj = objective == null ? "" : objective.strip();
        if (obj.isEmpty()) {
            throw new IllegalArgumentException("objective required");
        }
        if (obj.length() > MAX_OBJECTIVE_CHARS) {
            obj = obj.substring(0, MAX_OBJECTIVE_CHARS);
        }
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("at least one stage required");
        }
        if (stages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("too many stages: " + stages.size());
        }
        String suffix = companionId.toString().substring(0, 8);
        List<PrimaryGoal> primaries = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            PrimarySpec stage = stages.get(i);
            primaries.add(PrimaryGoal.unexpanded(
                    "primary-" + suffix + "-" + i, stage.description(), stage.waitFor()));
        }
        return new Goal("goal-" + suffix, obj, primaries);
    }

    /**
     * 占位链：单个 HARD_CODED 二级（asset_key="goal"）。行为与原实现一致，
     * 只是内部复用 {@link #fromSpec} 的组装/校验。
     */
    public static Goal fromObjective(UUID companionId, String objective) {
        if (companionId == null) {
            throw new IllegalArgumentException("companionId required");
        }
        String obj = objective == null ? "" : objective.strip();
        if (obj.isEmpty()) {
            throw new IllegalArgumentException("objective required");
        }
        if (obj.length() > MAX_OBJECTIVE_CHARS) {
            obj = obj.substring(0, MAX_OBJECTIVE_CHARS);
        }
        return fromSpec(companionId, obj, List.of(new SubtaskSpec(
                "working toward: " + obj,
                Map.of("asset_key", "goal", "minimum", 1),
                null)));
    }

    /**
     * 由分解规格构造一个单一级目标（含 1..N 个 HARD_CODED 二级目标）。
     *
     * @param companionId 同伴 UUID，用于生成稳定的节点 id
     * @param objective   目标正文；空白视为非法
     * @param specs       二级目标规格；非空且 ≤ {@link #MAX_SUBTASKS}，每份 condition 必须含非空 asset_key
     */
    public static Goal fromSpec(UUID companionId, String objective, List<SubtaskSpec> specs) {
        if (companionId == null) {
            throw new IllegalArgumentException("companionId required");
        }
        String obj = objective == null ? "" : objective.strip();
        if (obj.isEmpty()) {
            throw new IllegalArgumentException("objective required");
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("at least one subtask spec required");
        }
        if (specs.size() > MAX_SUBTASKS) {
            throw new IllegalArgumentException("too many subtask specs: " + specs.size());
        }
        if (obj.length() > MAX_OBJECTIVE_CHARS) {
            obj = obj.substring(0, MAX_OBJECTIVE_CHARS);
        }
        String suffix = companionId.toString().substring(0, 8);
        List<Subtask> subtasks = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            SubtaskSpec spec = specs.get(i);
            validateCondition(spec.condition(), i);
            subtasks.add(Subtask.hardCoded(
                    "subtask-" + suffix + "-" + i,
                    spec.description(),
                    spec.condition(),
                    spec.body()));
        }
        return new Goal("goal-" + suffix, obj,
                List.of(new PrimaryGoal("primary-" + suffix, obj, subtasks)));
    }

    /** 确定性条件必须可被 {@link HardCodedEvaluator} 判定：asset_key 非空；minimum（若有）非负且是非负整数。 */
    private static void validateCondition(Map<String, Object> condition, int index) {
        if (condition == null) {
            throw new IllegalArgumentException("spec " + index + " condition required");
        }
        if (condition.containsKey("type") && !"inventory".equals(condition.get("type"))) {
            if (!WorldFactConditions.valid(condition)) throw new IllegalArgumentException("invalid world fact condition at " + index);
            return;
        }
        if (condition.containsKey("group")) {
            Object min = condition.get("minimum");
            if (!InventoryGroups.known(condition.get("group")) || condition.containsKey("asset_key")
                    || !isNonNegativeInteger(min) || (min instanceof Number mn && mn.intValue() <= 0))
                throw new IllegalArgumentException("invalid inventory group condition at " + index);
            return;
        }
        Object assetKey = condition.get("asset_key");
        if (!(assetKey instanceof String key) || key.isBlank()) {
            throw new IllegalArgumentException("spec " + index + " lacks a non-blank asset_key");
        }
        Object minimum = condition.get("minimum");
        if (minimum != null && !isNonNegativeInteger(minimum)) {
            // 与 HardCodedEvaluator:34-37 对齐：minimum 必须是有限非负整数，
            // 否则 1.5/NaN/Infinity 建链能过、判定永久 false，任务卡死不产生失败。
            throw new IllegalArgumentException("spec " + index + " minimum must be a finite non-negative integer");
        }
    }

    /** minimum 必须是有限且整数值 ≥ 0（double/float 小数、NaN/Infinity 一律拒绝）。 */
    private static boolean isNonNegativeInteger(Object minimum) {
        return minimum instanceof Number n
                && Double.isFinite(n.doubleValue())
                && n.doubleValue() >= 0
                && n.doubleValue() == (double) n.intValue();
    }
}
