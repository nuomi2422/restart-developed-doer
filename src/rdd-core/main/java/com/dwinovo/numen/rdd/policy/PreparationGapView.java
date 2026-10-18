package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求 → 风险准备 的只读判定（第三批 N3 第一步，2026-10-05）。
 *
 * <p><b>关联 id 核查结论（先查再做，N1 的教训）</b>：
 * 风险准备这一侧用 {@code ResourceBudget.missingFor(RiskLevel, available)}，
 * 判据同样是 {@code String -> Integer} 的<b>持有量表</b>；
 * 而 {@code available} 应当来自 {@link AssetRegistry#usableCounts()} ——
 * <b>与 N2 第二步用的是同一份数据</b>。所以这里也是<b>真能 join</b> 的，
 * 不像 N1 那样只能弱对账。
 *
 * <p><b>这个类回答什么</b>：按目标的风险等级算「还缺哪些准备」。
 * <b>只陈述缺口</b>，不生成待办、不排序、不催任务 —— 那是 {@code RiskGate.check} 已经
 * 算好的事，本类只是把它与需求视图并排放到一起，让人能一次看清：
 * <ul>
 *   <li>需求差什么（{@link RequirementView}）；</li>
 *   <li>这个风险等级还缺什么准备（{@link RiskGate}）。</li>
 * </ul>
 *
 * <p><b>★ 关键边界</b>：
 * <ul>
 *   <li><b>没有持有数据 ⇒ 不产出缺口</b>。资产登记表为空时，
 *       {@code RiskGate} 会把所有准备都判成 missing，
 *       而那<b>只说明「没扫背包」</b>。此时必须整段标成
 *       {@code UNKNOWN_HELD} 并说明原因，<b>绝不能</b>把一堆 missing 当成真缺口报出去
 *       —— 那会让人去准备一堆其实已经有的东西。</li>
 *   <li>缺口的<b>键</b>是资源预算的键，与需求的资产键<b>不保证同源</b>，
 *       所以本类<b>不做</b>「需求缺口 ⊆ 准备缺口」这类求交 —— 求交需要两边键空间一致，
 *       而那正是 N1 踩过的坑。两边分别列出。</li>
 * </ul>
 */
public final class PreparationGapView {

    public enum HeldState {
        /** 有持有数据（资产登记表非空）。 */
        KNOWN,
        /** ★ 没有持有数据 ⇒ 本次<b>不产出</b>准备缺口，只说明「判断不了」。 */
        UNKNOWN_HELD
    }

    public record Gap(String assetKey, int required, int held, int missing) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asset_key", assetKey);
            m.put("required", required);
            m.put("held", held);
            m.put("missing", missing);
            return m;
        }
    }

    public record View(String goalId, HeldState heldState, List<Gap> preparationGaps,
                       int requirementAttentionCount, List<String> notes) {

        /**
         * 这份视图能不能用来判断「缺什么」。
         *
         * <p>★ {@code false} 时 {@link #preparationGaps()} <b>必须为空</b>，
         * 否则调用方会把「没数据」当成「缺一堆」。
         */
        public boolean usable() {
            return heldState == HeldState.KNOWN;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal_id", goalId);
            m.put("held_state", heldState.name());
            m.put("usable", usable());
            List<Map<String, Object>> gs = new ArrayList<>();
            for (Gap g : preparationGaps) {
                gs.add(g.toMap());
            }
            m.put("preparation_gaps", gs);
            m.put("requirement_attention_count", requirementAttentionCount);
            m.put("notes", notes);
            if (!usable()) {
                m.put("warning", "★ 没有持有数据，本次**不产出**准备缺口。"
                        + "空列表代表「判断不了」，不代表「什么都不缺」。");
            }
            m.put("note", "★ 只读：需求缺口与准备缺口**分别列出、刻意不求交**"
                    + "（两边键空间不保证同源，求交就会重演 N1 的假 join）");
            return m;
        }
    }

    private PreparationGapView() {
    }

    /**
     * 合成。
     *
     * @param level 风险等级（{@code null} 会被 {@code RiskGate} 当 NORMAL）
     */
    public static View build(RequirementManifest.Manifest manifest, RiskLevel level,
                             CompletedFactStore facts, AssetRegistry registry) {
        List<String> notes = new ArrayList<>();
        if (manifest == null || manifest.requirements() == null || manifest.requirements().isEmpty()) {
            notes.add("HAS_NO_MANIFEST");
            return new View("", HeldState.UNKNOWN_HELD, List.of(), 0, List.copyOf(notes));
        }
        Map<String, Integer> available = registry == null ? Map.of() : registry.usableCounts();
        if (available.isEmpty()) {
            // ★ 这是本类最重要的一条分支
            notes.add("NO_HELD_DATA：资产登记表为空（多半是没扫过背包）"
                    + " ⇒ 不产出准备缺口。RiskGate 在没有持有数据时会把所有准备都判成 missing，"
                    + "直接报出去会让人去准备一堆其实已经有的东西。");
int attention = RequirementView.build(manifest, facts, registry, null).attentionCount();
            return new View(manifest.goalId(), HeldState.UNKNOWN_HELD, List.of(),
                    attention, List.copyOf(notes));
        }

        var verdict = RiskGate.check(level, available);
        // ★ 要求量取自 ResourceBudget.requiredFor(level) —— 那是**风险预算的权威要求**，
        //   而不是从需求清单猜。需求清单里的 minimum 是「需求侧要多少」，
        //   两者语义不同；拿需求侧当风险预算会算错缺口。
        Map<String, Integer> budgetRequired = ResourceBudget.requiredFor(verdict.level());
        List<Gap> gaps = new ArrayList<>();
        for (String key : verdict.missing()) {
            int held = available.getOrDefault(key, 0);
            Integer req = budgetRequired.get(key);
            if (req == null) {
                // ★ 预算表里没有这个键 ⇒ 要求量真的未知。此时**不能编一个缺口**，
                //   也不能写 missing=0（那会被读成「不缺」）。只报持有量并说明。
                notes.add("准备缺口 '" + key + "' 不在风险预算要求表里 ⇒ 要求量未知，"
                        + "只报持有量 " + held + "，不给缺口数字");
                gaps.add(new Gap(key, -1, held, -1));
            } else {
                gaps.add(new Gap(key, req, held, Math.max(0, req - held)));
            }
        }
        gaps.sort((a, b) -> Integer.compare(
                b.missing() < 0 ? Integer.MIN_VALUE : b.missing(),
                a.missing() < 0 ? Integer.MIN_VALUE : a.missing()));
        int attention = RequirementView.build(manifest, facts, registry, null).attentionCount();
        notes.add("risk_level=" + verdict.level() + " allowed=" + verdict.allowed());
        if (!verdict.allowed()) {
            notes.add("RiskGate 建议的备料任务（本次不执行，仅记录）："
                    + String.join("; ", verdict.prepTasks()));
        }
        return new View(manifest.goalId(), HeldState.KNOWN, List.copyOf(gaps),
                attention, List.copyOf(notes));
    }
}