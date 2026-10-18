package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceQuery;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E6a：中文查询分词。
 *
 * <p><b>这个洞为什么之前没被发现</b>：原 {@code tokens()} 按
 * {@code [^\p{L}\p{N}]+} 切分，中文整段成一个 token，拿它做 {@code contains} 子串匹配 ——
 * 除非条目里一字不差出现这整句，否则<b>一条都命中不了</b>。
 * 而英文路径一直是对的，所以任何用英文查询写的单测都发现不了它。
 * 真正的暴露场景是「把中文任务注入经验目录」，那次目录恒为空。</p>
 */
class LexicalQueryTokenizerTest {

    private final LexicalExperienceRetriever retriever = new LexicalExperienceRetriever();

    private static ExperienceEntry entry(String title, String desc, List<String> triggers) {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title(title)
                .description(desc)
                .triggerStrings(triggers)
                .build();
    }

    private static List<String> tokenize(String s) {
        return LexicalQueryTokenizer.tokenize(s);
    }

    // ---------- 分词本身 ----------

    @Test
    void chineseQueryProducesOverlappingBigrams() {
        List<String> t = tokenize("挖钻石之前先铺水");
        assertTrue(t.contains("挖钻"), t.toString());
        assertTrue(t.contains("钻石"), t.toString());
        assertTrue(t.contains("先铺"), t.toString());
        assertTrue(t.contains("铺水"), t.toString());
    }

    @Test
    void functionWordBigramsAreDropped() {
        List<String> t = tokenize("我需要先准备一下然后再出发");
        assertFalse(t.contains("需要"), "「需要」命中说明不了任何东西：" + t);
        assertFalse(t.contains("然后"), "「然后」同上：" + t);
        assertTrue(t.contains("准备"), t.toString());
        assertTrue(t.contains("出发"), t.toString());
    }

    @Test
    void singleCharacterChineseRunProducesNoToken() {
        // 「水」能命中无数条目，会把所有条目一起抬分、淹没有用的
        assertFalse(tokenize("水").contains("水"), tokenize("水").toString());
    }

    @Test
    void latinBehaviourIsUnchanged() {
        List<String> t = tokenize("Mine diamond ore with iron pickaxe");
        assertTrue(t.contains("mine"), t.toString());
        assertTrue(t.contains("diamond"), t.toString());
        assertTrue(t.contains("iron"), t.toString());
        assertTrue(t.contains("pickaxe"), t.toString());
        // 拉丁词不该被塞进额外 2-gram
        assertFalse(t.contains("in"), t.toString());
        assertFalse(t.contains("ne"), t.toString());
    }

    @Test
    void mixedChineseAndLatinBothGetTokens() {
        List<String> t = tokenize("用 mc 挖钻石");
        assertTrue(t.contains("mc"), t.toString());
        assertTrue(t.contains("挖钻"), t.toString());
        assertTrue(t.contains("钻石"), t.toString());
    }

    @Test
    void emptyAndNullQueriesProduceNoTokens() {
        assertTrue(tokenize(null).isEmpty());
        assertTrue(tokenize("").isEmpty());
        assertTrue(tokenize("   ").isEmpty());
        assertTrue(tokenize("!!!").isEmpty());
    }

    @Test
    void kanaAndHangulAreNotTreatedAsChinese() {
        // 假名/谚文的 2-gram 语义密度和中文不同，算错会静默产出无意义 token。
        // 用码位数值断言：字面量在编辑链路上会被改写（本机踩过）。
        assertFalse(LexicalQueryTokenizer.isCjk('あ'), "平假名 U+3042");
        assertFalse(LexicalQueryTokenizer.isCjk('ア'), "片假名 U+30A2");
        assertFalse(LexicalQueryTokenizer.isCjk('가'), "谚文 U+AC00");
        assertTrue(LexicalQueryTokenizer.isCjk('挖'), "U+6316");
        assertTrue(LexicalQueryTokenizer.isCjk('钻'), "U+94BB");
        assertTrue(LexicalQueryTokenizer.isCjk('一'), "U+4E00，区间下界");
        assertTrue(LexicalQueryTokenizer.isCjk('鿿'), "U+9FFF，区间上界");
        // 区间边界外一格
        assertFalse(LexicalQueryTokenizer.isCjk('〇'), "U+3007 标点，不该算汉字");
    }

    // ---------- 端到端：中文查询真能召回 ----------

    @Test
    void chineseQueryNowRetrievesChineseEntry() {
        List<ExperienceEntry> entries = List.of(
                entry("挖钻石之前先铺水", "水会浇灭岩浆，先铺水再挖就不会被烧死", List.of("岩浆边挖矿")),
                entry("末影人战斗要带盾", "末影人近战伤害高，带盾能硬抗三下", List.of("末影人")));

        List<ExperienceHit> hits = retriever.retrieve(
                new ExperienceQuery("挖钻石", 5, null, List.of()), entries);

        assertFalse(hits.isEmpty(), "★ 中文查询必须能召回中文经验 —— 这是 E6 活目录的前提");
        assertEquals("挖钻石之前先铺水", hits.get(0).entry().title(), "最相关的应该排第一");
    }

    @Test
    void chineseQueryDoesNotMatchUnrelatedEntries() {
        List<ExperienceEntry> entries = List.of(
                entry("挖钻石之前先铺水", "水会浇灭岩浆", List.of("岩浆边挖矿")),
                entry("末影人战斗要带盾", "末影人近战伤害高", List.of("末影人")));

        List<ExperienceHit> hits = retriever.retrieve(
                new ExperienceQuery("挖钻石", 5, null, List.of()), entries);

        assertEquals(1, hits.size(), "只有一条相关，不能把末影人那条也捞进来：" + hits);
    }

    @Test
    void englishRetrievalStillWorksAfterTheChange() {
        List<ExperienceEntry> entries = List.of(
                entry("Tool defect", "tool says OK but the world did not change", List.of("fake success")),
                entry("Policy", "check before write", List.of("read first")));

        List<ExperienceHit> hits = retriever.retrieve(
                new ExperienceQuery("fake success", 5, null, List.of()), entries);
        assertEquals(1, hits.size());
        assertEquals("Tool defect", hits.get(0).entry().title());
    }

    /** 大小写与中英混写的查询也要能命中（case-insensitive 是原有契约）。 */
    @Test
    void queryIsCaseInsensitive() {
        List<ExperienceEntry> entries = List.of(
                entry("挖钻石", "desc", List.of("钻石")));
        List<ExperienceHit> hits = retriever.retrieve(
                new ExperienceQuery("钻石", 5, null, List.of()), entries);
        assertEquals(1, hits.size());
    }
}