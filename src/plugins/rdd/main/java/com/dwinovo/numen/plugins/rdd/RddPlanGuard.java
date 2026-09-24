package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规划守门器（纯逻辑）：模型输出<b>明显违反硬规则</b>时安全丢弃，而不是照单执行。
 *
 * <p>提示词只是请求，模型会不听。硬规则必须由代码执行，这一层就是执行的地方：
 * <ul>
 *   <li><b>未要求就规划可选升级</b>（书架 / 满级附魔台 / 铁砧 / 信标 / 生电部件）→ 丢弃。</li>
 *   <li><b>已持有的资产还要再去获取</b> → 从计划里去掉，记为复用了现有资产。</li>
 *   <li><b>可选工程主题</b>（生电 / 交易所 / 农场）在主人没要求时 → 整条阶段丢弃。</li>
 * </ul>
 *
 * <h2>清空的规则（边界写死，别靠感觉）</h2>
 * <ul>
 *   <li><b>违规项永不复活</b>：未被主人要求的可选升级/可选阶段被丢弃后，任何情况下都不会
 *       因为「结果空了」而被放回执行。宁可返回空表让上游安全降级，也不要照单去建刷铁机。</li>
 *   <li><b>全违规 → 空表</b>：返回空表即降级信号，交给上游走失败恢复。</li>
 *   <li><b>全已持有 → 保留</b>：这一条只适用于子步骤里「已持有」那一类——它们合法、只是多余，
 *       会被资产检测立刻验收；清空反而会把「本来就做完的一级」变成一次升级上报。
 *       阶段过滤没有这一类，所以阶段全被过滤时就是空表。</li>
 * </ul>
 *
 * <p>不改变既有解析行为：{@code parse} 仍是纯解析，守门是解析之后单独一步，便于单测与回滚。
 */
final class RddPlanGuard {

    /**
     * 子步骤过滤结果。
     *
     * @param allowed 允许进入计划的部分
     * @param dropped 被丢弃项的说明（可审计，不含隐藏推理）
     * @param reused  因已持有而被去掉的资产键（审计「复用了什么」）
     */
    record Filtered(List<SubtaskSpec> allowed, List<String> dropped, List<String> reused) {

        boolean degradedToEmpty() {
            return allowed.isEmpty();
        }
    }

    /**
     * 阶段过滤结果。
     *
     * @param allowed 允许进入计划的阶段
     * @param dropped 被丢弃的阶段说明
     */
    record Stages(List<PrimarySpec> allowed, List<String> dropped) {}

    private RddPlanGuard() {}

    /**
     * 过滤 Stage-B 的子步骤。
     *
     * @param specs     解析后的子步骤（可为空）
     * @param objective 当前一级主题（判定主人是否要求了可选升级）
     * @param held      当前真实背包（判定哪些已经持有）
     */
    static Filtered filterSubtasks(List<SubtaskSpec> specs, String objective, Map<String, Integer> held) {
        if (specs == null || specs.isEmpty()) {
            return new Filtered(List.of(), List.of(), List.of());
        }
        boolean optionalAllowed = RddPlanningPolicy.optionalAllowed(objective);

        List<SubtaskSpec> allowed = new ArrayList<>();
        List<SubtaskSpec> heldOnes = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        for (SubtaskSpec spec : specs) {
            String assetKey = assetKeyOf(spec);
            if (!optionalAllowed && RddPlanningPolicy.optionalAsset(assetKey)) {
                dropped.add("可选升级未获要求：" + assetKey);
                continue;
            }
            if (alreadyHeld(spec, held)) {
                reused.add(assetKey);
                heldOnes.add(spec);
                continue;
            }
            allowed.add(spec);
        }

        if (allowed.isEmpty()) {
            // 保护性让步只适用于「已持有」这一类：它们合法、只是多余，会被资产检测立刻验收，
            // 清空反而会把「本来就做完的一级」变成一次升级上报。
            // 违规的可选升级（dropped 那批）绝不因此复活。
            return new Filtered(List.copyOf(heldOnes), dropped, reused);
        }
        return new Filtered(List.copyOf(allowed), dropped, reused);
    }

    /**
     * 过滤 Stage-A 的阶段清单：主人没要求时，可选工程（生电/交易所/农场/满级）整条不要。
     *
     * <p><b>全部都是未获要求的可选分支时返回空阶段表</b>——交给上游安全降级/走失败恢复。
     * 绝不把原始违规阶段表放回执行：宁可这一级展开失败重来，也不要照单去建刷铁机。
     * （子步骤那边对「已持有」有不清空的保护，但那条保护不适用于违规的可选阶段。）
     */
    static Stages filterStages(List<PrimarySpec> stages, String objective) {
        if (stages == null || stages.isEmpty()) {
            return new Stages(List.of(), List.of());
        }
        if (RddPlanningPolicy.optionalAllowed(objective)) {
            return new Stages(List.copyOf(stages), List.of());
        }
        List<PrimarySpec> allowed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (PrimarySpec stage : stages) {
            String theme = stage == null ? "" : String.valueOf(stage.description());
            if (RddPlanningPolicy.optionalTheme(theme)) {
                dropped.add("可选分支未获要求：" + theme);
                continue;
            }
            allowed.add(stage);
        }
        return new Stages(List.copyOf(allowed), dropped);
    }

    // ---------- helpers ----------

    private static String assetKeyOf(SubtaskSpec spec) {
        if (spec == null || spec.condition() == null) {
            return "";
        }
        Object key = spec.condition().get("asset_key");
        return key == null ? "" : String.valueOf(key).trim();
    }

    private static int minimumOf(SubtaskSpec spec) {
        if (spec == null || spec.condition() == null) {
            return 1;
        }
        Object min = spec.condition().get("minimum");
        if (min instanceof Number n) {
            return n.intValue();
        }
        if (min instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return 1;
            }
        }
        return 1;
    }

    private static boolean alreadyHeld(SubtaskSpec spec, Map<String, Integer> held) {
        if (spec != null && spec.condition().containsKey("group"))
            return com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(spec.condition(), held);
        String key = assetKeyOf(spec);
        if (key.isEmpty() || held == null || held.isEmpty()) {
            return false;
        }
        Integer have = held.get(key);
        return have != null && have >= minimumOf(spec);
    }
}
