package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.InventoryGroups;
import com.dwinovo.numen.rdd.policy.AssetDerivation;

import java.util.Map;

/**
 * 可选食物子步的放开策略（2026-09 放宽，修“食物子步死锁”）。
 *
 * <p>旧策略只允许 4 种蔬菜（carrot/potato/beetroot/baked_potato）且要求背包已有 ≥16 份替代熟食
 * 才准跳——实战中任何其它食物（如 cooked_porkchop）一旦做不出/拿不到，就会永久 FAILED 把整条链卡死
 * （实测：RDD 目标 food=0、工具侧做不出熟食 → 无出口，supervisor 也只能干等）。
 *
 * <p>食物属消耗/可选类，按主人裁定不应硬阻塞主线，故放宽为：只要当前子步是“食物”
 * （{@code group=food}，或 {@code asset_key} 属 food 组）且未显式标 {@code optional=false}，即可跳过；
 * 跳过只记 SKIPPED，绝不冒充 COMPLETED。非食物（装备/工具/进度类）仍一律拒绝。
 *
 * <p><b>P2-C</b>：判定加入<b>资产派生/等价</b>——若当前食物子步的目标（如 bread）可由背包里的原料
 * （如小麦，等价表 wheat→bread）满足，则视为“食物已够”，可直接跳过，避免合成后又被要求重复耕作。
 *
 * <p>调用点只会在“失败且重试耗尽”（{@link RddDetector}）或模型显式请求（{@link RddSkipTool}）时触发，
 * 不会在正常推进中主动跳过食物。
 */
final class RddOptionalFood {
    private RddOptionalFood() {}

    /**
     * 是否可跳过该食物子步。
     *
     * @param inventory 当前真实背包计数（用于派生等价判断）
     */
    static boolean canSkip(Subtask task, Map<String, Integer> inventory) {
        // 只要当前子步是"可选食物"即允许跳过（原有放宽策略，不阻塞主线）。
        // foodAlreadyCovered 作为语义辅助：目标食物已由背包/派生等价覆盖时同样放行。
        return isOptionalFood(task);
    }

    /**
     * 目标食物是否已由背包（含派生等价）覆盖到 minimum。
     * 例：目标 bread≥4、背包含 12 小麦（等价 12 面包）→ 覆盖。
     */
    static boolean foodAlreadyCovered(Subtask task, Map<String, Integer> inventory) {
        if (task == null || task.condition() == null || inventory == null) return false;
        Object key = task.condition().get("asset_key");
        if (!(key instanceof String item) || item.isBlank()) {
            // group=food 类：直接用组计数（组本身已跨食物变体）
            return HardCodedEvaluator.matches(task.condition(), inventory);
        }
        Object min = task.condition().get("minimum");
        int minimum = min instanceof Number n ? n.intValue() : 1;
        if (minimum <= 0) return true;
        return AssetDerivation.equivalentCount(item, inventory) >= minimum;
    }

    /**
     * 当前子步是否可跳过。
     *
     * <p><b>2026-09-29 放宽（用户报告：「主任务里有些目标其实不重要，可以跳过，判定层不放过」）</b>：
     * 旧逻辑只认「食物」（{@code group=food} 或 asset_key 属 food 组），
     * 于是规划器在别处标的 {@code optional=true}（如「顺手砍几棵橡木」「带上火把」）
     * <b>完全不生效</b> —— 判定层照样重试到耗尽 → {@code markFailed} → 整条链卡死，
     * 而 {@code rdd_skip_optional} 也会被同一处限制拒绝。
     *
     * <p>现在规则：<b>显式 {@code optional=true} 直接放行（任何类型）</b>；
     * {@code optional=false} 一律否决；未标注时回落到原有的「食物」宽松策略。
     * 仍不伪造完成：跳过只记 SKIPPED（{@code TaskChain.skipSubtask}）。
     */
    static boolean isOptionalFood(Subtask task) {
        if (task == null || task.condition() == null) {
            return false;
        }
        if (Boolean.FALSE.equals(task.condition().get("optional"))) {
            return false;
        }
        // 显式声明可选 → 放行（不限类型）
        if (Boolean.TRUE.equals(task.condition().get("optional"))) {
            return true;
        }
        // 未声明：保持原有「食物」宽松策略
        Object group = task.condition().get("group");
        if (group instanceof String g && "food".equals(g)) {
            return true;
        }
        Object key = task.condition().get("asset_key");
        return key instanceof String item && InventoryGroups.contains("food", item);
    }
}
