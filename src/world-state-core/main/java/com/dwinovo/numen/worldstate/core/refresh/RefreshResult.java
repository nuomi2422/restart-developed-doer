package com.dwinovo.numen.worldstate.core.refresh;

import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.RefreshStatus;
import com.dwinovo.numen.worldstate.core.observation.Observation;

/**
 * 一次刷新的结果。无论成功失败都带明确信息——调用方据此决定下一步，而不是去猜。
 *
 * <p><b>失败不等于上一次观测被清空</b>：失败结果里 {@code observation} 为空，
 * 但存储里旧观测仍在；读取时应继续返回旧快照 + 时间戳，并把当前内容标为"无法确认"。
 */
public record RefreshResult(RefreshStatus status, Observation observation, FailReason reason,
                            String message, long elapsedMillis) {

    public boolean success() {
        return status == RefreshStatus.SUCCESS;
    }

    public static RefreshResult ok(Observation observation, long elapsedMillis) {
        return new RefreshResult(RefreshStatus.SUCCESS, observation, FailReason.NONE,
                "collected", elapsedMillis);
    }

    public static RefreshResult fail(RefreshStatus status, FailReason reason, String message,
                                     long elapsedMillis) {
        return new RefreshResult(status, null, reason, message, elapsedMillis);
    }

    public static RefreshResult failed(FailReason reason, String message, long elapsedMillis) {
        return fail(RefreshStatus.FAILED, reason, message, elapsedMillis);
    }

    public static RefreshResult timedOut(long timeoutMillis) {
        return fail(RefreshStatus.TIMED_OUT, FailReason.TIMED_OUT,
                "refresh timed out after " + timeoutMillis + " ms", timeoutMillis);
    }

    public static RefreshResult cancelled() {
        return fail(RefreshStatus.CANCELLED, FailReason.CANCELLED, "refresh cancelled", 0L);
    }
}
