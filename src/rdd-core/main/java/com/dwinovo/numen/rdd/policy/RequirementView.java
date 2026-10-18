package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求侧的<b>单一事实视图</b>（第三批 N2 第三步，2026-10-05）：
 * 把「需求 ↔ 完成事实」与「需求 ↔ 持有资产」两份对账<b>并排放进同一行</b>。
 *
 * <p><b>为什么必须合起来看</b>：两份对账各自都成立，但真正需要人介入的是<b>组合</b>：
 * <ul>
 *   <li>事实说阶段已完成、<b>但持有仍不达标</b> ⇒ 「做完了」不等于「东西在手」，
 *       这多半是采集口径问题（背包没扫/物品被消耗），值得看一眼；</li>
 *   <li>事实里没有、<b>而持有也不达标</b> ⇒ 缺口是事实性的（大概率真没做/没带够）；</li>
 *   <li>持有已达标 ⇒ 这一条不需要再问。</li>
 * </ul>
 * 拆成两份报告时，这些组合要人自己去心里做减法 —— 那就等于没做。
 *
 * <p><b>★ 本类不下结论、不给动作建议</b>：只把三列并排陈述，并标出
 * 「哪几行值得人看一眼」({@link #attention()})。不排序优先级、不催任务、不改任何一侧。
 *
 * <p><b>★ 措辞纪律</b>：{@code heldGap} 只表示<b>数量不足</b>，
 * {@code factCovered} 只表示<b>事实里有记录</b>；两者都不等于「做成了/没做成」。
 */
public final class RequirementView {

    /**
     * 值得人看一眼的组合。<b>枚举声明顺序即排序顺序（最可疑在前）</b>，
     * {@code NONE} 刻意放最后 —— 否则按 ordinal 升序排会把「不用看」顶到最前，
     * 整个「关注项优先」就反了（这个坑踩过）。
     */
    public enum Attention {
        /**
         * ★ 事实里有完成记录、但持有仍不达标 ⇒ 「做完」与「在手」对不上。
         * 最可能是采集口径问题（背包没扫/东西已消耗），值得人看一眼。
         */
        DONE_BUT_NOT_HELD,
        /** 事实里没有记录，且持有不达标。 */
        NOT_DONE_AND_NOT_HELD,
        /** 事实里没有记录，但持有已达标（可能是事实没记，不一定是没做）。 */
        HELD_BUT_NO_FACT,
        /** 持有已达标，不必再问。 */
        NONE
    }

    public record Row(String requirementKey, int minimum, Integer held, int heldGap,
                      String heldVerdict, int factCount, String factMatchedBy,
                      Attention attention,
                      List<Map<String, Object>> usage) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requirement_key", requirementKey);
            m.put("minimum", minimum);
            m.put("held", held);
            m.put("held_gap", heldGap);
            m.put("held_verdict", heldVerdict);
            m.put("fact_count", factCount);
            m.put("fact_matched_by", factMatchedBy);
            m.put("attention", attention.name());
            m.put("usage", usage == null ? List.of() : usage);
            return m;
        }
    }

    public record View(String goalId, List<Row> rows, List<String> notes) {

        public int attentionCount() {
            int n = 0;
            for (Row r : rows) {
                if (r.attention() != Attention.NONE) {
                    n++;
                }
            }
            return n;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal_id", goalId);
            m.put("row_count", rows.size());
            m.put("attention_count", attentionCount());
            List<Map<String, Object>> rs = new ArrayList<>();
            for (Row r : rows) {
                rs.add(r.toMap());
            }
            m.put("rows", rs);
            m.put("notes", notes);
            m.put("note", "★ 只读视图：三列并排陈述事实，**不给动作建议、不排序优先级、不改任何一侧**");
            m.put("column_meaning", "held_gap=数量不足（不等于没做）；"
                    + "fact_count=事实里的记录条数（不等于做成了）；"
                    + "attention=哪几行值得人看一眼（不是优先级排序）");
            return m;
        }
    }

    private RequirementView() {
    }

    /**
     * 合成视图。
     *
     * <p>实现上<b>直接复用</b>两个已验证的对账类，而不是自己再解析一遍事实/资产 ——
     * 重复实现同一套口径，迟早两份会漂移，而漂移会表现成「两份报告互相矛盾」。
     *
     * @param manifest     需求清单（必需）
     * @param facts        共同事实（可空）
     * @param registry     资产登记（可空）
     * @param usageByReq   可选：需求键 → 用量行（来自 {@link UsageLedger#usageByMemo}）。
     *                     为 null 时不附带用量信息。
     */
    public static View build(RequirementManifest.Manifest manifest,
                             CompletedFactStore facts, AssetRegistry registry,
                             Map<String, List<Map<String, Object>>> usageByReq) {
        List<String> notes = new ArrayList<>();
        if (manifest == null || manifest.requirements() == null || manifest.requirements().isEmpty()) {
            notes.add("HAS_NO_MANIFEST");
            return new View(manifest == null ? "" : manifest.goalId(), List.of(), List.copyOf(notes));
        }
        var fa = RequirementFactAudit.audit(manifest, facts);
        var aa = RequirementAssetAudit.audit(manifest, facts, registry, usageByReq);
        notes.addAll(fa.notes());
        notes.addAll(aa.notes());

        Map<String, RequirementFactAudit.Item> byKey = new LinkedHashMap<>();
        for (var i : fa.items()) {
            byKey.put(i.requirementKey(), i);
        }

        List<Row> rows = new ArrayList<>();
        for (var a : aa.items()) {
            var f = byKey.get(a.requirementKey());
            int factCount = f == null ? 0 : f.factCount();
            boolean factCovered = f != null && f.verdict() == RequirementFactAudit.Verdict.COVERED_BY_FACT;
            boolean heldOk = a.verdict() == RequirementAssetAudit.Verdict.MEETS_MINIMUM;
            boolean heldUnknown = a.verdict() == RequirementAssetAudit.Verdict.UNKNOWN_HELD;

            Attention at;
            if (heldUnknown) {
                // 持有量未知 ⇒ 无法判断该不该关注，如实说「不知道」
                at = Attention.NONE;
                notes.add("需求 '" + a.requirementKey() + "' 持有量未知，attention 按 NONE 处理"
                        + "（不是「不用看」，是「判断不了」）");
            } else if (heldOk) {
                at = factCovered ? Attention.NONE : Attention.HELD_BUT_NO_FACT;
            } else {
                at = factCovered ? Attention.DONE_BUT_NOT_HELD : Attention.NOT_DONE_AND_NOT_HELD;
            }
            rows.add(new Row(a.requirementKey(), a.minimum(), a.held(), a.gap(),
                    a.verdict().name(), factCount,
                    // 口径要如实且可读：事实侧只做**阶段键**比对（资产键≠阶段键），
                    // 所以只说「有没有比中」，不说 EXACT/PREFIX —— 那是事实侧内部的口径。
                    factCount > 0 ? "MATCHED" : "NO_MATCH", at, a.usage()));
        }
        // 关注项排在前面（枚举声明顺序 = 可疑程度降序，NONE 在最后）
        rows.sort((x, y) -> x.attention().ordinal() - y.attention().ordinal());
        return new View(manifest.goalId(), List.copyOf(rows), List.copyOf(notes));
    }
}