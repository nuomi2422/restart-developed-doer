package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行期事件。宿主实现 {@link AcxEventSink} 落 jsonl（我们已有 {@code AcMonitor} 可复用）。
 *
 * <p>事件是「结构化事实层」，跟人类可读的解释层分开 —— 这条是晋级门规范里的要求。</p>
 */
public final class AcxEvent {

    public enum Kind {
        RUN_STARTED,
        RUN_FINISHED,
        STEP_STARTED,
        STEP_SUCCEEDED,
        STEP_FAILED,
        STEP_PAUSED,
        STEP_IGNORED_FAILURE,
        CONTROL_STAGNATED,
        CONTROL_MAX_ITERS,
        IF_UNMATCHED,
        CIRCUIT_STEPS,
        CIRCUIT_TIMEOUT,
        CIRCUIT_RECURSION,
        VERIFIER_ABSENT,
        VERIFY_FAILED,
        RESUME_STARTED,
        RESUME_REJECTED,
        AUTHORING_REJECTED,
        PRECONDITION_FAILED,
        GUARD_FAILED,
        REF_UNRESOLVED
    }

    private final Kind kind;
    private final String runId;
    private final String acName;
    private final String stepId;
    private final AcxStatus status;
    private final int loopCount;
    private final long elapsedMs;
    private final Map<String, Object> detail;

    private AcxEvent(Builder b) {
        this.kind = b.kind;
        this.runId = b.runId;
        this.acName = b.acName;
        this.stepId = b.stepId;
        this.status = b.status;
        this.loopCount = b.loopCount;
        this.elapsedMs = b.elapsedMs;
        this.detail = b.detail == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.detail));
    }

    public Kind kind() { return kind; }
    public String runId() { return runId; }
    public String acName() { return acName; }
    public String stepId() { return stepId; }
    public AcxStatus status() { return status; }
    public int loopCount() { return loopCount; }
    public long elapsedMs() { return elapsedMs; }
    public Map<String, Object> detail() { return detail; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("run_id", runId);
        m.put("ac_name", acName);
        if (stepId != null) {
            m.put("step_id", stepId);
        }
        if (status != null) {
            m.put("status", status.name());
        }
        if (loopCount > 0) {
            m.put("loop_count", loopCount);
        }
        if (elapsedMs > 0) {
            m.put("elapsed_ms", elapsedMs);
        }
        m.putAll(detail);
        return m;
    }

    public static Builder of(Kind kind, String runId, String acName) {
        return new Builder().kind(kind).runId(runId).acName(acName);
    }

    public static final class Builder {
        private Kind kind;
        private String runId;
        private String acName;
        private String stepId;
        private AcxStatus status;
        private int loopCount;
        private long elapsedMs;
        private Map<String, Object> detail;

        public Builder kind(Kind v) { this.kind = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder acName(String v) { this.acName = v; return this; }
        public Builder stepId(String v) { this.stepId = v; return this; }
        public Builder status(AcxStatus v) { this.status = v; return this; }
        public Builder loopCount(int v) { this.loopCount = v; return this; }
        public Builder elapsedMs(long v) { this.elapsedMs = v; return this; }
        public Builder detail(Map<String, Object> v) { this.detail = v; return this; }
        public Builder detail(String k, Object v) {
            if (this.detail == null) {
                this.detail = new LinkedHashMap<>();
            }
            this.detail.put(k, v);
            return this;
        }

        public AcxEvent build() {
            return new AcxEvent(this);
        }
    }
}