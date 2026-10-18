package com.dwinovo.numen.acx.api;

import java.util.Map;

/**
 * ═══ INTERFACE CONTRACT: ACX-T1 积木 ═══
 * <p>语义：AC 的最小可执行单元。执行器保证传入的 params 已经完成 {@code $} 引用解析，
 * 并保证熔断阈值与停滞检测在调用前后生效。</p>
 * <p>方向：{@code AcxRunner} → {@code AcxTool.execute}</p>
 * <p>消费：一切 AC 脚本</p>
 * <p>违反：积木自己读世界不走 {@link AcxGameThreadGate} → 跨线程崩溃（见 ACX-G1）</p>
 * <p>违反：积木长时间阻塞 → 撞 wall-clock 熔断，整条 AC 记 TIMEOUT</p>
 */
public interface AcxTool {

    String name();

    AcxToolSchema schema();

    AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx);

    /** 调用上下文：给积木回填取消检查、执行身份、事件上报。 */
    interface AcxCallContext {

        String runId();

        String stepId();

        /** 熔断已触发时为 {@code true}。长耗时积木应在循环里轮询它并提前收手。 */
        boolean isCancelRequested();

        /**
         * 顶层 AC 名。
         *
         * <p>适配层的「已受理」挂账不能拿 {@link #runId()} 当键 —— resume 每次
         * {@code runFrom} 都生成新 runId，用它会永远对不上，resume 退化成重发命令。
         * 默认空串是为了老实现不用改（引擎自己会填）。</p>
         */
        default String acName() {
            return "";
        }

        /**
         * 本次调用是否处于「断点续跑」路径。
         *
         * <p>resume 从断点步骤重跑时，前序步骤的输出没有持久化，{@code $prev.x} 这类
         * 引用解析不出来（会保留字面串）。适配层的挂账签名不能因此判「换了目标」把
         * 已受理的任务丢掉 —— 定义变了的话 {@code resume} 的指纹校验已经拦掉了。
         * 默认 {@code false}。</p>
         */
        default boolean isResuming() {
            return false;
        }

        /** 上报一条与本次调用相关的结构化事件。 */
        void emitDetail(String key, Object value);
    }
}