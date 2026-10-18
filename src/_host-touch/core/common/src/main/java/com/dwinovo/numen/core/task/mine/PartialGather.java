package com.dwinovo.numen.core.task.mine;

/**
 * 「采到一部分」和「采够了」的纯判据——不碰 Minecraft。
 *
 * <h2>为什么需要它</h2>
 * {@code MineCompanionTask} 的契约是<b>有意</b>这么定的:附近采不到更多了,采到部分
 * 也返回 SUCCESS(见 {@code onTick} 里 no-more-in-range 那一支的注释)。对"附近尽量采
 * 一些"这很合理。
 *
 * <p>问题出在<b>上层</b>:看到 SUCCESS 就打勾。于是"清空这一片干草"变成"挖了三根就走",
 * 而工具明明在文案里写了 {@code gathered 3/8}——只是没人读它,或者读了也没法据此
 * 判,因为终态里没有一个能程序化检查的字段。
 *
 * <p>所以这里只做一件事:把缺口变成两个数。<b>不改终态</b>——那会动到所有消费者,
 * 属于要单独批的改动。
 */
public final class PartialGather {

    private PartialGather() {}

    /** 采够了没有:够就是够,不够就是不够,不看别的。 */
    public static boolean isPartial(int gathered, int requested) {
        return gathered < requested;
    }

    /** 还差多少(永远不为负——采超了不是缺口)。 */
    public static int shortfall(int gathered, int requested) {
        return Math.max(0, requested - gathered);
    }
}
