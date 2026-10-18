package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 「上轮产出回顾」—— 让第 N 轮复盘能看见第 N−1 轮写了什么、改了什么、被门禁怎么判。
 *
 * <p><b>为什么需要它</b>（架构 owner 2026-10-03 第 3 条 + 答 (a)）：
 * owner 原话「<b>可以多轮循环他可以改到自己满意为止，而且，他改的时候不是有自己的那些
 * 经验仓库吗他每写一个的话，都会有自己曾经的那些案例</b>」，并明确循环形态是
 * 「<b>AI 自己在下一轮 {@code learner_review} 里看着上轮结果再改</b>」——
 * 不是引擎内部自动循环，也不是人工喊。
 *
 * <p>⇒ 那「上轮结果」必须在下一轮的 prompt 里<b>看得见</b>，否则「看着上轮改」这句无处落地：
 * 学习者每轮都被当成第一次见面，写出来的东西互相不知情，改的其实是不同的东西。
 *
 * <p><b>数据从哪来</b>：<b>不新建文件、不加状态</b>。上一轮的产物已经被
 * {@code LearnerReviewTool} 整份写进 {@code monitor/learner.jsonl} 的
 * {@code reviewed} 事件里（{@code data.verdicts[]}，每条判定都带着它写的产物）。
 * 这里只<b>只读</b>它 —— 与 {@link FeedbackChannel} 只读 {@code rdd.jsonl}、
 * {@code LearnerIntakeTool} 只读 {@code instrumentation.jsonl} 同一纪律。
 * ⇒ 好处：重启不丢（产物在文件里）、不需要额外状态机、观测侧天然能核对。
 *
 * <p><b>★ 诚实边界</b>：这里给出的是「<b>上轮写了什么</b>」，<b>不是</b>「上轮的东西后来成没成」。
 * 后者要等 {@code acx.jsonl} 的 {@code run_finished} 与 {@code experience-*.jsonl} 的
 * {@code verified_count} 才谈得上，而那条链目前<b>还不通</b>。
 * ⇒ 所以本类<b>不叫</b> {@code lastResults}、字段也不叫 {@code succeeded}：
 * 名字一旦许诺了它兑现不了的东西，读的人就会照那个承诺做判断。
 */
public final class PriorRound {

    /** 一件产物：它是什么类型 + 一句人话摘要 + 门禁怎么判的。 */
    public record Product(String kind, String title, String quality, String detail) {
        /** 摘要行（进 prompt 的那一行）。字段一律不许留 null —— prompt 里出现 "null" 是噪音。 */
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append("- [").append(kind).append(']');
            if (title != null && !title.isBlank()) {
                sb.append(' ').append(title);
            }
            if (quality != null && !quality.isBlank()) {
                sb.append("（质量门：").append(quality).append('）');
            }
            if (detail != null && !detail.isBlank()) {
                sb.append(" — ").append(detail);
            }
            return sb.toString();
        }
    }

    /**
     * 上轮回顾的读数 + 文本。
     *
     * @param rounds   本次回顾覆盖了几轮（0 = 没有上轮，这是第一轮，正常情况）
     * @param products 上轮产出的件数（可能多于轮数：一条判定可以同时产出多样）
     * @param lines    进 prompt 的行
     * @param truncated 是否因为长度上限被截断（<b>不许静默截</b>：被截掉的轮次读的人会以为不存在）
     * @param skippedBadLines 坏行数（JSON 解析失败）。如实报，不假装读全了
     */
    public record Summary(int rounds, int products, List<String> lines,
                          boolean truncated, int skippedBadLines) {

        /** 没有上轮时（第一轮）返回的形状：<b>不是空字符串</b>。 */
        public static Summary none() {
            return new Summary(0, 0, List.of(), false, 0);
        }

        public boolean hasPriorRound() {
            return rounds > 0;
        }

        /**
         * 塞进复盘 prompt 的文本。
         *
         * <p>⚠️ <b>第一轮也要给一句明确的话</b>（而不是什么都不加）：
         * 复盘方必须知道「没有上轮」是一条结论，不是这条消息漏了。
         */
        public String promptBlock() {
            if (rounds <= 0) {
                return "【上轮产出】没有上轮记录（这是第一次复盘，或上轮日志已不可读）。\n";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("【上轮产出】以下是上轮（最近 ").append(rounds).append(" 轮）你自己写下的东西。\n");
            sb.append("· 本轮可以直接改它们，也可以判定它们仍然成立。\n");
            sb.append("· <b>没被列出来的产物，等于上轮没写过</b> —— 不要假设自己记得。\n");
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            if (truncated) {
                sb.append("（以上被长度上限截断了，更早的轮次没有列出来。）\n");
            }
            if (skippedBadLines > 0) {
                sb.append("（另有 ").append(skippedBadLines)
                        .append(" 行日志读不出来 —— 「读不到」不等于「当时没写」。）\n");
            }
            sb.append("⚠️ 这里只列了「写了什么」，<b>没有</b>列「后来成没成」：\n");
            sb.append("   产物是否真的好用，要靠本轮从现场证据重新判断，不要凭上一轮的字面承诺。\n");
            return sb.toString();
        }
    }

    /** 每种产物的行文本长度上限：一份 AC 脚本可能有几百行，整份塞进 prompt 会挤掉本轮的备忘录。 */
    public static final int DEFAULT_PRODUCT_CHARS = 160;

    /** 最多回顾几轮。默认 2：owner 说「看着上轮结果再改」，再往前对「改到满意」没有帮助。 */
    public static final int DEFAULT_ROUNDS = 2;

    private PriorRound() {
    }

    /**
     * 从 {@code learner.jsonl} 的若干行里取出上轮产出。
     *
     * @param jsonLines  整个文件（或尾部）的原始行
     * @param maxRounds  最多回顾几轮（{@code <=0} 视为 {@link #DEFAULT_ROUNDS}）
     * @param maxChars   单件产物的文本截断上限（{@code <=0} 视为 {@link #DEFAULT_PRODUCT_CHARS}）
     */
    public static Summary fromLines(List<String> jsonLines, int maxRounds, int maxChars) {
        int roundsWanted = maxRounds <= 0 ? DEFAULT_ROUNDS : maxRounds;
        int cap = maxChars <= 0 ? DEFAULT_PRODUCT_CHARS : maxChars;
        if (jsonLines == null || jsonLines.isEmpty()) {
            return Summary.none();
        }

        // 先找最近的 roundsWanted 条 reviewed 事件（时间升序 ⇒ 从尾部往回取）。
        List<JsonObject> reviewed = new ArrayList<>();
        int bad = 0;
        for (int i = jsonLines.size() - 1; i >= 0 && reviewed.size() < roundsWanted; i--) {
            String raw = jsonLines.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            JsonObject env;
            try {
                JsonElement e = JsonParser.parseString(raw);
                if (!e.isJsonObject()) {
                    bad++;
                    continue;
                }
                env = e.getAsJsonObject();
            } catch (RuntimeException ex) {
                bad++;
                continue;
            }
            if (!"reviewed".equals(str(env, "type"))) {
                continue;
            }
            JsonElement data = env.get("data");
            if (data == null || !data.isJsonObject()) {
                bad++;
                continue;
            }
            reviewed.add(data.getAsJsonObject());
        }
        if (reviewed.isEmpty()) {
            return new Summary(0, 0, List.of(), false, bad);
        }
        // 取出来的顺序是「最近 → 较早」，倒回来让 prompt 里最早的在前面（读起来像时间线）
        java.util.Collections.reverse(reviewed);

        List<String> lines = new ArrayList<>();
        int products = 0;
        for (JsonObject rev : reviewed) {
            String rid = str(rev, "review_id");
            JsonElement vs = rev.get("verdicts");
            if (vs == null || !vs.isJsonArray()) {
                continue;
            }
            for (JsonElement v : vs.getAsJsonArray()) {
                if (!v.isJsonObject()) {
                    continue;
                }
                products += productsOf(v.getAsJsonObject(), cap).size();
                for (Product p : productsOf(v.getAsJsonObject(), cap)) {
                    lines.add((rid.isEmpty() ? "" : rid + " ") + p.line());
                }
            }
        }
        return new Summary(reviewed.size(), products, lines, false, bad);
    }

    /** 一条判定里产出了哪几样东西。顺序固定（经验 → AC → 携带器 → 编译请求），不随 map 序漂。 */
    private static List<Product> productsOf(JsonObject verdict, int cap) {
        List<Product> out = new ArrayList<>();
        JsonObject entry = draftEntry(verdict);
        if (entry != null) {
            out.add(new Product("经验", clip(str(entry, "title"), cap),
                    gateVerdict(verdict), "七字段草稿（发布权在执行 AI 手里）"));
        }
        addIf(out, verdict, "ac_script_draft", "AC 脚本", cap);
        addIf(out, verdict, "carrier_draft", "携带器", cap);
        addIf(out, verdict, "self_compile_request", "编译请求", cap);
        return out;
    }

    private static void addIf(List<Product> out, JsonObject verdict, String key, String label, int cap) {
        String v = str(verdict, key);
        if (!v.isBlank()) {
            out.add(new Product(label, "", gateVerdict(verdict), clip(v, cap)));
        }
    }

    /** 经验草稿的 entry（`experience_draft` 是 `{entry, mapping}` 的两层形状）。 */
    private static JsonObject draftEntry(JsonObject verdict) {
        JsonElement d = verdict.get("experience_draft");
        if (d == null || !d.isJsonObject()) {
            return null;
        }
        JsonElement e = d.getAsJsonObject().get("entry");
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /** 门禁结论；没有 quality_gate 就是「没判」，不许当成「通过了」。 */
    private static String gateVerdict(JsonObject verdict) {
        JsonElement g = verdict.get("quality_gate");
        if (g == null || !g.isJsonObject()) {
            return "未判";
        }
        String v = str(g.getAsJsonObject(), "verdict");
        return v.isEmpty() ? "未判" : v;
    }

    /**
     * 拼「上轮回顾 + 本轮备忘录」这段 user prompt。
     *
     * <p>★ <b>为什么放在 core 而不是 {@code LearnerReviewer}</b>：那条路要走
     * {@code Services.CONFIG} 与 LLM 客户端，<b>测试 classpath 里加载不了</b>
     * （同 {@code ExperienceRecallTool} 的处境：那边连单测都写不了）。
     * 放在这里，纯 JVM、跑得到 —— 而「上轮到底有没有真的进 prompt」正是这条能力
     * 成立与否的唯一判据，<b>必须有测试钉住</b>，不能靠肉眼读代码确认。
     *
     * <p>★ <b>两段的先后是有意的</b>：上轮回顾在<b>本轮备忘录之前</b>。
     * 放后面会被本轮那批细节冲淡，而它恰恰是本轮判断的<b>参照系</b>
     * （「这条跟上轮那条说的是同一件事吗」，只在先看到上轮时才问得出来）。
     *
     * @param prior 上轮回顾；{@code null} 等同于「没有上轮」，<b>不留空</b>
     */
    public static String buildUserPrompt(List<Memo> memos, Summary prior) {
        return buildUserPrompt(memos, prior, null, null);
    }

    /**
     * 同上，但<b>额外把「上轮被拒收的产物 + 原因」摆进 prompt</b>（2026-10-05 实机教训）。
     *
     * <p>{@code rejections} 传 {@code null} 时退化成原行为（老调用方不受影响）。
     *
     * <p><b>位置</b>：放在上轮回顾之后、本轮备忘录之前 —— 它和上轮回顾是同一类东西
     *（「上轮发生了什么」），而本轮备忘录是「现在要判什么」。
     */
    public static String buildUserPrompt(List<Memo> memos, Summary prior,
                                         com.dwinovo.numen.plugins.learner.core.RejectionFeedback rejections,
                                         String usageText) {
        StringBuilder user = new StringBuilder();
        user.append((prior == null ? Summary.none() : prior).promptBlock()).append('\n');
        if (rejections != null) {
            user.append(rejections.promptBlock()).append('\n');
        }
        // ★ 传的是**已按同伴过滤好的文本**，不是账本对象：
        //   过滤责任在调用方（它才知道同伴 id），这里只负责排版。
        //   传对象进来容易被「忘了过滤就全塞进去」，那等于跨同伴污染。
        if (usageText != null && !usageText.isBlank()) {
            user.append(usageText).append('\n');
        }
        List<Memo> batch = memos == null ? List.of() : memos;
        user.append("复盘以下 ").append(batch.size()).append(" 条备忘录，逐条输出判定：\n\n");
        for (Memo m : batch) {
            user.append(m.toPromptBlock()).append('\n');
        }
        return user.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? "" : e.getAsString();
    }
}
