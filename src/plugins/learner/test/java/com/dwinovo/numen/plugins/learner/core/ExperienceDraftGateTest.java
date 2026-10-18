package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B2 / E3：七字段 → 经验库条目形状的映射 + 硬质量门。
 *
 * <p><b>这批的边界</b>：{@code plugins/learner} <b>刻意不依赖</b> {@code experience-core}
 * （两个 mod 各带一份同名类会炸），所以映射只能产出「schema 兼容的 JSON」。
 * 键名绑定由 {@link ExperienceDraftKeysBindToRealEntryTest} 读真源文件核对。</p>
 */
class ExperienceDraftGateTest {

    private static final String GOOD = "{\"mechanism\":\"下界竖井要先探底再往下挖\","
            + "\"preconditions\":\"y<=-12 且垂直通道\","
            + "\"failureConditions\":\"开阔熔岩湖上方；末影人直视范围内\","
            + "\"observableSignal\":\"HUD 血量<6 且无食物 → 停止下潜\","
            + "\"derivation\":\"血量<6 → 上岸 → 找食物 → 血量≥8 再回\","
            + "\"efficiency\":\"一次下潜 12 格 vs 3 格×4 次；时间 -70%\","
            + "\"evidence\":\"ep7 t2000 commentary 原话\","
            // E4：分类是第 2 批必填项（这条讲「怎么做」⇒ EXECUTION）。
            //   少了它，草稿就没有 type 键 ⇒ 进不了 ExperienceStore（learn() 对 type==null 抛）。
            + "\"experienceType\":\"EXECUTION\"}";

    /** E4：同一条正文，**故意不给分类** —— 用来验「没交就不许编 type」这条红线。 */
    private static final String GOOD_NO_TYPE = GOOD.replace(",\"experienceType\":\"EXECUTION\"}", "}");

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * ⚠️ {@link Experience#parse} 吃的是<b>整个判定对象</b>（找 {@code experience} 键），
     * 不是七字段本身。直接 {@code parse(GOOD)} 会返回 null —— 这个坑我踩了一次。
     */
    private static Experience sevenFields() {
        return Experience.parse(obj("{\"experience\":" + GOOD + "}"));
    }

    private static String withMechanism(String mechanism) {
        JsonObject o = obj(GOOD);
        o.addProperty("mechanism", mechanism);
        return o.toString();
    }

    private static Verdict verdict(String experienceJson, String... actions) {
        return verdictRaw(experienceJson, null, actions);
    }

    /**
     * B5：能带 {@code rewritten_query} 的判定。
     *
     * @param rewrittenJson 整个 JSON 数组字面量（含 {@code null} 形式），或 {@code null} = 不给这个键
     */
    private static Verdict verdictWithRewritten(String experienceJson, String rewrittenJson, String... actions) {
        return verdictRaw(experienceJson, rewrittenJson, actions);
    }

    private static Verdict verdictRaw(String experienceJson, String rewrittenJson, String... actions) {
        StringBuilder acts = new StringBuilder("[");
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) {
                acts.append(',');
            }
            acts.append('"').append(actions[i]).append('"');
        }
        acts.append(']');
        String rewritten = rewrittenJson == null ? "" : ",\"rewritten_query\":" + rewrittenJson;
        Verdict v = Verdict.parse("m-1",
                "{\"memo_id\":\"m-1\",\"actions\":" + acts + ",\"confidence\":0.8,"
                        + "\"reasoning\":\"r\"" + rewritten
                        + ",\"experience\":" + experienceJson + "}");
        assertNotNull(v, "判定本身要能解析出来");
        return v;
    }

    private static Memo fullMemo(String snapshot) {
        return new Memo("m-1", "往下挖的时候掉血", "mining", "先直挖 12 格再铺水", snapshot, 1000L);
    }

    private static ExperienceDraft draftOf(Verdict v, Memo m) {
        return ExperienceDraft.from(v, m);
    }

    // ---------- 映射 ----------

    @Test
    void draftUsesOnlyKeysTheExperienceStoreKnows() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20, armor=none"));
        for (String k : d.toJson().keySet()) {
            assertTrue(ExperienceDraft.ENTRY_KEYS.contains(k) || k.equals("title_display"),
                    "键 " + k + " 不在 ExperienceEntry.toJson() 的契约里，写进去也读不出来");
        }
        for (String k : ExperienceDraft.WRITTEN_KEYS) {
            assertTrue(d.toJson().has(k), "映射承诺会写 " + k + "，实际没写");
        }
    }

    /**
     * ★ Codex P1-1 的红线，<b>E4 之后原样保留</b>：<b>没交就不许写 type</b>。
     *
     * <p>变的是 type 的<b>来源</b>（从 {@code actions} 推断 → 学习者自己交），
     * 没变的是<b>不许编</b>：{@code actions} 含 {@code USE_CARRIER} 也<b>证明不了</b>
     * 分类是 POLICY —— 那还是「把操作建议当成分类事实」。</p>
     */
    @Test
    void draftDoesNotInventATypeWhenLearnerDidNotGiveOne() {
        ExperienceDraft d = draftOf(verdict(GOOD_NO_TYPE, "WRITE_EXPERIENCE", "USE_CARRIER"),
                fullMemo("hp=12/20"));
        assertFalse(d.toJson().has("type"),
                "学习者没交 experienceType ⇒ entry.type 不许出现（哪怕 actions 里有 USE_CARRIER）");
        assertEquals("POLICY", d.suggestedType(), "只能作为建议给出");
        assertTrue(d.explain().get("needs_classification").getAsBoolean());
        assertFalse(d.explain().get("entry_ready_for_store").getAsBoolean(),
                "没有 type 的草稿进不了库，必须如实说");
        assertTrue(d.explain().get("type_resolution").getAsString().contains("没有 type"));
        assertTrue(d.explain().get("suggested_type_why").getAsString().contains("只是建议"));
    }

    /** ★ E4：学习者交了合法分类 ⇒ 草稿就带 type，真的能落库了（这是断链被接上的那一环）。 */
    @Test
    void draftCarriesTheTypeTheLearnerGave() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertTrue(d.toJson().has("type"), "交了就要写 —— 否则 learn() 会因 type==null 抛异常");
        assertEquals("EXECUTION", d.toJson().get("type").getAsString());
        assertFalse(d.explain().get("needs_classification").getAsBoolean());
        assertTrue(d.explain().get("entry_ready_for_store").getAsBoolean());
        assertTrue(d.explain().get("type_resolution").getAsString().contains("学习者自己交"));
    }

    /** E4：交的是近义词/中文 ⇒ 判为非法，**不许猜一个**（type 决定身份键，猜错会并/劈条目）。 */
    @Test
    void draftRefusesANearSynonymAsType() {
        String near = GOOD.replace("\"experienceType\":\"EXECUTION\"", "\"experienceType\":\"失败经验\"");
        ExperienceDraft d = draftOf(verdict(near, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertFalse(d.toJson().has("type"), "「失败经验」不是 FAILURE ⇒ 不许当成 type 落库");
        assertTrue(d.explain().get("type_resolution").getAsString().contains("失败经验"));
    }

    @Test
    void draftIsAcceptedByTheExperienceStoreSchema() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertFalse(d.toJson().get("description").getAsString().isBlank(), "description 必填，不能空");
        assertEquals("下界竖井要先探底再往下挖", d.toJson().get("title").getAsString());
        assertEquals("OBSERVED", d.toJson().get("maturity").getAsString(),
                "一次成功未验证 = OBSERVED（doc 59 D5：允许进目录但必须标待验证）");
    }

    @Test
    void draftDoesNotInventToolNames() {
        // tool_names 映射不出来 —— 就不放这个键（B21：缺失不许用「无」冒充）
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertFalse(d.toJson().has("tool_names"), "映射不出来就不该出现这个键");
    }

    @Test
    void draftKeepsEvidenceSomewhereEvenThoughEntryHasNoSlot() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertTrue(d.toJson().get("rationale").getAsString().contains("ep7 t2000"),
                "evidence 在 ExperienceEntry 里没有槽位，但绝不能丢 —— 并进 rationale");
        assertEquals("ep7 t2000 commentary 原话", d.evidenceNote());
        assertEquals("ep7 t2000 commentary 原话",
                d.explain().get("experience_evidence").getAsString());
    }

    @Test
    void priorityMapsConfidenceAndStaysInRange() {
        assertEquals(1, ExperienceDraft.priorityFrom(0.0));
        assertEquals(100, ExperienceDraft.priorityFrom(1.0));
        assertEquals(80, ExperienceDraft.priorityFrom(0.8));
        assertEquals(1, ExperienceDraft.priorityFrom(-3));
        assertEquals(100, ExperienceDraft.priorityFrom(7));
    }

    /** ★ Codex P1-4：截断 title 会让两条长经验撞成一条。落盘的 title 必须是完整的。 */
    @Test
    void longTitleIsKeptWholeAndOnlyTheDisplayCopyIsClipped() {
        String longMech = "机制".repeat(60);
        ExperienceDraft d = draftOf(verdict(withMechanism(longMech), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertEquals(longMech, d.toJson().get("title").getAsString(),
                "★ title 是 id 的一部分，落盘时绝不能截断");
        assertEquals(ExperienceDraft.DISPLAY_TITLE_CHARS + 1,
                d.toJson().get("title_display").getAsString().length(),
                "展示版才截断");
        assertEquals(ExperienceDraft.DISPLAY_TITLE_CHARS,
                d.explain().get("title_display_truncated_to").getAsInt());
    }

    /** 两条只有后半段不同的长机制，落盘后不能撞成同一条。 */
    @Test
    void twoLongTitlesWithSamePrefixStayDistinct() {
        String head = "机制".repeat(40);
        ExperienceDraft a = draftOf(verdict(withMechanism(head + "甲"), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        ExperienceDraft b = draftOf(verdict(withMechanism(head + "乙"), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertNotEquals(a.toJson().get("title").getAsString(), b.toJson().get("title").getAsString());
        assertEquals(a.titleDisplay(), b.titleDisplay(), "展示版撞上是预期的，落盘版不能撞");
    }

    /** ★ Codex P1-3：前置条件不许被拼成因果链的下一步。 */
    @Test
    void applicabilityConditionsAreLabelledNotChained() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        String rec = d.toJson().get("recommended_response").getAsString();
        assertTrue(rec.contains("适用条件：y<=-12"), rec);
        assertTrue(rec.contains("禁用条件：开阔熔岩湖上方"), rec);
        assertFalse(rec.contains(" → y<=-12"), "前置条件不是因果链的一环：" + rec);
    }

    @Test
    void draftRequiresStructuredExperience() {
        Verdict noExp = Verdict.parse("m-1",
                "{\"memo_id\":\"m-1\",\"actions\":[\"NO_ACTION\"],\"confidence\":0.5,\"reasoning\":\"r\"}");
        assertNotNull(noExp);
        assertThrows(IllegalArgumentException.class, () -> ExperienceDraft.from(noExp, fullMemo("hp=1")));
    }

    @Test
    void draftSurvivesMissingMemo() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), null);
        assertNotNull(d.toJson().get("title"));
        assertFalse(d.toJson().get("tags").getAsJsonArray().toString().contains("memo:"),
                "没有 memo 就不该编一个 memo: 标签");
    }

    // ---------- B5：rewritten_query 接进检索（此前是僵尸字段） ----------

    /** 把 trigger_strings 读成 List<String>，读不出来就让测试炸（别拿空跑当过）。 */
    private static List<String> triggersOf(ExperienceDraft d) {
        List<String> out = new ArrayList<>();
        d.toJson().getAsJsonArray("trigger_strings").forEach(e -> out.add(e.getAsString()));
        return out;
    }

    /**
     * ★ B5 本体：{@code rewritten_query} 必须落进 {@code trigger_strings}。
     *
     * <p>断言的是<b>槽位</b>而不是「JSON 里出现过这个词」—— 检索器
     * （{@code LexicalExperienceRetriever:87}）只读 {@code triggerStrings}，
     * 落在别处的字段对它等于不存在。</p>
     */
    @Test
    void rewrittenQueryLandsInTheSlotTheRetrieverActuallyReads() {
        ExperienceDraft d = draftOf(
                verdictWithRewritten(GOOD, "[\"垂直竖井下潜\",\"铺水回撤\",\"血量低于六格\"]", "WRITE_EXPERIENCE"),
                fullMemo("hp=12/20, armor=none"));
        List<String> ts = triggersOf(d);
        assertTrue(ts.contains("垂直竖井下潜"), ts.toString());
        assertTrue(ts.contains("铺水回撤"), ts.toString());
        assertTrue(ts.contains("血量低于六格"), ts.toString());
        assertEquals(3, d.explain().get("rewritten_query_into_triggers").getAsInt(),
                "面板要能如实看到接进去了几条");
    }

    /** 检索词是 LLM 自由文本，会写成整句 ⇒ 截到契约上限，并说清上限是多少。 */
    @Test
    void aSentenceShapedQueryIsClippedToTheTriggerContract() {
        String sentence = "在".repeat(200);
        ExperienceDraft d = draftOf(
                verdictWithRewritten(GOOD, "[\"" + sentence + "\"]", "WRITE_EXPERIENCE"),
                fullMemo("hp=12/20"));
        List<String> ts = triggersOf(d);
        for (String t : ts) {
            assertTrue(t.length() <= ExperienceDraft.TRIGGER_CLIP_CHARS + 1,
                    "trigger_strings 契约是短线索，这条有 " + t.length() + " 字：" + t);
        }
        assertEquals(ExperienceDraft.TRIGGER_CLIP_CHARS,
                d.explain().get("trigger_clip_chars").getAsInt());
        assertEquals(1, d.explain().get("rewritten_query_into_triggers").getAsInt());
    }

    /** 没给检索词就报 0，不许编一条出来充数。 */
    @Test
    void noRewrittenQueryReportsZeroRatherThanInventingOne() {
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        assertEquals(0, d.explain().get("rewritten_query_into_triggers").getAsInt(),
                "学习者没交检索词 ⇒ 如实报 0，面板上能一眼看出「这轮没给」");
    }

    /**
     * ★ 反证：补检索线索<b>不许</b>动身份。
     *
     * <p>{@code ExperienceEntry.stableKey} / {@code fingerprint} 是 {@code (type, title)}
     * 的纯函数（已读源确认），{@code trigger_strings} 不参与 ⇒ 加检索词不该让任何老条目
     * 换 id、重新去重、并条目。这条红了就说明有人把检索键接到了标题或类型上。</p>
     */
    @Test
    void addingRetrievalKeysDoesNotChangeEntryIdentity() {
        ExperienceDraft without = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        ExperienceDraft with = draftOf(
                verdictWithRewritten(GOOD, "[\"垂直竖井下潜\",\"铺水回撤\"]", "WRITE_EXPERIENCE"),
                fullMemo("hp=12/20"));
        assertEquals(without.toJson().get("title").getAsString(), with.toJson().get("title").getAsString());
        assertEquals(without.toJson().get("type").getAsString(), with.toJson().get("type").getAsString(),
                "stableKey 只吃 (type, title)：这两个一样 ⇒ 同一条经验，不会被重新去重");
        assertNotEquals(triggersOf(without), triggersOf(with),
                "前提是线索真的变了，否则这条断言没有区分力");
    }

    /**
     * 计数诚实性：检索词与 preconditions 撞车时<b>只算一次新增</b>，并把丢掉的那次报出来。
     *
     * <p>GOOD 的 preconditions 就是「y&lt;=-12 且垂直通道」，这里拿它当检索词。</p>
     */
    @Test
    void aQueryDuplicatingAPreconditionIsNotDoubleCounted() {
        ExperienceDraft d = draftOf(
                verdictWithRewritten(GOOD, "[\"y<=-12 且垂直通道\",\"铺水回撤\"]", "WRITE_EXPERIENCE"),
                fullMemo("hp=12/20"));
        assertEquals(1, d.explain().get("rewritten_query_into_triggers").getAsInt(),
                "撞上 preconditions 的那条不算新增");
        assertEquals(1, d.explain().get("rewritten_query_duplicates").getAsInt(),
                "丢掉的那次必须报出来，否则「接了 1 条」会读成「只给了 1 条」");
        assertEquals(1, triggersOf(d).stream().filter("y<=-12 且垂直通道"::equals).count(),
                "同一条线索不许在 trigger_strings 里出现两次");
    }

    /**
     * ⚠️ 这条守卫<b>只能靠绕过 {@code Verdict.parse} 才测得到</b>。
     *
     * <p>{@code Verdict.parse}（Verdict.java:129-132）已经滤掉 null/blank，
     * 所以经它产出的判定里不会有空串。{@code Verdict} 是 record，
     * 直接 new 就能塞 null 进来 —— 那正是这条守卫当下防的东西：
     * 空线索落进 {@code trigger_strings} 后，检索器里
     * {@code containsAny(field, "")} 恒为真，等于给每个查询白送 +3.0。</p>
     */
    @Test
    void aDirectlyBuiltVerdictWithNullQueryNeverInjectsAnEmptyTrigger() {
        Verdict v = new Verdict("m-1", List.of(Verdict.Action.WRITE_EXPERIENCE), 0.8, "r",
                sevenFields(), "", "", "", List.of(), java.util.Arrays.asList(null, "   ", "铺水回撤"));
        ExperienceDraft d = ExperienceDraft.from(v, fullMemo("hp=12/20"));
        for (String t : triggersOf(d)) {
            assertFalse(t.isBlank(), "trigger_strings 里不许有空线索：" + triggersOf(d));
        }
        assertEquals(1, d.explain().get("rewritten_query_into_triggers").getAsInt());
        assertEquals(0, d.explain().get("rewritten_query_duplicates").getAsInt(),
                "null 与空白是「没给」，不是「重复」");
    }

    // ---------- 质量门 ----------

    @Test
    void gatePassesAFullHonestExperience() {
        Experience seven = sevenFields();
        ExperienceDraft d = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20, armor=none"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20, armor=none"), d.toJson().get("description").getAsString());
        assertFalse(r.hasHardFailure(), "不该有硬失败：" + r.failedIds() + " " + r.undecidableIds());
        // Q2/Q4 判不了 ⇒ 顶层不能报 PASS（Codex P1-2：那会变成假绿）
        assertEquals(ExperienceQualityGate.Verdict.REVIEW_REQUIRED, r.verdict());
        assertTrue(r.undecidableIds().contains("Q2"), r.undecidableIds().toString());
        assertTrue(r.undecidableIds().contains("Q4"), r.undecidableIds().toString());
    }

    /** 本批的关键诚实性：判不了的必须说判不了，不能报 PASS。 */
    @Test
    void gateNeverReportsPassWhileSomethingIsUndecidable() {
        Experience seven = sevenFields();
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20"), seven.mechanism() + seven.derivation());
        assertEquals(ExperienceQualityGate.Verdict.REVIEW_REQUIRED, r.verdict());
        assertTrue(r.undecidableCount() >= 2);
    }

    @Test
    void gateRejectsWhenSevenFieldsAreIncomplete() {
        Experience seven = Experience.parse(obj(
                "{\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\",\"derivation\":\"d\","
                        + "\"efficiency\":\"e\",\"evidence\":\"v\"}}"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20"), seven.mechanism());
        assertTrue(r.hasHardFailure());
        assertEquals(ExperienceQualityGate.Verdict.REJECT, r.verdict());
        assertTrue(r.failedIds().contains("Q0"), r.failedIds().toString());
        assertTrue(r.failedIds().contains("Q5"), "缺失效条件 ⇒ 不知道什么时候会害人");
    }

    @Test
    void gateRejectsEmptyAdviceSentence() {
        // doc 59 §6.2 最后一条：「不能只写『应该注意安全』这种不可执行句子」
        String advice = "{\"mechanism\":\"应该注意安全\",\"preconditions\":\"p\","
                + "\"failureConditions\":\"f\",\"observableSignal\":\"s\","
                + "\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"v\"}";
        Experience seven = Experience.parse(obj("{\"experience\":" + advice + "}"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20, armor=none"), seven.mechanism());
        assertTrue(r.failedIds().contains("Q6"), r.failedIds().toString());
    }

    /** ★ Codex P2-6：前缀匹配会误伤「注意安全：立即停止挖掘并…」这种正常句子。 */
    @Test
    void gateAcceptsAdviceLikeSentenceThatIsActuallyActionable() {
        String advice = "{\"mechanism\":\"注意安全：血量低于六格就立刻停止挖掘并先铺水再回撤\","
                + "\"preconditions\":\"y<=-12\",\"failureConditions\":\"开阔熔岩湖上方\","
                + "\"observableSignal\":\"血量<6\",\"derivation\":\"停止→铺水→回升\","
                + "\"efficiency\":\"省一次返工\",\"evidence\":\"ep7\"}";
        Experience seven = Experience.parse(obj("{\"experience\":" + advice + "}"));
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20, armor=none"), seven.mechanism());
        assertFalse(r.failedIds().contains("Q6"),
                "以「注意安全」开头但后面就是动作的句子不该被当成空话：" + r.failedIds());
    }

    @Test
    void gateRejectsWhenEventChainIsIncomplete() {
        Experience seven = sevenFields();
        // 没试过什么、也没环境快照 ⇒ 说不出「当时到底发生了什么」
        Memo thin = new Memo("m-1", "往下挖掉血", "mining", "", "", 1000L);
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, thin, seven.mechanism() + seven.derivation());
        assertTrue(r.failedIds().contains("Q1"), r.failedIds().toString());
    }

    /** ★ Codex P2-9：snapshot="garbage" 非空，但不构成任何现场事实。 */
    @Test
    void gateRejectsUnreadableSnapshot() {
        Experience seven = sevenFields();
        Memo junk = new Memo("m-1", "往下挖掉血", "mining", "先直挖", "garbage", 1000L);
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(
                seven, junk, seven.mechanism() + seven.derivation());
        assertTrue(r.failedIds().contains("Q1"),
                "快照读不出任何键值或血量 ⇒ 说不出当时发生了什么：" + r.failedIds());
    }

    @Test
    void gateRejectsMissingExperienceEntirely() {
        ExperienceQualityGate.Result r = ExperienceQualityGate.evaluate(null, fullMemo("hp=1"), null);
        assertEquals(ExperienceQualityGate.Verdict.REJECT, r.verdict());
        assertEquals("Q0", r.checks().get(0).id());
    }

    // ---------- 死亡判据（Codex P1-5 给了 6 个反例，逐个钉住） ----------

    @Test
    void hpIsBoundToItsOwnKeyNotToAnyNumberThatLooksLikeZero() {
        assertEquals(6.0, ExperienceQualityGate.hpFromSnapshot("hp=6/20"));
        assertEquals(6.0, ExperienceQualityGate.hpFromSnapshot("health=6"));
        // food / max_hp 都不是血量
        assertEquals(20.0, ExperienceQualityGate.hpFromSnapshot("hp=20/20, food=0/20"),
                "food=0/20 不能被当血量");
        assertNull(ExperienceQualityGate.hpFromSnapshot("max_hp=0"), "max_hp 不是当前血量");
        // 小数尾巴不能被误匹配成分数
        assertEquals(1.0, ExperienceQualityGate.hpFromSnapshot("hp=1.0/20"));
        // 负值 / 残缺 / 分母 0 / 非数字 一律读不到
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=-1"));
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=0/"), "分母残缺 = 读不到，不是血量 0");
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=0/0"), "分母 0 = 读不到");
        assertNull(ExperienceQualityGate.hpFromSnapshot("hp=abc"));
        assertNull(ExperienceQualityGate.hpFromSnapshot(""));
        assertNull(ExperienceQualityGate.hpFromSnapshot(null));
    }

    @Test
    void hpZeroIsTheOnlyDeathEvidence() {
        assertEquals(0.0, ExperienceQualityGate.hpFromSnapshot("hp=0/20"));
        assertEquals(0.0, ExperienceQualityGate.hpFromSnapshot("\"hp\": 0"));
        assertEquals(0.0, ExperienceQualityGate.hpFromSnapshot("0/20"), "裸 N/M 写法也认");
        // 濒死不等于死亡
        assertEquals(6.0, ExperienceQualityGate.hpFromSnapshot("hp=6/20"));
    }

    /** 死亡现场存在时 Q3 从 UNDECIDABLE 变 PASS。 */
    @Test
    void deathEvidenceUpgradesQ3() {
        Experience seven = sevenFields();
        ExperienceQualityGate.Result dead = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=0/20, armor=none"), seven.mechanism() + seven.derivation());
        ExperienceQualityGate.Result alive = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=9/20, armor=none"), seven.mechanism() + seven.derivation());
        assertEquals(ExperienceQualityGate.Status.PASS, statusOf(dead, "Q3"));
        assertEquals(ExperienceQualityGate.Status.UNDECIDABLE, statusOf(alive, "Q3"),
                "活着不是死亡证据，但这也不能说「没发生死亡」，所以是 UNDECIDABLE 不是 FAIL");
    }

    private static ExperienceQualityGate.Status statusOf(ExperienceQualityGate.Result r, String id) {
        for (ExperienceQualityGate.Check c : r.checks()) {
            if (c.id().equals(id)) {
                return c.status();
            }
        }
        return fail("门禁里没有这条检查：" + id + "，检查项集合变了");
    }

    // ---------- 输出形状 ----------

    @Test
    void gateMapIsSelfDescribing() {
        Experience seven = sevenFields();
        JsonObject m = ExperienceQualityGate.evaluate(
                seven, fullMemo("hp=12/20"), seven.mechanism() + seven.derivation()).toMap();
        assertTrue(m.has("verdict"));
        assertTrue(m.has("hard_fail"));
        assertTrue(m.has("undecidable"));
        assertTrue(m.has("needs_review"), "判不了的项必须能被机器读到，不能只埋在 checks 里");
        assertEquals(7, m.getAsJsonArray("checks").size(), "Q0..Q6 七条都在，缺一条就看不见");
        assertNotNull(m.getAsJsonArray("checks").get(0).getAsJsonObject().get("why"));
    }

    // ---------- 任务 4：七字段槽位（2026-10-03） ----------

    /**
     * ★ 本条钉的是「七字段**各占一个槽位**」，不是「JSON 里出现过这七个词」。
     *
     * <p>改之前它们被拼成散文塞进 {@code description}/{@code rationale}/{@code recommended_response}：
     * 「前置条件」和「失效条件」变成一段话里的两个分句，消费侧没法按字段取。
     */
    @Test
    void theSevenFieldsLandInTheirOwnSlotsUnchanged() {
        Experience seven = sevenFields();
        JsonObject e = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20")).toJson();

        assertEquals(seven.mechanism(), e.get("mechanism").getAsString(), "机制槽位");
        assertEquals(seven.preconditions(), e.get("preconditions").getAsString(), "前置条件槽位");
        assertEquals(seven.failureConditions(), e.get("failure_conditions").getAsString(), "失效条件槽位");
        assertEquals(seven.observableSignal(), e.get("observable_signal").getAsString(), "可观察信号槽位");
        assertEquals(seven.derivation(), e.get("derivation").getAsString(), "推导步骤槽位");
        assertEquals(seven.efficiency(), e.get("efficiency").getAsString(), "效率槽位");
        assertEquals(seven.evidence(), e.get("evidence").getAsString(), "证据槽位");
    }

    /**
     * ★ 反证：槽位里放的是<b>裁剪过的</b>或<b>加标签的</b>值就是错的。
     *
     * <p>{@code Experience.acceptable()} 判的是这七个<b>原值</b>非空；
     * 槽位里若放裁剪版或「适用条件：X」这种带标签的，门禁判据与落盘形状就对不上 ——
     * 那正是本任务要修的毛病本身，不能换个形式再犯一次。
     */
    @Test
    void theSlotsCarryTheRawValuesNotTheLabelledProse() {
        JsonObject e = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20")).toJson();
        for (String slot : new String[]{"mechanism", "preconditions", "failure_conditions",
                "observable_signal", "derivation", "efficiency", "evidence"}) {
            String v = e.get(slot).getAsString();
            assertFalse(v.startsWith("适用条件："), slot + " 带上了散文标签，消费侧就没法直接当条件用");
            assertFalse(v.startsWith("禁用条件："), slot + " 带上了散文标签");
            assertFalse(v.startsWith("证据："), slot + " 带上了散文标签");
            assertFalse(v.endsWith("…"), slot + " 是裁剪过的展示值，不是原值");
        }
    }

    /**
     * ★ 开槽位<b>不是</b>把散文删掉 —— 散文是给人读的那份（目录层 / L1·L2 展开 / 监测台肉眼核对）。
     *
     * <p>所以槽位与散文必须同时存在。少一边就有一类读者拿不到东西，
     * 而那类读者不会报错，只会「读不到」。
     */
    @Test
    void theProseKeptExistingAndTheSlotsDoNotReplaceIt() {
        JsonObject e = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20")).toJson();
        String description = e.get("description").getAsString();
        assertTrue(description.contains("应对步骤："), "散文里的因果链标签还在（人读的那份不丢）");
        assertTrue(description.contains("判据："), "散文里的判据标签还在");
        assertTrue(e.get("recommended_response").getAsString().contains("适用条件："),
                "散文里的适用/禁用条件标签还在");
        assertTrue(e.get("rationale").getAsString().contains("收益："), "散文里的收益/证据标签还在");
    }

    /**
     * 七字段<b>部分</b>为空时，空的那几个槽位写空串、<b>不是</b>缺键。
     *
     * <p>少写一个键，「这条经验声明了槽位但没填」与「这是老条目、产生时还没有槽位」
     * 就长得一模一样 —— 而这两种要区别对待：前者是质量问题，后者是历史包袱。
     *
     * <p>★ 为什么是「部分空」而不是「全空」：{@code Experience.parse:279} 有
     * {@code filledCount() == 0 → null} —— 七字段全空的判定**在解析层就被丢掉**，
     * 根本到不了 draft。所以「全空但仍出 draft」这种输入不存在，
     * 我第一版拿 {@link #GOOD_NO_TYPE} 当样本是**样本选错了**（它只少了
     * {@code experienceType}，七字段照样是满的）。
     * 「全空即丢弃」是既有正确行为，本任务不改它，这里也钉一条反证免得日后有人当成 bug 修。
     */
    @Test
    void aPartlyEmptySlotIsAnEmptyStringRatherThanAMissingKey() {
        JsonObject g = obj(GOOD);
        g.addProperty("efficiency", "");   // 只掏空一个：其余六个仍非空 ⇒ 仍算「产出了经验」
        g.addProperty("evidence", "");
        JsonObject e = draftOf(verdict(g.toString(), "WRITE_EXPERIENCE"), fullMemo("hp=12/20")).toJson();

        for (String slot : new String[]{"mechanism", "preconditions", "failure_conditions",
                "observable_signal", "derivation", "efficiency", "evidence"}) {
            assertTrue(e.has(slot), "★ 槽位 " + slot + " 必须写出来，哪怕空串；缺键就分不出「没填」与「老条目」");
        }
        assertEquals("", e.get("efficiency").getAsString(), "被掏空的那个槽位应是空串");
        assertEquals("", e.get("evidence").getAsString());
        assertTrue(e.get("mechanism").getAsString().length() > 0, "没掏空的槽位照常有内容");
    }

    /** ★ 反证：七字段<b>全空</b>的判定在解析层就被丢弃（既有行为，不是本任务引入的）。 */
    @Test
    void anAllBlankExperienceNeverReachesTheDraftAtAll() {
        JsonObject g = obj(GOOD);
        for (String k : new String[]{"mechanism", "preconditions", "failureConditions",
                "observableSignal", "derivation", "efficiency", "evidence"}) {
            g.addProperty(k, "");
        }
        Verdict v = verdict(g.toString(), "WRITE_EXPERIENCE");
        assertNull(v.experience(),
                "★ Experience.parse 的 filledCount()==0 → null 是既有正确行为：全空即「没产出经验」，"
                        + "不该产出一条空草稿进库。谁要改它，得先说清空草稿进库会造成什么。");
    }

    /**
     * ★ 反证：槽位的加入<b>不许</b>改这条经验的身份。
     *
     * <p>{@code stableKey} 只吃 {@code (type,title)}。若哪天有人顺手把七字段也塞进去，
     * 老条目会换 id、重新去重、并条目 —— 历史全断。
     *
     * <p>⚠️ 这里比的是 <b>{@code title}</b> 而不是 id：同一条草稿的 id 由
     * {@code ExperienceStore.stableKey(type,title)} 现算，而 learner 侧<b>刻意不依赖</b>
     * experience-core（split-package），拿不到那个函数。
     * 「七字段变了而身份没变」能钉住的是「键没被拿去做身份的一部分」，
     * 真正算 id 相等的那道判据在 {@code ExperienceEntry} 侧的测试里。
     */
    @Test
    void theSevenSlotsDoNotChangeTheIdentityOfAnEntry() {
        JsonObject g = obj(GOOD);
        g.addProperty("efficiency", "");   // 只掏空一个，其余仍非空 ⇒ 仍算产出了经验
        g.addProperty("evidence", "");
        ExperienceDraft full = draftOf(verdict(GOOD, "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        ExperienceDraft fewer = draftOf(verdict(g.toString(), "WRITE_EXPERIENCE"), fullMemo("hp=12/20"));
        // 身份键的输入只有 (type,title) —— 两份草稿的这两个键必须逐字相同
        assertEquals(full.toJson().get("title").getAsString(), fewer.toJson().get("title").getAsString(),
                "标题是 id 的组成部分，七字段不该影响它");
        assertEquals(full.toJson().get("type").getAsString(), fewer.toJson().get("type").getAsString(),
                "type 是身份键的另一半，也不该被七字段影响");
        assertNotEquals(full.toJson().get("efficiency").getAsString(),
                fewer.toJson().get("efficiency").getAsString(),
                "★ 反证前提：效率字段确实变了 —— 上面那两条断言不是恒真");
    }
}