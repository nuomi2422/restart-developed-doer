package com.dwinovo.numen.acx.api;

import java.util.concurrent.Callable;

/**
 * ═══ INTERFACE CONTRACT: ACX-G1 主线程闸门 ═══
 * <p>语义：AC 在工具线程跑，Minecraft 世界数据只能在服务端线程读。
 * 积木里任何读 {@code Level} / {@code BlockState} 的地方必须包在 {@link #call} 里。</p>
 * <p>方向：积木（读世界） → {@code AcxGameThreadGate} → 服务端线程执行</p>
 * <p>消费：所有需要读世界 / 改世界的积木</p>
 * <p>违反：跨线程传输错误（旧 DD 版实测踩过，2026-08-05 记录为已确认的坑）</p>
 * <p>参考：DD 版 {@code brain/ac/WorldReadGuard.java}</p>
 *
 * <p><b>为什么方法不是泛型</b>：{@code <T> T call(...)} 会让 lambda 无法实现该接口
 * （javac: invalid functional descriptor / method is generic），宿主被迫写匿名类。
 * 闸门实际只服务积木执行，返回类型就是 {@link AcxStepOutcome}，因此做成非泛型，
 * 让宿主可以直接写 {@code task -> server.execute(task)}。</p>
 */
@FunctionalInterface
public interface AcxGameThreadGate {

    /**
     * 在服务端线程执行 {@code task} 并把结果带回调用线程。
     *
     * <p>已经在服务端线程时应直接执行，不要绕线程池。</p>
     *
     * @throws Exception task 自身抛出的异常，或闸门无法在超时内完成
     */
    AcxStepOutcome call(Callable<AcxStepOutcome> task) throws Exception;
}