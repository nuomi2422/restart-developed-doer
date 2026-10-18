package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4「七字段经验」的契约测试。
 *
 * <p><b>为什么这一批非做不可（实机证据）</b>：2026-10-01 实机跑 {@code learner_review}，
 * LLM 判定质量<b>是对的</b>（4 条 {@code NO_ACTION} 判得对，唯一该沉淀的那条抓到了真 bug、0.95），
 * 但它给的 {@code experience_draft} <b>是一段散文，七个字段一个都没结构化</b>。
 * 根因：{@code 45} §2 的七字段<b>从来只是文档描述</b>，代码里是一个 {@code String}。
 *
 * <p>所以本类把「格式」钉成代码：模型必须交出 7 个具名字段，
 * 拿不出来就<b>如实说拿不出来</b>，而不是把一段话说得像有结构。
 */
class ExperienceSevenFieldsTest {

    private static final String GOOD = "{\"mechanism\":\"下界竖井要先探底再往下挖\","
            + "\"preconditions\":\"y<=-12 且垂直通道\","
            + "\"failureConditions\":\"开阔熔岩湖上方；末影人直视范围内\","
            + "\"observableSignal\":\"HUD 血量<6 且无食物 → 停止下潜\","
            + "\"derivation\":\"血量<6 → 上岸 → 找食物 → 血量≥8 再回\","
            + "\"efficiency\":\"一次下潜 12 格 vs 3 格×4 次；时间 -70%\","
            + "\"evidence\":\"ep7 t2000 commentary 原话 + 截图路径\","
            // E4：分类是第 2 批的必填项。这条讲「怎么做」⇒ EXECUTION。
            + "\"experienceType\":\"EXECUTION\"}";

    /** E4：同一条正文，但**故意不给分类**（用来验证「没交就不许当合格」）。 */
    private static final String GOOD_WITHOUT_TYPE = GOOD.replace(",\"experienceType\":\"EXECUTION\"}", "}");

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---------- 解析 ----------

    @Test
    void sevenFieldObjectParses() {
        Experience x = Experience.parse(obj("{\"experience\":" + GOOD + "}"));
        assertNotNull(x);
        assertEquals(7, x.filledCount());
        assertEquals("下界竖井要先探底再往下挖", x.mechanism());
        assertEquals("开阔熔岩湖上方；末影人直视范围内", x.failureConditions());
        assertTrue(x.requiredComplete());
        assertTrue(x.acceptable());
        assertEquals("", x.unacceptableReason());
    }

    @Test
    void legacyProseStringIsNotAcceptedAsExperience() {
        // ⚠️ 这是本轮最关键的一条：老格式 experience_draft 是一段散文。
        // **刻意不把它塞进 mechanism 蒙过去** —— 那样会让「格式已落地」看起来成立，
        // 而实际仍然是一段散文，下游拿到手还是没法按字段检索。
        Experience x = Experience.parse(obj(
                "{\"experience_draft\":\"hp=0 是有效值，不能因为是 0 就当缺失\"}"));
        assertNull(x, "一段散文不是结构化经验，必须是 null（缺失就缺失）");
    }

    @Test
    void missingExperienceKeyGivesNull() {
        assertNull(Experience.parse(obj("{\"reasoning\":\"x\"}")));
        assertNull(Experience.parse(null));
    }

    @Test
    void allEmptyExperienceObjectGivesNull() {
        assertNull(Experience.parse(obj("{\"experience\":{}}")), "全空 = 缺失，不是「一条空经验」");
    }

    @Test
    void nonObjectExperienceGivesNull() {
        assertNull(Experience.parse(obj("{\"experience\":\"一段话\"}")));
        assertNull(Experience.parse(obj("{\"experience\":123}")));
    }

    // ---------- 完整度 ----------

    @Test
    void missingTwoRequiredFieldsIsNotAcceptableAndSaysWhich() {
        Experience x = Experience.parse(obj("{\"experience\":{"
                + "\"mechanism\":\"m\",\"preconditions\":\"p\",\"derivation\":\"d\","
                + "\"efficiency\":\"e\",\"evidence\":\"v\"}}"));
        assertNotNull(x);
        assertEquals(5, x.filledCount());
        assertFalse(x.requiredComplete());
        assertFalse(x.acceptable());
        String why = x.unacceptableReason();
        assertTrue(why.contains("失效条件"), why);
        assertTrue(why.contains("可观察信号"), why);
        assertEquals(List.of("失效条件", "可观察信号"), x.missingFields());
    }

    @Test
    void oneRequiredFieldPresentIsStillNotAcceptable() {
        Experience x = Experience.parse(obj("{\"experience\":{"
                + "\"mechanism\":\"m\",\"preconditions\":\"p\",\"failureConditions\":\"会害人\","
                + "\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"v\"}}"));
        assertFalse(x.acceptable(), "有失效条件但没可观察信号 = 无法在世界里核对");
        assertTrue(x.unacceptableReason().contains("可观察信号"));
    }

    @Test
    void allSevenRequiredForThisBatchAcceptance() {
        // 用户 2026-10-01 定「先不严，第 2 批再卡」→ 本轮只卡「七字段齐全 + 两个关键字段非空」
        String fiveOfSeven = "{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\","
                + "\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"v\"}}";
        assertFalse(Experience.parse(obj(fiveOfSeven)).acceptable());
        assertTrue(Experience.parse(obj("{\"experience\":" + GOOD + "}")).acceptable());
    }

    // ---------- B21：缺失不许伪装成数据 ----------

    @Test
    void toMapOnlyContainsRealValues() {
        Experience x = Experience.parse(obj("{\"experience\":{"
                + "\"mechanism\":\"m\",\"failureConditions\":\"会害人\"}}"));
        Map<String, Object> m = x.toMap();
        assertEquals(2, m.size());
        assertTrue(m.containsKey("mechanism"));
        assertTrue(m.containsKey("failureConditions"));
        assertFalse(m.containsKey("efficiency"), "空的字段不许出现（B21：缺失用「没有这个键」表达）");
        assertFalse(m.containsKey("preconditions"));
    }

    @Test
    void blankStringCountsAsMissingNotAsContent() {
        // " " 是最阴的一种：看起来有内容，其实什么都没有
        Experience x = Experience.parse(obj("{\"experience\":{\"mechanism\":\"   \",\"evidence\":\"v\"}}"));
        assertEquals(1, x.filledCount());
        assertEquals(List.of("机制", "前置条件", "失效条件", "可观察信号", "推导步骤", "效率"),
                x.missingFields().stream().filter(k -> !k.equals("证据")).toList());
    }

    @Test
    void summarySaysUnacceptableInsteadOfPretending() {
        Experience x = Experience.parse(obj("{\"experience\":{\"mechanism\":\"一段话\"}}"));
        String s = x.summary();
        assertTrue(s.startsWith("不合格["), s);
        assertTrue(s.contains("一段话"), "不合格也要保留已有的内容，别全丢：" + s);
    }

    @Test
    void goodExperienceSummaryIsJustTheMechanism() {
        assertEquals("下界竖井要先探底再往下挖",
                Experience.parse(obj("{\"experience\":" + GOOD + "}")).summary());
    }

    // ---------- 与 Verdict 的接线 ----------

    @Test
    void verdictCarriesStructuredExperience() {
        String v = "{\"memo_id\":\"m-1\",\"actions\":[\"WRITE_EXPERIENCE\"],\"confidence\":0.9,"
                + "\"reasoning\":\"r\",\"experience\":" + GOOD + ",\"rewritten_query\":[\"q\"]}";
        Verdict parsed = Verdict.parse("m-1", v);
        assertNotNull(parsed);
        assertNotNull(parsed.experience());
        assertTrue(parsed.experience().acceptable());
        assertTrue(parsed.toJson().contains("experience_acceptable"));
        assertFalse(parsed.toJson().contains("experience_draft"),
                "旧键不许再出现 —— 否则「格式已落地」看起来成立");
    }

    @Test
    void verdictWithProseOnlyHasNullExperienceAndSaysNothingAboutIt() {
        Verdict parsed = Verdict.parse("m-2",
                "{\"memo_id\":\"m-2\",\"actions\":[\"WRITE_EXPERIENCE\"],\"confidence\":0.9,"
                        + "\"reasoning\":\"r\",\"experience_draft\":\"一段散文\"}");
        assertNotNull(parsed);
        assertNull(parsed.experience());
        assertFalse(parsed.toJson().contains("\"experience\""), "缺失就不放这个键（B21）");
        assertFalse(parsed.toJson().contains("experience_acceptable"));
    }

@Test
    void promptSpecDemandsTheTwoCriticalFields() {
        String spec = Experience.promptSpec();
        assertTrue(spec.contains("failureConditions"), spec);
        assertTrue(spec.contains("observableSignal"), spec);
        assertTrue(spec.contains("NO_ACTION"), "填不出关键字段就别硬凑，应当反回 NO_ACTION（这条别删）");
    }

    // ---------- E4：分类 ----------

    @Test
    void promptSpecNowDemandsAClassification() {
        String spec = Experience.promptSpec();
        assertTrue(spec.contains("experienceType"), "第 2 批起分类是必填项，prompt 必须写明");
        for (String t : Experience.EXPERIENCE_TYPES) {
            assertTrue(spec.contains(t), "prompt 里少了可选值 " + t + "，它只能靠猜");
        }
        assertTrue(spec.contains("正好这 8 个键"), "键数说 7 就会让人交 7 个键的旧格式");
    }

    @Test
    void typeIsNormalizedButNeverGuessed() {
        assertEquals("FAILURE", parseWithType("FAILURE").typeName());
        assertEquals("FAILURE", parseWithType("  failure  ").typeName(), "大小写与空白该归一");
        assertEquals("TOOL_DEFECT", parseWithType("tool_defect").typeName());
        // ★ 这些必须判非法：type 决定 stableKey / fingerprint，也就是条目的身份键。
        //   猜错 = 真实的一条被劈成两条，或两条不同的被并成一条。
        assertEquals("", parseWithType("失败经验").typeName(), "中文近义词不是分类");
        assertEquals("", parseWithType("FAILURE_TYPE").typeName());
        assertEquals("", parseWithType("").typeName());
    }

    @Test
    void missingOrInvalidTypeIsReportedNotHidden() {
        Experience noType = parse(GOOD_WITHOUT_TYPE);
        assertFalse(noType.typeComplete());
        assertFalse(noType.acceptable(), "七字段全填但没分类 ⇒ 仍不合格（不然落库时才炸）");
        assertTrue(noType.unacceptableReason().contains("experienceType"),
                "要说清缺的是分类：" + noType.unacceptableReason());

        Experience bad = parseWithType("失败经验");
        assertFalse(bad.acceptable());
        assertTrue(bad.typeProblem().contains("失败经验"),
                "要把它**交的那个值**原样说出来，不能只说「非法」：" + bad.typeProblem());
    }

    @Test
    void validTypeMakesAFullExperienceAcceptable() {
        assertTrue(parse(GOOD).acceptable());
        assertTrue(parseWithType("EXECUTION").typeComplete());
    }

    private static Experience parse(String experienceJson) {
        return Experience.parse(obj("{\"experience\":" + experienceJson + "}"));
    }

    private static Experience parseWithType(String type) {
        return parse(GOOD.replace("\"EXECUTION\"", "\"" + type + "\""));
    }
}
