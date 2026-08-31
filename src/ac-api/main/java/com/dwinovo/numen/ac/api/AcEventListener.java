package com.dwinovo.numen.ac.api;

/**
 * 执行生命周期事件监听（旁路观察）。与 {@link ExecutionListener} 的区别：
 * 本接口收到 step 级与 execution 级事件流，{@link ExecutionListener} 只收到
 * 终态记录。监听器抛异常不得破坏 AC 执行（执行器会捕获并隔离）。
 */
@FunctionalInterface
public interface AcEventListener {

    /** 收到一个执行事件。实现抛出的 RuntimeException 会被执行器捕获隔离。 */
    void onEvent(AcEvent event);
}
