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

class LexicalExperienceRetrieverTest {

    private final LexicalExperienceRetriever retriever = new LexicalExperienceRetriever();

    private ExperienceEntry exp(ExperienceType type, String title, String desc, String... triggers) {
        return ExperienceEntry.builder()
                .type(type)
                .title(title)
                .description(desc)
                .triggerStrings(List.of(triggers))
                .priority(50)
                .build();
    }

    @Test
    void returnsEmptyOnNoMatches() {
        ExperienceEntry e = exp(ExperienceType.FAILURE, "挖矿", "小心岩浆");
        assertTrue(retriever.retrieve(ExperienceQuery.of("盖房子", 5), List.of(e)).isEmpty());
    }

    @Test
    void onlyRelevantEntryIsReturned() {
        ExperienceEntry lava = exp(ExperienceType.FAILURE, "向下挖矿", "检查岩浆", "岩浆", "向下挖");
        ExperienceEntry mine = exp(ExperienceType.EXECUTION, "挖圆石", "用镐子挖", "圆石");
        List<ExperienceEntry> entries = List.of(lava, mine);

        List<ExperienceHit> hits = retriever.retrieve(ExperienceQuery.of("挖矿 岩浆", 5), entries);
        assertEquals(1, hits.size());
        assertEquals(lava.id(), hits.get(0).entry().id());
    }

    @Test
    void triggerStringHitsRankAboveTitleHits() {
        ExperienceEntry titleOnly = exp(ExperienceType.FAILURE, "挖矿时小心", "小心", "其他触发词");
        ExperienceEntry triggerHit = exp(ExperienceType.FAILURE, "小心岩浆", "挖矿", "挖矿");
        List<ExperienceEntry> entries = List.of(titleOnly, triggerHit);

        List<ExperienceHit> hits = retriever.retrieve(ExperienceQuery.of("挖矿", 5), entries);
        assertEquals(2, hits.size());
        assertEquals(triggerHit.id(), hits.get(0).entry().id());
    }

    @Test
    void reverseTriggerSubstringMatchesChineseWholePhrase() {
        // 查询整句包含触发词，即使分词不同也应命中
        ExperienceEntry e = exp(ExperienceType.TOOL_DEFECT, "假成功", "工具返回OK但世界没变", "工具返回OK");
        List<ExperienceHit> hits = retriever.retrieve(ExperienceQuery.of("上次smelt_item工具返回OK但熔炉没亮", 5), List.of(e));
        assertFalse(hits.isEmpty());
        assertEquals(e.id(), hits.get(0).entry().id());
    }

    @Test
    void respectsMinMaturityFilter() {
        ExperienceEntry raw = exp(ExperienceType.FAILURE, "向下挖矿", "检查岩浆", "岩浆");
        ExperienceEntry verified = ExperienceEntry.builder().from(raw)
                .maturity(ExperienceMaturity.VERIFIED)
                .build();
        List<ExperienceEntry> entries = List.of(raw, verified);

        List<ExperienceHit> hits = retriever.retrieve(
                new ExperienceQuery("岩浆", 5, ExperienceMaturity.VERIFIED, List.of()), entries);
        assertEquals(1, hits.size());
        assertEquals(verified.id(), hits.get(0).entry().id());
    }

    @Test
    void respectsTagFilter() {
        ExperienceEntry a = exp(ExperienceType.FAILURE, "下界准备", "带防火", "下界");
        ExperienceEntry b = exp(ExperienceType.FAILURE, "末地准备", "带水", "末地");
        a = ExperienceEntry.builder().from(a).tags(List.of("nether")).build();
        b = ExperienceEntry.builder().from(b).tags(List.of("end")).build();

        List<ExperienceHit> hits = retriever.retrieve(
                new ExperienceQuery("准备", 5, null, List.of("nether")), List.of(a, b));
        assertEquals(1, hits.size());
        assertEquals(a.id(), hits.get(0).entry().id());
    }

    @Test
    void emptyQueryFallsBackToMaturityAndPriority() {
        ExperienceEntry low = ExperienceEntry.builder()
                .from(exp(ExperienceType.FAILURE, "普通", "普通经验"))
                .maturity(ExperienceMaturity.OBSERVED)
                .priority(10)
                .build();
        ExperienceEntry high = ExperienceEntry.builder()
                .from(exp(ExperienceType.FAILURE, "生死", "生死经验"))
                .maturity(ExperienceMaturity.GENERALIZED)
                .priority(100)
                .build();

        List<ExperienceHit> hits = retriever.retrieve(ExperienceQuery.of("", 5), List.of(low, high));
        assertEquals(2, hits.size());
        assertEquals(high.id(), hits.get(0).entry().id());
    }

    @Test
    void capsAtLimit() {
        List<ExperienceEntry> entries = List.of(
                exp(ExperienceType.FAILURE, "经验一", "岩浆", "岩浆"),
                exp(ExperienceType.FAILURE, "经验二", "岩浆", "岩浆"),
                exp(ExperienceType.FAILURE, "经验三", "岩浆", "岩浆"));
        assertEquals(2, retriever.retrieve(ExperienceQuery.of("岩浆", 2), entries).size());
    }
}
