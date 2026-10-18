package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Locale;

/**
 * 资产的<b>语义</b>（规划层声明，不是资产事实）——回答「这件东西为什么重要、要留几件、能不能换」。
 *
 * <p>把"重要程度"和"使用性质"拆成两个字段：它们会重叠（打末影龙的食物 = 重要且可消耗；
 * 唯一一把当前必用的镐 = 重要且耐用品），所以不能用一个枚举表达。
 *
 * <p><b>重要程度随目标变化</b>：同一批小麦，繁殖动物时重要、任务结束后可能只是普通储备。
 * 目标取消后把对应语义标 {@link Importance#IRRELEVANT}，损失就不再触发动作。
 *
 * <p>纯数据 + 一个保守的默认分类器，可单测，不含 Minecraft 类型。
 *
 * @param assetKey            物品 id
 * @param importance          重要程度
 * @param nature              使用性质
 * @param servesGoal          服务的目标（可多个时由上层拆成多条，第一版一条一个）
 * @param requiredCapability  所需能力或数量（如"能挖黑曜石"/"至少 16 个"）
 * @param source              取得来源与首次确认时间（无法确认时写 UNKNOWN，别让模型补造）
 * @param substitutes         可替代条件（哪些装备/材料/库存能替代）
 */
public record AssetSemantics(String assetKey, Importance importance, Nature nature, String servesGoal,
                             String requiredCapability, String source, List<String> substitutes) {

    public enum Importance {
        /** 重要：值得立即复核。 */
        IMPORTANT,
        /** 次要：按批次合并复核，不打断。 */
        SECONDARY,
        /** 与当前目标无关：只记变化，不触发任务调整。 */
        IRRELEVANT
    }

    public enum Nature {
        /** 消耗品（食物、药水等，用了就少）。 */
        CONSUMABLE,
        /** 装备工具（耐用品）。 */
        EQUIPMENT,
        /** 材料。 */
        MATERIAL,
        /** 任务物品。 */
        QUEST_ITEM
    }

    public AssetSemantics {
        if (assetKey == null || assetKey.isBlank()) throw new IllegalArgumentException("assetKey required");
        importance = importance == null ? Importance.SECONDARY : importance;
        nature = nature == null ? guessNature(assetKey) : nature;
        servesGoal = servesGoal == null ? "" : servesGoal;
        requiredCapability = requiredCapability == null ? "" : requiredCapability;
        source = source == null || source.isBlank() ? "UNKNOWN" : source;
        substitutes = substitutes == null ? List.of() : List.copyOf(substitutes);
    }

    public boolean important() {
        return importance == Importance.IMPORTANT;
    }

    /** 规划声明的便捷构造：只给关键的几项。 */
    public static AssetSemantics declared(String assetKey, Importance importance, Nature nature, String servesGoal) {
        return new AssetSemantics(assetKey, importance, nature, servesGoal, "", "UNKNOWN", List.of());
    }

    /**
     * 保守的使用性质默认分类（规划未声明时兜底）。只认常识级特征，不认识就按材料。
     * <b>重要程度不在这里猜</b>——那是目标相对的，必须由规划声明。
     */
    public static Nature guessNature(String assetKey) {
        if (assetKey == null) return Nature.MATERIAL;
        String k = assetKey.toLowerCase(Locale.ROOT);
        if (k.endsWith("_pickaxe") || k.endsWith("_axe") || k.endsWith("_shovel") || k.endsWith("_hoe")
                || k.endsWith("_sword") || k.endsWith("_helmet") || k.endsWith("_chestplate")
                || k.endsWith("_leggings") || k.endsWith("_boots") || k.endsWith("_shield")
                || k.endsWith("_bow") || k.endsWith("_crossbow") || k.endsWith("_trident")
                || k.contains("shears") || k.contains("flint_and_steel") || k.contains("fishing_rod")
                || k.contains("_pickaxe")) {
            return Nature.EQUIPMENT;
        }
        if (k.contains("potion") || k.contains("_apple") || k.contains("bread")
                || k.contains("cooked_") || k.contains("raw_") || k.contains("stew")
                || k.contains("beef") || k.contains("porkchop") || k.contains("mutton")
                || k.contains("chicken") || k.contains("cod") || k.contains("salmon")) {
            return Nature.CONSUMABLE;
        }
        return Nature.MATERIAL;
    }
}
