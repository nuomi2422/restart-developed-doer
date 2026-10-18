package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACX 真实执行结果回灌的离线检查（B1，2026-10-05）。
 *
 * <p>守四件事：
 * <ol>
 *   <li><b>只回流自己产出的</b> —— 别人的 AC 运行不进学习者账本；</li>
 *   <li><b>幂等</b> —— 同一个 run 反射两次不灌成流水账；</li>
 *   <li><b>进行中不记 RESULT</b> —— 半截结论进 append-only 账本事后收不回；</li>
 *   <li><b>可追溯</b> —— 每条账都能追到 run_id、失败步骤与错误。</li>
 * </ol>
 */
class AcxExecutionReflectorTest {

    private static final UUID C = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "refl-" + n + "-" + System.nanoTime());
    }

    /** 造一个投递箱：一条已采纳（带 consumer_ref）、一条被拒。 */
    private static ArtifactOutbox outboxWith(Path dir) {
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        ArtifactOutbox.Delivery ok = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "rev-1", "m-1",
                "low_hp_flee", "{\"name\":\"low_hp_flee\"}");
        ob.mark(ok.artifactId(), ArtifactOutbox.Status.ADOPTED, "已进版本库", "low_hp_flee@1");
        return ob;
    }

    /**
     * 造一份<b>监测台</b> acx.jsonl（活的真源）。
     *
     * <p>★ 格式必须与实机一致：外层 {@code {data:{kind:"RUN_FINISHED", run_id, ac_name, status}}}，
     * 且<b>不带 ac_version</b>。用错格式就会造出一个「测过了其实没测」的假绿。
     */
    private static Path records(Path dir, String... lines) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("acx.jsonl");
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private static String run(String id, String name, String ver, String status, String failedStep) {
        return "{\"schema_version\":1,\"source\":\"numen\",\"category\":\"acx\",\"type\":\"acx_event\","
                + "\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"" + id + "\",\"ac_name\":\"" + name
                + "\",\"ac_version\":\"" + ver + "\",\"status\":\"" + status + "\",\"failed_step\":"
                + failedStep + ",\"error_message\":\"boom\"}}";
    }

    /** 中间步骤事件：必须被忽略（拿它当结论＝把半截当终局）。 */
    private static String stepEvent(String id, String name, String stepId) {
        return "{\"schema_version\":1,\"category\":\"acx\",\"type\":\"acx_event\","
                + "\"data\":{\"kind\":\"STEP_SUCCEEDED\",\"run_id\":\"" + id + "\",\"ac_name\":\"" + name
                + "\",\"step_id\":\"" + stepId + "\",\"status\":\"SUCCESS\"}}";
    }

    @Test
    void successRun_isReflectedAsResultSuccess() throws Exception {
        Path d = tmp("ok");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r1", "low_hp_flee", "1", "SUCCESS", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(1, rep.reflected(), "应反射 1 条: " + rep.toMap());

        UsageLedger.Entry e = ul.latestOf(ob.list(ArtifactOutbox.Kind.AC_SCRIPT).get(0).get("artifact_id").getAsString());
        assertEquals(UsageLedger.Phase.RESULT, e.phase());
        assertEquals(UsageLedger.Outcome.SUCCESS, e.outcome());
        assertTrue(e.source().startsWith(AcxExecutionReflector.SOURCE_PREFIX),
                "source 必须带 run 前缀（幂等与追溯都靠它）: " + e.source());
        assertTrue(e.detail().contains("r1"), "detail 要能追到 run_id: " + e.detail());
    }

    @Test
    void failRun_carriesFailedStepAndError() throws Exception {
        Path d = tmp("fail");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r2", "low_hp_flee", "1", "FAIL", "\"step_retreat\""));

        new AcxExecutionReflector(ob, ul).reflect(rec, true);
        String artifactId = ob.list(ArtifactOutbox.Kind.AC_SCRIPT).get(0).get("artifact_id").getAsString();
        UsageLedger.Entry e = ul.latestOf(artifactId);
        assertEquals(UsageLedger.Outcome.FAIL, e.outcome());
        assertTrue(e.detail().contains("step_retreat"),
                "★ 失败步骤必须带回来 —— 没有它就不知道该改哪: " + e.detail());
        assertTrue(e.detail().contains("boom"), "错误信息也要带: " + e.detail());
    }

    @Test
    void otherPeoplesRuns_areNotReflected() throws Exception {
        Path d = tmp("other");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        // 这条运行的是别人的脚本（不在我们 outbox 的已采纳产物里）
        Path rec = records(d,
                run("r3", "someone_elses_script", "1", "SUCCESS", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(0, rep.reflected(),
                "★ 不是我们产物的运行绝不许进我们的账本（那是别人的学习材料）");
        assertEquals(1, rep.skippedUnmatched());
        assertTrue(ul.all().isEmpty(), "账本应当一条都没写: " + ul.all().size());
    }

    @Test
    void twoAdoptedVersionsOfSameName_isReportedAsAmbiguous_notGuessed() throws Exception {
        // ★ 监测台不带版本号 ⇒ 同名多版本时**不许挑一个**。猜错一次就把别人的成败记到自己头上。
        Path d = tmp("ambig");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        ArtifactOutbox.Delivery a1 = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "dual", "{\"name\":\"dual\"}");
        ob.mark(a1.artifactId(), ArtifactOutbox.Status.ADOPTED, "v1", "dual@1");
        ArtifactOutbox.Delivery a2 = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-2",
                "dual", "{\"name\":\"dual\"}");
        ob.mark(a2.artifactId(), ArtifactOutbox.Status.ADOPTED, "v2", "dual@2");
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("rA", "dual", "1", "SUCCESS", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(1, rep.ambiguous(), "同名两版本 ⇒ 歧义");
        assertEquals(0, rep.reflected(), "★ 歧义时跳过，不猜");
        assertTrue(String.valueOf(rep.notes()).contains("歧义") || String.valueOf(rep.notes()).contains("无法判定"),
                "歧义要说出来: " + rep.notes());
        assertTrue(ul.all().isEmpty(), "歧义时账本不该被写");
    }

    /**
     * ★ P0 回归（2026-10-06）：两条已采纳产物拿到<b>同一个 {@code consumer_ref}</b> 时，
     * 必须报歧义 —— 不许因为「按 ref 折成 Map」而让后者覆盖前者。
     *
     * <p>原实现的 {@code Map<ref, artifact_id>} 会让同 ref 的候选只剩 1 个，
     * 于是上面那条 {@code candidates.size() > 1} 的歧义守卫<b>永远进不去</b> ⇒
     * <b>别人的 AC 运行成败被静默记到自己账本上</b>，而报告一切正常。
     *
     * <p>这条路在生产里可达（三步都在代码里）：{@code AcxLoader} 对缺失的
     * {@code version} 一律落到 {@code "1"}；{@code FileAcxLibrary.publish} 的
     * {@code versions.put(version, v)} 无重载保护 ⇒ 两次同名草稿拿到同一个 {@code "X@1"}。
     */
    @Test
    void twoAdoptedArtifactsWithSameRef_areReportedAsAmbiguous_notSilentlyOverwritten() throws Exception {
        Path d = tmp("same-ref");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        ArtifactOutbox.Delivery a1 = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "dup", "{\"name\":\"dup\",\"round\":1}");
        ob.mark(a1.artifactId(), ArtifactOutbox.Status.ADOPTED, "第一次采纳", "dup@1");
        ArtifactOutbox.Delivery a2 = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-2",
                "dup", "{\"name\":\"dup\",\"round\":2}");
        ob.mark(a2.artifactId(), ArtifactOutbox.Status.ADOPTED, "第二次也被采纳", "dup@1");

        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("rDup", "dup", "1", "SUCCESS", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(1, rep.ambiguous(),
                "★ 同一个 ref 下的两条产物必须报歧义 —— 覆盖掉一条等于「猜了一个」: " + rep.toMap());
        assertEquals(0, rep.reflected(), "歧义时跳过，不猜");
        assertTrue(ul.all().isEmpty(), "歧义时账本不该被写: " + ul.all());
    }

    @Test
    void reflectIsIdempotent() throws Exception {
        Path d = tmp("idem");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r5", "low_hp_flee", "1", "SUCCESS", "null"));
        var reflector = new AcxExecutionReflector(ob, ul);

        assertEquals(1, reflector.reflect(rec, true).reflected());
        var second = reflector.reflect(rec, true);
        assertEquals(0, second.reflected(), "★ 同一个 run 第二次不该再写");
        assertEquals(1, second.alreadyKnown());
        assertEquals(1, ul.all().size(), "账本里只该有一条（append-only，重复灌就收不回了）");
    }

    @Test
    void runningOrPaused_doesNotBecomeResult() throws Exception {
        Path d = tmp("running");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d,
                run("r6", "low_hp_flee", "1", "PAUSED", "null"),
                run("r7", "low_hp_flee", "1", "RUNNING", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(2, rep.skippedNotFinal(), "两条都不是终态");
        String artifactId = ob.list(ArtifactOutbox.Kind.AC_SCRIPT).get(0).get("artifact_id").getAsString();
        for (UsageLedger.Entry e : ul.all()) {
            assertFalse(e.phase() == UsageLedger.Phase.RESULT,
                    "★ 进行中/暂停绝不能记成 RESULT —— append-only 账本事后收不回来");
            assertEquals(UsageLedger.Phase.EXECUTED, e.phase());
        }
        assertEquals(UsageLedger.Outcome.UNKNOWN, ul.latestOf(artifactId).outcome());
    }

    @Test
    void unknownStatus_recordsExecution_butNotResult_andExplains() throws Exception {
        Path d = tmp("weird");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r8", "low_hp_flee", "1", "SOMETHING_NEW", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(1, rep.reflected());
        assertTrue(String.valueOf(rep.notes()).contains("SOMETHING_NEW"),
                "看不懂的状态要**说出来**，不能默默当正常: " + rep.notes());
        assertEquals(UsageLedger.Phase.EXECUTED, ul.all().get(0).phase());
    }

    @Test
    void rejectedDraft_hasNoRef_soRunsNeverMatchIt() throws Exception {
        Path d = tmp("rejected");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        ArtifactOutbox.Delivery bad = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "bad_draft", "prose");
        ob.mark(bad.artifactId(), ArtifactOutbox.Status.REJECTED, "解析失败", "");
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r9", "bad_draft", "1", "SUCCESS", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(0, rep.reflected(), "被拒的草稿没有版本，不可能被执行");
    }

    @Test
    void stepEventsAreIgnored_onlyRunFinishedCounts() throws Exception {
        Path d = tmp("steps");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d,
                stepEvent("rX", "low_hp_flee", "read"),
                stepEvent("rX", "low_hp_flee", "retreat"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, true);
        assertEquals(0, rep.reflected(),
                "★ STEP_* 是中间步骤，拿它当结论＝把半截当终局");
        assertTrue(ul.all().isEmpty());
    }

    @Test
    void missingMonitorFile_saysNotThatNothingRan() throws Exception {
        Path d = tmp("nomon");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        var rep = new AcxExecutionReflector(ob, ul).reflect(d.resolve("nope.jsonl"), true);
        assertEquals(0, rep.reflected());
        assertTrue(String.valueOf(rep.notes()).contains("不是"),
                "★ 监测台读不到时必须说「无法判断有没有运行」: " + rep.notes());
    }

    @Test
    void unreadableOutbox_saysSoInsteadOfReportingClean() throws Exception {
        Path d = tmp("noread");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r10", "low_hp_flee", "1", "SUCCESS", "null"));

        var rep = new AcxExecutionReflector(ob, ul).reflect(rec, false);
        assertEquals(0, rep.reflected());
        assertTrue(String.valueOf(rep.notes()).contains("不是"),
                "读不到时必须明说「不是没有运行」: " + rep.notes());
    }

    @Test
    void reflectedEntryCarriesCompanionSoIsolationStillWorks() throws Exception {
        Path d = tmp("companion");
        ArtifactOutbox ob = outboxWith(d);
        UsageLedger ul = new UsageLedger(d);
        Path rec = records(d, run("r11", "low_hp_flee", "1", "SUCCESS", "null"));

        new AcxExecutionReflector(ob, ul).reflect(rec, true);
        UsageLedger.Entry e = ul.all().get(0);
        assertEquals(C.toString(), e.companionId(),
                "★ 回流条目必须带同伴 id，否则按同伴过滤时它对谁都不可见");
    }
}