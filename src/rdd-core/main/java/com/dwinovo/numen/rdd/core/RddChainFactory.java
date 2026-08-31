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
 *   <li>{@link #fromSpec}——分解器产出的多级规格（每个二级带真实资产条件 + 可选身体指令），本片主路径。</li>
 *   <li>{@link #fromObjective}——{@link #fromSpec} 的薄封装：单一级、单个 HARD_CODED 占位二级
 *   （asset_key="goal"，minimum=1），用于分解失败/超时回落，保证目标不被吞掉。</li>
 * </ul>
 *
 * <p>纯 JVM，不碰 Minecraft。
 */
public final class RddChainFactory {

    /** 目标正文截断上限，与 {@code GoalState.MAX_OBJECTIVE_CHARS} 对齐。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;
    /** 一次分解最多产出的二级目标数，防 LLM 吐出几十个子步。 */
    public static final int MAX_SUBTASKS = 8;

    private RddChainFactory() {}

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

    /** 确定性条件必须可被 {@link HardCodedEvaluator} 判定：asset_key 非空；minimum（若有）非负。 */
    private static void validateCondition(Map<String, Object> condition, int index) {
        Object assetKey = condition.get("asset_key");
        if (!(assetKey instanceof String key) || key.isBlank()) {
            throw new IllegalArgumentException("spec " + index + " lacks a non-blank asset_key");
        }
        Object minimum = condition.get("minimum");
        if (minimum instanceof Number n && n.intValue() < 0) {
            throw new IllegalArgumentException("spec " + index + " minimum must be non-negative");
        }
    }
}
