package com.dwinovo.numen.rdd.side;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条支线实例的完整状态（不可变；每次推进用 {@code withX} 派生新实例）。
 *
 * <p>通用字段与具体类型解耦：{@link #phase()} 与 {@link #data()} 是类型自留的
 * 阶段/负载（例如睡觉用 SEEKING_BED / SLEEPING），通用层不理解它们，只负责持久化与比对。
 */
public record SideTask(
        UUID companionId,
        String instanceId,
        String typeId,
        long generation,
        long scheduledGameDay,
        long createdAtGameTime,
        long deadlineGameTime,
        SideTaskState state,
        String phase,
        Map<String, String> data,
        MainlineResumeToken mainlineToken) {

    public SideTask {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(typeId, "typeId");
        state = state == null ? SideTaskState.ACTIVE : state;
        phase = phase == null ? "" : phase;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public boolean terminal() {
        return state != SideTaskState.CREATED && state != SideTaskState.ACTIVE;
    }

    public boolean pastDeadline(long nowGameTime) {
        return nowGameTime >= deadlineGameTime;
    }

    public SideTask withState(SideTaskState next) {
        return new SideTask(companionId, instanceId, typeId, generation, scheduledGameDay,
                createdAtGameTime, deadlineGameTime, next, phase, data, mainlineToken);
    }

    public SideTask withPhase(String next) {
        return new SideTask(companionId, instanceId, typeId, generation, scheduledGameDay,
                createdAtGameTime, deadlineGameTime, state, next, data, mainlineToken);
    }

    public SideTask withData(Map<String, String> next) {
        return new SideTask(companionId, instanceId, typeId, generation, scheduledGameDay,
                createdAtGameTime, deadlineGameTime, state, phase, next, mainlineToken);
    }
}
