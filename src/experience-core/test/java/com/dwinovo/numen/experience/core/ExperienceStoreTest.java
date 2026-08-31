package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperienceStoreTest {

    @TempDir
    Path dir;

    private ExperienceStore store() {
        return ExperienceStore.at(dir.resolve("experience-test.jsonl"));
    }

    private ExperienceEntry entry(String title) {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title(title)
                .description("工具返回成功但世界没有变化")
                .rootCause("工具只确认调用无异常，没有确认世界状态")
                .recommendedResponse("操作后重新观察真实环境")
                .triggerStrings(List.of("假成功", "返回成功", "世界没变"))
                .toolNames(List.of("smelt_item"))
                .tags(List.of("mine", "world"))
                .priority(80)
                .build();
    }

    @Test
    void learnRequiresTypeTitleDescription() {
        ExperienceStore s = store();
        assertThrows(IllegalArgumentException.class, () -> s.learn(
                ExperienceEntry.builder().type(ExperienceType.FAILURE).title("只有标题").build()));
        assertThrows(IllegalArgumentException.class, () -> s.learn(
                ExperienceEntry.builder().type(ExperienceType.FAILURE).description("只有描述").build()));
    }

    @Test
    void learnAssignsStableIdAndDedupBySameTitle() {
        ExperienceStore s = store();
        ExperienceEntry first = s.learn(entry("向下挖矿前检查岩浆"));
        ExperienceEntry again = s.learn(entry("向下挖矿前检查岩浆"));

        assertEquals(ExperienceEntry.stableKey(ExperienceType.FAILURE, "向下挖矿前检查岩浆"), first.id());
        assertEquals(first.id(), again.id());
        assertEquals(1, s.size());
        // 合并保留首次 createdAt
        assertEquals(first.createdAt(), s.all().get(0).createdAt());
    }

    @Test
    void learnMergesFieldsAndUnionsTriggers() {
        ExperienceStore s = store();
        ExperienceEntry base = s.learn(entry("放熔炉前确认燃料"));
        ExperienceEntry more = s.learn(ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title("放熔炉前确认燃料")
                .description("熔炉不会自动烧，需要燃料和等待")
                .triggerStrings(List.of("熔炉", "燃料"))
                .tags(List.of("smelt"))
                .priority(90)
                .build());

        assertEquals(1, s.size());
        assertEquals(5, more.triggerStrings().size()); // 并集 = 3 原触发词 + 熔炉/燃料
        assertEquals(3, more.tags().size());           // 并集 = mine/world + smelt
        assertEquals(90, more.priority());             // 取高
        // 描述取新值；rationale/rootCause 保留旧值（新条目不填时不覆盖）
        assertEquals("熔炉不会自动烧，需要燃料和等待", more.description());
        assertEquals("工具只确认调用无异常，没有确认世界状态", more.rootCause());
    }

    @Test
    void recordEvidencePromotesMaturityBySuccessCount() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("掉岩浆前听到声音要绕路"));
        assertEquals(ExperienceMaturity.OBSERVED, e.maturity());

        ExperienceEntry v1 = s.recordEvidence(e.id(), true, "绕路成功");
        assertEquals(ExperienceMaturity.VERIFIED, v1.maturity());
        assertEquals(1, v1.verifiedCount());
        assertNotNull(v1.verifiedAt());

        ExperienceEntry v2 = s.recordEvidence(e.id(), true, "再次绕路成功");
        assertEquals(ExperienceMaturity.VERIFIED, v2.maturity());
        assertEquals(2, v2.verifiedCount());

        ExperienceEntry v3 = s.recordEvidence(e.id(), true, "第三次成功");
        assertEquals(ExperienceMaturity.GENERALIZED, v3.maturity());
        assertEquals(3, v3.verifiedCount());
    }

    @Test
    void failureAddsCounterexampleAndMovesObservedToAttemptedOnly() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("听到岩浆声要绕路"));

        ExperienceEntry afterFail = s.recordEvidence(e.id(), false, "没有绕路成功了");
        assertEquals(ExperienceMaturity.ATTEMPTED, afterFail.maturity());
        assertEquals(0, afterFail.verifiedCount());
        assertEquals(1, afterFail.counterexamples().size());

        // VERIFIED 后失败不降级，只加反例
        ExperienceEntry v = s.recordEvidence(e.id(), true, "绕路成功一次");
        assertEquals(ExperienceMaturity.VERIFIED, v.maturity());
        ExperienceEntry afterSecondFail = s.recordEvidence(e.id(), false, "这次又失败了");
        assertEquals(ExperienceMaturity.VERIFIED, afterSecondFail.maturity());
        assertEquals(2, afterSecondFail.counterexamples().size());
    }

    @Test
    void counterexamplesAreCapped() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("反例封顶"));
        for (int i = 0; i < 8; i++) {
            e = s.recordEvidence(e.id(), false, "反例" + i);
        }
        assertEquals(ExperienceStore.MAX_COUNTEREXAMPLES, e.counterexamples().size());
    }

    @Test
    void recordEvidenceUnknownIdReturnsNull() {
        assertNull(store().recordEvidence("missing", true, "n/a"));
    }

    @Test
    void persistsAndReloadsAcrossInstances() {
        Path file = dir.resolve("persist.jsonl");
        ExperienceStore w = ExperienceStore.at(file);
        ExperienceEntry e = w.learn(entry("持久化经验"));
        w.recordEvidence(e.id(), true, "确认");

        // 新实例（模拟重启）重新读盘
        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(1, r.size());
        ExperienceEntry loaded = r.all().get(0);
        assertEquals(e.id(), loaded.id());
        assertEquals(ExperienceMaturity.VERIFIED, loaded.maturity());
        assertEquals(1, loaded.verifiedCount());
    }

    @Test
    void skipsCorruptLinesWithoutLosingOthers() throws Exception {
        Path file = dir.resolve("corrupt.jsonl");
        ExperienceStore w = ExperienceStore.at(file);
        ExperienceEntry e = w.learn(entry("干净的一条"));
        java.nio.file.Files.writeString(file, "not-json\n", java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(1, r.size());
        assertEquals(e.id(), r.all().get(0).id());
    }
}
