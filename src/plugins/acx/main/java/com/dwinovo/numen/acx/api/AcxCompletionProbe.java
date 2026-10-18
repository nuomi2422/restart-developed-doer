package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ═══ INTERFACE CONTRACT: ACX-P1 完成探针 ═══
 *
 * <p>语义：受理回执（{@link AcxToolPort.Result#accepted}）只说明「任务排队了」。
 * 适配层在 resume 时用本探针查「那个 taskId 现在怎么样了」——
 * 拉模型；宿主也可以拿 task_finished 事件走
 * {@code PortToolAdapter.markCompleted / markFailed} 的推模型。</p>
 *
 * <p>方向：{@code PortToolAdapter} → {@code AcxCompletionProbe.probe}</p>
 * <p>典型实现：调 Numen 的 {@code task_status}；或直接读宿主任务表。</p>
 * <p>违反：探针把 RUNNING 报成 DONE → AC 提前推进，后续步骤全踩空</p>
 */
@FunctionalInterface
public interface AcxCompletionProbe {

    Snapshot probe(String taskId);

    enum State {
        RUNNING,
        DONE,
        FAILED
    }

    record Snapshot(State state, Map<String, Object> data, String message) {

        public Snapshot {
            if (state == null) {
                throw new IllegalArgumentException("Snapshot.state 不能为空");
            }
            data = data == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(data));
            message = message == null ? "" : message;
        }

        public static Snapshot running(String message) {
            return new Snapshot(State.RUNNING, Map.of(), message);
        }

        public static Snapshot done(Map<String, Object> data, String message) {
            return new Snapshot(State.DONE, data, message);
        }

        public static Snapshot failed(String message) {
            return new Snapshot(State.FAILED, Map.of(), message);
        }
    }
}
