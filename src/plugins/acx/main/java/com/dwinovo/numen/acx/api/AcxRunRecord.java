package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一次 AC 执行的完整记录。不可变，只有 {@link Builder} 可变。
 *
 * <p>相比现有 {@code ExecutionRecord} 补齐了这些字段（差异清单 §11）：
 * {@code loopCount} / {@code stagnantStep} / {@code pausedReason} / {@code progress} /
 * {@code elapsedMs} / {@code failedStep} / {@code currentStepId}。</p>
 *
 * <p>{@code loopCount} 与 {@code stagnantStep} 是「优雅中断」能不能被观测到的关键：
 * 没有它们，一条 PAUSED 记录看不出是循环停下还是第一步就没开始。</p>
 */
public final class AcxRunRecord {

    private final String runId;
    private final String acName;
    private final String acVersion;
    private final String fingerprint;
    private final Map<String, Object> input;
    private final AcxStatus status;
    private final int completedStepIndex;
    private final String currentStepId;
    private final int loopCount;
    private final String stagnantStep;
    private final String failedStep;
    private final String errorMessage;
    private final String pausedReason;
    private final Map<String, Object> progress;
    /** AC-B9：运行期变量快照（set 步写入的内容）。resume 时从这里恢复。 */
    private final Map<String, Object> vars;
    private final long elapsedMs;
    private final long timestamp;

    private AcxRunRecord(Builder b) {
        this.runId = b.runId;
        this.acName = b.acName;
        this.acVersion = b.acVersion;
        this.fingerprint = b.fingerprint;
        this.input = unmodifiable(b.input);
        this.status = b.status;
        this.completedStepIndex = b.completedStepIndex;
        this.currentStepId = b.currentStepId;
        this.loopCount = b.loopCount;
        this.stagnantStep = b.stagnantStep;
        this.failedStep = b.failedStep;
        this.errorMessage = b.errorMessage;
        this.pausedReason = b.pausedReason;
        this.progress = unmodifiable(b.progress);
        this.vars = unmodifiable(b.vars);
        this.elapsedMs = b.elapsedMs;
        this.timestamp = b.timestamp;
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> m) {
        return m == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    /** AC-B9：变量快照。空 map 表示这条记录来自还没写变量的旧版本。 */
    public Map<String, Object> vars() {
        return vars;
    }

    public String runId() {
        return runId;
    }

    public String acName() {
        return acName;
    }

    public String acVersion() {
        return acVersion;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Map<String, Object> input() {
        return input;
    }

    public AcxStatus status() {
        return status;
    }

    /** 已完整完成的<b>顶层</b>步骤数，resume 断点依据。 */
    public int completedStepIndex() {
        return completedStepIndex;
    }

    public String currentStepId() {
        return currentStepId;
    }

    /** 控制块累计循环轮数。 */
    public int loopCount() {
        return loopCount;
    }

    public String stagnantStep() {
        return stagnantStep;
    }

    public String failedStep() {
        return failedStep;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String pausedReason() {
        return pausedReason;
    }

    /** 聚合进度字段（mined / count 等），暂停时给 AI 诊断用。 */
    public Map<String, Object> progress() {
        return progress;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public long timestamp() {
        return timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static String shortUuid() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000000, 0x7FFFFFFF));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("ac_name", acName);
        m.put("ac_version", acVersion);
        m.put("fingerprint", fingerprint);
        m.put("input", input);
        m.put("status", status.name());
        m.put("completed_step_index", completedStepIndex);
        m.put("current_step_id", currentStepId);
        m.put("loop_count", loopCount);
        m.put("stagnant_step", stagnantStep);
        m.put("failed_step", failedStep);
        m.put("error_message", errorMessage);
        m.put("paused_reason", pausedReason);
        m.put("progress", progress);
        if (vars != null && !vars.isEmpty()) {
            m.put("vars", vars);
        }
        m.put("elapsed_ms", elapsedMs);
        m.put("timestamp", timestamp);
        return m;
    }

    @Override
    public String toString() {
        return "AcxRunRecord{ac=" + acName + ", status=" + status
                + ", completed=" + completedStepIndex
                + ", loop=" + loopCount
                + ", stagnant=" + stagnantStep
                + ", failed=" + failedStep + "}";
    }

    public static final class Builder {
        private String runId;
        private String acName;
        private String acVersion;
        private String fingerprint;
        private Map<String, Object> input;
        private AcxStatus status;
        private int completedStepIndex;
        private String currentStepId;
        private int loopCount;
        private String stagnantStep;
        private String failedStep;
        private String errorMessage;
        private String pausedReason;
        private Map<String, Object> progress;
        /** AC-B9 */
        private Map<String, Object> vars;
        private long elapsedMs;
        private long timestamp;

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder acName(String v) { this.acName = v; return this; }
        public Builder acVersion(String v) { this.acVersion = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder input(Map<String, Object> v) { this.input = v; return this; }
        public Builder status(AcxStatus v) { this.status = v; return this; }
        public Builder completedStepIndex(int v) { this.completedStepIndex = v; return this; }
        public Builder currentStepId(String v) { this.currentStepId = v; return this; }
        public Builder loopCount(int v) { this.loopCount = v; return this; }
        public Builder stagnantStep(String v) { this.stagnantStep = v; return this; }
        public Builder failedStep(String v) { this.failedStep = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder pausedReason(String v) { this.pausedReason = v; return this; }
        public Builder progress(Map<String, Object> v) { this.progress = v; return this; }

        /** AC-B9 */
        public Builder vars(Map<String, Object> v) { this.vars = v; return this; }
        public Builder elapsedMs(long v) { this.elapsedMs = v; return this; }
        public Builder timestamp(long v) { this.timestamp = v; return this; }

        public String getRunId() { return runId; }
        public AcxStatus getStatus() { return status; }
        public int getCompletedStepIndex() { return completedStepIndex; }
        public int getLoopCount() { return loopCount; }
        public String getStagnantStep() { return stagnantStep; }

        public AcxRunRecord build() {
            return new AcxRunRecord(this);
        }
    }
}