package com.dwinovo.numen.rdd.side;

import java.util.Objects;
import java.util.UUID;

/**
 * 当前"该执行谁"的唯一答案（2026-10-08 正式睡觉支线框架）。
 *
 * <p>主线和支线共用这一个出口：{@link ActiveTaskSelector} 视 {@link SideTaskRuntime} 有没有
 * 活跃支线，返回 {@link Side} 或调用方给的 {@link Mainline}。任何执行、监督、上下文、回执
 * 都必须先经过它，禁止同一同伴主线和支线双驾驶。
 *
 * <p>{@code instanceId + generation} 是回执归属身份：换目标、重启、支线结束后的迟到回执
 * 都靠它被拒绝（见 {@link ActiveTaskSelector#acceptsResult}），不能只依赖描述文字或复用的
 * subtask id。
 */
public sealed interface ActiveTask permits ActiveTask.Mainline, ActiveTask.Side {

    UUID companionId();

    String instanceId();

    String taskId();

    TaskScope scope();

    long generation();

    /** 主线引用：主线的执行身份 = 当前二级 + 计划代次。 */
    record Mainline(UUID companionId, String instanceId, String taskId, long generation) implements ActiveTask {
        public Mainline {
            Objects.requireNonNull(companionId, "companionId");
            instanceId = instanceId == null ? "" : instanceId;
            taskId = taskId == null ? "" : taskId;
        }

        @Override
        public TaskScope scope() {
            return TaskScope.MAINLINE;
        }
    }

    /** 支线引用：以支线实例 ID + 代次标识。 */
    record Side(UUID companionId, String instanceId, String taskId, long generation) implements ActiveTask {
        public Side {
            Objects.requireNonNull(companionId, "companionId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            taskId = taskId == null ? "" : taskId;
        }

        @Override
        public TaskScope scope() {
            return TaskScope.SIDE;
        }
    }
}
