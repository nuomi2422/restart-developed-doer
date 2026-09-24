package com.dwinovo.numen.plugins.rdd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 任务链规划策略（纯逻辑，零 MC 依赖，可单测）：<b>最小有效目标 + 成本/风险约束</b>。
 *
 * <p>要解决的是「计划看起来完整、实际又贵又险又没必要」这个老毛病：满级附魔台、15 个书架、
 * 全套顶级附魔、生电大工程、交易所——在主人只说了「通关」的时候，这些都不该默认进计划。
 *
 * <h2>为什么是独立的一个类</h2>
 * 约束既要在 Stage-A 的请求里出现，也要在 Stage-B 的请求里出现，还要能被测试直接断言。
 * 所以策略只在这里写一遍，两个 prompt 都从这里取——绝不把同一段规则复制进两份 prompt
 * （复制一次是两处会漂移的真相）。
 *
 * <h2>不是思维链</h2>
 * 这里产出的是<b>可见、短小、可断言的决策结果</b>（延后了哪些可选升级、本次适用于哪些硬规则），
 * 不是模型的隐藏推理。{@link #block} 的文本直接进请求，能被人和监测台逐条核对。
 */
final class RddPlanningPolicy {

    /** 可选升级：主人没明确要求时，默认不进计划。 */
    static final Set<String> OPTIONAL_ASSETS = Set.of(
            "minecraft:bookshelf",
            "minecraft:anvil",
            "minecraft:beacon",
            "minecraft:conduit",
            "minecraft:hopper",
            "minecraft:observer",
            "minecraft:piston",
            "minecraft:sticky_piston",
            "minecraft:dropper",
            "minecraft:dispenser",
            "minecraft:comparator",
            "minecraft:repeater");

    /** 主题级可选分支关键词：出现在 Stage-A 主题里即视为可选工程。 */
    static final List<String> OPTIONAL_THEME_KEYWORDS = List.of(
            "生电", "自动化", "交易所", "村民", "刷铁机", "刷怪塔", "农场", "树场", "羊毛机",
            "分类存储", "信标", "满级", "全套", "顶级附魔");

    /** 主人明确点名要这些时，可选升级才放行。 */
    static final List<String> REQUEST_KEYWORDS = List.of(
            "书架", "满级", "全套", "顶级", "附魔台升级", "生电", "自动化", "交易所", "村民",
            "刷铁机", "刷怪塔", "农场", "树场", "信标", "铁砧", "红石", "自动化");

    /** 高风险场景关键词 → 该场景必须带准备与失败回退约束。 */
    private static final List<String> RISK_DEEP_MINE = List.of("钻石", "深板岩", "深矿", "y=-58", "深层", "矿");
    private static final List<String> RISK_NETHER = List.of("下界", "烈焰", "要塞", "抗火");
    private static final List<String> RISK_END = List.of("末地", "末影", "传送门", "末影龙");

    private RddPlanningPolicy() {}

    /**
     * 主人是否明确要求了可选升级。
     *
     * <p>只在目标文本里出现明确关键词时放行——默认（「通关」「推进」这类）一律不放行。
     * 判断刻意保守：宁可延后，也不要擅自把昂贵工程塞进计划。
     */
    static boolean optionalAllowed(String objective) {
        if (objective == null || objective.isBlank()) {
            return false;
        }
        String text = objective.toLowerCase(Locale.ROOT);
        for (String keyword : REQUEST_KEYWORDS) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 本次被延后的可选升级清单（用于审计与观测，主人明确要求时为空）。 */
    static List<String> deferredOptional(String objective) {
        if (optionalAllowed(objective)) {
            return List.of();
        }
        return List.of("书架", "满级附魔台", "铁砧/信标", "生电与自动化", "村民交易体系");
    }

    /** 主题是否属于「可选工程」（生电/交易所/农场这类）。 */
    static boolean optionalTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            return false;
        }
        for (String keyword : OPTIONAL_THEME_KEYWORDS) {
            if (theme.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 资产键是否为可选升级。 */
    static boolean optionalAsset(String assetKey) {
        return assetKey != null && OPTIONAL_ASSETS.contains(assetKey.trim().toLowerCase(Locale.ROOT));
    }

    /** 本次适用于哪些风险约束（按目标与阶段判定）。 */
    static List<String> riskKinds(String objective, String stage) {
        String text = (objective == null ? "" : objective) + " " + (stage == null ? "" : stage);
        Set<String> kinds = new LinkedHashSet<>();
        if (containsAny(text, RISK_DEEP_MINE)) {
            kinds.add("深矿下探");
        }
        if (containsAny(text, RISK_NETHER)) {
            kinds.add("下界");
        }
        if (containsAny(text, RISK_END)) {
            kinds.add("末地");
        }
        return List.copyOf(kinds);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 渲染要注入请求的<b>策略块</b>。预算受限（约几百字），可被测试逐条断言。
     *
     * @param objective 当前目标或阶段主题
     * @param stage     阶段名（stage_a / stage_b / fallback）
     */
    static String block(String objective, String stage) {
        StringBuilder sb = new StringBuilder();
        sb.append("【规划策略｜最小有效目标优先，必须遵守】\n");

        // 1) 最小有效目标 + 可选升级
        sb.append("- 最小有效目标：只规划推进当前目标所必需的那一步；能用普通手段达成，就不上高级配置。\n");
        List<String> deferred = deferredOptional(objective);
        if (deferred.isEmpty()) {
            sb.append("- 可选升级：主人本次已明确要求，允许纳入计划（仍按最小有效目标计算数量）。\n");
        } else {
            sb.append("- 可选升级默认延后，本次不进计划：").append(String.join("、", deferred)).append("。\n")
                    .append("  它们只有两种情况下才可纳入：主人明确要求，或它是当前目标不可替代的前置。\n");
        }

        // 2) 复用现有资产
        sb.append("- 复用现有资产：背包里已有的工具/装备/设施不得重复获取；只有会被消耗的材料才按实际消耗补量。\n");

        // 3) 可验收
        sb.append("- 每步可验收：condition 必须给真实 asset_key + minimum，或可核对的世界事实；")
                .append("禁止把\"找村庄/四处探索/变强一些\"当作可验收终点，不得为凑完整而无限拆分。\n");

        // 4) 高风险
        List<String> risks = riskKinds(objective, stage);
        sb.append("- 高风险步骤必须写明准备与失败回退：");
        if (risks.isEmpty()) {
            sb.append("涉及深矿/下界/末地/岩浆/摔落/敌对生物时，先备好对应防护再动手。\n");
        } else {
            sb.append("本次适用 ").append(String.join("、", risks)).append("。\n");
        }
        sb.append("  失败先分诊：资源不存在或耗尽 / 路径不可达 / 工具调用或参数错误 / 稳定软件缺陷；")
                .append("未知原因保持未知，禁止由失败直接推出\"缺工具\"或\"等待自动生成工具\"。\n");
        sb.append("  下界不得把水桶灭火或床当作常规保命/重生方案。\n");

        // 5) 可选分支的门槛
        sb.append("- 生电/自动化/村民体系属可选分支，不是通关主线；仅当收益明确超过建造与维护成本时才纳入。\n");
        return sb.toString();
    }

    /** 观测用摘要：只记「决策结果」，不记任何隐藏推理。 */
    static List<String> appliedRules(String objective, String stage) {
        List<String> rules = new ArrayList<>();
        rules.add("minimal-effective-goal");
        if (deferredOptional(objective).isEmpty()) {
            rules.add("optional-allowed-by-owner");
        } else {
            rules.add("optional-deferred");
        }
        rules.add("reuse-held-assets");
        rules.add("verifiable-conditions");
        for (String risk : riskKinds(objective, stage)) {
            rules.add("risk:" + risk);
        }
        rules.add("side-branch-cost-gate");
        return List.copyOf(rules);
    }
}
