package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.fact.CompletedFactStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求清单 × 共同事实的<b>只读对账</b>（第三批 N2 第一步，2026-10-05）。
 *
 * <p><b>为什么先对账而不是直接接消费者</b>：N1 的教训是「拿不同源的键硬 join」。
 * 这次先确认键：{@code RequirementManifest.Manifest.goalId} 与
 * {@code StageFact.goalId} <b>是同一个字段名、同一个语义</b>（都来自 {@code Goal}），
 * 所以这里是<b>真能 join</b> 的 —— 不像事实侧 stageKey 与使用侧 artifact_id 那样只能弱对账。
 *
 * <p><b>这个类回答的问题</b>（都是事实陈述，不含建议）：
 * <ul>
 *   <li>哪些需求<b>已被完成事实覆盖</b>（该阶段被记为完成）；</li>
 *   <li>哪些需求<b>还没有任何完成事实</b>（= 还没做到，或做了但没被记）；</li>
 *   <li>哪些需求<b>在同一 goal 下被反复重做</b>（同一 stageKey 多次完成 ⇒ 可能是返工）。</li>
 * </ul>
 *
 * <p><b>★ 边界</b>：
 * <ul>
 *   <li><b>只读</b>：不改事实、不改需求、不触发任何动作；</li>
 *   <li><b>不猜因果</b>：「需求满足但没有完成事实」只说明<b>事实里没有</b>，
 *       可能是真没做，也可能是做了没记 —— 所以措辞一律是「事实里没有」，
 *       绝不写成「未完成」；</li>
 *   <li><b>空清单不是故障</b>：没有需求清单时返回 {@code HAS_NO_MANIFEST}，
 *       而不是「一切正常」。</li>
 * </ul>
 */
public final class RequirementFactAudit {

    /** 结论类别。措辞刻意都是「事实里有没有」，不是「做没做成」。 */
    public enum Verdict {
        /** 该需求对应的阶段在共同事实里被记为完成。 */
        COVERED_BY_FACT,
        /**
         * 事实里<b>没有</b>这个阶段的记录（<b>不等于没做</b>：可能做了没记）。
         *
         * <p>★ 措辞是硬要求：枚举名不能改成 {@code NOT_DONE} / {@code FAILED} 之类 ——
         * 那会把「事实里没有」说成「没做到」，而两者完全不同（做了没记的情况很常见）。
         */
        NO_FACT_RECORD
    }

    /**
     * ★ 关于「返工/重复完成」这个信号：<b>本类观测不到</b>。
     *
     * <p>{@code CompletedFactStore} 按 {@code lineageId + SEP + stageKey} 做键去重，
     * {@code recordStage} 对同一组合是<b>覆盖</b>而不是追加 ⇒ 同一阶段记两次只剩一条。
     * 所以「同一阶段被做了很多次」这个信号<b>不在这个结构里</b>。
     *
     * <p>因此本类<b>不</b>报 REPEATED_FACTS：报一个结构上不可能出现的结论，
     * 等于对外承诺了一个我们观测不到的能力。要观测返工得另找计数点
     * （例如 rdd 自己的任务历史），那是另一件事。
     */
    public static final String REWORK_NOT_OBSERVABLE_NOTE =
            "REWORK_NOT_OBSERVABLE：CompletedFactStore 按 lineage+stageKey 去重（重复记录被覆盖），"
                    + "所以「同一阶段做了几次」在这个结构里观测不到；本报告不报返工信号。";

    public record Item(String requirementKey, String goalId, int minimum,
                       List<String> alternatives, Verdict verdict, int factCount) {
    }

    public record Report(int requirementCount, int factCount, List<Item> items, List<String> notes) {

        public boolean hasManifest() {
            return !items.isEmpty();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("has_manifest", hasManifest());
            m.put("requirement_count", requirementCount);
            m.put("fact_count", factCount);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Item i : items) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("requirement_key", i.requirementKey());
                one.put("goal_id", i.goalId());
                one.put("minimum", i.minimum());
                one.put("alternatives", i.alternatives());
                one.put("verdict", i.verdict().name());
                one.put("fact_count", i.factCount());
                rows.add(one);
            }
            m.put("items", rows);
            m.put("notes", notes);
            m.put("rework_signal", REWORK_NOT_OBSERVABLE_NOTE);
            m.put("note", "★ 只读对账：只陈述「事实里有没有」，**不改任何一侧、也不下完成与否的结论**");
            return m;
        }
    }

    private RequirementFactAudit() {
    }

    /**
     * 对账。
     *
     * @param manifest 需求清单；{@code null} ⇒ 报 {@code HAS_NO_MANIFEST}
     * @param facts    共同事实（可为空仓库）
     */
    public static Report audit(RequirementManifest.Manifest manifest, CompletedFactStore facts) {
        List<String> notes = new ArrayList<>();
        if (manifest == null || manifest.requirements() == null || manifest.requirements().isEmpty()) {
            notes.add("HAS_NO_MANIFEST");
            notes.add(REWORK_NOT_OBSERVABLE_NOTE);
            return new Report(0, facts == null ? 0 : facts.stageCount(), List.of(), List.copyOf(notes));
        }
        notes.add(REWORK_NOT_OBSERVABLE_NOTE);

        // goalId → {stageKey → 次数}：用真实字段做键，不靠字符串猜
        Map<String, Map<String, Integer>> byGoal = new LinkedHashMap<>();
        if (facts != null) {
            for (com.dwinovo.numen.rdd.fact.StageFact f : facts.stageFacts()) {
                String goal = f.goalId() == null ? "" : f.goalId();
                byGoal.computeIfAbsent(goal, k -> new LinkedHashMap<>())
                        .merge(f.stageKey() == null ? "" : f.stageKey(), 1, Integer::sum);
            }
        }

        List<Item> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RequirementManifest.Requirement r : manifest.requirements()) {
            if (r == null || r.key() == null || r.key().isBlank()) {
                continue;
            }
            if (!seen.add(r.key())) {
                // 同一条需求出现两次：算一次，并在 notes 里说明（不静默去重）
                notes.add("需求清单里 '" + r.key() + "' 重复出现，只算一次");
                continue;
            }
            Map<String, Integer> stages = byGoal.getOrDefault(manifest.goalId(), Map.of());
            // 需求键可能就是 stageKey，也可能是前缀。用「相等或以其开头」两种口径，
            // 并在 detail 里如实说明用的是哪种 —— 猜口径而不说，就会得出假的覆盖率。
            int count = 0;
            String matchedBy = "NONE";
            for (Map.Entry<String, Integer> e : stages.entrySet()) {
                String k = e.getKey();
                if (k.equals(r.key())) {
                    count += e.getValue();
                    matchedBy = "EXACT";
                }
            }
            if (count == 0) {
                for (Map.Entry<String, Integer> e : stages.entrySet()) {
                    if (e.getKey().startsWith(r.key())) {
                        count += e.getValue();
                        matchedBy = "PREFIX";
                    }
                }
            }
            Verdict v;
            if (count == 0) {
                v = Verdict.NO_FACT_RECORD;
            } else {
                // ★ 不再按 count>1 报「返工」：该结构按 key 去重，count>1 不可能来自
                //   同一阶段被记两次（见 REWORK_NOT_OBSERVABLE_NOTE）。
                v = Verdict.COVERED_BY_FACT;
            }
            if (!"NONE".equals(matchedBy)) {
                notes.add("需求 '" + r.key() + "' 按 " + matchedBy + " 口径命中 " + count + " 条事实");
            }
            items.add(new Item(r.key(), manifest.goalId(), r.minimum(),
                    r.alternatives() == null ? List.of() : List.copyOf(r.alternatives()),
                    v, count));
        }
        items.sort((a, b) -> a.verdict().ordinal() - b.verdict().ordinal());
        return new Report(items.size(), facts == null ? 0 : facts.stageCount(),
                List.copyOf(items), List.copyOf(notes));
    }
}