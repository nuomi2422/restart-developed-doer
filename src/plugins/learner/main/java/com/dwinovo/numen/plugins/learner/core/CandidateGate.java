package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「这条事件该不该进候选队列」的<b>入队判定门</b>（{@code 59} 号 §4.1 的 T1–T7）。
 *
 * <p><b>为什么要有这个类（E1 那一环）</b>：{@code 60} 号 §3.2 原话是
 * 「{@code instrumentation.jsonl} 有事件，但『要不要记经验』这个判定<b>不存在</b>」。
 * 过去这件事只有两个可能的决定者：干活 AI 顺手写一条备忘录，或者什么都不写 ——
 * 于是「AI 恰好注意到」被当成了「该不该学」的判据。
 * 本类把这个判定<b>从提示词里搬进代码</b>，让「值不值得提炼」有可测的读数。
 *
 * <p><b>三态，不是两态</b>（与 {@link ExperienceQualityGate} 同一个纪律）：
 * <ul>
 *   <li>{@link Status#MATCH} —— 判据成立，且是<b>程序判得了</b>的那一类</li>
 *   <li>{@link Status#NO_MATCH} —— 判据不成立（例如「没有实际浪费量」，见 §4.2）</li>
 *   <li>{@link Status#UNDECIDABLE} —— <b>程序判不了</b>，需要人/AI 的判断。
 *       T1/T4/T5/T6/T7 全落在这里：一个只报了 MATCH 的门会把「我没查」显示成「查过了」，
 *       那是本工程最贵的一种错（假绿）。</li>
 * </ul>
 *
 * <p><b>为什么只有 T2/T3 能程序判定</b>：T1（成功且用了新路线/提效）需要「成功」与「路线是否新」
 * 两个都不存在于 {@code instrumentation.jsonl} 的量；T4（计划修正且修正后改善）需要修正前后的对比；
 * T5（执行者主动标记）与 T7（用户说重要）本身就是<b>人或 AI 的标记</b>，程序没有；
 * T6（规划者发现冲突）发生在规划者侧。⇒ 报 UNDECIDABLE 并写明「要谁来判」。
 *
 * <p><b>纯 JVM</b>：不 import 任何 NUMEN/MC 类，只吃字符串 + 已压平的观测 map，可独立单测。
 * <b>无状态</b>：T3 的「见过几次」由调用方以 {@code seenBefore} 传进来 ——
 * 本类不保存任何东西，跨调用去重是调用方的责任（免得单测里互相污染）。
 */
public final class CandidateGate {

    private CandidateGate() {
    }

    /** 入队判据的结论。语义与 {@link ExperienceQualityGate.Status} 一一对应。 */
    public enum Status { MATCH, NO_MATCH, UNDECIDABLE }

    /** T1–T7 的编号与短名（{@code 59} §4.1 表格顺序）。 */
    public record Trigger(String id, String name, Status status, String why) {
    }

    /**
     * 判定结论。
     *
     * @param enqueue       是否建议进候选队列（= 至少一条 MATCH）
     * @param primaryTrigger 命中的第一条 MATCH（按 T 编号序）；无则 null
     * @param triggers      全部七条的逐条结论，<b>包括 UNDECIDABLE 的</b>（不藏）
     * @param reason        一句话结论，给调用方直接看
     */
    public record Decision(boolean enqueue, String primaryTrigger, List<Trigger> triggers, String reason) {

        public Decision {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
        }

        /** 面板/日志用读数。刻意把 {@code undecidable} 与 {@code match} 分开列，别让人以为七条都查过。 */
        public JsonObject explain() {
            JsonArray arr = new JsonArray();
            int match = 0;
            int undecidable = 0;
            for (Trigger t : triggers) {
                JsonObject one = new JsonObject();
                one.addProperty("id", t.id());
                one.addProperty("name", t.name());
                one.addProperty("status", t.status().name());
                one.addProperty("why", t.why());
                arr.add(one);
                if (t.status() == Status.MATCH) {
                    match++;
                } else if (t.status() == Status.UNDECIDABLE) {
                    undecidable++;
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("enqueue", enqueue);
            o.addProperty("primary_trigger", primaryTrigger);
            o.addProperty("match", match);
            o.addProperty("undecidable", undecidable);
            o.addProperty("reason", reason);
            o.add("triggers", arr);
            o.addProperty("undecidable_means", "these five (T1/T4/T5/T6/T7) cannot be judged from events; "
                    + "only the working AI (learner_note) or the user can mark them");
            return o;
        }
    }

    private static final String T1 = "T1";
    private static final String T2 = "T2";
    private static final String T3 = "T3";
    private static final String T4 = "T4";
    private static final String T5 = "T5";
    private static final String T6 = "T6";
    private static final String T7 = "T7";

    /**
     * 判定「资源浪费真的发生了」时认的观测键<b>末段名</b>。
     *
     * <p>生产里这个数是 {@code RddSurplus} 写在 {@code context.gatheredBeyondMinimum}，
     * 经 {@code FeedbackChannel.flatten} 压平后键名是<b>带点路径的 camelCase</b>
     * （{@code context.gatheredBeyondMinimum}），不是 {@code gathered_beyond_minimum}。
     * 所以这里按<b>末段</b>匹配且<b>大小写无关</b>，两种写法都认。
     */
    private static final String WASTED_KEY = "gatheredbeyondminimum";

    /**
     * 判一条事件。
     *
     * @param sourceType  源事件的原始 {@code type}（不是 kind 家族 —— 「饿死」和「被打死」归因方向相反，
     *                   压成同一个 kind 就没法写经验了）
     * @param observation 源事件 {@code data} 已压平的一层（点路径 → 标量）
     * @param seenBefore  同一 {@link #fingerprint} 在此之前出现过几次；0 = 第一次见
     */
    public static Decision evaluate(String sourceType, Map<String, Object> observation, int seenBefore) {
        String type = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> obs = observation == null ? Map.of() : observation;

        List<Trigger> triggers = new ArrayList<>(7);
        Status t2 = decideT2(type, obs);
        triggers.add(undecidable(T1, "任务成功且用了新路线或明显提高效率",
                "instrumentation.jsonl 里没有「任务成功」事件，也没有「路线是否新」的量；"
                        + "要判它需要任务链的成功记录 + 路线对比（未做）"));
        triggers.add(new Trigger(T2, "任务失败/死亡/严重受伤/资源损失",
                t2 == null ? Status.UNDECIDABLE : t2,
                t2 == null ? unknownTypeWhy(type) : T2_WHY.getOrDefault(type, "T2 成立（失败类事件）")));
        triggers.add(new Trigger(T3, "同一问题重复出现",
                seenBefore >= 1 ? Status.MATCH : Status.NO_MATCH,
                seenBefore >= 1
                        ? "同一指纹已出现 " + (seenBefore + 1) + " 次（59 §4.1 T3：一次是意外，两次就是模式）"
                        : "这是同一指纹第 1 次出现，按 T3 还只是意外"));
        triggers.add(undecidable(T4, "AI 发生计划修正且修正后结果明显改善",
                "需要修正前后两次结果对比；事件流里只有修正发生，没有「有没有变好」"));
        triggers.add(undecidable(T5, "执行者主动标记「这段对以后有用」",
                "这个标记本身就来自 AI：用 learner_note 写一条就是 T5，不需要代码判定"));
        triggers.add(undecidable(T6, "规划者发现现有经验与当前场景冲突",
                "发生在规划者侧，入队判定这里拿不到它的结论"));
        triggers.add(undecidable(T7, "用户明确说这个很重要",
                "只有用户能说；代码没有这个信号"));

        String primary = null;
        for (Trigger t : triggers) {
            if (t.status() == Status.MATCH) {
                primary = t.id();
                break;
            }
        }
        boolean enqueue = primary != null;
        String reason = enqueue
                ? "命中 " + primary + "（" + nameOf(triggers, primary) + "）"
                : (t2 == null && type.isEmpty()
                        ? "没有源事件类型，无从判定"
                        : "没有程序判得了的触发成立；T1/T4/T5/T6/T7 需人/AI 判定");
        return new Decision(enqueue, primary, triggers, reason);
    }

    /** T2 的分类型判定；返回 {@code null} 表示「认不出来的类型」，交 UNDECIDABLE。 */
    private static Status decideT2(String type, Map<String, Object> obs) {
        switch (type) {
            // 「被玩家打死」与「被怪打死」归因方向相反，RddPlugin:684-696 就是为此拆开两个 type 的
            case "death":
            case "starvation_death":
            case "loop_detected":
            case "repeat_gather":
            case "recovery_failed":
            case "asset_mismatch":
                return Status.MATCH;
            case "resource_waste":
                // 59 §4.2：不为琐碎单步入队。「 surplus gather stopped because minimum already met」
                // 可能一个物品都没浪费 —— 那不是「资源损失」，不许靠事件名就入队。
                Double wasted = positiveNumberEndingWith(obs, WASTED_KEY);
                return wasted == null ? Status.NO_MATCH : Status.MATCH;
            case "instrumentation_change":
                // 「检测器自己被改了」不是同伴的教训，入队只会污染队列
                return Status.NO_MATCH;
            default:
                return null;
        }
    }

    private static final Map<String, String> T2_WHY = Map.of(
            "death", "同伴死亡（59 §4.1 T2 反例，价值最高）",
            "starvation_death", "饿死（与被打死归因方向不同，单列一类）",
            "loop_detected", "同一子任务超出重试预算 = 这次没成",
            "repeat_gather", "采集步骤到达重试上限 = 这次没成",
            "recovery_failed", "死亡后恢复动作卡住 = 这次没成",
            "asset_mismatch", "声明的资产与背包实际不符 = 这次没成",
            "resource_waste", "确实有超出最低需求的采集被丢弃（资源损失）");

    private static Trigger undecidable(String id, String name, String why) {
        return new Trigger(id, name, Status.UNDECIDABLE, why);
    }

    private static String unknownTypeWhy(String type) {
        return "不认识的事件类型 \"" + type + "\"，不猜它算不算教训（"
                + "宁可 UNDECIDABLE，也不要编一个看起来对的家族）";
    }

    private static String nameOf(List<Trigger> triggers, String id) {
        for (Trigger t : triggers) {
            if (t.id().equals(id)) {
                return t.name();
            }
        }
        return "";
    }

    /**
     * T3 的去重指纹：<b>同类事件 + 同一个归因</b>。
     *
     * <p>刻意只用少数字段：指纹越宽，「同一问题」的判定越松，一次意外就会被当成模式。
     * 取不到任何归因字段时<b>不</b>退回「同类型就算同一个」—— 那会让所有死亡塌成一类。
     *
     * <p><b>查键必须大小写无关 + 认点路径</b>（2026-10-03 实测踩到）：
     * {@code FeedbackChannel.flatten} 产出的键是<b>源事件自己的 camelCase 点路径</b>
     * （{@code deathCauseId}、{@code context.deathAttacker}），
     * 按小写扁平名去查<b>永远读不到</b> ⇒ 指纹会全部塌成裸的 {@code "death"}，
     * 于是「被怪打死两次」与「两次不同的死」被判成同一个问题。
     * <b>本类里两处按名取值（这里与 {@link #positiveNumberEndingWith}）都走同一套后缀匹配。</b>
     */
    public static String fingerprint(String sourceType, Map<String, Object> observation) {
        String type = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> obs = observation == null ? Map.of() : observation;
        StringBuilder sb = new StringBuilder(type);
        for (String key : new String[]{"task", "deathcauseid", "deathattacker", "deathkind", "reason"}) {
            String v = lower(valueEndingWith(obs, key));
            if (v != null && !v.isBlank()) {
                sb.append('|').append(key).append('=').append(v);
            }
        }
        return sb.toString();
    }

    /**
     * 找键<b>末段</b>等于 {@code suffixLower}（大小写无关）的那个值。
     *
     * <p>点路径只比<b>末段</b>是刻意的：{@code deathCauseId} 与
     * {@code context.deathCauseId} 都是「死亡原因」，多一层前缀不该让指纹变成另一个问题。
     */
    private static Object valueEndingWith(Map<String, Object> obs, String suffixLower) {
        for (Map.Entry<String, Object> e : obs.entrySet()) {
            String k = lower(e.getKey());
            if (k != null && (k.equals(suffixLower) || k.endsWith("." + suffixLower))) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 找出键<b>末段</b>等于 {@code suffixLower}（大小写无关）的那个数；非正数 / 读不出 → null。 */
    private static Double positiveNumberEndingWith(Map<String, Object> obs, String suffixLower) {
        for (Map.Entry<String, Object> e : obs.entrySet()) {
            String k = lower(e.getKey());
            if (k == null || !(k.equals(suffixLower) || k.endsWith("." + suffixLower))) {
                continue;
            }
            Double d = positiveNumber(e.getValue());
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static Double positiveNumber(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(String.valueOf(raw).trim());
            // 0 不算损失；负数是数据异常，也不当损失算（宁可 NO_MATCH 让人来看）
            return d > 0.0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lower(Object v) {
        return v == null ? null : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }

    /** 面板/诊断用：把一批判定汇总成一张表，便于确认「七条都出现了」。 */
    public static Map<String, Object> summaryOf(List<Decision> decisions) {
        Map<String, String> firstWhy = new LinkedHashMap<>();
        int match = 0;
        int enqueue = 0;
        for (Decision d : decisions) {
            if (d.enqueue()) {
                enqueue++;
            }
            for (Trigger t : d.triggers()) {
                if (t.status() == Status.MATCH) {
                    match++;
                    firstWhy.putIfAbsent(t.id(), t.why());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisions", decisions.size());
        out.put("enqueue_recommended", enqueue);
        out.put("trigger_matches", match);
        out.put("trigger_first_why", firstWhy);
        return out;
    }
}