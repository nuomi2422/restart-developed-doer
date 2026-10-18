package com.dwinovo.numen.acx.api;

/**
 * ═══ INTERFACE CONTRACT: ACX-R1 执行记录落盘 ═══
 * <p>语义：每次以终态收尾都会调用 {@code append}（含 SUCCESS，供质量台账计数）；
 * 是否落盘由实现决定 —— {@code JsonlRecordStore} 只写 PAUSED / FAIL / TIMEOUT，
 * 避免正常路径淹掉日志。</p>
 * <p>方向：{@code AcxRunner}（写） → Store（读）</p>
 * <p>消费：离线回归分析、事故复盘、监测台「上次为什么停」面板</p>
 * <p>违反：进程一挂就全丢 —— 现有 AC 的记录只在内存里，这是已登记的洞（差异清单 §11）</p>
 * <p>参考：DD 版 {@code ACExecutionStore} + {@code DebugBridge.acRecord} → {@code dd-errors.jsonl}</p>
 */
@FunctionalInterface
public interface AcxRecordStore {

    void append(AcxRunRecord record);

    /** 内存丢弃，供离线单测使用。 */
    static AcxRecordStore noop() {
        return record -> { };
    }
}