package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询同伴背包的物品清单（按物品汇总数量）。比 get_self_status 的槽位统计更实用：
 * 直接列出每种物品的名字和数量，规划合成/挖矿材料时用。由 Self-Compile 自变异系统生成。
 */
public final class RddGetInventoryTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_get_inventory";
    }

    @Override
    public String description() {
        return "列出同伴背包里的所有物品（名称+数量，按物品汇总）。当需要知道身上有什么材料、"
                + "数量够不够、缺什么来规划合成/挖矿时使用。不需要任何参数。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        );
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        var inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(key, stack.getCount(), Integer::sum);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total_slots", inv.getContainerSize());
        data.put("items", counts);
        reply.accept(TaskResult.ok("背包物品清单查询成功", data).toJson());
    }
}
