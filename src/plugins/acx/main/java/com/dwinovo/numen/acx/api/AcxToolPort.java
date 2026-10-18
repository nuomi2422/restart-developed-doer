package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ═══ INTERFACE CONTRACT: ACX-N1 宿主工具端口 ═══
 *
 * <p>语义：宿主（Numen / Minecraft 侧）把「执行层工具」适配成这个端口；
 * {@code acx-core} 的适配层再把端口包成 {@link AcxTool} 给执行器用。
 * 端口是纯 JVM 的 —— 这一层不许出现任何 Minecraft / Numen 类型。</p>
 *
 * <p><b>受理 ≠ 完成</b>（协议级一等公民）：长动作工具会先回
 * {@link Outcome#ACCEPTED}（受理回执，带 {@code taskId}），真正完成走
 * task_finished 事件或 {@link AcxCompletionProbe} 轮询。适配层把 ACCEPTED 映射为
 * PAUSED（断点保留、可 resume），绝不映射为 SUCCESS。</p>
 *
 * <p>方向：{@code PortToolAdapter} → {@code AcxToolPort.invoke}</p>
 * <p>违反：把 ACCEPTED 当 SUCCESS → 「以为挖完了其实才刚受理」，整条 AC 语义塌陷</p>
 * <p>违反：{@code invoke} 里做 Minecraft 世界读写却不走宿主线程门 → 跨线程崩溃</p>
 */
public interface AcxToolPort {

    /** LLM / 脚本可见的工具名（snake_case，与 Numen ToolRegistry 注册名一致）。 */
    String name();

    default String description() {
        return "";
    }

    AcxPortSchema schema();

    /**
     * 执行一次。实现方负责把宿主返回的信封翻译成 {@link Result}。
     * 抛异常由适配层兜住并记 FAIL，不算契约违规。
     */
    Result invoke(Map<String, Object> params);

    enum Outcome {
        /** 动作已做完，{@code data} 是真实结果。 */
        COMPLETED,
        /** 已受理，后台执行中；真正完成靠 probe / task_finished。 */
        ACCEPTED,
        /** 明确失败。 */
        FAILED
    }

    /**
     * 一次调用的结果信封。
     *
     * @param taskId   受理回执里的任务 id；非受理场景为空串
     * @param standing 常驻任务（如 follow）：永远没有完成终点，resume 不可自动续跑
     */
    record Result(Outcome outcome, Map<String, Object> data, String message,
                  String taskId, boolean standing) {

        public Result {
            if (outcome == null) {
                throw new IllegalArgumentException("Result.outcome 不能为空");
            }
            data = data == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(data));
            message = message == null ? "" : message;
            taskId = taskId == null ? "" : taskId;
        }

        public static Result completed(Map<String, Object> data, String message) {
            return new Result(Outcome.COMPLETED, data, message, "", false);
        }

        public static Result completed(Map<String, Object> data) {
            return completed(data, "completed");
        }

        public static Result accepted(String taskId, Map<String, Object> acceptance, String message) {
            return new Result(Outcome.ACCEPTED, acceptance, message, taskId, false);
        }

        public static Result acceptedStanding(String taskId, Map<String, Object> acceptance, String message) {
            return new Result(Outcome.ACCEPTED, acceptance, message, taskId, true);
        }

        public static Result failed(String message) {
            return new Result(Outcome.FAILED, Map.of(), message, "", false);
        }

        public boolean isCompleted() {
            return outcome == Outcome.COMPLETED;
        }

        public boolean isAccepted() {
            return outcome == Outcome.ACCEPTED;
        }

        public boolean isFailed() {
            return outcome == Outcome.FAILED;
        }
    }
}
