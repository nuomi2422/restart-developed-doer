package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 损失影响分析（资产语义 V1）：<b>核心不是"丢了重要物品就重规划"，而是"确认损失后，还能不能完成目标"</b>。
 *
 * <p>替代原先"重要丢一个就判、次要丢多个才判"的评分式决策：<b>重要等级决定值不值得关注，
 * 剩余能力决定需不需要行动</b>。本类只产<b>事实与建议</b>，不碰任务链、不自动改目标——
 * 是否插入补充支线/调整主线，留给上层（先分析可靠，再谈接入）。
 *
 * <h2>两条边界（不许违反）</h2>
 * <ol>
 *   <li>传入的 {@code lostItems} 是<b>净损失</b>（已确认仍缺失的量），不是死亡瞬间快照：
 *       掉落可能还在、已被捡回、或被转存——捡回了就不该报永久损失。</li>
 *   <li>{@code lossConfirmed=false} 表示"尚未确认丢失"（回收未结束 / 区块未加载 / 位置未检查）——
 *       此时不下"永久丢失"的结论，只建议先确认库存。</li>
 * </ol>
 *
 * <p>普通吃食物、用材料建造、物品放进箱子都不是损失——调用方不该把它们放进 {@code lostItems}。
 *
 * <p>纯函数、可单测，不含 Minecraft 类型。
 */
public final class LossImpactAnalyzer {

    public enum Recommendation {
        /** 无需动作（无关资产 / 已被捡回 / 正常消耗）。 */
        NONE,
        /** 换装继续（重要资产丢了，但需求仍被备用/替代满足）。 */
        SWAP_BACKUP,
        /** 先确认库存（次要受影响，或尚未确认丢失）。 */
        CONFIRM_STOCK,
        /** 补充（重要能力缺口，需求已不满足）。 */
        SUPPLEMENT
    }

    /** 一处受损失影响的目标需求。 */
    public record GoalImpact(String goalId, String requirementKey, int minimum, int available,
                             boolean satisfied, List<String> coveredBy) {
        public GoalImpact {
            coveredBy = coveredBy == null ? List.of() : List.copyOf(coveredBy);
        }
    }

    /**
     * 分析结论。
     *
     * @param importantLoss   是否涉及重要资产（值得关注）
     * @param backupSufficient 重要需求是否仍被备用/替代满足
     * @param lossConfirmed   本次损失是否已确认（false = 尚未确认丢失）
     * @param affectedGoals   被这次损失打穿的需求（走了替代/派生后仍不满足）
     * @param recommendation  建议处理
     */
    public record LossImpact(boolean importantLoss, boolean backupSufficient, boolean lossConfirmed,
                             List<GoalImpact> affectedGoals, Recommendation recommendation, String summary) {
        public LossImpact {
            affectedGoals = affectedGoals == null ? List.of() : List.copyOf(affectedGoals);
        }

        public boolean needsAction() {
            return recommendation == Recommendation.SUPPLEMENT;
        }
    }

    private LossImpactAnalyzer() {
    }

    public static LossImpact analyze(Map<String, Integer> lostItems,
                                     Map<String, Integer> heldAfterLoss,
                                     List<RequirementManifest.Manifest> manifests,
                                     Map<String, AssetSemantics> semantics,
                                     boolean lossConfirmed) {
        Map<String, Integer> lost = clean(lostItems);
        Map<String, Integer> held = clean(heldAfterLoss);
        // 损失前 ≈ 现在持有 + 净损失（净损失是已确认不再持有的部分）。
        Map<String, Integer> before = new LinkedHashMap<>(held);
        for (Map.Entry<String, Integer> e : lost.entrySet()) {
            before.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        boolean importantLoss = false;
        for (String key : lost.keySet()) {
            if (importanceOf(key, semantics) == AssetSemantics.Importance.IMPORTANT) {
                importantLoss = true;
            }
        }

        List<GoalImpact> affected = new ArrayList<>();
        boolean importantUnsatisfied = false;
        if (manifests != null) {
            for (RequirementManifest.Manifest manifest : manifests) {
                if (manifest == null) continue;
                for (RequirementManifest.Requirement r : manifest.requirements()) {
                    int beforeAvail = available(r, before);
                    int afterAvail = available(r, held);
                    // 只有"这次损失把原本满足的需求打穿"才算受影响（原本就缺的缺口不算）。
                    if (!(beforeAvail >= r.minimum() && afterAvail < r.minimum())) continue;
                    boolean important = importanceOf(r.key(), semantics) == AssetSemantics.Importance.IMPORTANT;
                    if (important) {
                        importantLoss = true;
                        importantUnsatisfied = true;
                    }
                    affected.add(new GoalImpact(manifest.goalId(), r.key(), r.minimum(), afterAvail,
                            false, coveredBy(r, held)));
                }
            }
        }

        boolean backupSufficient = importantLoss && !importantUnsatisfied;
        Recommendation recommendation = decide(affected, importantLoss, importantUnsatisfied, lossConfirmed);
        String summary = summarize(lost, affected, importantLoss, importantUnsatisfied, lossConfirmed,
                recommendation);
        return new LossImpact(importantLoss, backupSufficient, lossConfirmed, affected, recommendation, summary);
    }

    private static Recommendation decide(List<GoalImpact> affected, boolean importantLoss,
                                         boolean importantUnsatisfied, boolean lossConfirmed) {
        if (!importantLoss && affected.isEmpty()) {
            return Recommendation.NONE;                 // 无关 / 已捡回 / 正常消耗
        }
        if (importantUnsatisfied) {
            return lossConfirmed ? Recommendation.SUPPLEMENT : Recommendation.CONFIRM_STOCK;
        }
        if (importantLoss) {
            return Recommendation.SWAP_BACKUP;          // 重要资产丢了，但需求仍被备用满足
        }
        return Recommendation.CONFIRM_STOCK;            // 只有次要受影响：按批次复核
    }

    private static int available(RequirementManifest.Requirement r, Map<String, Integer> counts) {
        int best = AssetDerivation.equivalentCount(r.key(), counts);
        for (String alt : r.alternatives()) {
            best = Math.max(best, AssetDerivation.equivalentCount(alt, counts));
        }
        return best;
    }

    private static List<String> coveredBy(RequirementManifest.Requirement r, Map<String, Integer> counts) {
        List<String> out = new ArrayList<>();
        if (AssetDerivation.equivalentCount(r.key(), counts) > 0) out.add(r.key());
        for (String alt : r.alternatives()) {
            if (AssetDerivation.equivalentCount(alt, counts) > 0) out.add(alt);
        }
        return out;
    }

    private static AssetSemantics.Importance importanceOf(String assetKey, Map<String, AssetSemantics> semantics) {
        if (assetKey == null || semantics == null) return AssetSemantics.Importance.SECONDARY;
        AssetSemantics s = semantics.get(assetKey);
        if (s == null) s = semantics.get(assetKey.toLowerCase(Locale.ROOT));
        return s == null ? AssetSemantics.Importance.SECONDARY : s.importance();
    }

    private static Map<String, Integer> clean(Map<String, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (counts != null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    private static String summarize(Map<String, Integer> lost, List<GoalImpact> affected,
                                    boolean importantLoss, boolean importantUnsatisfied,
                                    boolean lossConfirmed, Recommendation recommendation) {
        StringBuilder sb = new StringBuilder();
        sb.append("损失：").append(lost.isEmpty() ? "无" : lost.toString());
        switch (recommendation) {
            case NONE -> sb.append("；无重要影响，无需动作");
            case SWAP_BACKUP -> sb.append("；重要资产缺失但需求仍有备用/替代满足，换装继续");
            case CONFIRM_STOCK -> sb.append(lossConfirmed
                    ? "；次要资产受影响，按批次复核即可"
                    : "；尚未确认丢失（回收未结束/区块未加载），先确认库存再决定");
            case SUPPLEMENT -> sb.append("；重要能力缺口，需要补充");
        }
        if (!affected.isEmpty()) {
            sb.append("；受影响目标=");
            for (int i = 0; i < affected.size(); i++) {
                GoalImpact g = affected.get(i);
                if (i > 0) sb.append(',');
                sb.append(g.goalId()).append(':').append(g.requirementKey())
                        .append("(需").append(g.minimum()).append("剩").append(g.available()).append(')');
            }
        }
        if (importantLoss && importantUnsatisfied && !lossConfirmed) {
            sb.append("；注意：即使目标已被阻塞，也请说明是'暂不可用、尚未确认丢失'");
        }
        return sb.toString();
    }
}
