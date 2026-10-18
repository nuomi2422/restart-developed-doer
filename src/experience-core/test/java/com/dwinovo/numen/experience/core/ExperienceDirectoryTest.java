package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E6b：L0 目录层渲染（断点 B3）。
 *
 * <p><b>这个类存在的理由</b>：{@code <experience>} 原来只有
 * total/verified/generalized 三个数，<b>没有任何正文</b>。经验正文只能靠 AI 主动调
 * {@code experience_recall} —— 而 AI 不知道该查什么，于是这层等于不存在。
 * 目录层的意义是让 AI 先看见「我有什么经验」，看见之后才可能决定要不要展开。</p>
 *
 * <p><b>这一组测试钉的是「不骗人」的四条</b>：不伪造、不假装与当前任务相关、
 * 不超预算、不给假的可读数。</p>
 */
class ExperienceDirectoryTest {

    private static ExperienceEntry entry(String id, String title, ExperienceMaturity m,
                                        int priority, List<String> triggers) {
        return ExperienceEntry.builder()
                .id(id)
                .type(ExperienceType.FAILURE)
                .title(title)
                .description("d")
                .maturity(m)
                .priority(priority)
                .triggerStrings(triggers)
                .build();
    }

    private static List<ExperienceHit> hits(ExperienceEntry... entries) {
        return java.util.Arrays.stream(entries)
                .map(e -> new ExperienceHit(e, 1.0, List.of()))
                .toList();
    }

    private static final ExperienceStore.LoadStats STATS = new ExperienceStore.LoadStats(22, 0, 0, 5, 2);

    // ---------- 不伪造 ----------

    @Test
    void emptyStoreProducesNoBlockAtAll() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                List.of(), 0, 0, 0, STATS, 6, 700);
        assertTrue(b.empty(), "库里没有经验时不该注入任何目录");
        assertEquals("", b.text());
    }

    @Test
    void hitsButZeroTotalProducesNoBlock() {
        // total 与 hits 不自洽时以 total 为准：没有条数就别列目录
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("x|y", "y", ExperienceMaturity.OBSERVED, 50, List.of())),
                0, 0, 0, STATS, 6, 700);
        assertTrue(b.empty());
        assertEquals("", b.text());
    }

    // ---------- 三个原有计数原样保留（面板按它们读） ----------

    @Test
    void originalThreeCountsAreStillThere() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|先查后写", "先查后写", ExperienceMaturity.VERIFIED, 80, List.of("读操作"))),
                22, 3, 1, STATS, 6, 700);
        assertTrue(b.text().contains("<total>22</total>"), b.text());
        assertTrue(b.text().contains("<verified>3</verified>"), b.text());
        assertTrue(b.text().contains("<generalized>1</generalized>"), b.text());
    }

    // ---------- 可读数必须来自代码，不能是猜的 ----------

    @Test
    void readableCountsComeFromLoadStatsNotFromDiskShape() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|先查后写", "先查后写", ExperienceMaturity.VERIFIED, 80, List.of())),
                22, 3, 1, STATS, 6, 700);
        // STATS = (line 22, legacy 0, failed 0, rekeyed 5, duplicate 2) => total = 22 - 2 = 20
        assertTrue(b.text().contains("<readable>20</readable>"),
                "readable 必须是 LoadStats.total()（已扣重复），不是磁盘行数：" + b.text());
        assertTrue(b.text().contains("<rekeyed>5</rekeyed>"), b.text());
        assertTrue(b.text().contains("<duplicate>2</duplicate>"), b.text());
        assertTrue(b.text().contains("<degraded>false</degraded>"), b.text());
    }

    @Test
    void degradedStoreSaysSo() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|a", "a", ExperienceMaturity.OBSERVED, 50, List.of())),
                3, 0, 0, new ExperienceStore.LoadStats(3, 0, 2, 0, 0), 6, 700);
        assertTrue(b.text().contains("<degraded>true</degraded>"),
                "解析失败必须报出来，不能让人以为库里是好的：" + b.text());
    }

    @Test
    void statsAbsentMeansThoseElementsAreSimplyNotThere() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|a", "a", ExperienceMaturity.OBSERVED, 50, List.of())),
                1, 0, 0, null, 6, 700);
        assertFalse(b.text().contains("<readable>"), "没给 stats 就不该出现这些元素：" + b.text());
        assertFalse(b.text().contains("<rekeyed>"), b.text());
        // 但目录本身照常给
        assertTrue(b.text().contains("<n "), b.text());
    }

    // ---------- 不假装与当前任务相关 ----------

    @Test
    void blockSaysItIsNotTaskFiltered() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|先查后写", "先查后写", ExperienceMaturity.VERIFIED, 80, List.of())),
                1, 1, 0, STATS, 6, 700);
        assertTrue(b.text().contains("未按当前任务筛选"), b.text());
    }

    // ---------- 每行必须可操作（能拿 id 去查） ----------

    @Test
    void everyRowCarriesIdMaturityAndPriority() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|先查后写", "先查后写", ExperienceMaturity.GENERALIZED, 80, List.of())),
                1, 1, 1, STATS, 6, 700);
        ExperienceDirectory.Row row = b.rows().get(0);
        assertEquals("policy|先查后写", row.id());
        assertEquals("GENERALIZED", row.maturity());
        assertEquals(80, row.priority());
        assertTrue(b.text().contains("id=\"policy|先查后写\""), b.text());
    }

    @Test
    void triggerHintIsClippedAndCapped() {
        String longTrigger = "这是一条非常非常长的触发词用来测试截断行为会不会把预算吃光";
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("failure|a", "a", ExperienceMaturity.OBSERVED, 50,
                        List.of(longTrigger, "第二个", "第三个不该出现"))),
                1, 0, 0, STATS, 6, 700);
        ExperienceDirectory.Row row = b.rows().get(0);
        assertTrue(row.triggers().length() <= 14 * 2 + 3, "触发词线索必须有上限：" + row.triggers());
        assertFalse(row.triggers().contains("第三个"), "最多两条：" + row.triggers());
    }

    // ---------- 不超预算 ----------

    @Test
    void fewerCandidatesThanTotalStillCountsAsTruncated() {
        // ★ 实测洞的回归：调用方传进来的 hits 已经被 recall 的 maxRows 砍过一轮，
        // 22 条库里只给 6 条候选 —— 循环里看不到「还有更多」，但库里确实还有 16 条没列。
        // 2026-10-02 20:15 实机注入就是这样报出了 truncated=false（错的）。
        ExperienceEntry[] six = new ExperienceEntry[6];
        for (int i = 0; i < six.length; i++) {
            six[i] = entry("policy|条目" + i, "条目" + i, ExperienceMaturity.OBSERVED, 50, List.of());
        }
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(six), 22, 1, 0, STATS, 6, 700);
        assertEquals(6, b.rows().size());
        assertTrue(b.truncated(), "列 6 条而库里有 22 条 ⇒ 必须报 truncated");
        assertTrue(b.text().contains("<truncated>true</truncated>"), b.text());
        assertTrue(b.text().contains("<listed>6</listed>"), b.text());
        assertTrue(b.text().contains("<total>22</total>"), b.text());
    }

    @Test
    void allRowsListedIsNotReportedAsTruncated() {
        // 反面：候选数 == 库里条数且都列出来了 ⇒ 不能冤报 truncated
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("policy|a", "a", ExperienceMaturity.OBSERVED, 50, List.of()),
                        entry("policy|b", "b", ExperienceMaturity.OBSERVED, 50, List.of())),
                2, 0, 0, STATS, 6, 700);
        assertFalse(b.truncated(), "全部列出时不该报截断");
        assertTrue(b.text().contains("<truncated>false</truncated>"), b.text());
    }

    @Test
    void rowBudgetIsEnforcedAndTruncationIsReported() {
        ExperienceEntry[] many = new ExperienceEntry[20];
        for (int i = 0; i < many.length; i++) {
            many[i] = entry("policy|条目" + i, "条目" + i, ExperienceMaturity.OBSERVED, 50, List.of());
        }
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(many), 20, 0, 0, STATS, 4, 700);
        assertEquals(4, b.rows().size(), "行数预算必须被遵守");
        assertTrue(b.truncated(), "截断了就必须说 truncated，不能让人以为就这么多");
        assertTrue(b.text().contains("<truncated>true</truncated>"), b.text());
        assertTrue(b.text().contains("<listed>4</listed>"), b.text());
    }

    @Test
    void charBudgetIsEnforcedAndClosingTagStillFits() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            big.append("很长的标题内容用来把字符预算撑满第").append(i).append("条");
        }
        ExperienceEntry[] many = new ExperienceEntry[15];
        for (int i = 0; i < many.length; i++) {
            many[i] = entry("policy|条目" + i, big.toString() + i, ExperienceMaturity.OBSERVED, 50, List.of());
        }
        ExperienceDirectory.Block b = ExperienceDirectory.render(hits(many), 15, 0, 0, STATS, 100, 700);
        assertTrue(b.text().length() <= 700 + 100,
                "字符预算要真被遵守（含闭合标签）：" + b.text().length());
        assertTrue(b.text().endsWith("</experience>\n"),
                "★ 闭合标签必须完整 —— 缺了它整块内容在解析侧就是「消失」，且不报错");
        assertTrue(b.text().contains("</n>"), b.text());
    }

    @Test
    void nonPositiveBudgetFallsBackToDefaults() {
        ExperienceEntry[] many = new ExperienceEntry[20];
        for (int i = 0; i < many.length; i++) {
            many[i] = entry("policy|条目" + i, "条目" + i, ExperienceMaturity.OBSERVED, 50, List.of());
        }
        ExperienceDirectory.Block b = ExperienceDirectory.render(hits(many), 20, 0, 0, STATS, 0, 0);
        assertEquals(ExperienceDirectory.DEFAULT_MAX_ROWS, b.rows().size());
    }

    // ---------- 结构不被内容破坏 ----------

    @Test
    void angleBracketsAndAmpersandsInTitleAreEscaped() {
        // 未转义的 < 会让注入侧的字符串查找找错闭合标签 ⇒ 整块内容「消失」且不报错
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("failure|a<b>&c", "危险 <script> & \"引号\"", ExperienceMaturity.OBSERVED, 50, List.of())),
                1, 0, 0, STATS, 6, 700);
        assertFalse(b.text().contains("<script>"), "标题里的裸标签必须被转义：" + b.text());
        assertTrue(b.text().contains("&lt;"), b.text());
        assertTrue(b.text().contains("&amp;"), b.text());
        assertTrue(b.text().contains("&quot;"), b.text());
        // 行数没算错 ⇒ 结构完好
        assertEquals(1, b.rows().size());
        assertTrue(b.text().endsWith("</experience>\n"), b.text());
    }

    @Test
    void newlinesInTitleDoNotBreakOneRowPerLine() {
        ExperienceDirectory.Block b = ExperienceDirectory.render(
                hits(entry("failure|a", "第一行\n第二行\r第三行", ExperienceMaturity.OBSERVED, 50, List.of())),
                1, 0, 0, STATS, 6, 700);
        assertEquals(1, b.rows().size());
        String body = b.text();
        int n = body.indexOf("<n ");
        int close = body.indexOf("</n>");
        assertTrue(n > 0 && close > n);
        assertFalse(body.substring(n, close).contains("\n"),
                "一行目录不能被换行劈开，否则按行解析会把一条经验算成两条");
    }

    @Test
    void listedCountEqualsActualRows() {
        ExperienceEntry[] many = new ExperienceEntry[5];
        for (int i = 0; i < many.length; i++) {
            many[i] = entry("policy|条目" + i, "条目" + i, ExperienceMaturity.OBSERVED, 50, List.of());
        }
        ExperienceDirectory.Block b = ExperienceDirectory.render(hits(many), 5, 0, 0, STATS, 3, 700);
        assertTrue(b.text().contains("<listed>" + b.rows().size() + "</listed>"),
                "listed 必须是真列出的行数（面板要靠它核 A5）：" + b.text());
    }
}