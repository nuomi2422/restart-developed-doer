package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个步骤的执行结果。
 *
 * <p>成功时 {@code output} 会被后续步骤用 {@code $prev.x} / {@code $<stepId>.x} 引用，
 * 所以积木应当把「可供下游消费的事实」放进 output，而不只是人话消息。</p>
 */
public final class AcxStepOutcome {

    private final AcxStatus status;
    private final Map<String, Object> output;
    private final String message;

    private AcxStepOutcome(AcxStatus status, Map<String, Object> output, String message) {
        this.status = status;
        this.output = output == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        this.message = message == null ? "" : message;
    }

    public static AcxStepOutcome success(Map<String, Object> output, String message) {
        return new AcxStepOutcome(AcxStatus.SUCCESS, output, message);
    }

    public static AcxStepOutcome success(Map<String, Object> output) {
        return success(output, "");
    }

    public static AcxStepOutcome failed(String message) {
        return new AcxStepOutcome(AcxStatus.FAIL, Map.of(), message);
    }

    public static AcxStepOutcome paused(String message, Map<String, Object> output) {
        return new AcxStepOutcome(AcxStatus.PAUSED, output, message);
    }

    public static AcxStepOutcome timedOut(String message) {
        return new AcxStepOutcome(AcxStatus.TIMEOUT, Map.of(), message);
    }

    public AcxStatus status() {
        return status;
    }

    public Map<String, Object> output() {
        return output;
    }

    public String message() {
        return message;
    }

    public boolean isSuccess() {
        return status == AcxStatus.SUCCESS;
    }

    @Override
    public String toString() {
        return "AcxStepOutcome{" + status + ", out=" + output.keySet() + ", msg=" + message + "}";
    }
}