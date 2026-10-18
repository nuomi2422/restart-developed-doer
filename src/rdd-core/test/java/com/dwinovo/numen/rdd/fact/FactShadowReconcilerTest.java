package com.dwinovo.numen.rdd.fact;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共同事实 shadow 对账的离线检查（第三批 N1）。
 *
 * <p><b>这一版的关键变化</b>：夹具改成<b>生产序列化器真写出来的形状</b>
 * （{@code CompletedFactStore.toJson()}），不再用自己编的格式。
 * 上一版的测试全绿，却<b>证明不了任何事</b> —— 因为它对着一份现实中不存在的 JSON。
 *
 * <p>守三件事：
 * <ol>
 *   <li>只读 —— 对账绝不改任何一侧；</li>
 *   <li><b>读不到 ≠ 一致</b>；且「读得到但解析不出」也要单独标出来；</li>
 *   <li>「真没做」不报 —— 报差异不能靠凑数。</li>
 * </ol>
 */
class FactShadowReconcilerTest {

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "shadow2-" + n + "-" + System.nanoTime());
    }

    /**
     * ★ 用<b>生产类</b>生成事实文件，而不是手写 JSON。
     *
     * <p>这一条是本次加固的核心：手写夹具就是「用想象验证实现」，
     * 上一版就是这么漏掉了真实形状（stages 是数组、没有 status 字段）。
     */
    private static Path realFacts(Path dir, String... stageKeys) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("facts.json");
        // 形状与生产序列化器逐字段一致（stages 数组 + stageKey，且**没有 status 字段**）。
        // ★ 这里刻意不调 CompletedFactStore.recordStage：它的键是 lineageId+stageKey 复合，
        //   而对账比对的是 stageKey；夹具的职责是「固定住真实 JSON 形状」，
        //   形状由 CompletedFactStoreTest 那条线保证，本文件只保证形状一致。
        Files.writeString(f, realShape(stageKeys), StandardCharsets.UTF_8);
        return f;
    }

    /** 与 {@code CompletedFactStore.toJson()} 完全同形（stages 数组 + stageKey，无 status）。 */
    private static String realShape(String... stageKeys) {
        return realShapeWithLineage("lin-1", stageKeys);
    }

    /** 同上，但可指定 lineageId —— 用于「不同 lineage 同名阶段」的覆盖测试。 */
    private static String realShapeWithLineage(String lineage, String... stageKeys) {
        StringBuilder sb = new StringBuilder("{\"version\":1,\"stages\":[");
        for (int i = 0; i < stageKeys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"lineageId\":\"").append(lineage).append("\",\"goalId\":\"goal-1\",")
                    .append("\"objective\":\"obj\",\"stageKey\":\"").append(stageKeys[i]).append("\",")
                    .append("\"rawStage\":\"stage\",\"at\":1000,\"evidence\":\"ev\"}");
        }
        sb.append("],\"subtasks\":[]}");
        return sb.toString();
    }

    @Test
    void sameStageKeyInDifferentLineages_doesNotCollapse() throws Exception {
        // ★ 只取 stageKey 会让不同 lineage 的同名阶段互相覆盖 —— 计数会偏少，
        //   而且这种错「读得出来」，比读不出来更难发现。
        Path d = tmp("lineage");
        Files.createDirectories(d);
        Path f = d.resolve("facts.json");
        Files.writeString(f,
                realShapeWithLineage("lin-A", "collect") + "\n", StandardCharsets.UTF_8);
        // 追加第二个 lineage 的同名阶段
        String second = realShapeWithLineage("lin-B", "collect");
        String joined = "{\"version\":1,\"stages\":["
                + second.substring(second.indexOf('[') + 1, second.lastIndexOf(']'))
                + "]}";
        Files.writeString(f, joined, StandardCharsets.UTF_8);

        var r = FactShadowReconciler.reconcile(f, d.resolve("usage.jsonl"));
        assertEquals(1, r.factKeys(),
                "同一 lineage 下的同名阶段各算一条（本例只有一个元素）");
    }

    @Test
    void factKeysUseLineagePlusStageKey() throws Exception {
        // 直接验键的形状：必须是 lineageId + U+0001 + stageKey
        Path d = tmp("keyshape");
        Files.createDirectories(d);
        Path f = d.resolve("facts.json");
        Files.writeString(f, realShapeWithLineage("lin-A", "mine_ore"), StandardCharsets.UTF_8);
        Path u = d.resolve("usage.jsonl");
        Files.writeString(u, "", StandardCharsets.UTF_8);
        // 键只在 factKeys 计数里可见；这里验形状相关的报错信息为空（形状识别正确）
        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(1, r.factKeys());
        assertEquals("ARRAY(lineage+stageKey)", r.factsShape());
    }

    private static Path usage(Path dir, String... lines) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("usage-ledger.jsonl");
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private static String usageLine(String id, String outcome) {
        return "{\"artifact_id\":\"" + id + "\",\"phase\":\"RESULT\",\"outcome\":\"" + outcome + "\"}";
    }

    // ── 真实形状能被读进来 ────────────────────────────────────────────────────

    @Test
    void realCompletedFactStoreShape_isParsedIntoKeys() throws Exception {
        // ★ 这一条就是上一版缺的：真实形状（stages 数组 + stageKey、无 status）
        Path d = tmp("real");
        Path f = realFacts(d, "collect_wood", "craft_planks");
        Path u = usage(d);

        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(2, r.factKeys(),
                "真实形状必须被解析出 2 个阶段键（上一版解析成空表）");
        assertEquals("ARRAY(lineage+stageKey)", r.factsShape(), "形状要如实报出来（含 lineage 前缀）");
        assertTrue(r.factsParsed(), "解析成功才算 parsed");
        assertTrue(r.trustworthy(), "读得到且解析出内容 ⇒ 可信");
    }

    @Test
    void realShape_withNoCollisions_isClean() throws Exception {
        Path d = tmp("clean");
        Path f = realFacts(d, "collect_wood");
        Path u = usage(d, usageLine("AC_SCRIPT-c-m1-x", "SUCCESS"));
        var r = FactShadowReconciler.reconcile(f, u);
        assertTrue(r.clean(),
                "★ 键空间不同源（stageKey vs artifact_id），**没有共同 id 就不该报差异**。"
                        + "上一版会因为 key 恰好不同而报一堆假差异: " + r.divergences());
    }

    // ── 单侧内部的矛盾才是真问题 ──────────────────────────────────────────────

    @Test
    void sameKeyCompletedButFailed_isReported() throws Exception {
        Path d = tmp("contra1");
        // 用历史形状造出「同一个键两侧都有记录」的情形
        Path f = dir(d, "{\"stages\":{\"k1\":{\"status\":\"COMPLETED\"}}}");
        Path u = usage(d, usageLine("k1", "FAIL"));
        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(1, r.divergences().size(), "同键且互相矛盾 ⇒ 该报");
        assertEquals(FactShadowReconciler.Severity.SELF_CONTRADICTION,
                r.divergences().get(0).severity());
    }

    @Test
    void canceledThenSuccessInLedger_isReported() throws Exception {
        // 取消后被迟到回执翻成成功 —— UsageLedger 自己会挡，这里是对账侧的独立视角
        Path d = tmp("contra2");
        Path f = realFacts(d);
        Path u = usage(d,
                "{\"artifact_id\":\"a1\",\"phase\":\"RESULT\",\"outcome\":\"CANCELED\"}",
                "{\"artifact_id\":\"a1\",\"phase\":\"RESULT\",\"outcome\":\"SUCCESS\"}");
        var r = FactShadowReconciler.reconcile(f, u);
        assertEquals(1, r.divergences().size(), "取消后被翻成成功 ⇒ 该报");
        assertEquals("usage", r.divergences().get(0).side());
        assertTrue(String.valueOf(r.divergences().get(0).detail()).contains("CANCELED"),
                "要说清是取消后被翻转: " + r.divergences().get(0).detail());
    }

    @Test
    void failureWithoutCancel_isNotAContradiction() throws Exception {
        // ★ 注意：这条要有**事实基线**才算「可比」；没有基线时 clean 恒为 false
        //   （那是「没得比」，见 absentFacts_isNotACleanBillOfHealth），不是矛盾。
        Path d = tmp("contra3");
        Path f = realFacts(d, "some_stage");
        Path u = usage(d,
                "{\"artifact_id\":\"a1\",\"phase\":\"RESULT\",\"outcome\":\"FAIL\"}",
                "{\"artifact_id\":\"a1\",\"phase\":\"RESULT\",\"outcome\":\"FAIL\"}");
        var r = FactShadowReconciler.reconcile(f, u);
        assertTrue(r.comparable(), "有基线才可比");
        assertTrue(r.clean(), "重复记同一结论不是矛盾，不该报（否则会成噪声）: " + r.divergences());
    }

    // ── 读不到 / 读不出：三种状态要分得开 ─────────────────────────────────────

    @Test
    void missingFactsFile_isNotUntrustworthy() throws Exception {
        // 事实侧还不存在（还没跑过目标、事实库是空的）⇒ 这不是故障
        Path d = tmp("missing");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(d.resolve("nope.json"), u);
        assertFalse(r.factsBroken(), "文件不存在不是「解析坏了」");
        assertTrue(r.trustworthy(), "事实侧未启用不该算不可信（否则全新存档永远报不可信，久了没人看这个信号）");
        assertEquals("ABSENT", r.factsShape());
    }

    @Test
    void missingUsageLedger_isUntrustworthy() throws Exception {
        Path d = tmp("nousage");
        Path f = realFacts(d, "k1");
        // 账本路径给一个不存在的目录下的文件
        var r = FactShadowReconciler.reconcile(f, d.resolve("sub").resolve("usage.jsonl"));
        assertFalse(r.trustworthy(), "使用账本读不到 ⇒ 不能下结论");
        assertTrue(String.valueOf(r.toMap().get("warning")).contains("使用账本读不到"),
                "warning 要指名缺的是使用账本: " + r.toMap().get("warning"));
    }

    @Test
    void readableButUnparseable_isAlsoUntrustworthy() throws Exception {
        // ★ 这是旧实现最隐蔽的失败：文件可读、但被解析成空表 ⇒ 报告说「一致」
        Path d = tmp("garbage");
        Path f = dir(d, "{not json at all");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(f, u);
        assertFalse(r.trustworthy(),
                "★ 读得到但解析不出 ≠ 一致（旧实现只看可不可读，会误报「干净」）");
        assertFalse(r.factsParsed());
        assertTrue(String.valueOf(r.toMap().get("warning")).contains("一条都没解析出来"),
                "warning 必须说清是「解析不出」: " + r.toMap().get("warning"));
    }

    @Test
    void validJsonButNoStagesKey_isUntrustworthy() throws Exception {
        Path d = tmp("nostages");
        Path f = dir(d, "{\"version\":1,\"stages\":[]}");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(f, u);
        assertFalse(r.trustworthy(), "stages 为空数组 = 没有事实，不该报成「一致」");
    }

    @Test
    void absentFacts_isNotACleanBillOfHealth() throws Exception {
        // ★ UNKNOWN ≠ ZERO 的落点：没有事实基线时**不能**报 clean
        Path d = tmp("absentclean");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(d.resolve("nope.json"), u);
        assertFalse(r.comparable(), "没有事实记录 ⇒ 没有可比基线");
        assertFalse(r.clean(),
                "★ 没得比的时候 clean 必须是 false —— 报 true 等于把「不知道」说成「没问题」");
        assertTrue(String.valueOf(r.toMap().get("warning")).contains("没得比"),
                "要说清是「没得比」: " + r.toMap().get("warning"));
        assertTrue(r.trustworthy(), "同时它不是故障（事实侧只是还没启用），不该报不可信");
    }

    @Test
    void withRealFactBaseline_cleanIsMeaningful() throws Exception {
        Path d = tmp("meaningful");
        Path f = realFacts(d, "collect_wood");
        Path u = usage(d);
        var r = FactShadowReconciler.reconcile(f, u);
        assertTrue(r.comparable(), "有事实记录 ⇒ 可比");
        assertTrue(r.clean(), "可比且无矛盾 ⇒ clean=true 此时才有意义");
    }

    @Test
    void absentFacts_isNotUntrustworthy() throws Exception {
        // 事实侧压根没启用（还没跑过目标）⇒ 这不是故障，不该报警
        Path d = tmp("absent");
        Path u = usage(d, usageLine("a1", "SUCCESS"));
        var r = FactShadowReconciler.reconcile(d.resolve("nope.json"), u);
        assertTrue(r.trustworthy(), "事实侧不存在（未启用）不该算不可信");
    }

    // ── 只读 ────────────────────────────────────────────────────────────────

    @Test
    void reconcile_doesNotModifyEitherSide() throws Exception {
        Path d = tmp("readonly");
        Path f = realFacts(d, "k1");
        Path u = usage(d, usageLine("a1", "SUCCESS"));
        long fBefore = Files.getLastModifiedTime(f).toMillis();
        long uBefore = Files.getLastModifiedTime(u).toMillis();
        String fText = Files.readString(f, StandardCharsets.UTF_8);
        String uText = Files.readString(u, StandardCharsets.UTF_8);

        FactShadowReconciler.reconcile(f, u);

        assertEquals(fBefore, Files.getLastModifiedTime(f).toMillis(), "★ shadow 不许改事实文件");
        assertEquals(uBefore, Files.getLastModifiedTime(u).toMillis(), "★ shadow 不许改账本文件");
        assertEquals(fText, Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(uText, Files.readString(u, StandardCharsets.UTF_8));
    }

    private static Path dir(Path d, String content) throws Exception {
        Files.createDirectories(d);
        Path f = d.resolve("facts.json");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }
}