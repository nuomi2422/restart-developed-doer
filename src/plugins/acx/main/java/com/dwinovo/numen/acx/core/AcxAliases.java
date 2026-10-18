package com.dwinovo.numen.acx.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧脚本名 → Numen 真实工具名的归一表。
 *
 * <p>解决的是「名字不能猜」：RDD 侧已实测过
 * {@code mine_block / mine_ore / gather → mine}、{@code equip → equip_item} 这类
 * 臆造名导致的静默空转（{@code RddBodyTools.canonical} 的教训）。AC 侧同理 ——
 * .ac 里写 {@code mine_block} 在 DD 是合法积木名，在 Numen 注册表里不存在，
 * 直接加载失败。桥接注册时按这张表把别名注册成指向同一端口的 {@code AliasTool}。</p>
 *
 * <p><b>只归一「名字」不归一「参数」</b>：别名到了真实端口后，参数按真实 schema
 * 校验，不匹配就响亮失败（如 DD {@code craft_item} 只传 count，Numen {@code craft}
 * 要 {@code item_id}）。参数映射/转换是下一批的事。</p>
 */
public final class AcxAliases {

    private static final Map<String, String> ALIAS_TO_CANONICAL;
    private static final Map<String, List<String>> CANONICAL_TO_ALIASES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // DD 名 → Numen 注册名
        m.put("mine_block", "mine");
        m.put("mine_ore", "mine");
        m.put("gather_block", "mine");
        m.put("navigate_to", "goto");
        m.put("move_to", "goto");
        m.put("walk_to", "goto");
        m.put("pickup_items", "collect_items");
        m.put("collect", "collect_items");
        m.put("craft_item", "craft");
        m.put("equip", "equip_item");
        m.put("whereami", "rdd_whereami");
        m.put("where_am_i", "rdd_whereami");
        ALIAS_TO_CANONICAL = Collections.unmodifiableMap(m);

        Map<String, List<String>> rev = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            rev.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        CANONICAL_TO_ALIASES = Collections.unmodifiableMap(rev);
    }

    private AcxAliases() { }

    /** 有映射返回真名；没有原样返回（不是错误 —— 大多数名字没有别名）。 */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        return ALIAS_TO_CANONICAL.getOrDefault(name, name);
    }

    public static boolean isAlias(String name) {
        return name != null && ALIAS_TO_CANONICAL.containsKey(name);
    }

    /** 某个真名对应的全部别名（按注册顺序）。 */
    public static List<String> aliasesFor(String canonicalName) {
        if (canonicalName == null) {
            return List.of();
        }
        return CANONICAL_TO_ALIASES.getOrDefault(canonicalName, List.of());
    }

    public static Map<String, String> all() {
        return ALIAS_TO_CANONICAL;
    }
}
