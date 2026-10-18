package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 经验<b>质量门</b>：学习者产的草稿能不能进经验库（{@code 59} 号 §6.2 的六条）。
 *
 * <p><b>三态结论</b>，不是两态：</p>
 * <ul>
 *   <li>{@link Verdict#PASS} —— 规定的必需检查<b>都做完了</b>且都成立</li>
 *   <li>{@link Verdict#REVIEW_REQUIRED} —— 没有硬失败，但<b>必需的语义核验还没做完</b>
 *       （Codex 审核 P1-2：六条里有两条本批判不了，硬说 PASS 就是「假绿」）</li>
 *   <li>{@link Verdict#REJECT} —— 有硬失败，<b>不许发布</b></li>
 * </ul>
 *
 * <p><b>为什么需要 REVIEW_REQUIRED</b>：§6.2 六条里，「多次独立复现」要跨 memo 聚合、
 * 「正文与原始证据不能矛盾」要人/LLM 对照原文 —— 本批都判不了。
 * 只报 PASS 会让一份「看着是门禁、实际只查了四条」的东西显得完整；
 * 只报 REJECT 会让所有含新类型经验的判定都发不出去。
 * ⇒ 缺核验就明说缺核验。</p>
 *
 * <p><b>入参是七字段 + 备忘录，不是扁平草稿</b>：把七字段压进 {@code ExperienceEntry}
 * 会丢结构（{@code preconditions} 没有对应槽位），再从扁平 JSON 反解就是<b>反解出来的假字段</b>。</p>
 */
public final class ExperienceQualityGate {

    private ExperienceQualityGate() {}

    /** 不可执行空话：<b>整句就是这些</b>才算（Codex 审核 P2-6：前缀匹配会误伤
     *  「注意安全：立即停止挖掘并…」这种正常句子）。 */
    private static final String[] PURE_EMPTY_ADVICE = {
            "应该注意安全", "要注意安全", "小心点", "小心一些", "注意安全", "应该小心",
            "要小心", "注意一点", "小心使用", "注意使用"};

    /** 正文短到这个程度不可能可执行。 */
    private static final int MIN_BODY_CHARS = 16;

    /** 血量键名。{@code max_hp} / {@code food} 之类<b>不是</b>血量（Codex 审核 P1-5）。 */
    private static final List<String> HP_KEYS = List.of("hp", "health");

    public enum Status { PASS, FAIL, UNDECIDABLE }

    public enum Verdict {
        /** 必需检查都做完了且都成立。 */
        PASS,
        /** 没有硬失败，但必需的语义核验（Q2/Q4）还没做。 */
        REVIEW_REQUIRED,
        /** 有硬失败，不许发布。 */
        REJECT
    }

    /** 一条检查的结果。{@code id} 与 {@code 59} §6.2 的条目一一对应。 */
    public record Check(String id, String name, Status status, String why) {

        public boolean blocking() {
            return status == Status.FAIL;
        }
    }

    public record Result(List<Check> checks) {

        public boolean hasHardFailure() {
            for (Check c : checks) {
                if (c.blocking()) {
                    return true;
                }
            }
            return false;
        }

        /** 顶层的诚实结论。{@code UNDECIDABLE} 不算失败，但会把 PASS 降级成 REVIEW_REQUIRED。 */
        public Verdict verdict() {
            if (hasHardFailure()) {
                return Verdict.REJECT;
            }
            for (Check c : checks) {
                if (c.status() == Status.UNDECIDABLE) {
                    return Verdict.REVIEW_REQUIRED;
                }
            }
            return Verdict.PASS;
        }

        public int undecidableCount() {
            int n = 0;
            for (Check c : checks) {
                if (c.status() == Status.UNDECIDABLE) {
                    n++;
                }
            }
            return n;
        }

        public List<String> failedIds() {
            List<String> out = new ArrayList<>();
            for (Check c : checks) {
                if (c.blocking()) {
                    out.add(c.id());
                }
            }
            return out;
        }

        public List<String> undecidableIds() {
            List<String> out = new ArrayList<>();
            for (Check c : checks) {
                if (c.status() == Status.UNDECIDABLE) {
                    out.add(c.id());
                }
            }
            return out;
        }

        public JsonObject toMap() {
            JsonArray arr = new JsonArray();
            for (Check c : checks) {
                JsonObject one = new JsonObject();
                one.addProperty("id", c.id());
                one.addProperty("name", c.name());
                one.addProperty("status", c.status().name());
                one.addProperty("why", c.why());
                arr.add(one);
            }
            JsonObject o = new JsonObject();
            o.addProperty("verdict", verdict().name());
            o.addProperty("hard_fail", hasHardFailure());
            o.addProperty("undecidable", undecidableCount());
            o.add("failed", strArray(failedIds()));
            o.add("needs_review", strArray(undecidableIds()));
            o.add("checks", arr);
            return o;
        }

        private static JsonArray strArray(List<String> values) {
            JsonArray a = new JsonArray();
            for (String v : values) {
                a.add(v);
            }
            return a;
        }
    }

    /**
     * 跑一遍质量门。
     *
     * @param seven 学习者的七字段（{@code null} 直接判 REJECT，不许当空经验）
     * @param memo  出处备忘录；{@code null} 时 Q1 必 FAIL（说不出当时发生了什么）
     * @param body  映射后的正文（{@code ExperienceDraft} 的 {@code description}），
     *              用来判「可执行」。{@code null} 时取 {@code seven.mechanism()}。
     */
    public static Result evaluate(Experience seven, Memo memo, String body) {
        List<Check> checks = new ArrayList<>();

        if (seven == null) {
            checks.add(new Check("Q0", "七字段结构化经验存在", Status.FAIL,
                    "判定里没有结构化的 experience（七字段），只有一段散文或什么都没有"));
            return new Result(List.copyOf(checks));
        }
        if (!seven.acceptable()) {
            checks.add(new Check("Q0", "七字段齐全且关键字段非空", Status.FAIL,
                    "七字段不合格：" + seven.unacceptableReason()));
        } else {
            checks.add(new Check("Q0", "七字段齐全且关键字段非空", Status.PASS,
                    "七字段全填，失效条件与可观察信号都在"));
        }

        // ① 至少一次完整事件链 —— 可判定到「雏形」：问题 + 试过什么 + 环境快照三段齐，
        //    且快照**能读出东西**（Codex 审核 P2-9：snapshot="garbage" 也非空，
        //    但那不构成任何现场事实）。
        boolean chain = memo != null
                && !memo.problem().isBlank() && !memo.tried().isBlank()
                && memo.hasSnapshot() && snapshotReadable(memo.snapshot());
        checks.add(new Check("Q1", "至少一次完整事件链（雏形）", chain ? Status.PASS : Status.FAIL,
                chain ? "备忘录带了 问题/已尝试/环境快照 三段，且快照能读出键值或血量"
                        : "备忘录缺 问题/已尝试/环境快照 之一，或快照读不出任何事实，说不出「当时到底发生了什么」"));

        // ② 多次独立复现 —— 需要跨 memo 聚合，本批没有。
        checks.add(new Check("Q2", "或多次独立复现", Status.UNDECIDABLE,
                "本批没有跨 memo 聚合计数，无法判定；请人工或后续批次核"));

        // ③ 一次高价值失败 / 死亡 —— 只有快照里**血量键**明确为 0 才算证据。
        Double hp = memo == null ? null : hpFromSnapshot(memo.snapshot());
        boolean death = hp != null && hp == 0.0;
        checks.add(new Check("Q3", "或一次高价值失败/死亡",
                death ? Status.PASS : Status.UNDECIDABLE,
                death ? "快照里血量键 = 0，是一次明确的死亡现场"
                        : hp == null ? "快照里读不到血量键；既不算证据，也不能因此说它没发生"
                                     : "快照里血量 = " + hp + "，不是死亡现场"));

        // ④ 正文与原始证据不能互相矛盾 —— 要人/LLM 对照原文，本批判不了。
        checks.add(new Check("Q4", "正文与原始证据不矛盾", Status.UNDECIDABLE,
                "需要把草稿正文与 memo 原文对照，代码判不了（不许用「看起来不矛盾」冒充 PASS）"));

        // ⑤ 必须写清适用条件和不适用条件 —— 可判定「非空」，判不了「写清没写清」
        //    （Codex 审核 P2-9：占位文字也非空）。所以措辞是「在场」不是「清楚」。
        boolean cond = !seven.preconditions().isBlank() && !seven.failureConditions().isBlank();
        checks.add(new Check("Q5", "适用条件与不适用条件都在场", cond ? Status.PASS : Status.FAIL,
                cond ? "前置条件与失效条件都非空（注意：非空 ≠ 写得清楚，那要人看）"
                        : "缺 " + (seven.preconditions().isBlank() ? "前置条件" : "")
                          + (seven.failureConditions().isBlank() ? "失效条件" : "")
                          + "（缺一个就不知道自己什么时候会害人）"));

        // ⑥ 不能只写「应该注意安全」这种不可执行句子 —— 可判定「不是纯空话」，
        //    判不了「真的可执行」（Codex 审核 P2-9）。措辞按这个来。
        String text = body == null || body.isBlank() ? seven.mechanism() : body;
        String trimmed = text == null ? "" : text.trim();
        boolean empty = trimmed.isEmpty();
        boolean tooShort = trimmed.length() < MIN_BODY_CHARS;
        boolean pure = isPureEmptyAdvice(trimmed);
        boolean vacuous = empty || tooShort || pure;
        checks.add(new Check("Q6", "正文不是一句空话", vacuous ? Status.FAIL : Status.PASS,
                empty ? "正文是空的"
                        : tooShort ? "正文只有 " + trimmed.length() + " 字，写不成可执行步骤"
                        : pure ? "正文整句就是「" + trimmed + "」这类不可执行的提醒"
                               : "正文 " + trimmed.length() + " 字，不是纯空话（是否真能执行要人看）"));

        return new Result(List.copyOf(checks));
    }

    /**
     * 快照里能不能读出<b>任何</b>事实。
     *
     * <p>只判「有没有」，不判「读出来对不对」—— 后者是各条检查自己的事。
     * 键值写法与 {@code N/M} 血量写法都认；别的（{@code "garbage"}）不认。</p>
     */
    static boolean snapshotReadable(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return false;
        }
        if (!kv(snapshot).isEmpty()) {
            return true;
        }
        return FRACTION.matcher(snapshot).find();
    }

    /**
     * 从快照里取血量；取不到返回 {@code null}。
     *
     * <p><b>为什么不能只用一条正则</b>（Codex 审核 P1-5 给的反例）：
     * {@code "hp=20/20, food=0/20"} 里 {@code food} 会被当血量、
     * {@code "max_hp=0"} 的键名不含边界、{@code "hp=1.0/20"} 的小数尾巴会被误匹配。
     * ⇒ 先按 {@code , ;} 切出 key=value（键只留字母数字下划线），
     * <b>键名必须正好是 {@code hp} 或 {@code health}</b>，值再整体解析。
     * 负数、残缺（{@code 0/}）、非数字一律当「读不到」。</p>
     */
    static Double hpFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        boolean sawHpKey = false;
        for (Map.Entry<String, String> e : kv(snapshot).entrySet()) {
            if (!HP_KEYS.contains(e.getKey())) {
                continue;
            }
            sawHpKey = true;
            Double v = parseHpValue(e.getValue());
            if (v != null) {
                return v;
            }
        }
        if (sawHpKey) {
            // ★ 有血量键但读不出合法数值（负数 / 分母 0 / 残缺 / 非数字）
            //   ⇒ 就是「读不到」。绝不能再掉到下面的裸 N/M 分支去 ——
            //   那会让 "hp=0/0" 变成血量 0 = 死亡现场（Codex 审核 P1-5 的反例）。
            return null;
        }
        // 整个快照里根本没有血量键时，才认「裸 N/M」写法（快照只记了一个数的情况）
        var m = FRACTION.matcher(snapshot);
        if (m.matches()) {
            try {
                double hp = Double.parseDouble(m.group(1));
                double max = Double.parseDouble(m.group(2));
                return (max <= 0 || hp < 0) ? null : hp;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** {@code 12/20} 形式。 */
    private static final java.util.regex.Pattern FRACTION =
            java.util.regex.Pattern.compile("(\\d{1,4}(?:\\.\\d+)?)\\s*/\\s*(\\d{1,4}(?:\\.\\d+)?)");

    /**
     * 键值对：{@code hp=6/20} / {@code "hp": 6} / {@code hp:6}，按 {@code , ;} 切。
     *
     * <p>与 {@link Memo#parseKeyValues} 同一套切法（不按空格切，否则
     * {@code "hp": 6} 会碎成两段），但这里是本类自己的一份 ——
     * {@code Memo} 那份是 private，且它服务于携带器分级、不该被死亡判据复用。</p>
     */
    private static Map<String, String> kv(String snapshot) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String rawPart : snapshot.split("[,;]")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            int sep = -1;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '=' || c == ':') {
                    sep = i;
                    break;
                }
            }
            if (sep <= 0) {
                continue;
            }
            String k = part.substring(0, sep).replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
            String v = part.substring(sep + 1).replaceAll("[^A-Za-z0-9_./-]", "").toLowerCase(Locale.ROOT);
            if (!k.isBlank() && !out.containsKey(k)) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** 值 → 血量。支持 {@code 6} 与 {@code 6/20}；负数/残缺/非数字 → {@code null}。 */
    private static Double parseHpValue(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        var m = FRACTION.matcher(v.trim());
        if (m.matches()) {
            try {
                double hp = Double.parseDouble(m.group(1));
                double max = Double.parseDouble(m.group(2));
                if (max <= 0 || hp < 0) {
                    return null;      // 分母 0 或负值 = 读不出来，不是「血量 0」
                }
                return hp;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        try {
            double hp = Double.parseDouble(v.trim());
            return hp < 0 ? null : hp;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 整句就是空话提醒才算（前缀匹配会误伤「注意安全：立即停止挖掘并…」）。 */
    private static boolean isPureEmptyAdvice(String trimmed) {
        for (String p : PURE_EMPTY_ADVICE) {
            if (trimmed.equals(p)) {
                return true;
            }
        }
        return false;
    }
}