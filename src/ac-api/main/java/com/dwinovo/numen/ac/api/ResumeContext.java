package com.dwinovo.numen.ac.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次暂停的可恢复上下文。把“执行身份”绑定到断点：
 * 只有同一 AC 的 {@code name + version + fingerprint} 才能 resume，
 * 版本或内容变化一律拒绝静默续跑（需迁移/重规划）。
 *
 * <p>{@code resumeStepIndex} 指向<b>暂停的那一步</b>（默认 resume 重试该步）；
 * {@code input} 与 {@code state} 保留原始输入和此前已完成步骤的输出，
 * 保证续跑不从头重跑、不丢上下文。
 */
public record ResumeContext(
        String executionId,
        int attempt,
        String acName,
        String acVersion,
        String acFingerprint,
        Map<String, Object> input,
        Map<String, Object> state,
        String resumeStepId,
        int resumeStepIndex,
        String pausedReason) {

    public ResumeContext {
        if (executionId == null || executionId.isBlank())
            throw new IllegalArgumentException("executionId required");
        if (attempt < 0) throw new IllegalArgumentException("attempt must be >= 0");
        if (acName == null || acName.isBlank() || acVersion == null || acVersion.isBlank())
            throw new IllegalArgumentException("AC name/version required");
        if (resumeStepId == null || resumeStepId.isBlank())
            throw new IllegalArgumentException("resumeStepId required");
        if (resumeStepIndex < 0) throw new IllegalArgumentException("resumeStepIndex must be >= 0");
        input = input == null ? Map.of() : new LinkedHashMap<>(input);
        state = state == null ? Map.of() : new LinkedHashMap<>(state);
    }
}
