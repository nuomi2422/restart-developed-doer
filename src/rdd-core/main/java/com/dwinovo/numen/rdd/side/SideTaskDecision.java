package com.dwinovo.numen.rdd.side;

/**
 * 开一条支线之前的决策：立即执行 / 推迟 / 本次跳过。
 *
 * <p>与 {@link SideTaskVerdict#skip} <b>不是一回事</b>：那个是支线<b>已经开始后</b>超时收尾；
 * 这个是<b>开之前</b>问一句「现在该不该开」——把"要不要现在做这件事"和"这件事做没做完"分开。
 *
 * @param kind       EXECUTE=执行 / DEFER=推迟 / SKIP=本次跳过（本晚不再来，下晚重新判断）
 * @param deferTicks DEFER 时的重评估冷却（tick）；引擎会抬到"至少一拍"防每拍重决策
 * @param reason     决策依据（写进监测事件，便于复盘）
 */
public record SideTaskDecision(Kind kind, int deferTicks, String reason) {

    public enum Kind {
        EXECUTE,
        DEFER,
        /** 本次跳过：不执行，但**不是**完成、也不是永久关闭；下一晚重新判断。 */
        SKIP
    }

    public SideTaskDecision {
        kind = kind == null ? Kind.EXECUTE : kind;
        if (deferTicks < 0) deferTicks = 0;
        reason = reason == null ? "" : reason;
    }

    public static final SideTaskDecision EXECUTE = new SideTaskDecision(Kind.EXECUTE, 0, "");

    public static SideTaskDecision defer(int ticks, String reason) {
        return new SideTaskDecision(Kind.DEFER, ticks, reason);
    }

    public static SideTaskDecision skip(String reason) {
        return new SideTaskDecision(Kind.SKIP, 0, reason);
    }
}
