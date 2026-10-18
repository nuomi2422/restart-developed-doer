package com.dwinovo.numen.acx.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一份 AC 定义（对应一个 {@code .ac} 文件）。
 *
 * <p>相比现有 {@code AcDefinition} 多了 {@code version} / {@code description} / {@code tags} /
 * {@code plannerNotes} / {@code safetyNotes} / {@code preconditions}，
 * 以及可嵌套的 {@link AcxStep#children()}。</p>
 */
public final class AcxDefinition {

    private final String name;
    private final String version;
    private final String description;
    private final List<String> tags;
    private final List<AcxStep> steps;
    private final String plannerNotes;
    private final String safetyNotes;
    private final List<AcxPrecondition> preconditions;
    private final AcxLimitsSpec limits;

    public AcxDefinition(String name,
                         String version,
                         String description,
                         List<String> tags,
                         List<AcxStep> steps,
                         String plannerNotes,
                         String safetyNotes) {
        this(name, version, description, tags, steps, plannerNotes, safetyNotes, List.of());
    }

    public AcxDefinition(String name,
                         String version,
                         String description,
                         List<String> tags,
                         List<AcxStep> steps,
                         String plannerNotes,
                         String safetyNotes,
                         List<AcxPrecondition> preconditions) {
        this(name, version, description, tags, steps, plannerNotes, safetyNotes, preconditions,
                AcxLimitsSpec.of());
    }

    public AcxDefinition(String name,
                         String version,
                         String description,
                         List<String> tags,
                         List<AcxStep> steps,
                         String plannerNotes,
                         String safetyNotes,
                         List<AcxPrecondition> preconditions,
                         AcxLimitsSpec limits) {
        this.name = Objects.requireNonNull(name, "name");
        this.version = version == null ? "1" : version;
        this.description = description == null ? "" : description;
        this.tags = tags == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(tags));
        this.steps = steps == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(steps));
        this.plannerNotes = plannerNotes == null ? "" : plannerNotes;
        this.safetyNotes = safetyNotes == null ? "" : safetyNotes;
        this.preconditions = preconditions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(preconditions));
        this.limits = limits == null ? AcxLimitsSpec.of() : limits;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String description() {
        return description;
    }

    public List<String> tags() {
        return tags;
    }

    public List<AcxStep> steps() {
        return steps;
    }

    public String plannerNotes() {
        return plannerNotes;
    }

    public String safetyNotes() {
        return safetyNotes;
    }

    /** 起跑前置条件；空表示没有门槛。求值语义见 {@link AcxPrecondition}。 */
    public List<AcxPrecondition> preconditions() {
        return preconditions;
    }

    /** 本 AC 自己声明的运行上限；{@link AcxLimitsSpec#isEmpty()} 表示全用全局默认。 */
    public AcxLimitsSpec limits() {
        return limits;
    }

    /** 全部步骤 id（含嵌套 children），用于查重。 */
    public Set<String> allStepIds() {
        Set<String> ids = new LinkedHashSet<>();
        collectIds(this, ids);
        return ids;
    }

    private static void collectIds(AcxDefinition def, Set<String> out) {
        for (AcxStep step : def.steps()) {
            collectStepIds(step, out);
        }
    }

    private static void collectStepIds(AcxStep step, Set<String> out) {
        out.add(step.id());
        if (step.children() != null) {
            for (AcxStep child : step.children()) {
                collectStepIds(child, out);
            }
        }
    }

    /** 深度优先展开全部步骤（含嵌套），返回新列表。 */
    public List<AcxStep> allStepsDeep() {
        List<AcxStep> out = new ArrayList<>();
        for (AcxStep step : steps) {
            out.addAll(step.flattenSelfAndDescendants());
        }
        return out;
    }

    /** 结构化视图，供监测台 / 快照输出。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", version);
        m.put("description", description);
        m.put("tags", tags);
        m.put("steps", steps.stream().map(AcxDefinition::stepToMap).toList());
        if (!preconditions.isEmpty()) {
            m.put("preconditions", preconditions.stream().map(AcxPrecondition::toMap).toList());
        }
        if (limits != null && !limits.isEmpty()) {
            m.put("limits", limits.toMap());
        }
        if (!plannerNotes.isEmpty()) {
            m.put("planner_notes", plannerNotes);
        }
        if (!safetyNotes.isEmpty()) {
            m.put("safety_notes", safetyNotes);
        }
        return m;
    }

    private static Map<String, Object> stepToMap(AcxStep step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", step.id());
        m.put("block", step.block());
        m.put("params", step.params());
        if (step.children() != null) {
            m.put("children", step.children().stream().map(AcxDefinition::stepToMap).toList());
        }
        return m;
    }

    @Override
    public String toString() {
        return "AcxDefinition{name=" + name + ", version=" + version + ", steps=" + steps.size() + "}";
    }
}
