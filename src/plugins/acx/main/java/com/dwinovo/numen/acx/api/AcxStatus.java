package com.dwinovo.numen.acx.api;

/**
 * AC 执行的终态。
 *
 * <p>比现有 AC 多一个 {@link #TIMEOUT}。现有 AC 只有 SUCCESS / PAUSED / FAILED，
 * 结果是「被熔断杀掉」和「做砸了」混在一起，事后分不出是哪个。</p>
 *
 * <p>语义边界（这是整个模块最容易搞错的地方）：</p>
 * <ul>
 *   <li>{@link #SUCCESS} —— 所有步骤都正常走完。</li>
 *   <li>{@link #PAUSED} —— <b>预期停止</b>：等环境、等人工、等材料，或者 while 因停滞/硬上限优雅收手。
 *       可以 resume 续跑，<b>不算失败</b>，不消耗重试预算。</li>
 *   <li>{@link #FAIL} —— 做砸了：步骤失败且未声明 {@code ignore_failure}，或引用了不存在的积木。</li>
 *   <li>{@link #TIMEOUT} —— 被 wall-clock 熔断杀掉。跟 FAIL 分开是因为它不反映脚本质量。</li>
 * </ul>
 */
public enum AcxStatus {
    SUCCESS,
    PAUSED,
    FAIL,
    TIMEOUT;

    public boolean isTerminalOk() {
        return this == SUCCESS;
    }

    /** PAUSED / FAIL / TIMEOUT 才需要落盘（对齐 DD 的落盘时机）。 */
    public boolean needsDurableRecord() {
        return this != SUCCESS;
    }
}