package com.dwinovo.numen.acx.core;

import java.util.List;

import com.dwinovo.numen.acx.api.AcxPortSchema;

/**
 * Numen 执行层工具的<b>参考目录</b>（纯数据，不执行）。
 *
 * <p>用途：① 迁入时按此实现真正的 {@code AcxToolPort} 壳；
 * ② 离线测试用它当夹具，保证 .ac 引用的是真名字、真字段。</p>
 *
 * <p>字段来源（2026-10-02 只读实测 minecraft-numen，行号当时 HEAD）：</p>
 * <ul>
 *   <li>{@code get_self_status}：{@code GetSelfStatusTool.java:61-136}（裸 JSON，无 success 包裹）</li>
 *   <li>{@code goto}：{@code MoveToTool.java:48-71}（x/y/z nullable 必填；异步 setTask）</li>
 *   <li>{@code mine}：{@code AutoMineTool.java:46-59}（block_ids[] + count 必填；完成 data 见 MineCompanionTask.java:939-948）</li>
 *   <li>{@code collect_items}：{@code CollectItemsTool.java:42-54}</li>
 *   <li>{@code craft}：{@code CraftTool.java:40-51}（当场同步返回 data.crafted/carrying）</li>
 *   <li>{@code rdd_get_inventory}：{@code RddGetInventoryTool.java:40-56}</li>
 *   <li>{@code task_status / task_stop / set_timer}：{@code TaskStatusTool.java:57-84} 等</li>
 *   <li>受理信封形状：{@code TaskDispatch.java:82,100-106}（success=true 但 data.async=true）</li>
 * </ul>
 *
 * <p><b>目录故意不全</b>：没有实测到参数形状的工具（如 {@code scan_blocks}、
 * {@code attack}）宁缺毋滥 —— 迁入时按实际源码补齐，禁止臆造 schema。</p>
 */
public final class NumenToolCatalog {

    public record ToolSpec(String name, String description, AcxPortSchema schema) { }

    private NumenToolCatalog() { }

    public static ToolSpec find(String name) {
        for (ToolSpec t : core()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    public static List<ToolSpec> core() {
        return List.of(
            new ToolSpec("get_self_status",
                "读取自身状态（位置/生命/饥饿/维度/装备/背包用量）。裸 JSON，无 success 包裹。",
                s().output("position.x", "position.y", "position.z",
                        "hp", "max_hp", "hunger", "saturation",
                        "dimension", "biome", "on_ground", "in_water", "in_lava", "air",
                        "backpack_slots.used", "backpack_slots.total", "target")
                    .build()),

            new ToolSpec("rdd_whereami",
                "取当前维度与坐标。",
                s().output("dimension", "x", "y", "z", "yaw", "on_ground").build()),

            new ToolSpec("goto",
                "寻路移动到目标坐标。异步任务：先回受理回执（data.async=true），完成走 task_finished。",
                s().param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable()
                        .desc("目标 X（必填但可为 null）"))
                    .param("y", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable()
                        .desc("目标 Y（必填但可为 null）"))
                    .param("z", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable()
                        .desc("目标 Z（必填但可为 null）"))
                    .param("block", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .desc("目标方块名（可选）"))
                    .param("may_alter_terrain", AcxPortSchema.Param.opt(AcxPortSchema.Type.BOOLEAN)
                        .desc("是否允许破坏/放置地形"))
                    .output("final_x", "final_y", "final_z", "ground_y", "task_id")
                    .build()),

            new ToolSpec("mine",
                "按方块 id 列表挖到指定数量。异步任务。",
                s().param("block_ids", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING_ARRAY)
                        .desc("目标方块 id 列表（含 deepslate 变体）"))
                    .param("count", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).range(1, 256)
                        .desc("目标数量"))
                    .output("target", "requested", "gathered", "partial", "shortfall", "note", "task_id")
                    .build()),

            new ToolSpec("collect_items",
                "捡起周围掉落物。异步任务。",
                s().param("item_ids", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING_ARRAY)
                        .desc("限定物品 id；缺省捡全部"))
                    .param("radius", AcxPortSchema.Param.opt(AcxPortSchema.Type.INTEGER).range(1, 48)
                        .desc("搜索半径"))
                    .output("collected", "task_id")
                    .build()),

            new ToolSpec("craft",
                "按配方合成物品（当场同步返回）。",
                s().param("item_id", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING))
                    .param("count", AcxPortSchema.Param.opt(AcxPortSchema.Type.INTEGER).range(1, 256)
                        .desc("默认 1"))
                    .output("crafted", "carrying")
                    .build()),

            new ToolSpec("lookup_recipe",
                "查询配方文本（无结构化 data，仅 message）。",
                s().param("item_id", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)).build()),

            new ToolSpec("rdd_get_inventory",
                "读背包：总槽位 + 物品 id→数量。",
                s().output("total_slots", "items").build()),

            new ToolSpec("task_status",
                "查当前身体任务 / 计时器状态。",
                s().output("task_id", "task", "state", "elapsed_s", "budget_left_s").build()),

            new ToolSpec("task_stop",
                "停止身体任务（缺省停当前）。",
                s().param("task_id", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING))
                    .output("task_id")
                    .build()),

            new ToolSpec("set_timer",
                "登记一个到点提醒（不占身体）。",
                s().param("after_s", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER))
                    .param("reason", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING))
                    .output("timer_id", "after_s", "reason")
                    .build()),

            // ── 2026-10-02 真机实测补录（形状与 DD 完全不同，按实测写）──

            new ToolSpec("scan_mature_crops",
                "扫描附近【成熟】作物（age 到顶的小麦/胡萝卜/土豆/甜菜根/下界疣/甜浆果/可可），"
                + "最近优先、最多 32。输出 {crops:[{x,y,z,block,distance}]}。只读。",
                s().param("radius", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).range(1, 32)
                        .desc("球形搜索半径（1-32）"))
                    .output("crops")
                    .build()),

            new ToolSpec("farm_nearby",
                "一次调用把身边一片田的【成熟】作物全部“收 + 原地留苗”（产物进背包 + age 复位为 0，"
                + "不拆块、不耗种子，对齐车万女仆 harvest）。只碰成熟作物，输出 {harvested}。",
                s().param("radius", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).range(1, 32)
                        .desc("一片田的水平半径（1-32）"))
                    .output("harvested")
                    .build()),

            new ToolSpec("farm_cell",
                "原子收【一格】成熟作物 + 原地留苗（产物进背包 + age 复位为 0，不拆块、不耗种子）；"
                + "非成熟/太远/未加载一律明确失败、什么都不改。输出 {block, harvested}。",
                s().param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("作物 X"))
                    .param("y", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("作物 Y"))
                    .param("z", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("作物 Z"))
                    .output("block", "harvested")
                    .build()),

            new ToolSpec("scan_blocks",
                "球形范围内扫描方块。实测输出是 {matches:[{x,y,z,block,distance}]}，"
                + "没有 DD 那种 done/count/target_absX 扁平字段；筛选最近目标用 $filter+$pick+$take。",
                s().param("radius", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).range(1, 192)
                        .desc("球形搜索半径（1-192）"))
                    .param("block_ids", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING_ARRAY)
                        .desc("带命名空间的方块 id 列表"))
                    .output("matches")
                    .build()),

            new ToolSpec("inspect_block",
                "查单个方块（同步裸 JSON）。",
                s().param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER)
                        .desc("方块 X"))
                    .param("y", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER)
                        .desc("方块 Y"))
                    .param("z", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER)
                        .desc("方块 Z"))
                    .output("x", "y", "z", "block", "properties", "is_air", "is_solid",
                            "is_liquid", "hardness", "unbreakable", "needs_correct_tool",
                            "current_hand_correct_tool", "estimated_mining_ticks",
                            "distance_to_me", "in_reach")
                    .build()),

            new ToolSpec("get_world_info",
                "维度/游戏时间/天气（同步裸 JSON）。",
                s().output("dimension", "game_time", "is_bright_outside", "is_dark_outside",
                        "weather")
                    .build()),

            new ToolSpec("scan_nearby_entities",
                "扫描附近实体（按距离排序）。实测输出 {entities:[{id,type,category,position:{x,y,z},distance,hp}]}，"
                + "最多 20 条，truncated=true 表示还有更多。★ 2026-10-09 按实机回执补齐输出字段："
                + "此前故意不声明 outputs，导致按 type 筛羊的 .ac 在离线 strict 自检里被判"
                + "『引用了不存在的字段』（而实机是对的）——宁缺毋滥不该变成看不见真字段。",
                s().param("radius", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).range(1, 64)
                        .desc("搜索半径（1-64）"))
                    .param("type_filter", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)
                        .withEnum("hostile", "passive", "player", "all")
                        .desc("实体筛选"))
                    .output("entities", "total_found", "truncated", "radius_searched", "filter")
                    .build()),

            new ToolSpec("equip_item",
                "把背包里的物品拿到手上（或 unequip 放下）。同步工具：AC 直调时回执可能超时，"
                + "参数加 ignore_failure=true 照常执行。",
                s().param("action", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .withEnum("equip", "unequip").desc("equip（默认）/ unequip"))
                    .param("item_id", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .desc("要装备的物品 id；unequip 忽略"))
                    .param("slot", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .withEnum("mainhand", "offhand", "head", "chest", "legs", "feet", "armor")
                        .desc("equip 省略则按物品类型自动路由；unequip 必填"))
                    .build()),

            new ToolSpec("interact_at",
                "对世界坐标按一次鼠标键（左/右）。同步工具：AC 直调时回执可能超时，"
                + "参数加 ignore_failure=true 照常执行。必须已站到工作距离内（~4.5 格）。",
                s().param("button", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)
                        .withEnum("left", "right").desc("right=使用/激活，left=攻击/破坏"))
                    .param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).nullable().desc("瞄准 X"))
                    .param("y", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).nullable().desc("瞄准 Y"))
                    .param("z", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).nullable().desc("瞄准 Z"))
                    .param("hold_ticks", AcxPortSchema.Param.opt(AcxPortSchema.Type.INTEGER)
                        .desc("0/null=单击；>0=按住刻数；-1=按到完成"))
                    .param("item_id", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .desc("可选：先装备再用"))
                    .build()),

            new ToolSpec("count_entities_in_box",
                "数一个长方体区域内的实体。用于判『圈内已有几只羊』这类区域条件。",
                s().param("x1", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("盒角1 x"))
                    .param("y1", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("盒角1 y"))
                    .param("z1", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("盒角1 z"))
                    .param("x2", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("盒角2 x"))
                    .param("y2", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("盒角2 y"))
                    .param("z2", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER).desc("盒角2 z"))
                    .param("type_id", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .desc("只数该实体种，如 minecraft:sheep"))
                    .param("category", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                        .withEnum("passive", "all").desc("passive=动物 / all=全部（默认 all）"))
                    .output("count", "type_id", "box")
                    .build()),

            new ToolSpec("attack",
                "开打。省略 entity_ids 就是清场所有附近敌对实体。异步任务。",
                s().param("entity_ids", AcxPortSchema.Param.opt(AcxPortSchema.Type.INT_ARRAY).range(1, 20)
                        .desc("scan_nearby_entities 给的运行时实体 id（1-20 个）；省略=打所有"))
                    .build())
        );
    }

    private static AcxPortSchema.Builder s() {
        // Numen 的 Schema.object() 是 additionalProperties=false，这里对齐
        return AcxPortSchema.builder().allowUnknown(false);
    }
}
