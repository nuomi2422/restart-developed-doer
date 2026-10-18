package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 支线任务 / 直线任务 / 即时分任务（Local Repair Task）。
 *
 * <p>【用户架构概念 1/4】不改大规划的临时任务机制，应对突发（死亡、物品丢失、环境突变、
 * 顺手可做）。特点：<b>小、快、即时、可选</b>，不轻易扰动主任务链。死亡后不整链重规划，
 * 先生成即时直线任务处理眼前问题。
 *
 * <p>本类是<b>纯数据 + 纯函数</b>：承载支线任务的字段、目标生命周期、恢复策略与
 * 「中断风暴护栏」判定。不碰任务链、不触 MC、不派工（派工由宿主执行）。
 *
 * <h2>生命周期（父链重规划时级联取消）</h2>
 * 每条支线任务挂 {@code parentLink}（所属主链）+ {@code causationId}（因果来源事件），
 * 主链重规划时按 parentLink <b>级联取消</b>，防孤儿任务逃逸版本管理。
 *
 * <h2>中断风暴护栏</h2>
 * 每类中断 {@code cooldown}、每分钟上限、连续失败 N 次才升级重规划；数量与总时长双上限，
 * 超限<b>上报</b>而非继续插队。见 {@link Guard}。
 */
public final class LocalRepairTask {

    private LocalRepairTask() {}

    /** 优先级通道：critical 抢占 / high 插短任务 / normal 只更新状态 / low 不打断。 */
    public enum Priority { CRITICAL, HIGH, NORMAL, LOW }

    /** 触发来源。 */
    public enum Trigger { DEATH, ITEM_LOST, ENV_CHANGED, ASSET_ALREADY_PRESENT, MANUAL }

    /** 恢复策略四档（checkpoint 恢复）：断点续 -> 重搜目标 -> 局部重规划 -> 上报 Supervisor。 */
    public enum ResumePolicy { RESUME_CHECKPOINT, RESEARCH_TARGET, LOCAL_REPLAN, ESCALATE_SUPERVISOR }

    /**
     * 一条支线任务（不可变）。
     *
     * @param id            支线任务 id
     * @param parentLink    所属主链 id（父链重规划时据此级联取消）
     * @param causationId   因果来源（哪个事件触发；用于去重与追溯）
     * @param trigger       触发来源
     * @param priority      优先级通道
     * @param timeoutTicks  本任务超时 tick
     * @param resumePolicy  恢复策略
     * @param checkpoint    断点数据（被打断后据此续跑；null=无断点，从头）
     * @param successCondition 成功条件的人类可读描述（机器判定由宿主映射）
     * @param failurePolicy 失败策略（如 ESCALATE/ABORT）
     * @param description   任务描述（给执行体的可读说明）
     */
    public record Task(String id, String parentLink, String causationId, Trigger trigger,
                       Priority priority, long timeoutTicks, ResumePolicy resumePolicy,
                       String checkpoint,
                       String successCondition, String failurePolicy, String description) {
        public Task {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
            if (parentLink == null || parentLink.isBlank()) throw new IllegalArgumentException("parentLink required");
            if (trigger == null) trigger = Trigger.MANUAL;
            if (priority == null) priority = Priority.NORMAL;
            if (resumePolicy == null) resumePolicy = ResumePolicy.RESUME_CHECKPOINT;
            if (timeoutTicks <= 0) timeoutTicks = 20 * 60; // 1 min 缺省
        }

        /** 该支线是否可抢占当前动作（critical/high 才抢占）。 */
        public boolean preemptive() {
            return priority == Priority.CRITICAL || priority == Priority.HIGH;
        }

        /** 是否带断点（可续跑）。 */
        public boolean hasCheckpoint() {
            return checkpoint != null && !checkpoint.isBlank();
        }

        /** 便捷构造：无断点。 */
        public static Task of(String id, String parentLink, String causationId, Trigger trigger,
                              Priority priority, long timeoutTicks, ResumePolicy resumePolicy,
                              String successCondition, String failurePolicy, String description) {
            return new Task(id, parentLink, causationId, trigger, priority, timeoutTicks, resumePolicy,
                    null, successCondition, failurePolicy, description);
        }
    }

    /**
     * 中断风暴护栏（纯函数）。按触发类追踪冷却与频率，防止突发连环插队把主链撕碎。
     *
     * <p>判定顺序：连续失败达阈值 -> {@link Decision#ESCALATE}（升级重规划）；
     * 仍在冷却 -> {@link Decision#SUPPRESS}（压掉）；每分钟超限 -> {@link Decision#SUPPRESS}；
     * 否则 -> {@link Decision#ALLOW}。
     */
    public static final class Guard {

        private final int cooldownTicks;
        private final int maxPerMinute;
        private final int escalateAfterConsecutiveFailures;

        public Guard(int cooldownTicks, int maxPerMinute, int escalateAfterConsecutiveFailures) {
            this.cooldownTicks = Math.max(0, cooldownTicks);
            this.maxPerMinute = Math.max(1, maxPerMinute);
            this.escalateAfterConsecutiveFailures = Math.max(1, escalateAfterConsecutiveFailures);
        }

        /** 缺省护栏：同类冷却 10s、每分钟最多 6 次、连续失败 3 次升级。 */
        public static Guard defaults() {
            return new Guard(10 * 20, 6, 3);
        }

        /** 每个触发类的中断追踪状态（宿主持有并更新）。 */
        public static final class Tracker {
            private final java.util.Map<Trigger, Long> lastFireTick = new java.util.EnumMap<>(Trigger.class);
            private final java.util.Map<Trigger, List<Long>> recentFires = new java.util.EnumMap<>(Trigger.class);
            private final java.util.Map<Trigger, Integer> consecutiveFailures = new java.util.EnumMap<>(Trigger.class);

            public void recordFire(Trigger t, long tick) {
                lastFireTick.put(t, tick);
                recentFires.computeIfAbsent(t, k -> new ArrayList<>()).add(tick);
            }

            public void recordFailure(Trigger t) {
                consecutiveFailures.merge(t, 1, Integer::sum);
            }

            public void recordSuccess(Trigger t) {
                consecutiveFailures.put(t, 0);
            }

            public long lastFireTick(Trigger t) { return lastFireTick.getOrDefault(t, Long.MIN_VALUE); }
            public int consecutiveFailures(Trigger t) { return consecutiveFailures.getOrDefault(t, 0); }

            /** 最近 1 分钟（1200 tick）内的触发次数。 */
            public int firesLastMinute(Trigger t, long now) {
                List<Long> list = recentFires.get(t);
                if (list == null) return 0;
                int count = 0;
                for (long fire : list) {
                    if (now - fire <= 20 * 60) count++;
                }
                return count;
            }
        }

        public enum Decision { ALLOW, SUPPRESS, ESCALATE }

        /**
         * 评估是否允许放行一条支线任务。
         *
         * @param tracker 该触发类的追踪状态
         * @param trigger 触发来源
         * @param nowTick 当前游戏 tick
         * @param priority 优先级（CRITICAL 不受冷却，但仍受每分钟上限——防持续 critical 源无限抢占）
         */
        public Decision evaluate(Tracker tracker, Trigger trigger, long nowTick, Priority priority) {
            if (tracker == null) return Decision.ALLOW;
            // 连续失败达阈值：升级重规划（不再继续插队）
            if (tracker.consecutiveFailures(trigger) >= escalateAfterConsecutiveFailures) {
                return Decision.ESCALATE;
            }
            // critical 不受冷却（生死攸关直通），但【仍受每分钟上限】——W 审核：
            // 持续 critical 源（持续掉血/岩浆边）否则会连发事件无限抢占。
            if (priority == Priority.CRITICAL) {
                if (tracker.firesLastMinute(trigger, nowTick) >= maxPerMinute) {
                    return Decision.SUPPRESS;
                }
                return Decision.ALLOW;
            }
            // 冷却内重复 -> 压掉
            long last = tracker.lastFireTick(trigger);
            if (last != Long.MIN_VALUE && nowTick - last < cooldownTicks) {
                return Decision.SUPPRESS;
            }
            // 每分钟超限 -> 压掉
            if (tracker.firesLastMinute(trigger, nowTick) >= maxPerMinute) {
                return Decision.SUPPRESS;
            }
            return Decision.ALLOW;
        }
    }

    /** 便捷：从触发来源给出缺省优先级（死亡/物品丢失为高，环境为中，顺手为低）。 */
    public static Priority defaultPriority(Trigger trigger) {
        if (trigger == null) return Priority.NORMAL;
        return switch (trigger) {
            case DEATH -> Priority.CRITICAL;
            case ITEM_LOST -> Priority.HIGH;
            case ENV_CHANGED -> Priority.NORMAL;
            case ASSET_ALREADY_PRESENT, MANUAL -> Priority.LOW;
        };
    }
}
