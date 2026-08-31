package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceQuery;
import com.dwinovo.numen.experience.api.ExperienceRetriever;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 默认的纯词法经验检索：对每条候选经验按字段命中加权打分，再叠加成熟度与优先级。
 *
 * <p><b>这是接缝里的第一块实现，不是终点。</b>中文场景下词法命中天然弱
 * （“同义不同字”召回不到），将来接向量记忆时替换这个 retriever 即可，模型不用改。
 *
 * <p>打分结构（透明，命中词随结果返回）：
 * <pre>{@code
 *   triggerStrings 命中   +3.0/词   （显式触发词是最强信号）
 *   title 命中            +2.0/词
 *   toolNames 命中        +2.0/词
 *   tags 命中             +1.5/词
 *   description/rootCause/recommended +1.0/词
 *   rationale             +0.8/词
 *   触发词反向出现在查询中   +2.0/个
 *   成熟度系数  OBSERVED 0.6 / ATTEMPTED 0.8 / VERIFIED 1.0 / GENERALIZED 1.2
 *   优先级系数  0.5 + priority/100
 * }</pre>
 *
 * <p>空查询文本时按“成熟度 × 优先级 × 近期性”兜底排序（相当于列最可信的几条）。
 */
public final class LexicalExperienceRetriever implements ExperienceRetriever {

    /** 查询文本为空时的兜底排序（可信度优先）。 */
    private static final double[] MATURITY_FACTOR = {
            0.6,   // OBSERVED
            0.8,   // ATTEMPTED
            1.0,   // VERIFIED
            1.2    // GENERALIZED
    };

    @Override
    public List<ExperienceHit> retrieve(ExperienceQuery query, List<ExperienceEntry> entries) {
        List<ExperienceEntry> eligible = entries.stream()
                .filter(e -> ExperienceMaturity.atLeast(e.maturity(), query.minMaturity()))
                .filter(e -> query.tags().isEmpty() || e.tags().stream().anyMatch(query.tags()::contains))
                .toList();

        String text = query.text() == null ? "" : query.text().toLowerCase(Locale.ROOT);
        List<String> tokens = tokens(text);

        if (tokens.isEmpty() && text.isBlank()) {
            return eligible.stream()
                    .sorted(Comparator.comparingDouble(this::fallbackScore).reversed())
                    .limit(query.limit())
                    .map(e -> new ExperienceHit(e, fallbackScore(e), List.of()))
                    .toList();
        }

        List<ExperienceHit> hits = new ArrayList<>();
        for (ExperienceEntry e : eligible) {
            Scored scored = score(e, text, tokens);
            if (scored.score <= 0) {
                continue;
            }
            hits.add(new ExperienceHit(e, scored.score, List.copyOf(scored.matched)));
        }
        hits.sort(Comparator.comparingDouble(ExperienceHit::score).reversed());
        return hits.size() <= query.limit() ? hits : hits.subList(0, query.limit());
    }

    private record Scored(double score, Set<String> matched) {}

    private Scored score(ExperienceEntry e, String queryLower, List<String> tokens) {
        double score = 0;
        Set<String> matched = new LinkedHashSet<>();
        for (String tok : tokens) {
            if (contains(e.title(), tok)) {
                score += 2.0;
                matched.add(tok);
            }
            if (containsAny(e.triggerStrings(), tok)) {
                score += 3.0;
                matched.add(tok);
            }
            if (containsAny(e.toolNames(), tok)) {
                score += 2.0;
                matched.add(tok);
            }
            if (containsAny(e.tags(), tok)) {
                score += 1.5;
                matched.add(tok);
            }
            if (contains(e.description(), tok)) {
                score += 1.0;
                matched.add(tok);
            }
            if (contains(e.rootCause(), tok)) {
                score += 1.0;
                matched.add(tok);
            }
            if (contains(e.recommendedResponse(), tok)) {
                score += 1.0;
                matched.add(tok);
            }
            if (contains(e.rationale(), tok)) {
                score += 0.8;
                matched.add(tok);
            }
        }
        // 反向：显式触发词整串出现在查询里（中文整句触发词不靠分词也能命中）。
        if (!queryLower.isBlank()) {
            for (String t : e.triggerStrings()) {
                if (!t.isBlank() && queryLower.contains(t.toLowerCase(Locale.ROOT))) {
                    score += 2.0;
                    matched.add(t);
                }
            }
        }
        if (score <= 0) {
            return new Scored(0, matched);
        }
        score *= MATURITY_FACTOR[e.maturity().ordinal()];
        score *= 0.5 + e.priority() / 100.0;
        return new Scored(score, matched);
    }

    /** 空查询兜底分：成熟度系数 × 优先级系数 × 轻量近期性。 */
    private double fallbackScore(ExperienceEntry e) {
        double recent = Math.max(0, 1.0 - (System.currentTimeMillis() - e.lastAccessedAt()) / (7.0 * 24 * 3600_000L));
        return MATURITY_FACTOR[e.maturity().ordinal()] * (0.5 + e.priority() / 100.0) * (0.8 + 0.2 * recent);
    }

    private static boolean contains(String field, String token) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(token);
    }

    private static boolean containsAny(List<String> values, String token) {
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 分词：按非字母/数字切分，保留 CJK 连串与拉丁词。 */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (String p : parts) {
            if (!p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }
}
