package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 执行层小规划 · 有界审议 + 方案选择（Bounded Deliberation & Plan Choice）。
 *
 * <p>【用户架构概念 3/4 + 4/4】大规划属 RDD；下场干活的执行人员常不会选方案/工具利用率低。
 * 给它加"小规划块"：收到具体任务后 -> 列可行方案 -> 比较 -> 选择 -> 执行。
 * 比较维度：风险/耗时/效率/可行性/工具好不好用/环境是否方便/执行成本/后续是否方便。
 * 不是"能不能完成"，而是"现在哪种完成方式更合适"。
 *
 * <p>反空转铁律（W 层研究）：候选<b>从策略表枚举</b>（禁止自由生成）；快照二值硬过滤；
 * <b>只剩 1 个候选 -> 跳过比较直接执行</b>（快路径，反空转命根子）；硬预算 + 必然终结：
 * 合法出口只有两个——"选定方案执行"或"无可行方案发重规划事件"，绝不循环自问。
 *
 * <p>本类是纯函数：不调 LLM、不触 MC。LLM（若用）只是给候选打分的可选增强，不是必需。
 */
public final class Deliberation {

    private Deliberation() {}

    /**
     * 一个候选方案（来自策略表/工具前置后置条件组合，不自由生成）。
     *
     * @param id          方案 id
     * @param action      动作描述（交给身体的 execute 意图）
     * @param feasible    可行性（0..1，快照二值硬过滤后仍可给软分）
     * @param efficiency  效率（0..1）
     * @param toolFit     工具适配（0..1）
     * @param risk        风险（0..1，越高越差）
     * @param timeCost    耗时（0..1，越高越差）
     */
    public record Candidate(String id, String action,
                            double feasible, double efficiency, double toolFit,
                            double risk, double timeCost) {}

    /** 评分权重（W 层建议，可调）：可行 0.35、效率 0.20、工具 0.15、风险 -0.20、耗时 -0.10。 */
    public record Weights(double feasible, double efficiency, double toolFit, double risk, double timeCost) {
        public static Weights defaults() {
            return new Weights(0.35, 0.20, 0.15, -0.20, -0.10);
        }
    }

    /**
     * 选择结果。三个合法出口（W 审核补第三出口，防信息缺口一律升级重规划）：
     * <ul>
     *   <li>{@code EXECUTE} —— 选定方案执行</li>
     *   <li>{@code INSERT_RECON} —— 信息不足（无候选但可去侦察）→ 插侦察任务，不升级重规划</li>
     *   <li>{@code REPLAN_NEEDED} —— 确无可行方案 → 发重规划事件</li>
     * </ul>
     */
    public record Decision(Kind kind, Candidate chosen, List<String> trace) {
        public enum Kind { EXECUTE, INSERT_RECON, REPLAN_NEEDED }
    }

    /** 按权重打分（越高越优）。 */
    public static double score(Candidate c, Weights w) {
        return c.feasible() * w.feasible()
                + c.efficiency() * w.efficiency()
                + c.toolFit() * w.toolFit()
                + c.risk() * w.risk()
                + c.timeCost() * w.timeCost();
    }

    /** 缺省最低可接受分：低于此分（含全负分场景）不硬选，改插侦察/重规划。 */
    public static final double MIN_ACCEPTABLE_SCORE = 0.0;

    /**
     * 审议：硬过滤 -> 快路径 -> 打分择优 -> 必然终结（三出口）。
     *
     * @param candidates 策略表枚举的候选（可能为空）
     * @param hardFilter 快照二值硬过滤（工具/可达/足够/安全）；null 表示不过滤。
     *                   <b>必须与执行前置校验共用一份实现</b>，否则快路径执行会失败（W 审核）。
     * @param weights    评分权重；null 用 defaults
     * @param minAcceptable 最低可接受分；低于它不硬选（&lt;0 表示不设限）。用 {@link #MIN_ACCEPTABLE_SCORE} 为缺省
     * @param reconAvailable 信息不足时是否可插侦察任务（true=插侦察，false=直接重规划）
     * @return 决策（EXECUTE / INSERT_RECON / REPLAN_NEEDED），带决策 trace
     */
    public static Decision deliberate(List<Candidate> candidates, Predicate<Candidate> hardFilter,
                                      Weights weights, double minAcceptable, boolean reconAvailable) {
        List<String> trace = new ArrayList<>();
        Weights w = weights == null ? Weights.defaults() : weights;

        List<Candidate> viable = new ArrayList<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (hardFilter == null || hardFilter.test(c)) {
                    viable.add(c);
                } else {
                    trace.add("过滤 " + c.id() + "：不满足硬条件");
                }
            }
        }

        if (viable.isEmpty()) {
            // 第三出口：信息不足（可侦察）→ 插侦察任务，不升级整链重规划
            if (reconAvailable) {
                trace.add("无可行方案但可侦察 -> INSERT_RECON（第三出口，防抖动回潮）");
                return new Decision(Decision.Kind.INSERT_RECON, null, trace);
            }
            trace.add("无可行方案且不可侦察 -> REPLAN_NEEDED");
            return new Decision(Decision.Kind.REPLAN_NEEDED, null, trace);
        }

        // 快路径：只剩 1 个候选，跳过比较直接执行（反空转命根子）
        if (viable.size() == 1) {
            Candidate only = viable.get(0);
            trace.add("仅 1 个可行方案 " + only.id() + " -> 快路径直接执行（跳过比较）");
            return new Decision(Decision.Kind.EXECUTE, only, trace);
        }

        // 多方案：按权重打分择最优
        viable.sort(Comparator.comparingDouble((Candidate c) -> score(c, w)).reversed());
        Candidate best = viable.get(0);
        for (Candidate c : viable) {
            trace.add(String.format("候选 %s 得分 %.3f", c.id(), score(c, w)));
        }
        // 最低分数线：全负分/低于阈值时不硬选最烂的（W 审核）
        double bestScore = score(best, w);
        if (minAcceptable >= 0 && bestScore < minAcceptable) {
            if (reconAvailable) {
                trace.add(String.format("最优 %.3f 低于可接受 %.3f -> INSERT_RECON", bestScore, minAcceptable));
                return new Decision(Decision.Kind.INSERT_RECON, null, trace);
            }
            trace.add(String.format("最优 %.3f 低于可接受 %.3f 且不可侦察 -> REPLAN_NEEDED", bestScore, minAcceptable));
            return new Decision(Decision.Kind.REPLAN_NEEDED, null, trace);
        }
        trace.add("选定 " + best.id() + "（合法出口之一：执行选定方案）");
        return new Decision(Decision.Kind.EXECUTE, best, trace);
    }

    /** 便捷重载：保留旧两出口语义（无最低分数线、不可侦察）。 */
    public static Decision deliberate(List<Candidate> candidates, Predicate<Candidate> hardFilter, Weights weights) {
        List<String> trace = new ArrayList<>();
        Weights w = weights == null ? Weights.defaults() : weights;
        List<Candidate> viable = new ArrayList<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (hardFilter == null || hardFilter.test(c)) viable.add(c);
                else trace.add("过滤 " + c.id() + "：不满足硬条件");
            }
        }
        if (viable.isEmpty()) {
            trace.add("无可行方案 -> 发重规划事件");
            return new Decision(Decision.Kind.REPLAN_NEEDED, null, trace);
        }
        viable.sort(Comparator.comparingDouble((Candidate c) -> score(c, w)).reversed());
        Candidate best = viable.get(0);
        trace.add(viable.size() == 1 ? "仅 1 个可行方案 -> 快路径" : "多方案打分择优");
        trace.add("选定 " + best.id());
        return new Decision(Decision.Kind.EXECUTE, best, trace);
    }
}
