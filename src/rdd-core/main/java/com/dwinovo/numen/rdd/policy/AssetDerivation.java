package com.dwinovo.numen.rdd.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * P2-C 资产派生/等价（GPT 外脑建议 · 2026-09-25）：回答"合成前的东西，合成后还算不算有"。
 *
 * <p>动机（用户实测）：拿到小麦 → 合成面包 → 任务链认为"小麦没了" → 又去补种小麦。
 * 物理上小麦确实被消耗，但**从战略价值看**，小麦和面包都属"食物储备"，不该因为形态转换
 * 就被当成净损失去重复耕作。本层提供**保守的、显式的**等价/派生关系，只在**组级**判定里生效，
 * 不篡改物品级事实计数（物品计数永远忠实）。
 *
 * <p>设计原则（保守）：
 * <ul>
 *   <li>只登记"玩家常识级"的稳定派生，不做全套配方推导（配方由 {@code lookup_recipe} 查）；</li>
 *   <li>只用于"是否还需要再获取"这类**战略判断**，不用于"够不够"的硬门判定；</li>
 *   <li>关系表是纯数据，可单测、可扩展。</li>
 * </ul>
 *
 * <p>纯 JVM，不含 Minecraft 类型。
 */
public final class AssetDerivation {

    /** 派生关系：{@code from → to}（1 单位 from 可视为若干 to 的战略等价）。 */
    public record Rule(String from, String to, int toPerFrom) {
        public Rule {
            if (from == null || to == null || from.isBlank() || to.isBlank() || toPerFrom < 1) {
                throw new IllegalArgumentException("invalid derivation rule");
            }
        }
    }

    /** 保守基线：只放"常识级"派生（食物形态转换 / 原木→木板 / 锭→块）。 */
    private static final Map<String, java.util.List<Rule>> RULES = buildRules();

    private AssetDerivation() {}

    private static Map<String, java.util.List<Rule>> buildRules() {
        Map<String, java.util.List<Rule>> m = new LinkedHashMap<>();
        // 食物：小麦→面包（3:1），面包等属 FOOD 组的等价体
        add(m, new Rule("minecraft:wheat", "minecraft:bread", 1));
        // 原木→木板：1 原木 = 4 木板（战略等价，用于"还要不要砍树"判断）
        for (String log : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"}) {
            add(m, new Rule("minecraft:" + log + "_log", "minecraft:" + log + "_planks", 4));
        }
        // 铁锭→铁块（9:1）、金锭→金块
        add(m, new Rule("minecraft:iron_ingot", "minecraft:iron_block", 1));
        add(m, new Rule("minecraft:gold_ingot", "minecraft:gold_block", 1));
        return Map.copyOf(m);
    }

    private static void add(Map<String, java.util.List<Rule>> m, Rule rule) {
        m.computeIfAbsent(rule.from(), k -> new java.util.ArrayList<>()).add(rule);
    }

    /** 某物品的派生产物规则（不可变，空=无规则）。 */
    public static java.util.List<Rule> rulesFor(String from) {
        if (from == null) return java.util.List.of();
        return RULES.getOrDefault(from, java.util.List.of());
    }

    /**
     * 战略等价计数：把 {@code counts} 里所有能派生到 {@code target} 的物品折算进来。
     * 例如 target=bread 时，面包计入，小麦也按规则折算。
     *
     * <p><b>只用于战略判断（"还要不要再获取"）</b>，不用于硬门（够不够）。
     * 纯函数、可单测。
     */
    public static int equivalentCount(String target, Map<String, Integer> counts) {
        if (target == null || counts == null) return 0;
        int total = counts.getOrDefault(target, 0);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            for (Rule r : rulesFor(e.getKey())) {
                if (r.to().equals(target)) {
                    total += e.getValue() * r.toPerFrom();
                }
            }
        }
        return total;
    }

    /** 是否已登记派生关系。 */
    public static boolean known(String from) {
        return from != null && RULES.containsKey(from);
    }

    /** 全部规则条数（测试/诊断用）。 */
    public static int ruleCount() {
        int n = 0;
        for (var list : RULES.values()) n += list.size();
        return n;
    }
}
