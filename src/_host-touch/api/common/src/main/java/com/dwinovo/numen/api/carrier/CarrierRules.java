package com.dwinovo.numen.api.carrier;

import java.util.List;

/**
 * 携带器的<b>默认规则集</b>（{@code 38} v3.5 §1 的可执行形态）。
 *
 * <p><b>2026-10-01 从 {@code Memo.CarrierRules} 搬到这里</b>：规则集是<b>共享策略</b>，
 * 不是 learner 私有 —— {@code plugins/rdd} 要在构建执行 AI 上下文时跑<b>同一份</b>规则，
 * 否则两个插件的携带语义会漂（而携带器判错 = 下游拿到「看起来对但错」的携带清单）。
 *
 * <p><b>短路语义</b>：每级是<b>闸</b> —— 不成立就停，后续级<b>不求值</b>，
 * 且 {@code why} 会标出「后续级未求值」（B21：让没做的事看起来像做过 = 同一种错）。
 *
 * <p><b>失败的那一级要带 {@code fix}</b>：只说「不行」而不说「缺什么」是<b>功能回退</b>
 * （2026-10-01 被 2026-09-29 的旧回归测试抓到）。
 *
 * <p><b>级数仍留白</b>（{@code 38} v3 B5）：这里只落<b>结构 + 可插拔</b>，
 * 加一条规则不改动既有规则的判定结果。
 *
 * <p><b>纯 JVM、零 MC import、零 core.common import</b>（{@code numen-plugin.gradle:37-44}
 * 封死跨插件 import，所以装备语义只能用 {@link ItemSemantics} 的 id 后缀判定）。
 */
public final class CarrierRules {

    private CarrierRules() {
    }

    /**
     * 顺序即判断顺序；短路在前一级触发时，后面的<b>不被调用</b>。
     *
     * <p>⚠️ <b>改这一份就同时改了 learner 与 rdd 的行为</b> —— 这正是把它放在共享层的原因：
     * 只有一份，就不存在「两边漂了」这种事。
     */
    public static final List<CarrierChain.Rule> DEFAULT = List.of(
            // 第 1 级：这一轮到底该不该动 —— 目标是谁。
            // ⚠️ 2026-10-01 实机抓到：原来这里**把 hasHp() 也算成「目标明确」**，
            //    于是只给 hp/food/dim/pos 的快照会让第 1 级成立 → 后面全被判完，
            //    **短路根本没机会发生**。
            //    但第 1 级问的是「目标是谁」，血量答不了这个问题 → 不能放 hasHp()。
            new CarrierChain.Rule("指向谁",
                    f -> f.has("target") || f.hostileNearby() || f.passiveNearby(),
                    List.of("战斗相关经验（附近有敌对）"),
                    List.of("先弄清这轮的目标是谁")),

            // 第 2 级：装备够不够 —— ★ 按<b>语义</b>判，不是「非空」。
            // 2026-10-01 实机：主手拿 smart_slab_init（一块模组台阶方块）曾被判成「有武器」。
            new CarrierChain.Rule("装备",
                    f -> {
                        if (f.hostileNearby() && !f.hasRealWeapon()) {
                            return false;
                        }
                        return f.hasRealArmor() || "HIGH".equals(f.hpBand());
                    },
                    List.of(),
                    buildEquipFix()),

            // 第 3 级：血量撑不撑得住
            new CarrierChain.Rule("血量",
                    f -> !"CRITICAL".equals(f.hpBand()),
                    List.of(),
                    List.of("食物/治疗类经验", "撤退/避险类经验")),

            // 第 4 级：兜底 —— **只留，不猜**
            new CarrierChain.Rule("兜底",
                    f -> true,
                    List.of("撤退/避险类经验"),
                    List.of("撤退/避险类经验"))
    );

    /**
     * 「装备」这一级不成立时缺什么 —— <b>按语义</b>，不是按「非空」。
     *
     * <p>这一段对应 2026-10-01 实机抓到的缺陷：原实现用 {@code truthy()}，
     * 于是 {@code weapon=smart_slab_init}（台阶方块）被判成「有武器」、
     * {@code armor=dirt} 被判成「有护甲」→ 携带器<b>建议错的东西</b>。
     *
     * <p>这里给的是<b>通用缺项</b>：具体「到底缺武器还是缺护甲」由调用方结合
     * {@code why} 里的 {@code 武器=无} / {@code 护甲=无} 自行取用 ——
     * 因为 {@code fix} 是无参构造，拿不到 facts。
     */
    private static List<String> buildEquipFix() {
        return List.of("武器获取类工具", "护甲获取类工具");
    }
}
