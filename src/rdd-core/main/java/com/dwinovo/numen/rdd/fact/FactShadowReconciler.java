package com.dwinovo.numen.rdd.fact;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共同事实的 <b>shadow 对账</b>（第三批 N1，2026-10-05）。
 *
 * <p><b>为什么先做 shadow 而不是直接修</b>：共同事实（{@link CompletedFactStore}）
 * 声称「这些阶段完成了」，而使用账本（learner 的 {@code usage-ledger.jsonl}）记录
 * 「这些产物真的被用了」。两边<b>本来就该有出入</b>，但出入的形态决定问题在哪：
 * <ul>
 *   <li>事实说有、用账说没被用 ⇒ 可能<b>真的做完了但没人消费</b>（学习闭环缺口），也可能只是账本没记；</li>
 *   <li>事实说没完成、用账说用过了 ⇒ <b>事实没被更新</b>，下一轮会重复做；</li>
 *   <li>两边都没有 ⇒ 那就是真没做，<b>不是问题</b>，不该报出来充数。</li>
 * </ul>
 *
 * <p><b>★ 只读，绝不改任何一侧。</b> 这是 shadow 的定义：先量出差异形态，再决定改哪边。
 * 现在就自动「修正」事实，等于用一份还没验证的猜测去覆盖生产状态 ——
 * 那正是 AGENTS 里记的「源码改了 ≠ 生效」那类错误在数据上的版本。
 *
 * <p><b>依赖策略</b>：两侧都只按<b>文件</b>读（事实 {@code rdd-facts/<uuid>.json}、
 * 账本 {@code usage-ledger.jsonl}），不 import 任何插件。
 * 因为 {@code numen-plugin.gradle} 刻意封死跨插件 import，
 * 而账本归 learner、事实归 rdd —— 谁都不是谁的编译期依赖，只能走文件。
 */
public final class FactShadowReconciler {

    /**
     * 差异的严重程度。<b>枚举声明顺序即优先级</b>（越靠前越可疑，排序直接用 {@link #ordinal()}）。
     *
     * <p>刻意不给常量带构造参数：枚举里加字段会让「常量 vs 静态字段」的解析变脆，
     * 而且这里只需要顺序，不需要额外数据。
     */
    public enum Severity {
        /**
         * 同一个产物/键上出现自相矛盾的记录：事实说完成但账本说失败，
         * 或账本里取消之后又被记成成功。<b>这类是本报告唯一敢报的「真问题」</b>，
         * 因为它只依赖单侧内部的矛盾，不依赖两侧 key 是否真的对应。
         */
        SELF_CONTRADICTION
    }

    /**
     * 一条差异。
     *
     * @param key     关联键（事实侧用阶段键；用账侧用 artifact_id）
     * @param detail  人能读的说明，<b>不带结论</b>（只陈述两边各自说了什么）
     */
    public record Divergence(Severity severity, String side, String key, String detail) {
    }

    /** 一次对账的完整结果。 */
    public record Report(boolean factsReadable, boolean usageReadable,
                         int factKeys, int usageKeys, String factsShape,
                         List<Divergence> divergences) {

        /**
         * 事实文件<b>读到了但一条都没解析出来</b>。
         *
         * <p>★ 这是修复前那个 bug 的核心：真实文件可读、但被解析成空表，
         * 而旧判断只看「可不可读」⇒ 报告说「一致」。现在必须显式区分：
         * <b>可读 ≠ 解析成功 ≠ 一致</b>，三者要分开报。
         */
        /**
         * 事实文件<b>读到了但一条都没解析出来</b>。
         *
         * <p>★ 这是修复前那个 bug 的核心：真实文件可读、但被解析成空表，
         * 而旧判断只看「可不可读」⇒ 报告说「一致」。现在必须显式区分：
         * <b>可读 ≠ 解析成功 ≠ 一致</b>，三者要分开报。
         *
         * <p><b>三种情况要分清</b>：
         * <ul>
         *   <li>文件不存在（{@code ABSENT}）⇒ 事实侧还没启用，<b>不是故障</b>；</li>
         *   <li>读到了但解析不出内容 ⇒ <b>故障</b>（形状不认识或文件坏了）；</li>
         *   <li>读到且解析出内容 ⇒ 正常。</li>
         * </ul>
         */
        public boolean factsParsed() {
            return "ABSENT".equals(factsShape) || factKeys > 0;
        }

        /** 事实侧是否处于「应当有内容却读不出」的故障态。 */
        public boolean factsBroken() {
            return factsReadable && !"ABSENT".equals(factsShape) && factKeys == 0;
        }

        /**
         * ★ 有没有<b>可比的事实基线</b>。
         *
         * <p>事实侧没有记录时（{@code ABSENT}，即这个同伴还没跑过 rdd 目标），
         * 本次<b>没有可比的对象</b> —— 那不是「一致」，是「没得比」。
         *
         * <p>这条就是 {@code UNKNOWN ≠ ZERO} 那条红线在对账这里的落点：
         * 没有基线时把 {@code clean} 报成 {@code true}，等于把「不知道」说成「没问题」。
         */
        public boolean comparable() {
            return factKeys > 0;
        }

        /**
         * 是否「干净」。
         *
         * <p>★ <b>没有基线时返回 false</b>：不是「有问题」，而是「没得比」——
         * 具体区分看 {@link #comparable()} 与 {@link #factsShape()}。
         */
        public boolean clean() {
            return comparable() && divergences.isEmpty();
        }

        /**
         * 两侧都「可用」才可信。
         *
         * <p><b>注意与 {@link #factsParsed()} 的区别</b>：事实侧<b>不存在</b>时
         * （{@code ABSENT}，即还没跑过目标、事实库是空的）判为可信 ——
         * 那不是故障。若把它判成不可信，一个全新存档会永远报「不可信」，
         * 久了就没人看这个信号了（狼来了）。
         */
        public boolean trustworthy() {
            return usageReadable && !factsBroken();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trustworthy", trustworthy());
            m.put("facts_readable", factsReadable);
            m.put("facts_shape", factsShape);
            m.put("facts_parsed", factsParsed());
            m.put("facts_broken", factsBroken());
            m.put("comparable", comparable());
            m.put("usage_readable", usageReadable);
            if (!comparable()) {
                // ★ 没有基线 ≠ 一致。必须显式说，否则 clean=true 会被读成「核对通过」
                String reason = "ABSENT".equals(factsShape)
                        ? "这个同伴还没有任何共同事实记录（多半没跑过 rdd 目标）⇒ **没得比**，不是「没问题」"
                        : "事实侧读得到但一条都没解析出来（形状=" + factsShape + "）⇒ 这次是故障，没得比";
                String prev = String.valueOf(m.get("warning") == null ? "" : m.get("warning"));
                m.put("warning", prev + (prev.isEmpty() ? "" : "；") + "★ " + reason);
            }
            if (!trustworthy()) {
                StringBuilder why = new StringBuilder("★ 本报告不可当结论用");
                if (factsBroken()) {
                    why.append("：事实文件读得到但**一条都没解析出来**（形状=")
                            .append(factsShape).append("），这是故障，不是「没有问题」");
                }
                if (!usageReadable) {
                    why.append("；使用账本读不到");
                }
                m.put("warning", why.toString());
            }
            m.put("fact_keys", factKeys);
            m.put("usage_keys", usageKeys);
            m.put("clean", clean());
            List<Map<String, Object>> ds = new ArrayList<>();
            for (Divergence d : divergences) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("severity", d.severity().name());
                one.put("side", d.side());
                one.put("key", d.key());
                one.put("detail", d.detail());
                ds.add(one);
            }
            m.put("divergences", ds);
            m.put("note", "★ shadow 只读：这里只量差异，**不自动改任何一侧**");
            return m;
        }
    }

    /**
     * 事实侧复合键的分隔符，<b>必须与 {@code CompletedFactStore.COMPOSITE_SEP} 一致</b>。
     *
     * <p>那是个 {@code U+0001} 字符（不打印）。这里写死同一常量而不是去反射读，
     * 是因为：反射读私有常量在模块化/重命名下会<b>静默拿到 null</b>，
     * 而键分隔符错了只会让计数对不上——更难查。
     */
    private static final String FACT_SEP = "\u0001";

    private FactShadowReconciler() {
    }

    /**
     * 对账。
     *
     * <p><b>⚠️ 键空间说明（本类最重要的一段）</b>：
     * <ul>
     *   <li><b>事实侧</b>：{@code CompletedFactStore} 的键是
     *       {@code lineageId + SEP + stageKey}（见该类 {@code fromJson}），
     *       来源是<b>目标分解</b>；</li>
     *   <li><b>使用侧</b>：{@code artifact_id}，形状
     *       {@code <kind>-<companion>-<memoId>-<hash>}。</li>
     * </ul>
     * 两者<b>本来不同源</b>，所以本报告<b>只报单侧内部的矛盾</b>
     * （同键「完成 vs 失败」、账本里「取消后被翻成成功」），
     * <b>不</b>把「key 相同」当成因果证据。
     *
     * <p><b>2026-10-05 更新</b>：B1 已经把「判定 ↔ 产物 ↔ 执行」串起来
     * （{@code Verdict.usageOutcome} 按 memoId 反查填入）。于是
     * <b>「这条判定产出的东西跑成没成」现在可查了</b> ——
     * 那属于 learner 侧的自查（见 {@code LearnerReviewTool} 的 usage_outcome），
     * 不在本类的职责内。本类继续只做「事实库 vs 账本」两侧的体量与矛盾对照。
     *
     * @param factsFile 共同事实文件（{@code rdd-facts/<uuid>.json}）
     * @param usageLedger 使用账本（{@code usage-ledger.jsonl}）；可为 null（当未接）
     */
    public static Report reconcile(Path factsFile, Path usageLedger) {
        FactsRead fr = readFacts(factsFile);
        boolean factsReadable = factsFile != null && Files.isReadable(factsFile);
        Map<String, String> usageOutcomes = readUsage(usageLedger);
        boolean usageReadable = usageLedger == null || Files.isReadable(usageLedger);

        List<Divergence> ds = new ArrayList<>();
        // 事实侧内部：有完成记录但同时被账本记成失败/取消的（同一个键，两侧同时有记录）
        for (Map.Entry<String, String> e : fr.keys.entrySet()) {
            String usageSide = usageOutcomes.get(e.getKey());
            if (usageSide == null) {
                continue;
            }
            if ("COMPLETED".equals(e.getValue()) && "FAIL".equals(usageSide)) {
                ds.add(new Divergence(Severity.SELF_CONTRADICTION, "facts", e.getKey(),
                        "同一个键：事实侧记为已完成，使用账本记为失败"));
            }
        }
        // 使用侧内部：取消之后又被记成成功 —— 结论不该被迟到回执翻转
        for (Map.Entry<String, EntryView> e : usageEntries(usageLedger).entrySet()) {
            EntryView v = e.getValue();
            if (v.sawCanceled && "SUCCESS".equals(v.outcome)) {
                ds.add(new Divergence(Severity.SELF_CONTRADICTION, "usage", e.getKey(),
                        "账本里出现过 CANCELED，之后又记了 SUCCESS —— 结论不应被迟到回执翻转"));
            }
        }
        ds.sort((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()));
        return new Report(factsReadable, usageReadable, fr.keys.size(), usageOutcomes.size(),
                fr.shape, List.copyOf(ds));
    }

    /** 读事实的结果：键表 + 形状（用于区分「没读到」与「读到了但解析不出」）。 */
    private record FactsRead(Map<String, String> keys, String shape) {
    }

    /** 一个产物在账本里的汇总视图（取最新阶段，并记住是否出现过取消）。 */
    private record EntryView(String outcome, boolean sawCanceled) {
    }

    /**
     * 读事实侧的「完成 / 未完成」键值。
     *
     * <p><b>刻意宽松</b>：字段名认不准就<b>都</b>试一遍，但<b>猜不到时返回空</b>，
     * 并由 {@link Report#trustworthy()} 标出「读到了但没解析出内容」——
     * 宁可显式说「没解析出来」，也不把空表当成「事实侧没有完成项」。
     */
    /**
     * 读事实侧的「完成键」。
     *
     * <p>★★ 这一段修复前<b>读不懂真实数据</b>：{@code CompletedFactStore.toJson()}（见该类 114–140 行）
     * 写出的是 <b>stages 数组</b>，元素字段为
     * {@code lineageId / goalId / objective / stageKey / rawStage / at / evidence}，
     * <b>根本没有 status 字段</b>。而旧实现按「stages 是对象映射 + 读 status」解析，
     * 于是真实文件被解析成<b>空表</b>，而 {@code trustworthy()} 只看「文件可不可读」，
     * 文件可读 ⇒ 报告被标成<b>可信的「一致」</b>。
     * ⇒ 一句话：<b>旧实现对真实数据永远报「没有问题」，而实际是「一条都没读进来」</b>。
     *
     * <p>现在的规则：
     * <ul>
     *   <li>兼容两种形状：{@code stages} 是<b>数组</b>（真实形状）或对象映射（历史/测试形状）；</li>
     *   <li>键统一用 <b>stageKey</b>（真实数据里就是它），对象映射形状则用它的键；</li>
     *   <li>状态一律视为 {@code COMPLETED} —— 因为「进这个文件」本身就意味着该阶段被记为完成
     *       （{@code CompletedFactStore} 只在完成时写入）；没有 status 就<b>不猜</b>，
     *       而是按事实语义记 COMPLETED，并在 {@link Report#factsShape} 里说明用的是哪种形状。</li>
     * </ul>
     */
    private static FactsRead readFacts(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new FactsRead(out, "ABSENT");
        }
        String shape = "UNPARSEABLE";
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (e.isJsonObject()) {
                JsonElement stages = e.getAsJsonObject().get("stages");
                if (stages != null && stages.isJsonArray()) {
                    // ★ 键必须按生产语义拼：CompletedFactStore 用的是
                    //   lineageId + COMPOSITE_SEP(\u0001) + stageKey。
                    //   只取 stageKey 会让**不同 lineage 的同名阶段互相覆盖** ——
                    //   那是又一处「读得出来但读错了」，比读不出来更危险。
                    shape = "ARRAY(lineage+stageKey)";
                    for (JsonElement el : stages.getAsJsonArray()) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject so = el.getAsJsonObject();
                        String lineage = str(so, "lineageId");
                        String stageKey = str(so, "stageKey");
                        if (stageKey.isBlank()) {
                            continue;
                        }
                        out.put(lineage.isBlank() ? stageKey : lineage + FACT_SEP + stageKey,
                                "COMPLETED");
                    }
                } else if (stages != null && stages.isJsonObject()) {
                    shape = "OBJECT(status)";
                    for (Map.Entry<String, JsonElement> en : stages.getAsJsonObject().entrySet()) {
                        String st = en.getValue().isJsonObject()
                                ? str(en.getValue().getAsJsonObject(), "status") : "";
                        out.put(en.getKey(), st.isBlank() ? "COMPLETED" : st);
                    }
                } else {
                    shape = "NO_STAGES_KEY";
                }
            }
        } catch (IOException | RuntimeException ex) {
            shape = "IO_OR_PARSE_ERROR";
        }
        return new FactsRead(out, shape);
    }

    /** 账本按产物汇总，并记住「是否出现过取消」。 */
    private static Map<String, EntryView> usageEntries(Path file) {
        Map<String, EntryView> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    continue;
                }
                String id = str(o, "artifact_id");
                String outcome = str(o, "outcome");
                if (id.isBlank() || outcome.isBlank() || "UNKNOWN".equals(outcome)) {
                    continue;
                }
                EntryView prev = out.get(id);
                boolean sawCanceled = (prev != null && prev.sawCanceled())
                        || "CANCELED".equals(outcome);
                // 最新写入覆盖（同 key 后写即最新）
                out.put(id, new EntryView(outcome, sawCanceled));
            }
        } catch (IOException ex) {
            return out;
        }
        return out;
    }

    /** 读使用账本侧的「SUCCESS / FAIL / CANCELED」，取每个产物最新阶段那条。 */
    private static Map<String, String> readUsage(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    continue;
                }
                String id = str(o, "artifact_id");
                String outcome = str(o, "outcome");
                if (id.isBlank() || outcome.isBlank() || "UNKNOWN".equals(outcome)) {
                    continue;
                }
                // append-only，同 key 后写覆盖前写 = 取最新
                out.put(id, outcome);
            }
        } catch (IOException ex) {
            return out;
        }
        return out;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}