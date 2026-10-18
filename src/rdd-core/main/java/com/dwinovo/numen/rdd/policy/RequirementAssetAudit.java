package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求 → 共同事实 → 持有资产 的<b>三方只读对账</b>（第三批 N2 第二步，2026-10-05）。
 *
 * <p><b>为什么这次三侧能对上</b>（N1 的教训对照）：
 * <ul>
 *   <li>需求侧：{@code RequirementManifest.Requirement.key}（或 {@code PrimaryGoal.waitFor} 的
 *       {@code AssetRequirement.assetKey}）—— 都是「要什么资产」的键；</li>
 *   <li>事实侧：{@code StageKeyNormalizer.normalize(阶段描述)}；</li>
 *   <li>持有侧：{@code AssetRegistry.usableCounts()} 的 {@code assetKey -> count}。</li>
 * </ul>
 * ★ <b>资产键与阶段键不是一回事</b>：阶段键是「做了什么动作」的规范化串，
 * 资产键是「要什么物品」。所以本类<b>只把「需求」与「持有」直接比</b>，
 * 事实那一列只作参考，<b>不</b>假装它俩能 join。
 *
 * <p><b>★ 报告口径（硬要求）</b>：三种状态都只陈述<b>数量关系</b>，不下结论：
 * <ul>
 *   <li>{@code MEETS_MINIMUM} —— 持有量 ≥ 需求最小值（<b>这是数量事实</b>）；</li>
 *   <li>{@code BELOW_MINIMUM} —— 持有量 &lt; 最小值 ⇒ <b>缺口 = 最小值 - 持有量</b>
 *       （给数字，不给「你应该去捡」这种建议）；</li>
 *   <li>{@code UNKNOWN_HELD} —— 资产登记表里<b>没有这个键</b>。
 *       ★ 这<b>不能</b>算成缺口：没登记 ≠ 没有。只有 {@code inventory_scan} 扫过才算数
 *       （见 {@code AssetRegistry.usableCounts()} 的注释）。</li>
 * </ul>
 *
 * <p><b>★ 与 {@link RequirementFactAudit} 的关系</b>：那个类比「需求 ↔ 事实」，
 * 这个类比「需求 ↔ 持有」，并把两边的结果并排放进同一行 ——
 * 这样人才看得出「事实说做完了、但持有仍不达标」这种真正需要人看的组合。
 * <b>本类同样只读</b>，不改任何一侧，不触发获取动作。
 */
public final class RequirementAssetAudit {

    public enum Verdict {
        /** 持有量 ≥ 最小值。 */
        MEETS_MINIMUM,
        /** 持有量 &lt; 最小值，有明确缺口数字。 */
        BELOW_MINIMUM,
        /**
         * ★ 资产登记表里没有这个键 ⇒ <b>不知道持有多少</b>。
         * 刻意与 {@code BELOW_MINIMUM} 分开：没登记不等于没有。
         */
        UNKNOWN_HELD
    }

    public record Item(String requirementKey, int minimum, List<String> alternatives,
                       Integer held, int gap, Verdict verdict,
                       int factCount, String factMatchedBy,
                       List<Map<String, Object>> usage) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requirement_key", requirementKey);
            m.put("minimum", minimum);
            m.put("alternatives", alternatives);
            m.put("held", held);
            m.put("gap", gap);
            m.put("verdict", verdict.name());
            m.put("fact_count", factCount);
            m.put("fact_matched_by", factMatchedBy);
            m.put("usage", usage == null ? List.of() : usage);
            return m;
        }
    }

    public record Report(String goalId, int requirementCount, List<Item> items,
                         boolean heldKnown, List<String> notes) {

        /** 资产登记表里一个键都没有 ⇒ 这次对账<b>只知道需求侧</b>。 */
        public boolean knowsHeld() {
            return heldKnown;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal_id", goalId);
            m.put("requirement_count", requirementCount);
            m.put("knows_held", knowsHeld());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Item i : items) {
                rows.add(i.toMap());
            }
            m.put("items", rows);
            m.put("notes", notes);
            m.put("note", "★ 只读对账：只陈述数量关系，**不触发获取、不下『该去捡』这类结论**");
            m.put("unknown_vs_missing", "UNKNOWN_HELD（登记表里没这个键，= 不知道）"
                    + " 与 BELOW_MINIMUM（持有量不足，有明确缺口）是两件事");
            return m;
        }
    }

    private RequirementAssetAudit() {
    }

    /**
     * 对账。
     *
     * @param manifest      需求清单（必需；{@code null} ⇒ 空报告 + 说明）
     * @param facts         共同事实（可空，只作参考列）
     * @param registry      资产登记表（可空 ⇒ 全部 {@code UNKNOWN_HELD}）
     * @param usageByReq    可选：需求键 → 用量行（来自 {@link UsageLedger#usageByMemo}）。
     *                      为 null 时不附带用量信息。
     */
    public static Report audit(RequirementManifest.Manifest manifest,
                               CompletedFactStore facts, AssetRegistry registry,
                               Map<String, List<Map<String, Object>>> usageByReq) {
        List<String> notes = new ArrayList<>();
        if (manifest == null || manifest.requirements() == null || manifest.requirements().isEmpty()) {
            notes.add("HAS_NO_MANIFEST");
            return new Report(manifest == null ? "" : manifest.goalId(), 0, List.of(),
                    false, List.copyOf(notes));
        }

        Map<String, Integer> held = registry == null ? Map.of() : registry.usableCounts();
        boolean heldKnown = !held.isEmpty();
        if (registry == null) {
            notes.add("NO_ASSET_REGISTRY：没有资产登记，持有量一律记为「不知道」而不是 0");
        } else if (!heldKnown) {
            notes.add("REGISTRY_EMPTY：资产登记表里一个键都没有（可能没扫过背包）"
                    + " ⇒ 全部记为 UNKNOWN_HELD，不记成缺口");
        }

        Map<String, Integer> factCounts = new LinkedHashMap<>();
        Map<String, String> factBy = new LinkedHashMap<>();
        if (facts != null) {
            for (var f : facts.stageFacts()) {
                if (f.goalId() == null || !manifest.goalId().equals(f.goalId())) {
                    continue;
                }
                String k = f.stageKey() == null ? "" : f.stageKey();
                factCounts.merge(k, 1, Integer::sum);
                factBy.putIfAbsent(k, "EXACT");
            }
        }

        List<Item> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RequirementManifest.Requirement r : manifest.requirements()) {
            if (r == null || r.key() == null || r.key().isBlank() || !seen.add(r.key())) {
                continue;
            }
            int min = Math.max(1, r.minimum());
            Integer h = held.get(r.key());
            Verdict v;
            int gap = 0;
            if (h == null) {
                v = Verdict.UNKNOWN_HELD;
            } else if (h >= min) {
                v = Verdict.MEETS_MINIMUM;
            } else {
                v = Verdict.BELOW_MINIMUM;
                gap = min - h;
            }
            // 事实列只作参考：阶段键与资产键**不是一回事**，不做 join 也不假装 join
            String matchedBy = "NOT_COMPARED";
            int fc = 0;
            for (Map.Entry<String, Integer> e : factCounts.entrySet()) {
                if (e.getKey().equals(r.key())) {
                    fc += e.getValue();
                    matchedBy = "EXACT";
                }
            }
            if (fc == 0) {
                for (Map.Entry<String, Integer> e : factCounts.entrySet()) {
                    if (e.getKey().startsWith(r.key())) {
                        fc += e.getValue();
                        matchedBy = "PREFIX";
                    }
                }
            }
            if ("NOT_COMPARED".equals(matchedBy)) {
                notes.add("需求 '" + r.key() + "' 是资产键，事实侧是阶段键，"
                        + "两者语义不同 ⇒ 本次不做 join（只并列展示）");
            }
            List<Map<String, Object>> usage = usageByReq == null ? List.of() : 
                    usageByReq.getOrDefault(r.key(), List.of());
            items.add(new Item(r.key(), min,
                    r.alternatives() == null ? List.of() : List.copyOf(r.alternatives()),
                    h, gap, v, fc, matchedBy, usage));
        }
        return new Report(manifest.goalId(), items.size(), List.copyOf(items),
                heldKnown, List.copyOf(notes));
    }
}