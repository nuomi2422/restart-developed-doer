package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条<b>结构化</b>的经验（{@code 45} 号 §2 用户定的七字段格式）。
 *
 * <p><b>为什么要有这个 record（2026-10-01 实机抓到的）</b>：
 * 用户 2026-10-01 口述七字段格式时，它<b>只是一段文档描述</b>，
 * 实机证据：{@code learner_review} 真跑了 LLM，判定质量也对
 *（唯一该沉淀的那条抓到了真 bug、置信度 0.95），
 * 但它给出的 {@code experience_draft} <b>是一段散文，七个字段一个都没结构化</b>。
 *
 * <p><b>所以这一条把「格式」从文档变成代码</b>：模型必须交出 7 个具名字段，
 * 拿不出来就是拿不出来（{@link #completeness()} 会如实说），
 * <b>而不是</b>把一段话说得像有结构。
 *
 * <p><b>B21 在这里的含义</b>：缺字段用「<b>字段不出现</b>」表达，
 * <b>禁止</b>用 {@code ""} / {@code "无"} / {@code "未知"} 冒充。
 * {@link #toMap()} 只放真值。
 *
 * <p><b>卡严程度</b>：用户 2026-10-01 定「先不严，第 2 批再卡」。
 * 所以本 record <b>只卡字段齐全 + 两个关键字段非空</b>，
 * <b>不卡「像不像真东西」</b> —— 后者（第 2 批）是「拿到下一批同类场景去对」。
 *
 * <p><b>★ {@code experienceType} 是第 2 批加的（E4），为什么不并进 {@link #FIELDS}</b>：
 * 七字段是用户 2026-10-01 口述定的格式（45 号 §2），<b>它是「一条经验的正文」</b>；
 * 而 {@code experienceType} 是<b>这条正文的分类标签</b>。两者性质不同，混在一起会让
 * 「七字段全填」这个已经被文档钉死的判据悄悄变味。
 * 实测依据（2026-10-02 查 live 实例 {@code learner.jsonl}）：七字段那条链上
 * <b>3/3 都是 {@code filled=7 acceptable=true}</b>，并且 {@code evidence} 字段引的
 * 是真实 memo id 与真实数字（「实测 3447 字符 / 原始 83496」）—— <b>学习者是会判断、
 * 交得出合格货的</b>。所以第 2 批要做的不是替它判，而是<b>把它的判断接住</b>。
 *
 * <p><b>为什么必须有它</b>：B8 的 {@code ExperienceDraft} 刻意不写 {@code type}
 * （七字段里没有「类型」这一项，从 {@code actions} 推断是「把操作建议当成分类事实」，
 * Codex 审核 P1-1）。于是草稿的 {@code entry} 里<b>没有 type</b>，
 * 而 {@code ExperienceStore.learn()} 对 {@code type == null} 直接抛异常
 * ⇒ <b>学习者明明交出了好内容，却一条都落不了库</b>。这就是 E4 要修的断链。
 */
public record Experience(
        String mechanism,
        String preconditions,
        String failureConditions,
        String observableSignal,
        String derivation,
        String efficiency,
        String evidence,
        String experienceType
) {

    private static final Gson GSON = new Gson();

    /** 七字段的规范名（给 prompt 用，也给渲染用）。顺序即 {@code 45} §2 的顺序。 */
    public static final List<String> FIELDS = List.of(
            "mechanism", "preconditions", "failureConditions",
            "observableSignal", "derivation", "efficiency", "evidence");

    /** 中文名，只用于给人看（prompt 与报错）。 */
    private static final Map<String, String> CN = Map.of(
            "mechanism", "机制",
            "preconditions", "前置条件",
            "failureConditions", "失效条件",
            "observableSignal", "可观察信号",
            "derivation", "推导步骤",
            "efficiency", "效率",
            "evidence", "证据");

    /**
     * 分类的合法取值（<b>E4</b>）。
     *
     * <p>⚠️ <b>刻意不写成枚举</b>：{@code plugins/learner} <b>不依赖</b>
     * {@code experience-core}（B8 类注释里那条 JPMS 约束），所以这里只能存字符串。
     * 两者的一致性由 {@code ExperienceTypeNamesBindToRealEnumTest}
     * <b>直接读 experience-core 源文件</b>做集合相等断言 —— 那边改枚举值，这边会红。</p>
     */
    public static final List<String> EXPERIENCE_TYPES = List.of(
            "EXECUTION", "FAILURE", "TOOL_DEFECT", "WORLD_RELATION", "POLICY");

    /** 每个分类的判据（给 prompt 用，也是给「它到底分没分对」的人工核对用）。 */
    private static final Map<String, String> TYPE_RULES = Map.of(
            "EXECUTION", "讲「怎么做」：执行动作、工具用法、步骤顺序",
            "FAILURE", "讲「为什么这次没成」：失败的原因与对策",
            "TOOL_DEFECT", "讲「工具/字段本身有问题」：前提写错、字段失效、能力缺失",
            "WORLD_RELATION", "讲「世界里的关系」：物品、生物、地形彼此怎么互相影响",
            "POLICY", "讲「以后必须怎么做」：给同伴的硬约束、铁律、必须先问主人");

    public Experience {
        mechanism = nz(mechanism);
        preconditions = nz(preconditions);
        failureConditions = nz(failureConditions);
        observableSignal = nz(observableSignal);
        derivation = nz(derivation);
        efficiency = nz(efficiency);
        evidence = nz(evidence);
        experienceType = nz(experienceType);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 取某个字段的规范化值。 */
    public String field(String name) {
        return switch (name) {
            case "mechanism" -> mechanism;
            case "preconditions" -> preconditions;
            case "failureConditions" -> failureConditions;
            case "observableSignal" -> observableSignal;
            case "derivation" -> derivation;
            case "efficiency" -> efficiency;
            case "evidence" -> evidence;
            default -> "";
        };
    }

    /** 七个字段里<b>非空</b>的有几个（0..7）。 */
    public int filledCount() {
        int n = 0;
        for (String f : FIELDS) {
            if (!field(f).isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** 七个字段里<b>缺</b>的有哪些（返回中文名）。 */
    public List<String> missingFields() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String f : FIELDS) {
            if (field(f).isBlank()) {
                out.add(CN.getOrDefault(f, f));
            }
        }
        return out;
    }

    /**
     * 「关键字段齐不齐」。
     *
     * <p><b>为什么只有这两个是关键</b>：{@code 45} §2 自己标了「失效条件（常被漏）」，
     * 而没有 {@code 失效条件} 或 {@code 可观察信号} 的经验，
     * 既不知道自己什么时候会害人，<b>也没法在世界里核对它到底成不成立</b> ——
     * 那正是 V1「判据要落在可查的外部事实上」要排除的东西。
     */
    public boolean requiredComplete() {
        return !failureConditions.isBlank() && !observableSignal.isBlank();
    }

    // ---------- E4：分类（experienceType）----------

    /**
     * 归一后的分类名（<b>空串 = 没交或交的不是合法值</b>）。
     *
     * <p><b>只做大小写与空白归一</b>，<b>不做语义猜测</b>：中文「失败经验」、
     * 近义词「事故」一律判为非法。理由：这个字段直接决定 {@code stableKey(type,title)}
     * 与 {@code fingerprint(type,title)} —— 也就是<b>这条经验的身份键</b>。
     * 猜错分类 = 同一条真实经验被劈成两条，或者两条不同的被并成一条。
     * 宁可判它「没交」（如实报缺失），也不猜。</p>
     */
    public String typeName() {
        if (experienceType == null || experienceType.isBlank()) {
            return "";
        }
        String t = experienceType.trim().toUpperCase(java.util.Locale.ROOT);
        return EXPERIENCE_TYPES.contains(t) ? t : "";
    }

    /** 分类是否<b>已定且合法</b>（决定草稿能不能带 {@code type} 落库）。 */
    public boolean typeComplete() {
        return !typeName().isEmpty();
    }

    /** 如实报分类为什么不合格 —— 空串表示合格。 */
    public String typeProblem() {
        if (typeComplete()) {
            return "";
        }
        if (experienceType == null || experienceType.isBlank()) {
            return "缺 experienceType（五选一：" + String.join("/", EXPERIENCE_TYPES) + "）";
        }
        return "experienceType 交的是「" + experienceType.trim() + "」，"
                + "不在五选一里（" + String.join("/", EXPERIENCE_TYPES) + "）—— "
                + "这是**分类事实**，只能从上面挑一个，不能拿近义词顶";
    }

    /** 第 2 批的合格线：<b>七字段全填 + 两个关键字段非空 + 分类已定</b>（E4 加了分类这一条）。 */
    public boolean acceptable() {
        return filledCount() == FIELDS.size() && requiredComplete() && typeComplete();
    }

    /** 如实报告为什么不合格 —— 空字符串表示合格。 */
    public String unacceptableReason() {
        if (acceptable()) {
            return "";
        }
        List<String> miss = missingFields();
        StringBuilder sb = new StringBuilder();
        if (!miss.isEmpty()) {
            sb.append("缺字段 ").append(String.join("、", miss));
        }
        if (!requiredComplete()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            List<String> key = new java.util.ArrayList<>();
            if (failureConditions.isBlank()) {
                key.add("失效条件");
            }
            if (observableSignal.isBlank()) {
                key.add("可观察信号");
            }
            sb.append("关键字段 ").append(String.join("、", key)).append(" 为空");
        }
        String tp = typeProblem();
        if (!tp.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(tp);
        }
        return sb.toString();
    }

    /** 渲染：<b>只放真值</b>（B21），缺失的字段直接不出现。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : FIELDS) {
            String v = field(f);
            if (!v.isBlank()) {
                m.put(f, v);
            }
        }
        return m;
    }

    /** 给人看的一行摘要（缺什么直接说）。 */
    public String summary() {
        if (acceptable()) {
            return mechanism;
        }
        return "不合格[" + unacceptableReason() + "]: " + (mechanism.isBlank() ? "(无机制)" : mechanism);
    }

    /**
     * 从 LLM 的 JSON 对象解析。
     *
     * <p><b>刻意不接「一段散文」当经验</b>：老格式的 {@code experience_draft} 是字符串，
     * 本方法<b>不会</b>把字符串塞进 {@code mechanism} 蒙过去 ——
     * 那样会让「格式已落地」这件事<b>看起来成立</b>，而实际仍然是一段散文。
     *
     * @return 解析出来的经验；<b>对象不存在或全空时返回 {@code null}</b>（缺失就缺失）
     */
    public static Experience parse(JsonObject verdictObj) {
        if (verdictObj == null || !verdictObj.has("experience") || verdictObj.get("experience").isJsonNull()) {
            return null;
        }
        JsonElement e = verdictObj.get("experience");
        if (!e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        Experience x = new Experience(
                optString(o, "mechanism"),
                optString(o, "preconditions"),
                optString(o, "failureConditions"),
                optString(o, "observableSignal"),
                optString(o, "derivation"),
                optString(o, "efficiency"),
                optString(o, "evidence"),
                optString(o, "experienceType"));
        return x.filledCount() == 0 ? null : x;
    }

    private static String optString(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) {
            return "";
        }
        JsonElement e = o.get(k);
        if (!e.isJsonPrimitive()) {
            return "";
        }
        return e.getAsString();
    }

    /** 给 prompt 用的格式说明（中文，直接可读）。 */
    public static String promptSpec() {
        return "experience 必须是**对象**，且正好这 8 个键：\n"
                + "  {\"mechanism\":\"世界规律是什么\","
                + "\"preconditions\":\"满足哪些条件才能用\","
                + "\"failureConditions\":\"什么情况下这条会害你\","
                + "\"observableSignal\":\"在世界里看得到的判据\","
                + "\"derivation\":\"从信号到动作的因果链\","
                + "\"efficiency\":\"相比朴素做法省多少\","
                + "\"evidence\":\"从哪来的\","
                + "\"experienceType\":\"五选一\"}\n"
                + "硬要求一：failureConditions 与 observableSignal **不许留空**。"
                + "填不出这两项，说明你还没真的理解这件事 → 这种情况应该给 NO_ACTION，"
                + "而不是硬凑一段话。\n"
                + "硬要求二：experienceType **必填**，只能从这 5 个里挑**一个**，不许自造：\n"
                + typeRules()
                + "  experienceType 决定这条经验在库里被归到哪一类，也决定它的身份键 —— "
                + "填错等于把这条经验劈成两条、或把两条并成一条，所以"
                + "**不能拿近义词顶**（「失败经验」不算 FAILURE，「事故」更不算）。"
                + "挑不出来就说明你还没想清楚这是哪一类 → 给 NO_ACTION。\n"
                + "反例（不合格）：\"experience_draft\":\"hp=0 不能当缺失\" 这种一段散文 —— "
                + "它没有结构，不接受。";
    }

    /** 五个分类的判据（prompt 用；也让人能核对它分得对不对）。 */
    public static String typeRules() {
        StringBuilder sb = new StringBuilder();
        for (String t : EXPERIENCE_TYPES) {
            sb.append("    ").append(t).append(" = ").append(TYPE_RULES.get(t)).append('\n');
        }
        return sb.toString();
    }
}