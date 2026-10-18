package com.dwinovo.numen.api.carrier;

import com.dwinovo.numen.api.carrier.CarrierChain.Facts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 独立闹钟（E2）：与 {@link CarrierChain} 并存的<b>并行提醒器</b>。
 *
 * <p><b>为什么要有这个类（2026-10-06 实机观察）</b>：旧携带器是一条串行短路链 ——
 * 第 1 级不成立就根本不问第 2 级（这是链的<b>正确语义</b>，已由
 * {@code CarrierChainTest} 钉死，不能改）。但用户实际看到的是：
 * 「晚上不睡、没吃饱、血没满、有苦力怕，都没有提醒」——
 * 因为低血/目标不明等任一前置条件不成立时，饥饿/夜间/苦力怕这些<b>彼此无关</b>的告警
 * 被整条链一起短路掉了。<b>互不相关的条件不该互相压制。</b>
 *
 * <p><b>形态：各自独立求值，一个都不许压掉另一个</b>。每个闹钟内部可以有自己的分级
 * （比如低血分 P0/P1、苦力怕按距离/点燃分档），但闹钟之间<b>没有先后闸门</b>：
 * {@link #evaluate} 把 {@link #ALL} 全部算一遍，返回所有命中。
 *
 * <p><b>与旧链的关系</b>：旧链保留为兼容入口（{@code <carry>} 块原样保留），
 * 本类输出独立的 {@code <alarms>} 块；两条通道互不改写对方语义。
 *
 * <p><b>纯 JVM</b>：不 import MC、不 import {@code core.common}
 * （{@code numen-plugin.gradle:37-44} 封死跨插件 import）。
 * 所有事实都从 {@link CarrierChain.Facts} 的键值里读；<b>缺失 = 不触发</b>，
 * 绝不拿默认值猜（DL-4：UNKNOWN ≠ 0）。
 *
 * <p><b>提醒字段</b>（E2 计划）：rule/version、事实引用、短原因、优先级、去重键。
 * 时间/过期与边沿去抖在调用方（{@code RddCarryHint}）完成 —— 本类保持纯函数。
 */
public final class CarrierAlarms {

    /** 优先级。P0 = 现在就可能有后果（点燃的苦力怕/贴脸/危急血量）；P1 = 该处理；P2 = 仅提示。 */
    public enum Prio {
        P0, P1, P2
    }

    /**
     * 一条闹钟。
     *
     * @param rule    规则名（稳定 id，去重键 = rule + 同伴作用域）
     * @param version 版本（规则语义变更时 +1）
     * @param prio    优先级（可按事实动态：同一闹钟升级要可观测）
     * @param active  是否处于触发态（事实缺失必须为 false —— 不猜）
     * @param factsRef 事实引用（渲染进提醒，便于执行者核对依据）
     * @param advice  短原因/建议（一句话，不许长篇经验、不许命令口吻）
     */
    public record Alarm(String rule, int version, Function<Facts, Prio> prio,
                        Predicate<Facts> active, Function<Facts, String> factsRef,
                        Function<Facts, String> advice) {
    }

    /** 一次命中（渲染用）。 */
    public record Hit(String rule, int version, String prio, String facts, String advice) {
    }

    private CarrierAlarms() {
    }

    /** 饥饿线：与 {@code NumenPlayer.HUNGRY_LEVEL = 6} 对齐（身体自理同一条线）。 */
    public static final int HUNGRY_LEVEL = 6;

    /** 软饥饿线：饱食度不满（≤ 此值）但未到饥饿线 —— 仅 P2 提示「方便时垫一口」，不唤醒、不命令。 */
    public static final int FOOD_TOPUP_LEVEL = 17;

    /** 低血线：与 {@link CarrierChain.Facts#hpBand()} 的 LOW 上沿对齐（<=10 低血，<=4 危急）。 */
    public static final int LOW_HP = 10;
    public static final int CRITICAL_HP = 4;

    /** 苦力怕贴脸线（格）：<= 这个距离按危急处理。 */
    public static final int CREEPER_CLOSE = 4;

    /** 全部闹钟。顺序仅影响渲染顺序，<b>不影响求值</b>（全部独立算）。 */
    public static final List<Alarm> ALL = List.of(
            hungry(), hungrySoft(), lowHp(), night(), creeper());

    /** 值得主动唤醒执行者的紧急闹钟（E2.2）：会立刻出事的两个。 */
    private static final Set<String> WAKE_RULES = Set.of("creeper", "low_hp");

    /**
     * 边沿 → 是否主动唤醒执行者（E2.2：危险事件在 LLM 忙 / 长任务执行时也能送达）。
     *
     * <p>只在 {@code added} / {@code escalated} 且 <b>P0</b> 时唤醒；{@code night} 是信息
     * 不是险情、{@code hungry} 已有 {@code NumenEvents.gotHungry} 的 urgent 通道，都不重复叫。
     * 平稳期（{@code active}）与解除（{@code removed}）不叫。纯函数 ——
     * 唤醒的冷却/限频在调用方（{@code RddCarryHint}）。
     */
    public static boolean wakeWorthy(String edge, Hit hit) {
        if (hit == null || !"P0".equals(hit.prio())) {
            return false;
        }
        if (!"added".equals(edge) && !"escalated".equals(edge)) {
            return false;
        }
        return WAKE_RULES.contains(hit.rule());
    }

    /**
     * 独立求值：把所有闹钟各算一遍，<b>没有短路</b>，返回全部命中。
     *
     * <p>单条闹钟求值抛异常 → <b>跳过该条</b>（并留下 {@code rule=error} 命中），
     * 不影响其它闹钟 —— 这正是独立求值的意义所在。
     */
    public static List<Hit> evaluate(Facts f) {
        List<Hit> hits = new ArrayList<>();
        if (f == null) {
            return hits;
        }
        for (Alarm a : ALL) {
            try {
                if (!a.active().test(f)) {
                    continue;
                }
                String advice = a.advice().apply(f);
                if (advice == null || advice.isBlank()) {
                    continue;
                }
                hits.add(new Hit(a.rule(), a.version(), a.prio().apply(f).name(),
                        a.factsRef().apply(f), advice));
            } catch (RuntimeException ex) {
                // 不静默：把「这条闹钟判定出错」作为命中报出去（与链的 <判定出错> 同一种诚实）。
                hits.add(new Hit(a.rule(), a.version(), Prio.P2.name(),
                        "rule-error", "闹钟判定出错（" + ex.getClass().getSimpleName() + "），本条跳过"));
            }
        }
        return hits;
    }

    // ---- 五条闹钟（每条内部可分级；彼此独立） ----

    /** 饥饿：饱食度 <= 6。有食物/无食物/库存未知说三种话（不把 UNKNOWN 当 0）。 */
    private static Alarm hungry() {
        return new Alarm("hungry", 1, f -> Prio.P1,
                f -> {
                    int food = intOf(f, "food");
                    return food >= 0 && food <= HUNGRY_LEVEL;
                },
                f -> "food=" + intOf(f, "food") + " food_items=" + intOf(f, "food_items"),
                f -> {
                    int items = intOf(f, "food_items");
                    if (items == 0) {
                        return "饱食度低（" + intOf(f, "food") + "/20）且背包里没有食物：先找/做点吃的，别等掉血。";
                    }
                    if (items > 0) {
                        return "饱食度低（" + intOf(f, "food") + "/20），背包还有 " + items
                                + " 个食物：可以吃点东西。";
                    }
                return "饱食度低（" + intOf(f, "food") + "/20）：考虑吃点东西（背包食物数未知，先看一眼背包）。";
            });
    }

    /**
     * 软饥饿：饱食度不满（{@link #HUNGRY_LEVEL} &lt; food ≤ {@link #FOOD_TOPUP_LEVEL}）。
     * 仅 P2 提示「方便时垫一口」，把「别等掉到危险线」提前说；到饥饿线后由
     * {@link #hungry()} 接手（本条让位，不重复报）。P2 不进 {@link #WAKE_RULES}，不唤醒。
     */
    private static Alarm hungrySoft() {
        return new Alarm("hungry_soft", 1, f -> Prio.P2,
                f -> {
                    int food = intOf(f, "food");
                    return food > HUNGRY_LEVEL && food <= FOOD_TOPUP_LEVEL;
                },
                f -> "food=" + intOf(f, "food") + " food_items=" + intOf(f, "food_items"),
                f -> {
                    int items = intOf(f, "food_items");
                    if (items > 0) {
                        return "饱食度不满（" + intOf(f, "food") + "/20），背包还有 " + items
                                + " 个食物：方便时吃点垫一下，别等掉到危险线。";
                    }
                    if (items == 0) {
                        return "饱食度不满（" + intOf(f, "food") + "/20）且背包没食物：顺手补点吃的。";
                    }
                    return "饱食度不满（" + intOf(f, "food") + "/20）：方便时吃点东西垫一下。";
                });
    }

    /** 低血：<=10 提醒、<=4 危急升级 P0。<b>未满血是状态，不是无条件吃饭指令</b>。 */
    private static Alarm lowHp() {
        return new Alarm("low_hp", 1,
                f -> f.hasHp() && f.hp() <= CRITICAL_HP ? Prio.P0 : Prio.P1,
                f -> f.hasHp() && f.hp() <= LOW_HP,
                f -> "hp=" + f.hp() + " band=" + f.hpBand(),
                f -> {
                    if (f.hp() <= CRITICAL_HP) {
                        return "血量很低（" + f.hp() + "）：先脱离战斗/拉开距离，找机会回血，别再硬拼。";
                    }
                    return "血量偏低（" + f.hp() + "）：留意附近威胁，找机会回血（吃东西或撤退）。";
                });
    }

    /**
     * 夜晚：只认采样到的 {@code night=1}（采样端只在有昼夜循环的维度写这个键 ——
     * 下界/末地不写，所以不会错误建议睡觉）。分三档：有敌对优先清威胁、有床可睡、
     * 没床就撑过夜晚。床未知不装知道。
     */
    private static Alarm night() {
        return new Alarm("night", 1, f -> Prio.P1,
                f -> "1".equals(f.get("night")),
                f -> "night=1 time=" + intOf(f, "time") + " bed=" + intOf(f, "bed")
                        + " hostile=" + f.hostileNearby(),
                f -> {
                    if (f.hostileNearby()) {
                        return "天黑了，附近还有敌对生物：先清掉威胁或退到安全点，再考虑睡觉。";
                    }
                    int bed = intOf(f, "bed");
                    if (bed > 0) {
                        return "天黑了：带着床，可以考虑找个安全的地方睡觉。";
                    }
                    if (bed == 0) {
                        return "天黑了且没带床：点火把/回安全点撑过夜晚，别在露天硬熬。";
                    }
                    return "天黑了：考虑睡觉或找安全点（床未知，先看一眼背包）。";
                });
    }

    /**
     * 苦力怕：范围内有苦力怕即触发；点燃或贴脸（<=4 格）升 P0。
     * 「脱离」= 采样不到苦力怕 → 键缺失 → 不触发（恢复解除）。
     */
    private static Alarm creeper() {
        return new Alarm("creeper", 1,
                f -> {
                    int d = intOf(f, "creeper");
                    if (d < 0) {
                        return Prio.P2;
                    }
                    boolean lit = "true".equals(f.get("ignited"));
                    return (lit || d <= CREEPER_CLOSE) ? Prio.P0 : Prio.P1;
                },
                f -> intOf(f, "creeper") >= 0,
                f -> "creeper=" + intOf(f, "creeper") + " ignited=" + f.get("ignited"),
                f -> {
                    int d = intOf(f, "creeper");
                    boolean lit = "true".equals(f.get("ignited"));
                    if (lit) {
                        return "附近有苦力怕已点燃（约 " + d + " 格）：立刻拉开距离或找掩体，别靠近！";
                    }
                    if (d <= CREEPER_CLOSE) {
                        return "苦力怕贴脸（约 " + d + " 格）：别再靠近，先拉开距离或处理它。";
                    }
                    return "附近有苦力怕（约 " + d + " 格）：保持距离，别把它引到基地/同伴身边。";
                });
    }

    /**
     * 读一个整型事实。缺失 / 非数字 → <b>-1</b>（未知哨兵，不写 0 —— DL-4）。
     * 注意与真实值 0 的区别由调用方语义决定（food_items=-1 才是未知）。
     */
    private static int intOf(CarrierChain.Facts f, String key) {
        String v = f.get(key);
        if (v == null || v.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
