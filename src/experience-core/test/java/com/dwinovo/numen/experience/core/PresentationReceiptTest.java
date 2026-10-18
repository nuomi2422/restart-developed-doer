package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ E7 前半段：<b>「这条经验被用过」第一次留痕</b>。
 *
 * <p><b>为什么这个测试文件必须存在</b>：回流链路上游的四个执行器
 * （{@code recordEvidence / retract / reinstate / supersede}）一直都在，
 * 但实测 live 实例 66 条经验里 {@code retracted=true} / {@code supersedes≠""} /
 * {@code consecutive_failures>0} 各为 <b>0</b> —— 不是因为逻辑坏了，
 * 而是因为<b>没有任何地方记录「哪条经验被用过」</b>，AI 没有理由知道该回报谁。
 * 没有回执表，这条链路永远是空的，而且测试不会红。</p>
 *
 * <p>⚠️ <b>工具层测不了</b>：{@code plugins/experience} 的测试 classpath 里
 * 没有 gson 也没有 NumenTool（都是 compileOnly），所以 {@code ExperienceRecallTool} /
 * {@code ExperienceVerifyTool} 只能进游戏实跑验。这里钉的是 core 层的记账语义。</p>
 */
class PresentationReceiptTest {

    @TempDir
    Path tmp;

    private ExperienceMemory memory() {
        return ExperienceMemory.at(tmp.resolve("exp.jsonl"), new LexicalExperienceRetriever());
    }

    private static ExperienceEntry entry(String title) {
        return ExperienceEntry.builder()
                .type(ExperienceType.WORLD_RELATION).title(title)
                .description("开阔熔岩湖上方先铺水再挖黑曜石").build();
    }

    private static List<ExperienceHit> hits(List<ExperienceEntry> entries) {
        List<ExperienceHit> out = new ArrayList<>();
        for (ExperienceEntry e : entries) {
            out.add(new ExperienceHit(e, 1.0, List.of()));
        }
        return out;
    }

    // ---- 1. 只有「AI 主动要」的呈现才进待回报 ----

    /**
     * ★ 这条是整个设计的核心判断：L0 目录与规划知识是<b>每轮自动印在 prompt 里</b>的，
     * 同伴很可能压根没看。让它们进待回报清单，满屏都是「本任务没有结论」的噪音，
     * 回流通道等于白建。
     */
    @Test
    void onlyTheMaidAskingForThemGoesOnThePendingList() {
        ExperienceMemory m = memory();
        String id = m.learn(entry("熔岩湖挖矿先铺水")).id();

        // 自动的两条：留「被用过」的长期计数，但不进待回报。
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_DIRECTORY, false);
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_PLANNING, false);
        assertEquals(0, m.receipts().pendingCount(),
                "★ 自动呈现不进待回报清单 —— 否则每轮都欠一次「结论」");
        assertEquals(2, m.receipts().presentedCount(id),
                "自动呈现仍然计「被呈现过」—— 这是长期读数，与待回报是两件事");

        // 主动的那一条：进待回报。
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_RECALL_TOOL, true);
        assertEquals(1, m.receipts().pendingCount(), "AI 主动召回的要进待回报");
        assertEquals(3, m.receipts().presentedCount(id));
    }

    /**
     * 老调用点（4 参 recall）默认按「自动」处理。
     *
     * <p>方向刻意保守：不知道是不是自动的，就当自动的 ⇒ 最多漏报待回报，
     * 不会凭空造出一堆待回报。</p>
     */
    @Test
    void theLegacyFourArgRecallIsTreatedAsAutomatic() {
        ExperienceMemory m = memory();
        m.learn(entry("熔岩湖挖矿先铺水"));
        m.recall("熔岩湖", 5, null, List.of());
        assertEquals(0, m.receipts().pendingCount(), "老签名不许默认进待回报（保守方向）");
        assertEquals(1, m.receipts().presentedCount(
                m.all().get(0).id()), "但仍要留痕");
    }

    // ---- 2. 回报之后清单要真的清掉 ----

    @Test
    void recordingEvidenceClearsItFromThePendingList() {
        ExperienceMemory m = memory();
        String id = m.learn(entry("熔岩湖挖矿先铺水")).id();
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_RECALL_TOOL, true);
        assertEquals(1, m.pendingReports().size());

        assertNotNull(m.recordEvidence(id, true, "铺了水没掉下去"), "记一次成功");
        assertEquals(0, m.pendingCount(), "★ 回报过就不该再催 AI 回报第二次");
        assertEquals(1, m.receipts().presentedCount(id), "清的是「待回报」，不是「被用过」");
    }

    /**
     * ⚠️ 打不中的 id 也要清。
     *
     * <p>否则 AI 会一遍遍看到同一条根本不存在的 id、反复来问同一个问题 ——
     * 而每问一次得到的是 {@code "no experience with id"}。</p>
     */
    @Test
    void evidenceOnAnUnknownIdStillClearsThePendingRow() {
        ExperienceMemory m = memory();
        ExperienceEntry e = entry("熔岩湖挖矿先铺水");
        m.learn(e);
        m.receipts().record(hits(List.of(e)), PresentationReceipt.SURFACE_RECALL_TOOL, true);
        assertEquals(1, m.receipts().pendingCount());

        assertNull(m.recordEvidence(e.id() + "-已过期", false, "乱写的 id"),
                "打不中就是 null（如实报，不假装成功）");
        assertEquals(1, m.receipts().pendingCount(),
                "★ 存在的那条不能因为另一次失败回报被误清 —— 这次记的是另一个 id");
    }

    // ---- 3. 清单满了要拒收并说出来，不能静默丢 ----

    /**
     * ★ 静默丢是最坏的一种：AI 回报了一条「系统从没收到过」的 id，
     * 回来只得到 {@code "no experience with id"}，永远查不出原因。
     */
    @Test
    void thePendingListRefusesOverflowAndSaysSo() {
        ExperienceMemory m = memory();
        List<ExperienceEntry> many = new ArrayList<>();
        for (int i = 0; i < PresentationReceipt.MAX_PENDING + 5; i++) {
            many.add(m.learn(entry("挖矿要点" + i)));
        }
        PresentationReceipt receipts = m.receipts();
        receipts.record(hits(many), PresentationReceipt.SURFACE_RECALL_TOOL, true);

        assertEquals(PresentationReceipt.MAX_PENDING, receipts.pendingCount(),
                "★ 待回报清单有硬上限，不是无界增长");
        assertEquals(5, receipts.readout().get("pending_rejected_over_limit"),
                "★ 被拒的必须计数报出来，不静默丢");
    }

    // ---- 4. 反证：没被呈现过的经验不许假装被用过 ----

    /**
     * ★ 反证测试。回流读数最大的风险是「为了让面板好看而假装有连接」——
     * 本条确保「被用过」只能由真实的 {@code recall} 产生。
     */
    @Test
    void evidenceRecordedWithoutAnyRecallIsReportedAsNeverPresented() {
        ExperienceMemory m = memory();
        String id = m.learn(entry("熔岩湖挖矿先铺水")).id();

        m.recordEvidence(id, true, "没人召回过，纯自测");

        assertEquals(0, m.receipts().presentedCount(id), "没召回过 ⇒ 被呈现 0 次");
        assertNull(m.receipts().lastShown(id), "没召回过 ⇒ 没有呈现记录可查");
        assertEquals(1, m.stats().verified(),
                "★ 但记录本身是真的（合法的人工自测路径仍然工作）");
    }

    // ---- 5. 读数与生命周期 ----

    @Test
    void theReceiptIsSessionOnlyAndSaysSoOutLoud() {
        ExperienceMemory m = memory();
        m.learn(entry("熔岩湖挖矿先铺水"));
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_RECALL_TOOL, true);
        PresentationReceipt r = m.receipts();
        assertEquals(Boolean.TRUE, r.readout().get("session_only"),
                "★ 不落盘这件事必须自己说出来（跨重启的那次任务早就结束，回报无意义）");
        assertEquals(1, r.readout().get("tracked_ids"));
    }

    @Test
    void thePendingRowCarriesEnoughToBeActionable() {
        ExperienceMemory m = memory();
        ExperienceEntry e = entry("熔岩湖挖矿先铺水");
        m.learn(e);
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_RECALL_TOOL, true);

        PresentationReceipt.Shown s = m.pendingReports().get(0);
        assertEquals(e.id(), s.id());
        assertEquals("熔岩湖挖矿先铺水", s.title(), "★ 清单要能被人读，否则 AI 选不出该回报哪条");
        assertEquals(PresentationReceipt.SURFACE_RECALL_TOOL, s.surface());
        assertTrue(s.reportable());
        assertTrue(s.toMap().containsKey("id") && s.toMap().containsKey("title"),
                "回报清单要能直接序列化进工具返回值");
    }

    /**
     * ★ 实机抓到的 bug（2026-10-03 B13）：L0 目录每轮都重印一遍，它会把待回报清单里
     * 那一行的 surface 与时间戳<b>一起换掉</b>。两个后果都已在游戏里看到：
     * <ol>
     *   <li>行显示 {@code surface=directory_l0}，而真正让这条进入清单的是
     *       {@code recall_tool} —— 读的人会以为「这是被自动印出来的，不必回报」；</li>
     *   <li>更致命：配对锚点被推到最新的 L0 时间戳，于是「主动召回之后、那次任务
     *       收尾之前」这段窗口里的结果<b>永远配不上</b>，
     *       {@code OutcomeCorrelator} 的 {@code OUTCOME_KNOWN} 实际上够不着。</li>
     * </ol>
     * 单测抓不到它，因为老测试每条 id 只呈现过一次、只有一种 surface。
     */
    @Test
    void aPassiveRedrawDoesNotStealThePendingRowsSurfaceOrTimestamp() {
        ExperienceMemory m = memory();
        ExperienceEntry e = entry("熔岩湖挖矿先铺水");
        m.learn(e);
        m.recall("熔岩湖", 5, null, List.of(), PresentationReceipt.SURFACE_RECALL_TOOL, true);
        PresentationReceipt.Shown before = m.pendingReports().get(0);
        assertEquals(PresentationReceipt.SURFACE_RECALL_TOOL, before.surface());

        // L0 目录下一轮又把它印了一遍（非回报性呈现）
        m.recall("", 6, null, List.of(), PresentationReceipt.SURFACE_DIRECTORY, false);

        PresentationReceipt.Shown after = m.pendingReports().get(0);
        assertEquals(PresentationReceipt.SURFACE_RECALL_TOOL, after.surface(),
                "★ 待回报这一行必须记住「是主动要来的」，不能被自动重印改写成被动呈现");
        assertTrue(after.reportable(), "被动重印不该把一条待回报变成不可回报");
        assertEquals(before.atMillis(), after.atMillis(),
                "★ 时间戳被刷新 ⇒ 主动召回与任务收尾之间的那段窗口配不上，OUTCOME_KNOWN 永远出不来");
        assertEquals(2, m.receipts().presentedCount(e.id()),
                "「被呈现过几次」照旧要算全部 surface，只有待回报那一行走 reportable 那次");
        assertEquals(PresentationReceipt.SURFACE_DIRECTORY, m.receipts().lastShown(e.id()).surface(),
                "最近一次呈现是 L0 —— 这是 lastShown 的语义，与待回报清单互不干扰");
    }

    /** 空库时清单必须是空列表而不是 null（工具侧直接遍历它）。 */
    @Test
    void anEmptyReceiptHasAnEmptyPendingListNotNull() {
        ExperienceMemory m = memory();
        assertEquals(List.of(), m.pendingReports());
        assertEquals(0, m.receipts().pendingCount());
        assertNull(m.receipts().lastShown("不存在的 id"), "没呈现过就没有呈现记录");
    }
}