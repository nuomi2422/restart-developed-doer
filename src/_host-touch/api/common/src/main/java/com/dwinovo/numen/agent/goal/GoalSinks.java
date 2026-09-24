package com.dwinovo.numen.agent.goal;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * 目标处理的可插拔接缝：RDD（或其他目标系统）在这里登记一个 sink，接管
 * {@code /goal} 设定的长期目标。
 *
 * <h2>为什么要有它</h2>
 * 引擎自带的长期目标是「objective + 独立评估器」。RDD 是另一套任务链（一级目标→二级目标→
 * 资产/检测），两者不能同时驱动同一个目标。这里定一条纪律：<b>最多一个 sink</b>——RDD 在场
 * 就交给 RDD（引擎不再注入 initialDirective、不留 goal、不走评估器），RDD 不在场就走引擎默认。
 *
 * <h2>兜底就是「插件不在场」</h2>
 * 没有注册任何 sink 时 {@link #dispatch} 恒返回 {@code false}，引擎原样跑自己那套。RDD
 * 出问题、被卸载、注册失败，都会自然落回引擎默认——不需要额外开关。
 */
public final class GoalSinks {

    /** 返回 {@code true} = 已接管该目标，引擎不再走默认目标循环。 */
    @FunctionalInterface
    public interface Sink {
        boolean accept(UUID companionId, String objective);
    }

    private static volatile Sink sink;
    private static volatile BiFunction<UUID, String, Boolean> clearer;

    private GoalSinks() {}

    /** RDD 插件在 setup 时登记。后登记的替换先登记的（理应只有一个）。 */
    public static void register(Sink s) {
        sink = s;
    }

    /** 目标被清掉时通知接管者；返回 {@code true} = 接管者认领。 */
    public static void registerClear(BiFunction<UUID, String, Boolean> c) {
        clearer = c;
    }

    /** 引擎在 {@code /goal X} 设定目标时调用。 */
    public static boolean dispatch(UUID companionId, String objective) {
        Sink s = sink;
        if (s == null || companionId == null) {
            return false;
        }
        try {
            return s.accept(companionId, objective == null ? "" : objective);
        } catch (RuntimeException e) {
            // 接管者炸了：按没接管处理，引擎走默认兜底。
            com.dwinovo.numen.Constants.LOG.error("[numen] 目标 sink 出错，退回引擎默认目标循环", e);
            return false;
        }
    }

    /** 引擎在目标收工时调用。 */
    public static boolean clear(UUID companionId, String reason) {
        BiFunction<UUID, String, Boolean> c = clearer;
        if (c == null || companionId == null) {
            return false;
        }
        try {
            Boolean ok = c.apply(companionId, reason);
            return ok != null && ok;
        } catch (RuntimeException e) {
            com.dwinovo.numen.Constants.LOG.error("[numen] 目标清空 sink 出错", e);
            return false;
        }
    }

    /** 有没有接管者（供调试/日志）。 */
    public static boolean hasSink() {
        return sink != null;
    }
}
