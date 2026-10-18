package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7 {@code gap-experience-id-not-canonical}：经验条目的去重键必须归一。
 *
 * <p>起因是实测数据：{@code experience-0c210695-….jsonl} 22 条里有 5 条前缀是
 * {@code tooldefect|}（<b>无下划线</b>），而 {@code ExperienceEntry.stableKey} 产出的是
 * {@code tool_defect|}（<b>有下划线</b>）—— 其余前缀（{@code failure} / {@code policy} /
 * {@code execution}）本来就是 canonical 的，没问题。两套拼写并存 ⇒
 * 那 5 条永远匹配不上新写入，重复 learn 只会堆条目而不是合并。</p>
 *
 * <p><b>⚠️ 这一组测试的一半是「不许合并」的负向测试</b>：Codex 审核首版时指出
 * 「指纹归一」很容易把两条真经验并成一条（假合并 = 丢证据，比多一条更糟），
 * 首版确实因此有 3 个 P0。下面 {@code fingerprintMustNotMerge*} 与
 * {@code semanticSymbols*} 钉的就是这些边界。</p>
 */
class ExperienceFingerprintTest {

    @TempDir
    Path dir;

    private ExperienceStore store() {
        return ExperienceStore.at(dir.resolve("experience-test.jsonl"));
    }

    private static String line(String id, String type, String title, String description) {
        return "{\"v\":1,\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"title\":\"" + title
                + "\",\"description\":\"" + description + "\",\"maturity\":\"OBSERVED\","
                + "\"priority\":50,\"created_at\":1000}";
    }

    private static String lineWithCounterexamples(String id, String type, String title,
                                                  String maturity, int verifiedCount, String cex) {
        return "{\"v\":1,\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"title\":\"" + title
                + "\",\"description\":\"d\",\"maturity\":\"" + maturity + "\",\"verified_count\":" + verifiedCount
                + ",\"counterexamples\":[\"" + cex + "\"],\"priority\":50,\"created_at\":1000}";
    }

    private void write(String... lines) throws Exception {
        Files.writeString(store().file(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    // ---- 1. 读时归一 ----

    @Test
    void loadRekeysLegacyIdSpellingToStableKey() throws Exception {
        write(
                line("tooldefect|工具等级必须匹配目标硬度", "TOOL_DEFECT", "工具等级必须匹配目标硬度", "现象"),
                line("tool_defect|附魔台gui按钮无法点击", "TOOL_DEFECT", "附魔台gui按钮无法点击", "现象"),
                line("Policy|先查后写", "POLICY", "先查后写", "现象"));

        ExperienceStore s = store();
        assertEquals(3, s.size());
        LoadStatsView v = LoadStatsView.of(s);
        // 第 1 条是实测见过的无下划线旧拼写、第 3 条是大写漂移 → 被重算
        // 第 2 条本来就是 stableKey 形状 → 不该被动
        assertEquals(2, v.rekeyed);
        assertEquals(0, v.duplicate);
        for (ExperienceEntry e : s.all()) {
            assertEquals(ExperienceEntry.stableKey(e.type(), e.title()), e.id());
        }
    }

    @Test
    void loadMergesEntriesThatCollideAfterRekey() throws Exception {
        // 同一条经验被两种 id 拼写各写了一遍 —— 归一后必然撞车
        write(
                line("tooldefect|挖钻石要用铁镐", "TOOL_DEFECT", "挖钻石要用铁镐", "现象 A"),
                line("tool_defect|挖钻石要用铁镐", "TOOL_DEFECT", "挖钻石要用铁镐", "现象 B"));

        ExperienceStore s = store();
        assertEquals(1, s.size(), "归一后撞 id 的条目必须合并成一条，不能是两条");
        LoadStatsView v = LoadStatsView.of(s);
        assertEquals(1, v.rekeyed);
        assertEquals(1, v.duplicate);
        // ★ total() 必须等于真有的条数，不能像以前那样 loadedLine+1 虚高
        assertEquals(s.size(), v.total, "total() 与 size() 必须一致，否则面板上的条数是假的");
    }

    /**
     * 合并必须把双方的证据都留下。
     *
     * <p>⚠️ 首版这里只断言「maturity 非空、verifiedCount≥0」，旧的「静默丢弃第二条」
     * 实现照样能过 —— 等于没测。现在钉死具体值。</p>
     */
    @Test
    void duplicateMergeKeepsBothSidesEvidence() throws Exception {
        write(
                lineWithCounterexamples("failure|岩浆边别硬挖", "FAILURE", "岩浆边别硬挖",
                        "OBSERVED", 0, "有一次没铺水也成功了"),
                lineWithCounterexamples("failure|岩浆边别硬挖", "FAILURE", "岩浆边别硬挖",
                        "VERIFIED", 2, "铺了水反而挖不动了"));

        ExperienceStore s = store();
        assertEquals(1, s.size());
        ExperienceEntry merged = s.all().get(0);
        assertEquals(ExperienceMaturity.VERIFIED, merged.maturity(), "成熟度取高者");
        assertEquals(2, merged.verifiedCount(), "验证次数取高者");
        assertEquals(2, merged.counterexamples().size(),
                "★ 反例必须并集：首版是「后来者赢」，先那条的反例会被 persist 永久删掉");
        assertTrue(merged.counterexamples().contains("有一次没铺水也成功了"));
        assertTrue(merged.counterexamples().contains("铺了水反而挖不动了"));
        assertEquals(1000, merged.createdAt(), "合并必须保留首次 createdAt");
    }

    /**
     * ★★ 合并不许抹掉七字段与撤回状态（2026-10-04 修的真洞）。
     *
     * <p>为什么这条最要紧：{@code ExperienceStore.merge()} 原来从零 {@code builder()}
     * 逐个 setter 拼，<b>不走 {@code Builder.from()}</b>，于是七字段槽位与 E8 元数据
     * 在<b>所有去重路径</b>上被静默清空。而
     * {@code ExperienceEntry.Builder.from()}（:449-484）本来就搬全了这两组，
     * {@code ExperienceEntry} 的注释（:463/:478）也写着「必须一起搬」——
     * 它守住了 withEvidence/retracted/unretracted/supersedes/withConsecutiveFailures
     * 五条变异路径，唯独漏了 merge()。
     *
     * <p>第二段尤其严重：<b>一条被人工撤回的经验会复活并重新进入 usable()</b>。
     * 「撤回」是主人/学习者表达「这条不可信」的唯一手段，被一次后续 learn 撤销掉，
     * 等于把「已撤回」这个状态变成无效。
     */
    @Test
    void mergeKeepsSevenSlotsAndRetractionInsteadOfErasingThem() {
        ExperienceStore s = store();

        // ── ① 七字段：先写满，再 learn 同一条但七字段全空
        ExperienceEntry full = ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title("挖矿前先探路").description("D")
                .mechanism("MECH").preconditions("PRE").failureConditions("FAIL")
                .observableSignal("SIG").derivation("DER").efficiency("EFF").evidence("EVI")
                .build();
        s.learn(full);
        s.learn(ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title("挖矿前先探路").description("D2").build());

        ExperienceEntry kept = s.all().get(0);
        assertEquals("MECH", kept.mechanism(), "★ 机制不能被一次空 learn 抹掉");
        assertEquals("PRE", kept.preconditions(), "★ 前置条件不能被抹掉");
        assertEquals("FAIL", kept.failureConditions(), "★ 失效条件不能被抹掉");
        assertEquals("SIG", kept.observableSignal(), "★ 可观察信号不能被抹掉");
        assertEquals("DER", kept.derivation(), "★ 推导步骤不能被抹掉");
        assertEquals("EFF", kept.efficiency(), "★ 效率不能被抹掉");
        assertEquals("EVI", kept.evidence(), "★ 证据不能被抹掉");
        assertEquals("D2", kept.description(), "★ 而正文该更新的还是要更新（这一条防「无脑保留旧值」）");

        // ── ② 撤回不可逆：retract 之后一次 learn 不许把它复活
        ExperienceStore r = store();
        r.learn(ExperienceEntry.builder()
                .type(ExperienceType.POLICY).title("不要在地形边缘跳").description("D").build());
        String id = r.all().get(0).id();
        r.retract(id, "主人说这条不适用");
        assertTrue(r.all().get(0).retracted(), "前置：撤回生效");
        assertEquals("主人说这条不适用", r.all().get(0).retractedReason());

        int usableBefore = r.usable().size();
        r.learn(ExperienceEntry.builder()
                .type(ExperienceType.POLICY).title("不要在地形边缘跳").description("D 又写了一遍").build());

        ExperienceEntry after = r.all().get(0);
        assertTrue(after.retracted(),
                "★★ 被撤回的经验不许因为一次 learn 而复活 —— 撤回是不可逆的，只有显式 unretract 能恢复");
        assertEquals("主人说这条不适用", after.retractedReason(), "撤回理由不能被空值抹掉");
        assertEquals(usableBefore, r.usable().size(),
                "★ 被撤回的经验不许重新进入 usable() —— 那等于「撤回」这个状态失效");
    }

    @Test
    void duplicateMergeKeepsIdSelfConsistentWithTitle() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE)
                .title("先铺水  再挖").description("A").build());
        // 空格宽度不同 ⇒ id 不同 ⇒ 靠指纹兜底才合并
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE)
                .title("先铺水 再挖").description("B").build());

        assertEquals(1, s.size());
        ExperienceEntry merged = s.all().get(0);
        // ★ merge 换了 title 就必须换 id。留着旧 id 会让「id == stableKey(type,title)」
        //   在内存里就不成立，下次重载时被再归一一次 ⇒ 同一个条目两次重载之间 id 漂移。
        assertEquals("先铺水 再挖", merged.title());
        assertEquals(ExperienceEntry.stableKey(merged.type(), merged.title()), merged.id());
    }

    /** 未知 type 的条目不许被归一成 {@code unknown|title} —— 那会删数据。 */
    @Test
    void unknownTypeEntryKeepsItsOriginalId() throws Exception {
        write(line("kindfromthefuture|某条未来经验的标题", "SOMETHING_ELSE", "某条未来经验的标题", "现象"));

        ExperienceStore s = store();
        assertEquals(1, s.size());
        assertEquals("kindfromthefuture|某条未来经验的标题", s.all().get(0).id(),
                "type 解析不出来时必须原样保留 id");
        assertEquals(0, s.loadStats().rekeyed(), "未知 type 不该被计入 rekeyed");
    }

    // ---- 2. 写入端也必须归一 ----

    @Test
    void learnIgnoresCallerSuppliedIdAndUsesStableKey() {
        ExperienceStore s = store();
        ExperienceEntry stored = s.learn(ExperienceEntry.builder()
                .id("tooldefect|调用方自己编的 id")
                .type(ExperienceType.TOOL_DEFECT)
                .title("调用方自己编的 id")
                .description("现象")
                .build());
        assertEquals(ExperienceEntry.stableKey(ExperienceType.TOOL_DEFECT, "调用方自己编的 id"), stored.id());
        assertEquals("tool_defect|调用方自己编的 id", stored.id());
    }

    @Test
    void learnMergesWhenOnlySpacingWidthOrCaseDrifted() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title("挖  钻石 之前先  铺水")
                .description("现象 A")
                .build());
        s.learn(ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title("挖 钻石 之前先 铺水")
                .description("现象 B")
                .build());
        // 空格宽度变了 ⇒ 人读起来是同一个标题 ⇒ 该合并
        assertEquals(1, s.size());
        assertEquals("现象 B", s.all().get(0).description());
    }

    // ---- 3. ★ 指纹不许乱并（首版三个 P0 的边界） ----

    @Test
    void fingerprintMustNotMergeDifferentTitles() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE)
                .title("挖钻石之前先铺水").description("A").build());
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE)
                .title("挖钻石之前先挖深").description("B").build());
        assertEquals(2, s.size());
    }

    @Test
    void fingerprintDoesNotMergeAcrossTypes() {
        assertNotEquals(
                ExperienceEntry.fingerprint(ExperienceType.FAILURE, "同名"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "同名"));
    }

    /** 语义符号写在标题两端时<b>不能</b>剥掉。 */
    @Test
    void fingerprintMustNotStripEdgePunctuation() {
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "-64"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "64"));
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "!enabled"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "enabled"));
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "C#"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "C"));
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "[0,1)"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "(0,1]"));
        // 句末句号仍然不同 —— 保守优先，漏合并只是多一条
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖钻石。"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖钻石"));
    }

    /** 内部空白<b>不能</b>删除：{@code "1 23"} 与 {@code "12 3"} 是不同的坐标/参数。 */
    @Test
    void fingerprintMustNotDeleteInteriorWhitespace() {
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "1 23"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "12 3"));
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖钻石,之后先铺水"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖钻石之后先铺水"));
    }

    /** 这两组是首版被 Codex 判为 P0 的具体碰撞，钉死防回归。 */
    @Test
    void semanticSymbolsNeverFuse() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder().type(ExperienceType.POLICY).title("-64").description("下界").build());
        s.learn(ExperienceEntry.builder().type(ExperienceType.POLICY).title("64").description("恰是 64").build());
        s.learn(ExperienceEntry.builder().type(ExperienceType.POLICY).title("1 23").description("带空格").build());
        s.learn(ExperienceEntry.builder().type(ExperienceType.POLICY).title("12 3").description("不带空格").build());
        assertEquals(4, s.size(), "带语义的符号标题绝不能被指纹并成一条");
    }

    @Test
    void fingerprintAbsorbsOnlySpacingWidthAndCase() {
        assertEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "  挖   钻石  "),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖 钻石"));
        assertEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "Tool GUI"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "ｔｏｏｌ　gui"));
        assertNotEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "钻石要铁镐"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "钻石要钻石镐"));
    }

    @Test
    void stableKeyStaysLooserThanFingerprint() {
        // stableKey 只 trim+小写 —— 空格宽度不同的两个标题 id 不同、指纹相同
        assertNotEquals(ExperienceEntry.stableKey(ExperienceType.POLICY, "挖  钻石"),
                ExperienceEntry.stableKey(ExperienceType.POLICY, "挖 钻石"));
        assertEquals(ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖  钻石"),
                ExperienceEntry.fingerprint(ExperienceType.POLICY, "挖 钻石"));
    }

    // ---- 4. 旧 id 兜底 ----

    @Test
    void recordEvidenceResolvesLegacyIdByTitle() throws Exception {
        write(line("tooldefect|旧拼写标题", "TOOL_DEFECT", "旧拼写标题", "现象"));

        ExperienceStore s = store();
        // 归一后磁盘 id 已经是 tool_defect|…，但外部可能还拿着旧拼写
        ExperienceEntry updated = s.recordEvidence("tooldefect|旧拼写标题", true, null);
        assertNotNull(updated, "旧拼写 id 必须兜底命中，否则归一化前流出去的 id 会静默失效");
        assertEquals(1, updated.verifiedCount());
        assertTrue(updated.maturity().level() >= ExperienceMaturity.VERIFIED.level());
    }

    /**
     * ★ Codex P0：兜底不能只比 {@code '|'} 后半段。
     * 否则 {@code tooldefect|t} 会命中镜像里第一条同名条目，哪怕它是 POLICY ——
     * 证据记到错的类型上，还把它误升成 VERIFIED。
     */
    @Test
    void recordEvidenceLegacyIdRespectsTypePrefix() throws Exception {
        write(
                line("policy|同名标题", "POLICY", "同名标题", "策略"),
                line("tool_defect|同名标题", "TOOL_DEFECT", "同名标题", "缺陷"));

        ExperienceStore s = store();
        assertEquals(2, s.size());

        ExperienceEntry wrong = s.recordEvidence("tooldefect|同名标题", true, null);
        assertNotNull(wrong);
        assertEquals(ExperienceType.TOOL_DEFECT, wrong.type(), "必须命中 TOOL_DEFECT，不能命中 POLICY");
        assertEquals(1, wrong.verifiedCount());

        ExperienceEntry untouched = s.all().stream()
                .filter(e -> e.type() == ExperienceType.POLICY).findFirst().orElseThrow();
        assertEquals(0, untouched.verifiedCount(), "POLICY 那条不该被动");
        assertEquals(ExperienceMaturity.OBSERVED, untouched.maturity());
    }

    /** 未知前缀一律拒绝 —— 不做「去掉下划线」那种通配。 */
    @Test
    void recordEvidenceRejectsUnknownLegacyPrefix() throws Exception {
        write(line("policy|同名标题", "POLICY", "同名标题", "策略"));
        assertNull(store().recordEvidence("garbage|同名标题", true, null));
        assertNull(store().recordEvidence("同名标题", true, null));
    }

    @Test
    void recordEvidenceStillReturnsNullForUnknownId() {
        assertNull(store().recordEvidence("failure|根本没这条", true, null));
    }

    // ---- 5. 计数口径 ----

    /** legacy 数组里的重复也要进 legacy 计数，否则 {@code total()} 会算出负数。 */
    @Test
    void legacyArrayDuplicatesDoNotBreakTotal() throws Exception {
        Files.writeString(store().file(),
                "[" + line("failure|重复一", "FAILURE", "重复一", "A") + ","
                        + line("failure|重复一", "FAILURE", "重复一", "B") + ","
                        + line("failure|重复一", "FAILURE", "重复一", "C") + "]",
                StandardCharsets.UTF_8);

        ExperienceStore s = store();
        assertEquals(1, s.size());
        LoadStatsView v = LoadStatsView.of(s);
        assertEquals(3, s.loadStats().loadedLegacy(), "三条都成功解析进过镜像（含重复）");
        assertEquals(2, s.loadStats().duplicate());
        assertEquals(1, v.total, "★ legacy 路径算不出负数：首版是 1 + 0 - 3 = -2");
        assertEquals(s.size(), v.total);
    }

    /** 坏行只进 failed，不混进 loadedLine。 */
    @Test
    void brokenLineCountsAsFailedNotAsLoaded() throws Exception {
        write(line("failure|好的那条", "FAILURE", "好的那条", "A"), "{\"id\":\"x\",\"title\":");

        ExperienceStore s = store();
        assertEquals(1, s.size());
        assertEquals(1, s.loadStats().loadedLine());
        assertEquals(1, s.loadStats().loadedFailed());
        assertEquals(1, s.loadStats().total());
        assertEquals(s.size(), s.loadStats().total());
    }

    /**
     * {@code migrateLegacyShape()} 走完会把 {@code loaded} 置回 false，下一次读会整份重载。
     * 若重载前不清 mirror，每条都会撞上「自己那条」被当重复合并 ⇒
     * {@code loadedLine=2, duplicate=2, total()=0}，条目凭空消失。
     * 这个坑在「撞 id 就静默丢弃」的旧逻辑下是看不见的。
     */
    @Test
    void reloadAfterMigrationDoesNotDoubleCount() throws Exception {
        ExperienceStore seed = ExperienceStore.at(dir.resolve("reload-src.jsonl"));
        ExperienceEntry a = seed.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE)
                .title("重载一").description("A").build());
        ExperienceEntry b = seed.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE)
                .title("重载二").description("B").build());
        Files.writeString(store().file(),
                "[" + a.toJson() + "," + b.toJson() + "]", StandardCharsets.UTF_8);

        ExperienceStore s = store();
        assertEquals(2, s.migrateLegacyShape());
        // 同一个 store 再读一次（migrateLegacyShape 走完把 loaded 置回 false）
        assertEquals(2, s.size());
        LoadStatsView v = LoadStatsView.of(s);
        assertEquals(2, v.total, "重载后条数不能被自己的重复吃掉");
        assertEquals(2, s.loadStats().loadedLine());
        assertEquals(0, s.loadStats().duplicate());
    }

    /**
     * 新建库（磁盘上还没有文件）时 {@code loadedLine=0} 是<b>对的</b>：
     * 那几个数描述的是「从磁盘读进来多少」，不是「现在有多少」。
     * 这个口径差是有意的，钉住免得以后有人当 bug 又去「修」。
     */
    @Test
    void freshStoreReportsZeroLoadedButHasSize() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE).title("新写一条").description("A").build());
        assertEquals(1, s.size());
        assertEquals(0, s.loadStats().loadedLine(), "没读盘就不该报读入条数");
        assertEquals(0, s.loadStats().total());
    }

    // ---- 6. 合并副作用 ----

    /** 合并时触发词取并集 —— 指纹/id 合并不能把已有的检索入口弄丢。 */
    @Test
    void triggerListUnionsOnMerge() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE).title("T")
                .description("A").triggerStrings(List.of("a")).build());
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE).title("T")
                .description("B").triggerStrings(List.of("b")).build());
        assertEquals(List.of("a", "b"), s.all().get(0).triggerStrings());
    }

    /** 反例有上限，并集超过上限时保留最新的那些。 */
    @Test
    void counterexamplesAreCappedAfterUnion() {
        ExperienceStore s = store();
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE).title("C")
                .description("A").counterexamples(List.of("旧1", "旧2", "旧3")).build());
        s.learn(ExperienceEntry.builder().type(ExperienceType.FAILURE).title("C")
                .description("B").counterexamples(List.of("新1", "新2", "新3")).build());
        List<String> cex = s.all().get(0).counterexamples();
        assertEquals(ExperienceStore.MAX_COUNTEREXAMPLES, cex.size(), "超过上限要截断，不能无限膨胀");
        assertTrue(cex.contains("新1"), "最新的反例必须留着");
    }

    /** 3 参构造器向后兼容（面板/旧调用方还有人在用）。 */
    @Test
    void threeArgLoadStatsStillCompiles() {
        ExperienceStore.LoadStats s = new ExperienceStore.LoadStats(3, 1, 0);
        assertEquals(4, s.total());
        assertEquals(0, s.rekeyed());
        assertEquals(0, s.duplicate());
    }

    /** record 的访问器在测试里读起来更顺，故包一层只读视图。 */
    private record LoadStatsView(int total, int rekeyed, int duplicate) {
        static LoadStatsView of(ExperienceStore s) {
            ExperienceStore.LoadStats st = s.loadStats();
            return new LoadStatsView(st.total(), st.rekeyed(), st.duplicate());
        }
    }
}