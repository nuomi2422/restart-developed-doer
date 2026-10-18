package com.dwinovo.numen.acx.api;

/**
 * ═══ INTERFACE CONTRACT: ACX-X1 成功经验回流 ═══
 * <p>语义：一次执行以 {@link AcxStatus#SUCCESS} 收尾时，把摘要
 * {@link AcxExperience} 交给下游（记忆库 / 学习者 / 监测台）。</p>
 * <p>方向：{@code AcxRunner}（写） → Sink（读）</p>
 * <p>时机：<b>只在 SUCCESS</b>。PAUSED 是「还没完」不是经验；FAIL/TIMEOUT 走 JSONL 记录复盘。</p>
 * <p>消费：用户架构里那句「好用 → 待验证 / 待使用区，成功经验库」的入口。</p>
 * <p>纪律：Sink 抛异常必须被执行器吞掉 —— 记经验是观测行为，不能反过来把一次成功改成失败。</p>
 */
@FunctionalInterface
public interface AcxExperienceSink {

    void onSuccess(AcxExperience experience);

    /** 未接线时的默认值。 */
    static AcxExperienceSink noop() {
        return experience -> { };
    }
}
