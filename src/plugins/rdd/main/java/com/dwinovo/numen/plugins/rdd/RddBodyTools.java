package com.dwinovo.numen.plugins.rdd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RDD body 语义 → NUMEN 真实工具的翻译桥。
 *
 * <p>分解器（LLM）只被允许产出受控词表 {@code mine / craft / equip_item / collect_items}，参数统一用
 * {@code {item, count}}（item=物品/矿石命名空间ID，count=数量）。本类把它翻译成对应 NUMEN 工具真正接受的
 * 参数形状（如 AutoMineTool 要 {@code block_ids} 数组，且石头级矿石要补 deepslate_ 变体）。
 *
 * <p>历史/别名兼容：早期分解器产出过臆造名 {@code mine_block / move_to / equip} → {@link #canonical} 归一；
 * 归一不出的（臆造且无别名）= 规划 bug，由调用方转 {@code subtask_capability_gap} 响亮暴露，
 * 不再静默"只检测不执行"空转（旧病：mine_block 每轮重试静默刷 20 分钟）。
 */
final class RddBodyTools {

    private RddBodyTools() {}

    /** 受支持的真实工具名；分解器只能产出这些。 */
    static final String MINE = "mine";
    static final String EQUIP = "equip_item";
    static final String CRAFT = "craft";
    static final String COLLECT = "collect_items";

    /** 受支持词表（供 prompt/文档一致引用）。 */
    static final List<String> SUPPORTED = List.of(MINE, EQUIP, CRAFT, COLLECT);

    private static final Pattern RAW_ORE = Pattern.compile("^([a-z0-9_.-]+):raw_([a-z0-9_]+)$");
    private static final Pattern STONE_ORE = Pattern.compile("^([a-z0-9_.-]+):([a-z0-9_]+)_ore$");

    /** 已知"挖矿掉落物"物品 → 产出它的矿石方块路径。这些是物品不是方块，挖矿目标要映射到矿石再挖
     * （路径与物品名不同的特殊例：lapis_lazuli → lapis_ore）。deepslate 变体由注册校验兜底跳过不存在的。 */
    private static final Map<String, String> DROP_TO_ORE = Map.of(
            "diamond", "diamond_ore",
            "coal", "coal_ore",
            "redstone", "redstone_ore",
            "lapis_lazuli", "lapis_ore",
            "emerald", "emerald_ore");

    /**
     * 村庄补给常见的作物掉落物 → 实际可破坏的作物方块。它们不是矿石，但 RDD 的
     * {@code mine} 语义同样是“取得此物品”；若不在这张小白名单内，非方块物品仍必须
     * 走 capability_gap，不能猜一个不存在的 {@code *_ore}。
     *
     * <p>只覆盖原版作物 ID；本映射不检查成熟度，不能据此宣称只采成熟作物。这个映射只修正
     * {@code minecraft:carrot} 这类“掉落物 ID 不等于方块 ID”的参数边界。
     */
    private static final Map<String, String> CROP_DROP_TO_BLOCK = Map.of(
            "carrot", "carrots",
            "potato", "potatoes",
            "wheat", "wheat",
            "beetroot", "beetroots");

    /** 归一 body.task_type → 真实工具名；臆造且无别名 → null（调用方转 capability_gap）。 */
    static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "mine", "mine_block", "mine_ore", "gather" -> MINE;
            case "equip_item", "equip" -> EQUIP;
            case "craft" -> CRAFT;
            case "collect_items", "collect" -> COLLECT;
            default -> null;
        };
    }

    /**
     * 把 body.args（{item, count, slot?} 语义）翻译成真实工具的 JsonObject 参数。
     *
     * @param conditionMinimum 该二级 condition 的 minimum，body 缺 count 时对齐用（可为 null）
     * @return 真实工具 args；缺关键参数翻译不出 → null
     */
    static JsonObject buildArgs(String canonical, Map<String, Object> a, Integer conditionMinimum) {
        if (a == null) {
            return null;
        }
        String item = str(a.get("item"));
        Integer count = count(a, conditionMinimum);
        return switch (canonical) {
            case MINE -> mineArgs(item, count);
            case EQUIP -> equipArgs(item, str(a.get("slot")));
            case CRAFT -> craftArgs(item, count);
            case COLLECT -> collectArgs(item);
            default -> null;
        };
    }

    private static JsonObject mineArgs(String item, Integer count) {
        List<String> ids = mineBlockIds(item);
        if (ids.isEmpty()) {
            return null;
        }
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String id : ids) {
            arr.add(id);
        }
        o.add("block_ids", arr);
        o.addProperty("count", count == null ? 1 : count);
        return o;
    }

    private static JsonObject equipArgs(String item, String slot) {
        if (item == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("action", "equip");
        o.addProperty("item_id", item);
        if (slot != null && !slot.isBlank()) {
            o.addProperty("slot", slot);
        }
        return o;
    }

    private static JsonObject craftArgs(String item, Integer count) {
        if (item == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item_id", item);
        o.addProperty("count", count == null ? 1 : count);
        return o;
    }

    private static JsonObject collectArgs(String item) {
        JsonObject o = new JsonObject();
        if (item != null && !item.isBlank()) {
            JsonArray arr = new JsonArray();
            arr.add(item);
            o.add("item_ids", arr);
        }
        return o; // 空 = 捡附近所有掉落物
    }

    /**
     * mine 的 target 展开：raw_铁 → 铁矿石 + 深板岩变体；挖矿掉落物物品（diamond/coal/redstone/lapis/emerald，
     * 是物品不是方块）→ 映射到产出它的矿石方块（+deepslate 变体，注册校验自动跳过不存在的）；
     * 已是矿石/其他真实方块的字面 id → 原样 +（石头级）补深板岩变体。
     * 非方块且非已知掉落（把合成品/装备当矿挖）= 规划 bug → 空表，调用方转 capability_gap，
     * 不再把 item 原样当 block_id 透传给 AutoMine 报 "no valid block ids"。
     */
    private static List<String> mineBlockIds(String item) {
        List<String> out = new ArrayList<>();
        if (item == null) {
            return out;
        }
        String id = item.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return out;
        }
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        Matcher raw = RAW_ORE.matcher(id);
        if (raw.matches()) {
            String metal = raw.group(2);
            addRegistered(out, ns + ":" + metal + "_ore");
            addRegistered(out, ns + ":deepslate_" + metal + "_ore");
            return out;
        }
        // 挖矿掉落物物品不是方块：挖产出它的矿石
        String ore = DROP_TO_ORE.get(path);
        if (ore != null) {
            addRegistered(out, ns + ":" + ore);
            addRegistered(out, ns + ":deepslate_" + ore);
            return out;
        }
        // 村庄农田：carrot/potato/beetroot 是掉落物，真实方块名称是复数形式。
        // AutoMine 已负责寻路、破坏和统计新获得物品，复用它而不另造一套收获任务。
        String crop = cropBlockPath(path);
        if (crop != null) {
            addRegistered(out, ns + ":" + crop);
            return out;
        }
        // 字面 id：只收真实注册方块（宝石等非方块物品不再透传成 block_id）
        if (isRegisteredBlock(ns + ":" + path)) {
            out.add(id);
            Matcher ore2 = STONE_ORE.matcher(id);
            if (ore2.matches() && !path.startsWith("deepslate_")) {
                addRegistered(out, ns + ":deepslate_" + ore2.group(2) + "_ore");
            }
        }
        return out;
    }

    /** 纯映射，单测不依赖 NeoForge 的注册表运行时。 */
    static String cropBlockPath(String itemPath) {
        return itemPath == null ? null : CROP_DROP_TO_BLOCK.get(itemPath);
    }

    private static boolean isRegisteredBlock(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            return rl != null && BuiltInRegistries.BLOCK.containsKey(rl);
        } catch (RuntimeException ignore) {
            // 个别 tick 注册表异常时按不存在处理，宁可空表走 capability_gap 也不透传坏 id
            return false;
        }
    }

    private static void addRegistered(List<String> out, String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                out.add(id);
            }
        } catch (RuntimeException ignore) {
            // 注册表个别 tick 异常时放弃补变体；主 id 已在列表
        }
    }

    // ---- 防御式取值：body.args 的值可能是 JsonElement / String / Number ----

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JsonPrimitive p) {
            return p.getAsString();
        }
        if (v instanceof JsonElement e) {
            return e.isJsonPrimitive() ? e.getAsString() : null;
        }
        return String.valueOf(v);
    }

    private static Integer count(Map<String, Object> a, Integer fallback) {
        Object v = a.get("count");
        Integer n = null;
        if (v instanceof JsonPrimitive p && p.isNumber()) {
            n = p.getAsInt();
        } else if (v instanceof Number num) {
            n = num.intValue();
        } else if (v instanceof String s) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                n = null;
            }
        }
        if (n == null || n <= 0) {
            n = fallback;
        }
        return (n == null || n <= 0) ? null : n;
    }
}
