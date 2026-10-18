package com.dwinovo.numen.acx.api;

/**
 * ═══ INTERFACE CONTRACT: ACX-E1 事件出口 ═══
 * <p>语义：执行器只产生结构化事件，不认识 jsonl、不认识监测台、不认识网络。
 * 落盘与推送由宿主决定。</p>
 * <p>方向：{@code AcxRunner}（写） → {@code AcxEventSink}（读）</p>
 * <p>消费：监测台 jsonl、离线回归分析</p>
 * <p>违反：执行器里出现日志文件路径 → core 与宿主耦合，无法离线单测</p>
 */
@FunctionalInterface
public interface AcxEventSink {

    void accept(AcxEvent event);

    /** 什么都不做的出口，供离线单测与「明确不想观测」的场合使用。 */
    static AcxEventSink noop() {
        return event -> { };
    }
}