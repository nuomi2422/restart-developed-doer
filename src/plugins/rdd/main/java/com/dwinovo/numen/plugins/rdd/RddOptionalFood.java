package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.InventoryGroups;

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
 * <p>调用点只会在“失败且重试耗尽”（{@link RddDetector}）或模型显式请求（{@link RddSkipTool}）时触发，
 * 不会在正常推进中主动跳过食物。
 */
final class RddOptionalFood {
    private RddOptionalFood() {}

    /** @param inventory 保留参数（旧策略靠它算替代reserve）；新策略不再依赖，但保持调用点签名不变。 */
    static boolean canSkip(Subtask task, Map<String, Integer> inventory) {
        return isOptionalFood(task);
    }

    /** 当前子步是否“可跳过的食物”：group=food 或 asset_key 为食物；显式 optional=false 否决。 */
    static boolean isOptionalFood(Subtask task) {
        if (task == null || task.condition() == null) {
            return false;
        }
        if (Boolean.FALSE.equals(task.condition().get("optional"))) {
            return false;
        }
        Object group = task.condition().get("group");
        if (group instanceof String g && "food".equals(g)) {
            return true;
        }
        Object key = task.condition().get("asset_key");
        return key instanceof String item && InventoryGroups.contains("food", item);
    }
}
