package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「上轮产出回顾」的判据（架构 owner 2026-10-03 第 3 条 + 答 (a)）。
 *
 * <p><b>这条能力的全部意义</b>：owner 要的是「AI 自己在下一轮 {@code learner_review} 里
 * 看着上轮结果再改」。那么「<b>它到底看没看上轮</b>」就是这条能力成立与否的唯一判据 ——
 * 而在此之前，从外面<b>一个字都问不出来</b>（E2 那次「它到底有没有去找工具」只能人工翻日志）。
 */
class PriorRoundTest {

    // ---------- 测试用的日志行构造 ----------

    private static String reviewed(String reviewId, String verdictJson) {
        return "{\"schema_version\":1,\"event_id\":\"learner-1\",\"timestamp\":\"2026-10-03T00:00:00Z\","
                + "\"source\":\"numen\",\"category\":\"learner\",\"type\":\"reviewed\",\"data\":{"
                + "\"review_id\":\"" + reviewId + "\",\"companion\":\"c1\",\"reviewed\":1,"
                + "\"verdicts\":[" + verdictJson + "]}}";
    }

    private static String noted(String memoId) {
        return "{\"type\":\"noted\",\"data\":{\"memo_id\":\"" + memoId + "\"}}";
    }

    /** 一条「写了经验 + 门禁 REVIEW_REQUIRED」的判定。 */
    private static String verdictWithExperience(String title, String gateVerdict) {
        return "{\"memo_id\":\"m-1\",\"actions\":[\"WRITE_EXPERIENCE\"],"
                + "\"experience_draft\":{\"entry\":{\"title\":\"" + title + "\"},\"mapping\":{}},"
                + "\"quality_gate\":{\"verdict\":\"" + gateVerdict + "\"}}";
    }

    private static String verdictWithAc(String script) {
        return "{\"memo_id\":\"m-2\",\"actions\":[\"USE_AC\"],"
                + "\"ac_script_draft\":\"" + script + "\"}";
    }

    // ---------- 判据 ----------

    /**
     * ★ 核心：上轮写了什么，下一轮<b>看得见</b>。
     *
     * <p>钉的是「标题与产物文本都进了 prompt」。不是「日志里有」，不是「字段存在」——
     * 是<b>真的出现在给 LLM 的那段文本里</b>。</p>
     */
    @Test
    void theProductsOfTheLastRoundReachTheNextPrompt() {
        List<String> lines = new ArrayList<>();
        lines.add(reviewed("rev-1", verdictWithExperience("下界前先铺水", "REVIEW_REQUIRED")));
        lines.add(reviewed("rev-2", verdictWithAc("script mine_then_look()")));

        PriorRound.Summary s = PriorRound.fromLines(lines, 2, 200);
        assertEquals(2, s.rounds());
        assertEquals(2, s.products(), "两轮各一件产物");
        String block = s.promptBlock();
        assertTrue(block.contains("下界前先铺水"), "★ 经验标题必须在 prompt 里：" + block);
        assertTrue(block.contains("mine_then_look"), "★ AC 脚本必须在 prompt 里：" + block);
    }

    /**
     * ★ 反证：没有上轮时<b>也要给一句明确的话</b>，不是留空。
     *
     * <p>留空的话，复盘方分不清「这是第一轮」与「上轮日志读不出来」——
     * 而这两种要区别对待：前者是正常，后者是观测面坏了。</p>
     */
    @Test
    void theFirstRoundSaysSoInsteadOfLeavingItBlank() {
        PriorRound.Summary s = PriorRound.fromLines(List.of(), 2, 200);
        assertEquals(0, s.rounds());
        assertFalse(s.hasPriorRound());
        String block = s.promptBlock();
        assertFalse(block.isBlank(), "★ 不能留空");
        assertTrue(block.contains("没有上轮"), "要明说没有：" + block);
        assertTrue(block.contains("第一次复盘") || block.contains("不可读"),
                "还要说清是「没跑过」还是「读不到」：" + block);
    }

    /**
     * ★ 「没有上轮」与「读不到日志」必须<b>不是同一句话</b>。
     *
     * <p>这是全批最重要的一条纪律：「0」有三种含义（真没有 / 采样窗口没覆盖 / 上游断了读不到），
     * 第三种最危险 —— 显示「没有」会让人以为「它没写过」。</p>
     */
    @Test
    void unreadableLinesAreCountedRatherThanSilentlyTreatedAsNoPriorRound() {
        List<String> lines = new ArrayList<>();
        lines.add("{ this is not json");
        lines.add(reviewed("rev-1", verdictWithExperience("写过了", "PASS")));
        PriorRound.Summary s = PriorRound.fromLines(lines, 2, 200);
        assertEquals(1, s.rounds(), "读得出那一条就仍然算有上轮");
        assertEquals(1, s.skippedBadLines(), "★ 坏行要计数，不许当没发生");
        assertTrue(s.promptBlock().contains("行日志读不出来"),
                "prompt 里要如实说有几行读不出来：" + s.promptBlock());
    }

    /**
     * ★ 反证：门禁结论<b>不许</b>在缺的时候显示成「通过」。
     *
     * <p>没有 {@code quality_gate} 就是「没判」。写成 PASS 等于凭空替门禁说话，
     * 而读的人会拿它当依据。</p>
     */
    @Test
    void aMissingQualityGateIsReportedAsUnjudgedNeverAsPassed() {
        List<String> lines = List.of(reviewed("rev-1", verdictWithAc("draft x")));
        String block = PriorRound.fromLines(lines, 2, 200).promptBlock();
        assertTrue(block.contains("未判"), "没有门禁结论要说「未判」：" + block);
        assertFalse(block.contains("PASS"), "★ 缺门禁不许显示成通过：" + block);
    }

    /**
     * 只回顾最近几轮 —— 目录里翻出五轮前的东西改它，多半不是 owner 说的「改到满意」。
     */
    @Test
    void onlyTheMostRecentRoundsAreLookedBack() {
        List<String> lines = new ArrayList<>();
        lines.add(reviewed("rev-1", verdictWithExperience("很早的一条", "PASS")));
        lines.add(reviewed("rev-2", verdictWithExperience("上一轮那条", "PASS")));
        lines.add(reviewed("rev-3", verdictWithExperience("最新那条", "PASS")));

        PriorRound.Summary s = PriorRound.fromLines(lines, 2, 200);
        assertEquals(2, s.rounds());
        String block = s.promptBlock();
        assertTrue(block.contains("最新那条"), "最新一轮在");
        assertTrue(block.contains("上一轮那条"), "次新也在");
        assertFalse(block.contains("很早的一条"), "★ 更早的应该被漏掉（按轮次上限，不是按行数）");
    }

    /**
     * ★ 一份 AC 脚本可能有几百行，整份塞进 prompt 会把本轮的备忘录挤出去。
     * 截断必须在行文本里<b>看得见</b>（带省略号），不许让读的人以为那就是全部。
     */
    @Test
    void aHugeProductIsClippedAndTheClipIsVisible() {
        String big = "x".repeat(5000);
        List<String> lines = List.of(reviewed("rev-1", verdictWithAc(big)));
        PriorRound.Summary s = PriorRound.fromLines(lines, 2, 50);
        String block = s.promptBlock();
        assertTrue(block.contains("…"), "★ 截断了要看得见：" + block.substring(0, Math.min(120, block.length())));
        // 判「没被整份塞进去」要看长度，不能看「有没有出现过某个子串」——
        // 我第一版写成 assertFalse(block.contains("xxxxxx"))，而截断到 50 字符后
        // 仍然含 6 个连续 x ⇒ 断言自己错了，实现是对的。
        int run = 0;
        int longest = 0;
        for (int i = 0; i < block.length(); i++) {
            run = block.charAt(i) == 'x' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        assertTrue(longest <= 60,
                "★ 5000 字的脚本不许整份进 prompt（最长连续 x=" + longest + "）：" + block.length() + " 字符");
    }

    /**
     * ★ 这条能力的诚实边界：它给的是「<b>写了什么</b>」，<b>不是</b>「后来成没成」。
     *
     * <p>所以 prompt 里必须有一句提醒，否则复盘方会照着「上轮这么写的」当成
     * 「上轮这么成了」—— 那正是 owner 第 3 条最不想要的（「凭字面承诺做判断」）。</p>
     */
    @Test
    void thePromptSaysTheseAreNotResultsOnlyDrafts() {
        List<String> lines = List.of(reviewed("rev-1", verdictWithExperience("那条", "PASS")));
        String block = PriorRound.fromLines(lines, 2, 200).promptBlock();
        assertTrue(block.contains("没有") && block.contains("成没成"),
                "★ 要明说这不是结果，只是产出：" + block);
        assertTrue(block.contains("不要"), "还要说清不要凭它做判断");
    }

    /** 一条判定同时产出多样时，产物数要按「件」算而不是按「条」算。 */
    @Test
    void oneVerdictWithSeveralProductsCountsEachOfThem() {
        String v = "{\"memo_id\":\"m-1\",\"actions\":[\"WRITE_EXPERIENCE\",\"USE_AC\",\"SELF_COMPILE\"],"
                + "\"experience_draft\":{\"entry\":{\"title\":\"挖矿\"},\"mapping\":{}},"
                + "\"ac_script_draft\":\"a\",\"self_compile_request\":\"b\"}";
        PriorRound.Summary s = PriorRound.fromLines(List.of(reviewed("rev-1", v)), 2, 200);
        assertEquals(1, s.rounds(), "还是一轮");
        assertEquals(3, s.products(), "★ 一轮里三件产物，不能算成一件");
    }

    /** 空行与非对象行要跳过，不许把它们算成「坏行」（那是不同的事）。 */
    @Test
    void blankLinesAreSkippedRatherThanCountedAsBad() {
        List<String> lines = List.of("", "   ", "[1,2,3]", reviewed("rev-1", verdictWithAc("x")));
        PriorRound.Summary s = PriorRound.fromLines(lines, 2, 200);
        assertEquals(1, s.rounds());
        assertEquals(1, s.skippedBadLines(), "只有那个 JSON 数组算坏行，空行不算");
    }

    // ---------- ★ prompt 拼装：这条能力的唯一判据 ----------

    /**
     * ★★★ 本批最该有的一条测试。
     *
     * <p>上面九条测的都是 {@code PriorRound} 这个纯类的行为；
     * 而「上轮到底有没有<b>真的进 prompt</b>」是 owner 第 3 条能不能成立的<b>唯一</b>判据。
     *
     * <p><b>我第一版全是假绿</b>：把 {@code LearnerReviewer} 里那句
     * {@code if (prior != null)} 改成 {@code if (false && …)}（= 让上轮永远不出现），
     * 十条测试<b>一条没红</b>、{@code MUT_EXIT=0}。原因：那些测试只读
     * {@code Summary.promptBlock()}，<b>从没走过真正拼 prompt 的那段代码</b>。
     *
     * <p>⇒ 所以把拼装下沉到 core（纯 JVM、测得到），并用本条钉住。</p>
     */
    @Test
    void thePriorRoundActuallyReachesThePromptThatGoesToTheModel() {
        List<String> lines = List.of(reviewed("rev-1", verdictWithExperience("上轮那条经验", "PASS")));
        PriorRound.Summary s = PriorRound.fromLines(lines, 2, 200);
        List<Memo> memos = List.of(new Memo("m-now", "本轮这个问题", "s", "t", "hp=10/20", 1L));

        String prompt = PriorRound.buildUserPrompt(memos, s);
        assertTrue(prompt.contains("上轮那条经验"),
                "★★ 上轮产物必须真的出现在发给模型的 prompt 里：" + prompt);
        assertTrue(prompt.contains("本轮这个问题"), "本轮备忘录也在");
        // ★ 顺序也是判据：上轮是本轮判断的参照系，放后面会被细节冲淡
        assertTrue(prompt.indexOf("上轮产出") < prompt.indexOf("本轮这个问题"),
                "★ 上轮回顾要排在本轮备忘录之前（它是参照系，不是附注）：\n" + prompt);
    }

    /**
     * 反证：<b>没有上轮</b>时，prompt 里也要有一句「没有上轮」。
     *
     * <p>钉的是「复盘方分得清『这是第一轮』与『消息漏了』」。</p>
     */
    @Test
    void theFirstRoundStillGetsAnExplicitLineInTheRealPrompt() {
        String prompt = PriorRound.buildUserPrompt(
                List.of(new Memo("m", "p", "s", "t", "hp=1/20", 1L)), null);
        assertTrue(prompt.contains("没有上轮"), "null prior 也要有明确说明：" + prompt);
        assertTrue(prompt.contains("复盘以下 1 条"), "本轮备忘录照常");
    }
}
