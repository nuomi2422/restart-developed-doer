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

    // ===== B1（2026-10-02）：兼容「.jsonl 后缀但内容是整个 JSON 数组」的遗留形状 =====
    //
    // 病根：早期版本把整个数组写进 .jsonl 文件（文件只有 1 行）。读取端原先只认逐行
    // JSONL，对数组调 getAsJsonObject() 抛 IllegalStateException，被 catch(RuntimeException)
    // 静默吞掉 ⇒ mirror 为空 ⇒ 注入侧 total()==0 ⇒ 经验正文一条都进不了上下文。
    // 同类坑 MemoQueue 在 2026-09-29 已修，经验库当时漏了。

    /** 造一个「.jsonl 后缀但内容是整个数组」的遗留文件。 */
    private Path writeLegacyArrayFile(String name, List<ExperienceEntry> entries) throws Exception {
        Path file = dir.resolve(name);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entries.get(i).toJson().toString());
        }
        sb.append(']');
        java.nio.file.Files.writeString(file, sb.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readsLegacyArrayShapedJsonl() throws Exception {
        ExperienceStore seed = ExperienceStore.at(dir.resolve("seed-src.jsonl"));
        ExperienceEntry a = seed.learn(entry("遗留一"));
        ExperienceEntry b = seed.learn(entry("遗留二"));
        Path file = writeLegacyArrayFile("legacy-array.jsonl", List.of(a, b));

        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(2, r.size(), "遗留数组形状必须能读出来");
        assertEquals(a.id(), r.all().get(0).id());

        ExperienceStore.LoadStats s = r.loadStats();
        assertEquals(2, s.loadedLegacy(), "应记为 legacy 而不是 line");
        assertEquals(0, s.loadedLine());
        assertEquals(0, s.loadedFailed());
        assertEquals(2, s.total());
        assertEquals(true, s.needsMigrate());
    }

    @Test
    void normalJsonlStillReadsAsLine() throws Exception {
        // 回退保护：正常逐行 JSONL 不能被误判成 legacy。
        ExperienceStore w = ExperienceStore.at(dir.resolve("normal.jsonl"));
        w.learn(entry("正常一条"));
        w.learn(entry("正常两条"));

        ExperienceStore r = ExperienceStore.at(dir.resolve("normal.jsonl"));
        assertEquals(2, r.size());
        ExperienceStore.LoadStats s = r.loadStats();
        assertEquals(2, s.loadedLine());
        assertEquals(0, s.loadedLegacy(), "正常 JSONL 不该被当成遗留形状");
        assertEquals(false, s.needsMigrate());
    }

    @Test
    void countsFailedEntriesInsteadOfHidingThem() throws Exception {
        Path file = dir.resolve("half-broken.jsonl");
        ExperienceStore w = ExperienceStore.at(file);
        w.learn(entry("好的那条"));
        java.nio.file.Files.writeString(file, "{\"id\":\"x\",\"title\":",
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(1, r.size(), "坏的跳过，好的还在");
        assertEquals(1, r.loadStats().loadedFailed(), "失败必须被记成可见的数，不能只有一行日志");
        assertEquals(true, r.loadStats().degraded());
    }

    @Test
    void migrateLegacyShapeIsIdempotentAndBacksUpOnce() throws Exception {
        ExperienceStore seed = ExperienceStore.at(dir.resolve("mig-src.jsonl"));
        ExperienceEntry a = seed.learn(entry("迁移一"));
        ExperienceEntry b = seed.learn(entry("迁移二"));
        Path file = writeLegacyArrayFile("migrate-me.jsonl", List.of(a, b));

        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(2, r.migrateLegacyShape(), "迁移应返回条数");

        // 迁移后应是逐行 JSONL：两个新实例读都走正常路径
        ExperienceStore again = ExperienceStore.at(file);
        assertEquals(2, again.size());
        assertEquals(0, again.loadStats().loadedLegacy(), "迁完就不该还需要迁移");
        assertEquals(2, again.loadStats().loadedLine());
        assertEquals(a.id(), again.all().get(0).id());
        assertEquals(b.id(), again.all().get(1).id());

        // 再迁一次：不应重复备份、不应丢条目
        assertEquals(2, r.migrateLegacyShape());
        long backups = java.nio.file.Files.list(dir)
                .filter(p -> p.getFileName().toString().startsWith("migrate-me.jsonl.pre-migrate-"))
                .count();
        assertEquals(1, backups, "同一文件的迁移备份只应有一份");
        assertEquals(2, ExperienceStore.at(file).size(), "条目不能丢");
    }

    @Test
    void singleObjectFileIsAlsoTreatedAsLegacy() throws Exception {
        ExperienceStore seed = ExperienceStore.at(dir.resolve("single-src.jsonl"));
        ExperienceEntry a = seed.learn(entry("单条"));
        Path file = dir.resolve("single.jsonl");
        java.nio.file.Files.writeString(file, a.toJson().toString(), java.nio.charset.StandardCharsets.UTF_8);

        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(1, r.size(), "整个文件只有一条也要能读");
        assertEquals(1, r.loadStats().loadedLegacy());
    }
}
