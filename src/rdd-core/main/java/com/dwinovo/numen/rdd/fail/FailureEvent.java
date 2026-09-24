package com.dwinovo.numen.rdd.fail;

import java.util.Objects;

/**
 * 失败事件（P2.0）：把"某个二级/一级为什么失败"结构化，作为诊断层输入。
 *
 * <p>纯数据、不做决策（决策在 {@link FailureClassifier}）。reason 是人类可读事实；
 * 禁止把"能力缺口"之类推断塞进来——那正是本层要避免的旧因果。
 *
 * @param subtaskId 失败的二级 id（可能为 null，如一级级失败）
 * @param primaryId 所属一级 id（可能为 null）
 * @param kind      硬分类类型
 * @param reason    事实描述（观测到的现象，不是结论）
 * @param atMillis  发生时间
 */
public record FailureEvent(String subtaskId, String primaryId, FailureKind kind, String reason, long atMillis) {
    public FailureEvent {
        Objects.requireNonNull(kind, "failure kind required");
        reason = reason == null ? "" : reason;
    }

    public static FailureEvent of(String subtaskId, String primaryId, FailureKind kind, String reason) {
        return new FailureEvent(subtaskId, primaryId, kind, reason, System.currentTimeMillis());
    }
}
