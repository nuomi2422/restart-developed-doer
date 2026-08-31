package com.dwinovo.numen.ac.api;

/**
 * 不可变执行事件。供主 AI、任务链、监测台等<b>旁路</b>订阅执行生命周期，
 * 不携带完整 input/output（那些在 {@link ExecutionRecord} 里），只带摘要与定位信息。
 *
 * <p>事件顺序由执行器保证确定：{@code EXECUTION_STARTED} → (每个 step 的
 * STARTED/SUCCEEDED|PAUSED|FAILED) → 终态事件。{@code stepIndex == -1} 表示无步骤。
 */
public record AcEvent(
        Kind kind,
        String executionId,
        int attempt,
        String runId,
        String acName,
        String acVersion,
        String acFingerprint,
        String stepId,
        int stepIndex,
        String tool,
        String message,
        long timestamp) {

    public enum Kind {
        EXECUTION_STARTED,
        STEP_STARTED,
        STEP_SUCCEEDED,
        STEP_PAUSED,
        STEP_FAILED,
        EXECUTION_SUCCEEDED,
        EXECUTION_PAUSED,
        EXECUTION_FAILED
    }

    public AcEvent {
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId required");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        if (acName == null || acName.isBlank()) throw new IllegalArgumentException("acName required");
    }

    public boolean isStepEvent() {
        return stepIndex >= 0 && stepId != null && !stepId.isBlank();
    }
}
