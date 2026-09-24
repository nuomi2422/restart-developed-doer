package com.dwinovo.numen.rdd.fact;

import com.dwinovo.numen.rdd.api.Goal;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 目标血缘（goal lineage）：把"哪一个战役/目标的完成事实"隔离开，防跨战役污染。
 *
 * <p>P0 规则（保守、可解释）：
 * <ul>
 *   <li>lineage = {@code goal.id() + "#" + 归一化目标正文}。同一同伴对<b>同一目标正文</b>重发
 *       /goal（重绑/重规划）→ lineage 不变 → 继承阶段事实；换了目标正文 → lineage 变 → 不继承。</li>
 *   <li>目标正文归一化用 {@link StageKeyNormalizer}，所以"击败末影龙"与"击败末影龙！"同源；
 *       但不做同义词（"击败末影龙"≠"完成末影龙挑战"）。</li>
 *   <li>{@link #similar} 供 Level-2 指纹近似（同 goalId 下正文相似）时降级只继承阶段事实：
 *       用字符 bigram 的 Jaccard 相似度，阈值 {@link #SIMILARITY_THRESHOLD}。</li>
 * </ul>
 */
public final class GoalLineage {

    /** Level-2 指纹近似阈值（bigram Jaccard）。保守取 0.6：宁可少继承也不污染。 */
    public static final double SIMILARITY_THRESHOLD = 0.6;

    private GoalLineage() {}

    /** 血缘 id：goalId#归一目标正文。 */
    public static String of(Goal goal) {
        if (goal == null) {
            throw new IllegalArgumentException("goal required");
        }
        return lineageId(goal.id(), goal.description());
    }

    /** 血缘 id：goalId#归一目标正文（供无 Goal 对象的持久化侧使用）。 */
    public static String lineageId(String goalId, String objective) {
        String id = goalId == null ? "goal" : goalId;
        return id + "#" + fingerprint(objective);
    }

    /** 目标指纹 = 归一化目标正文。 */
    public static String fingerprint(String objective) {
        return StageKeyNormalizer.normalize(objective);
    }

    /** 两个指纹是否近似（字符 bigram Jaccard ≥ 阈值；任一侧为空 → false）。 */
    public static boolean similar(String fingerprintA, String fingerprintB) {
        if (fingerprintA == null || fingerprintB == null) {
            return false;
        }
        if (fingerprintA.equals(fingerprintB)) {
            return true;
        }
        Set<String> a = bigrams(fingerprintA);
        Set<String> b = bigrams(fingerprintB);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size() >= SIMILARITY_THRESHOLD;
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s.length() == 1) {
            out.add(s);
            return out;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }
}
