package com.dwinovo.numen.ac.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次执行尝试的终态快照。
 *
 * <p>{@code runId} 是本次尝试的唯一主键；{@code executionId} 是跨尝试稳定的
 * 执行链 ID（resume 沿用同一 executionId，attempt 递增），用于按整次执行查询历史。
 * {@code resume} 仅在 {@code PAUSED} 时非空，携带执行身份与断点。
 */
public record ExecutionRecord(
        String runId,
        String executionId,
        String acName,
        Status status,
        int completedStepIndex,
        String currentStepId,
        String message,
        Map<String, Object> output,
        long startedAt,
        long finishedAt,
        ResumeContext resume) {

    public enum Status { SUCCESS, PAUSED, FAILED }

    public ExecutionRecord {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        if (acName == null || acName.isBlank()) throw new IllegalArgumentException("acName required");
        output = output == null ? Map.of() : new LinkedHashMap<>(output);
    }

    /** 兼容：旧 9 字段构造（executionId 默认取 runId，无 resume 上下文）。 */
    public ExecutionRecord(String runId, String acName, Status status, int completedStepIndex,
                           String currentStepId, String message, Map<String, Object> output,
                           long startedAt, long finishedAt) {
        this(runId, runId, acName, status, completedStepIndex, currentStepId, message,
                output, startedAt, finishedAt, null);
    }

    /** 兼容：10 字段构造（executionId 默认取 runId）。 */
    public ExecutionRecord(String runId, String acName, Status status, int completedStepIndex,
                           String currentStepId, String message, Map<String, Object> output,
                           long startedAt, long finishedAt, ResumeContext resume) {
        this(runId, runId, acName, status, completedStepIndex, currentStepId, message,
                output, startedAt, finishedAt, resume);
    }
}
