package com.dwinovo.numen.api.carrier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 携带器 = <b>分级判断链</b>（{@code 38} v3 B5 / v3.5 §1 的可执行形态）。
 *
 * <p><b>为什么要有这个类（2026-10-01 实机查证）</b>：原 {@code Memo.assessCarrier} 虽然注释写着
 * 「三级判断」，但实现是<b>把三级无条件全算一遍</b> —— 那不是判断链，是扁平打分。
 * 用户 2026-10-01 确认过：「携带器就是分级判断链」「分段判断连带着来的」。
 *
 * <p><b>短路的准确语义</b>（B5 原文）：<b>第 1 级不成立，就根本不问第 2 级</b>。
 * 所以每级是一个<b>闸</b>：不成立 → 停止，后续级<b>不求值</b>。
 *
 * <p><b>★ 必须如实说「没问」</b>（B21 同一类）：{@link Result#why} 里要标出
 * 「第 N 级不成立，后续级未求值」。否则调用方会以为全判过了 ——
 * <b>让没做的事看起来像做过</b>，和「让缺失看起来像数据」是同一种错。
 *
 * <p><b>级数仍留白</b>（B5）：这里只落<b>结构 + 可插拔</b>，
 * 具体挂几条规则由游戏内真问题决定。加一条规则<b>不改动</b>既有规则的判定结果。
 *
 * <p><b>「安全位」的正式定义</b>：它是<b>某一级判定成立时携带的内容</b>，
 * <b>不是</b>固定的第 1 级。这里用 {@code SafetySpot} 作为示范（一个可插拔的实现）。
 *
 * <p><b>纯 JVM</b>：不 import MC、<b>不 import {@code core.common}</b>
 * （{@code numen-plugin.gradle:37-44} 封死跨插件 import）。
 */
public final class CarrierChain {

    private CarrierChain() {
    }

    /**
     * 一级判断。
     *
     * @param name    级名（会出现在 why 里，可读）
     * @param applies 这一级<b>成立</b>吗？<b>不成立则短路，后续级不求值</b>
     * @param carry   <b>成立</b>时携带的内容
     * @param fix     <b>不成立</b>时携带的内容 —— 即「<b>补齐它才成立</b>的东西」。
     *                <p>⚠️ 这个字段是本轮实机/单测逼出来的：短路之后若什么都不带，
     *                调用方只知道「不行」，<b>却不知道缺什么</b> —— 那是功能回退。
     *                所以<b>失败的级要说出缺什么</b>，这才是携带器存在的意义。
     */
    public record Rule(String name, Predicate<Facts> applies, List<String> carry, List<String> fix) {
        public Rule(String name, Predicate<Facts> applies, List<String> carry) {
            this(name, applies, carry, List.of());
        }

        public Rule {
            name = name == null ? "" : name;
            carry = carry == null ? List.of() : List.copyOf(carry);
            fix = fix == null ? List.of() : List.copyOf(fix);
        }
    }

    /**
     * 求值时可见的事实 + <b>求值日志</b>。
     *
     * <p>日志存在的唯一目的：让「短路真的发生了」<b>可被单测证明</b>
     * ——不是靠读 why 文本，而是靠断言后续级<b>确实没被调用</b>。
     */
    public static final class Facts {
        private final Map<String, String> kv;
        private final String rawLower;
        private final List<String> evaluated = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();

        Facts(Map<String, String> kv, String rawLower) {
            this.kv = kv;
            this.rawLower = rawLower;
        }

        /** 快照里的键值（小写、已清洗）。取值不到就是空串，<b>不是默认值</b>。 */
        public String get(String key) {
            String v = kv.get(key);
            return v == null ? "" : v;
        }

        public boolean has(String key) {
            String v = kv.get(key);
            return v != null && !v.isBlank();
        }

        public String raw() {
            return rawLower;
        }

        /** 血量；解析不出返回 -1（哨兵，调用方须用 hasHp() 区分）。 */
        public int hp() {
            return ItemSemantics.MemoFactsBridge.hpOf(rawLower);
        }

        public boolean hasHp() {
            return hp() >= 0;
        }

        /** 血量档：CRITICAL / LOW / MID / HIGH / UNKNOWN。 */
        public String hpBand() {
            int h = hp();
            if (h < 0) {
                return "UNKNOWN";
            }
            if (h <= 4) {
                return "CRITICAL";
            }
            if (h <= 10) {
                return "LOW";
            }
            if (h <= 15) {
                return "MID";
            }
            return "HIGH";
        }

        /** 有敌对实体吗（含 hostile=true 或实体名命中）。 */
        public boolean hostileNearby() {
            if ("true".equals(get("hostile"))) {
                return true;
            }
            return ItemSemantics.anyContains(rawLower, ItemSemantics.HOSTILE_NAMES);
        }

        /** 有被动实体吗。 */
        public boolean passiveNearby() {
            return ItemSemantics.anyContains(rawLower, ItemSemantics.PASSIVE_NAMES);
        }

        /** 手里的东西<b>是不是武器</b>（按语义，不按「非空」）。 */
        public boolean hasRealWeapon() {
            return ItemSemantics.isWeapon(get("weapon")) || ItemSemantics.isWeapon(get("sword"))
                    || ItemSemantics.isWeapon(get("axe")) || ItemSemantics.isWeapon(get("bow"));
        }

        /** 有没有<b>真的</b>护甲（按语义，不按「非空」）。 */
        public boolean hasRealArmor() {
            if ("none".equals(get("armor")) || "no".equals(get("armor"))) {
                return false;
            }
            return ItemSemantics.isAnyArmor(get("armor"))
                    || ItemSemantics.isAnyArmor(get("chestplate"))
                    || ItemSemantics.isAnyArmor(get("helmet"))
                    || ItemSemantics.isAnyArmor(get("leggings"))
                    || ItemSemantics.isAnyArmor(get("boots"));
        }

        /** 手里的东西<b>既不是武器也不是护甲</b>（比如一块台阶方块）—— 判定「认不出来」用。 */
        public boolean handItemUnrecognized() {
            String w = get("weapon");
            return !w.isBlank() && !ItemSemantics.isWeapon(w);
        }

        // ---- 求值日志（供单测断言短路真的发生）----

        void markEvaluated(String level) {
            evaluated.add(level);
        }

        void markSkipped(String level) {
            skipped.add(level);
        }

        public List<String> evaluatedLevels() {
            return List.copyOf(evaluated);
        }

        public List<String> skippedLevels() {
            return List.copyOf(skipped);
        }
    }

    /**
     * 求值结果。
     *
     * @param carry   携带清单（只读输出）
     * @param why     人类可读的判断依据；<b>含「后续级未求值」的说明</b>
     * @param stoppedAt 第几级短路了（-1 = 全部走完）
     * @param evaluatedLevels 实际求值过的级
     * @param skippedLevels   因短路而<b>没被求值</b>的级
     */
    public record Result(List<String> carry, String why, int stoppedAt,
                         List<String> evaluatedLevels, List<String> skippedLevels) {

        public boolean shortCircuited() {
            return stoppedAt >= 0;
        }
    }

    /**
     * 按顺序求值，<b>遇第一个不成立的级就停</b>。
     *
     * @param rules 规则链（顺序即判断顺序）
     * @param facts 事实（<b>由本方法填充求值日志</b>）
     */
    public static Result evaluate(List<Rule> rules, Facts facts) {
        List<String> carry = new ArrayList<>();
        StringBuilder why = new StringBuilder();
        int stoppedAt = -1;

        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            facts.markEvaluated(r.name());
            boolean ok;
            try {
                ok = r.applies().test(facts);
            } catch (RuntimeException e) {
                // 判定本身出错 → 视为「不成立」并短路，**不猜**
                ok = false;
                if (why.length() > 0) {
                    why.append("  ");
                }
                why.append(r.name()).append("=<判定出错>");
                facts.markSkipped(r.name() + "(以及其后)");
                stoppedAt = i;
                break;
            }
            if (why.length() > 0) {
                why.append("  ");
            }
            why.append(r.name()).append('=').append(ok ? "成立" : "不成立");
            if (!ok) {
                // ★ 短路：不问后续级，并如实说。
                // 但**这一级要说出「缺什么才成立」** —— 只说「不行」而不说「缺什么」是功能回退。
                for (String c : r.fix()) {
                    if (!carry.contains(c)) {
                        carry.add(c);
                    }
                }
                facts.markSkipped(r.name() + "(以及其后)");
                stoppedAt = i;
                why.append(" → **后续级未求值**");
                break;
            }
            for (String c : r.carry()) {
                if (!carry.contains(c)) {
                    carry.add(c);
                }
            }
        }

        // ⚠️ 2026-10-01 实机抓到：这里原来写的是 facts.skippedLevels().size()，
        //    而 skippedLevels 每短路一次只塞**一条**（名字+「以及其后」），
        //    于是 4 条规则在第 1 级短路时它报「后续 1 项未求值」——**实际是 3 项**。
        //    报了个不对的数 = 让「没做的事」看起来像只做了一件 → B21 同一类。
        //    正确算法：剩余规则数 = 总数 - 已走过的 - 1。
        int notEvaluated = stoppedAt >= 0 ? Math.max(0, rules.size() - stoppedAt - 1) : 0;
        String stoppedNote = stoppedAt >= 0
                ? "（在第 " + (stoppedAt + 1) + " 级短路，后续 " + notEvaluated + " 项未求值）"
                : "（全部 " + rules.size() + " 级已求值）";

        return new Result(List.copyOf(carry), why + "  " + stoppedNote, stoppedAt,
                facts.evaluatedLevels(), facts.skippedLevels());
    }

    /**
     * 构造一份待求值的事实集。
     *
     * <p><b>2026-10-01 从包级改为 public</b>：本类搬到 {@code api:common} 之后，
     * {@code plugins/learner} 与 {@code plugins/rdd} 都要用它 ——
     * 跨插件 import 被 {@code numen-plugin.gradle:37-44} 封死，
     * <b>共享同一份判断链是唯一不让语义漂移的做法</b>（两份实现必然漂）。
     *
     * @param kv        快照解析出的键值（小写、已清洗）
     * @param rawLower  快照原文（小写），供实体名片段匹配
     */
    public static Facts factsOf(Map<String, String> kv, String rawLower) {
        return new Facts(kv, rawLower == null ? "" : rawLower.toLowerCase(Locale.ROOT));
    }
}