package com.dwinovo.numen.acx.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次成功执行的摘要载荷，供「成功经验库」消费（契约 {@link AcxExperienceSink} / ACX-X1）。
 *
 * <p>刻意只带摘要不带全量输出：经验库要的是「哪个版本的哪个 AC 在什么代价下成功过」，
 * 不是把执行现场整包搬进记忆。需要现场时按 {@code runId} 回查 JSONL / 事件流。</p>
 */
public record AcxExperience(
        String acName,
        String acVersion,
        String fingerprint,
        int completedSteps,
        int loopCount,
        long elapsedMs,
        long timestamp) {

    public static AcxExperience from(AcxRunRecord record) {
        return new AcxExperience(
                record.acName() == null ? "" : record.acName(),
                record.acVersion() == null ? "" : record.acVersion(),
                record.fingerprint() == null ? "" : record.fingerprint(),
                record.completedStepIndex(),
                record.loopCount(),
                record.elapsedMs(),
                record.timestamp());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ac_name", acName);
        m.put("ac_version", acVersion);
        m.put("fingerprint", fingerprint);
        m.put("completed_steps", completedSteps);
        m.put("loop_count", loopCount);
        m.put("elapsed_ms", elapsedMs);
        m.put("timestamp", timestamp);
        return m;
    }
}
