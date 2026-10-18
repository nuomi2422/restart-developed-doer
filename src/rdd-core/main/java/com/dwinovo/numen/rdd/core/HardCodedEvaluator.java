package com.dwinovo.numen.rdd.core;

import java.util.Map;

/**
 * 硬编码完成条件的纯 JVM 判定：只回答「给一堆物品计数，condition 满足没有」。
 *
 * <p>condition 形状（与 {@code Subtask.hardCoded} 一致）：
 * <pre>{@code
 * { "asset_key": "minecraft:oak_log", "minimum": 5 }
 * }</pre>
 *
 * <p>判定是纯函数、无副作用、无 IO——真身读背包的工作在宿主侧（{@code RddDetector}），
 * 这一层只做「计数够了没」，好单测。
 */
public final class HardCodedEvaluator {

    private HardCodedEvaluator() {}

    /** 缺省阈值：condition 里没给 minimum 时按 1 计。 */
    public static final int DEFAULT_MINIMUM = 1;

    /** {@code mode} 缺省值 = 持有语义（与本轮改动前的字节行为完全一致）。 */
    public static final String MODE_HOLD = "hold";
    /** {@code mode} 显式取值：本次二级期间<b>新增</b>多少，而不是当前持有多少。 */
    public static final String MODE_ACQUIRE = "acquire";

    /**
     * @param condition 二级目标的硬编码条件（{@code asset_key} 必需）
     * @param counts    物品 ID → 数量（宿主从真实环境统计）
     * @return 计数达到/超过 minimum 才算满足；缺 key 或条件畸形返回 {@code false}
     */
    public static boolean matches(Map<String, Object> condition, Map<String, Integer> counts) {
        return matches(condition, counts, null);
    }

    /**
     * 带 baseline 的判定：{@code mode=acquire} 时按<b>增量</b>算。
     *
     * <p><b>为什么需要 acquire</b>（2026-09-30 实机假完成）：派"收集 wheat_seeds x10"时她背包
     * 天生就有 33 个，{@code hold} 判据当场秒过 —— 二级直接 COMPLETED，<b>她一件都没种</b>。
     * 持有语义对"现在手里够不够"是对的，对"这一步有没有真的干活"是错的。
     *
     * <p>{@code baseline} = 进入该二级那一刻的计数（{@link TaskChain} 捕获，见
     * {@code acquireBaselines}）。{@code mode=hold}（缺省）时<b>不读 baseline</b>，
     * 与两参重载逐字一致；{@code mode=acquire} 且 baseline 缺失 → <b>判 false</b>
     * （宁可"还没达成"，也不在没有基线时假装达成）。
     *
     * <p>{@code acquire} 算的是<b>净增量</b>（当前 − 基线），所以"边做边消耗"会抵消收益。
     * 这是刻意的：{@code hold} 管"现在手里够不够"（不消耗型目标），
     * {@code acquire} 管"这一步有没有真的弄到东西"（采集型目标）。两者职责不重叠。
     *
     * <p>未知 {@code mode} 值一律 {@code false}：不静默降级成 hold
     * （那会让拼错的键悄悄变成另一种语义，正是本轮要消灭的那类假完成）。
     */
    public static boolean matches(Map<String, Object> condition, Map<String, Integer> counts,
                                  Map<String, Integer> baseline) {
        if (condition == null || counts == null) {
            return false;
        }
        if (condition.containsKey("type") && !"inventory".equals(condition.get("type"))) return false;
        Object minimumObj = condition.get("minimum");
        if (minimumObj != null && (!(minimumObj instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue())) return false;
        int minimum = minimumObj instanceof Number n ? n.intValue() : DEFAULT_MINIMUM;
        if (minimum < 0) return false;
        if (condition.containsKey("group")) {
            Object group = condition.get("group");
            return !condition.containsKey("asset_key") && InventoryGroups.known(group) && minimum > 0
                    && InventoryGroups.count((String) group, counts) >= minimum;
        }
        Object assetKey = condition.get("asset_key");
        if (!(assetKey instanceof String key) || key.isBlank()) return false;
        if (MODE_ACQUIRE.equals(modeOf(condition))) {
            if (baseline == null) {
                // 2026-10-02 改过一次又撤回：曾在这里降级成 hold 语义，理由是「别让子目标不可完成」。
                // 但那是在**掩盖上游 bug** —— 真正的病根是 rdd_submit 走的 startCurrent() 不拍基线，
                // 已在 TaskChain.startCurrentWithCounts + RddSubmitTool 那一层从源头修掉。
                //
                // 这里必须保持「无基线 = 判未达成」（旧存档场景，守护
                // HardCodedEvaluatorModeTest.acquireWithoutBaselineIsNeverSatisfied 与
                // TaskChainAcquireBaselineTest.oldSaveWithoutBaselineKeyLoadsAsNoBaseline）：
                // acquire 的职责是「防她早就有了、算假完成」，一旦无基线就放行，
                // 旧存档里「她背包有 99 个种子 + 任务是 gather seeds」会**瞬间假完成**。
                // 宁可漏判，也不假完成 —— 这是本条守卫的原意，不要动。
                return false;
            }
            int gained = countOf(key, counts) - countOf(key, baseline);
            return gained >= minimum;
        }
        if (!MODE_HOLD.equals(modeOf(condition))) return false; // 未知/畸形 mode：显式不满足
        return holdSatisfied(key, minimum, counts);
    }

    /**
     * {@code hold} 语义：背包/持有量达到 {@code minimum} 就算满足（硬指标，不猜）。
     */
    private static boolean holdSatisfied(String key, int minimum, Map<String, Integer> counts) {
        if (minimum == 0) return true;
        return countOf(key, counts) >= minimum;
    }

    /**
     * 计数：精确键优先；精确键<b>不存在</b>时，对「裸名」做<b>变体族兜底</b>再数。
     *
     * <p>2026-10-07 用户实测缺陷：模型会写 {@code minecraft:bed} —— 但 1.13+ 的床是按颜色拆开的
     * （{@code white_bed}/{@code red_bed}…），根本没有 {@code minecraft:bed} 这个物品；羊毛/木板/
     * 台阶/树苗同理。于是条件永远对不上、二级卡在 STALLED（实测「合成床」卡死，而背包里明明有
     * {@code minecraft:white_bed}）。
     *
     * <p>兜底只在【精确键不存在】且【键名是不带下划线的裸名（如 bed/wool/planks）】时生效：
     * 把 {@code minecraft:<裸名>} 当成「该族的任意变体」，把 {@code *_<裸名>} 全部加总。
     * 精确键在的时候一律走精确，不改变既有语义。
     */
    static int countOf(String key, Map<String, Integer> counts) {
        if (key == null || counts == null) {
            return 0;
        }
        Integer exact = counts.get(key);
        if (exact != null) {
            return Math.max(0, exact);
        }
        String suffix = bareVariantSuffix(key);
        if (suffix == null) {
            return 0;
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            if (k.endsWith(suffix)) {
                total += e.getValue();
            }
        }
        return total;
    }

    /** {@code minecraft:bed} → {@code "_bed"}；带下划线 / 非 minecraft 命名空间 / 空名 → null（不兜底）。 */
    private static String bareVariantSuffix(String key) {
        if (!key.startsWith("minecraft:")) {
            return null;
        }
        String name = key.substring("minecraft:".length());
        if (name.isEmpty() || name.indexOf('_') >= 0) {
            return null;
        }
        return "_" + name;
    }

    /**
     * 取并校验 {@code mode}：缺省 → {@code hold}；只接受 {@code hold}/{@code acquire}。
     * 畸形值返回 {@code null}（调用方据此判"条件非法"，不要当 hold 处理）。
     */
    public static String modeOf(Map<String, Object> condition) {
        if (condition == null || !condition.containsKey("mode")) return MODE_HOLD;
        Object raw = condition.get("mode");
        if (raw == null) return MODE_HOLD;
        if (!(raw instanceof String s)) return null;
        String v = s.trim();
        if (v.isEmpty()) return MODE_HOLD;
        return (MODE_HOLD.equals(v) || MODE_ACQUIRE.equals(v)) ? v : null;
    }
}
