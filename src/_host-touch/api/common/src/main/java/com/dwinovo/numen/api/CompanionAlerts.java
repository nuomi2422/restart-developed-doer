package com.dwinovo.numen.api;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * 插件给同伴送「急件」的门面（{@code com.dwinovo.numen.api} 是插件编译期能看见的契约面）。
 *
 * <p>与 {@link NumenApi#enqueue} 的区别：enqueue 是「像主人打字」——进收件箱、按普通
 * 消息节奏处理；这里走世界事件通道（urgent），同伴正忙时排队、在下一个允许插入的
 * 时机立刻送达，还能把她从失败暂停里拉起来。危险提醒用这条。
 *
 * <p><b>调用方只该在最高优先级（P0）时调用</b>——每次送达都会花掉同伴的一轮模型调用；
 * 非 P0 的提醒走被动上下文（携带器 {@code <alarms>}）即可，不要走这里。
 * 正文写短建议，别塞长篇。
 */
public final class CompanionAlerts {

    private CompanionAlerts() {}

    /**
     * 危险提醒（急件）：rule/prio/facts 进事件属性（可对账），advice 是给同伴看的短建议。
     * 传入 null 同伴直接忽略；其余异常由事件通道自己兜底（不打断调用方）。
     */
    public static void danger(NumenPlayer companion, String rule, String prio, String facts, String advice) {
        if (companion == null) {
            return;
        }
        com.dwinovo.numen.event.NumenEvents.alarm(companion, rule, prio, facts, advice);
    }
}
