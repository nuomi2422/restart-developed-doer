package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.plugins.learner.core.ArtifactOutbox.Kind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「判定 → 产物 → 执行结果」这条链的离线检查（B1 的另一半）。
 *
 * <p><b>这条链为什么关键</b>：两侧键空间本来不同源（事实侧 stageKey、
 * 使用侧 artifact_id）。但 {@code artifact_id} 里<b>含 memoId</b>，
 * 所以能按 memoId 把执行结果挂回具体判定 —— 挂上之后，
 * 「这条判定产出的脚本跑成没成」才成为可查询的事实，
 * 而不是散在账本里对不上号的记录。
 */
class UsageByMemoTest {

    private static final UUID C = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "ubm-" + n + "-" + System.nanoTime());
    }

    /** 走真实链路：outbox.submit 产出 artifactId → reflector 入账 → usageByMemo 反查。 */
    @Test
    void usageOfOneVerdict_isFoundByItsMemoId() throws Exception {
        Path d = tmp("chain");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        UsageLedger ul = new UsageLedger(d);

        // 一条判定产出 AC 草稿，被采纳，然后真跑了一次
        ArtifactOutbox.Delivery del = ob.submit(Kind.AC_SCRIPT, C, "rev-1", "m-100",
                "hp_guard", "{\"name\":\"hp_guard\"}");
        ob.mark(del.artifactId(), ArtifactOutbox.Status.ADOPTED, "ok", "hp_guard@1");

        Path mon = d.resolve("acx.jsonl");
        java.nio.file.Files.writeString(mon,
                "{\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"r1\",\"ac_name\":\"hp_guard\","
                        + "\"status\":\"SUCCESS\"}}\n", java.nio.charset.StandardCharsets.UTF_8);
        var rep = new AcxExecutionReflector(ob, ul).reflect(mon, true);
        assertEquals(1, rep.reflected(), "先确认反射链路通: " + rep.toMap());

        var rows = UsageLedger.usageByMemo(ul, "m-100", EnumSet.of(Kind.AC_SCRIPT));
        assertEquals(1, rows.size(), "★ 必须能按 memoId 找回这条判定的产物: " + rows.size());
        Map row = rows.get(0);
        assertEquals("RESULT", row.get("phase"));
        assertEquals("SUCCESS", row.get("outcome"));
        assertEquals("hp_guard", row.get("name"));
        assertEquals(del.artifactId(), row.get("artifact_id"));
        assertEquals(false, row.get("validity_claimed"),
                "★ 执行成功也不许自动算「有效」—— 时间先后不是因果");
    }

        @Test
    void otherVerdict_isNotMatched() throws Exception {
        Path d = tmp("other");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        UsageLedger ul = new UsageLedger(d);
        ArtifactOutbox.Delivery a = ob.submit(Kind.AC_SCRIPT, C, "rev-1", "m-A", "a_script", "x");
        ArtifactOutbox.Delivery b = ob.submit(Kind.AC_SCRIPT, C, "rev-1", "m-B", "b_script", "x");
        ul.append(a.artifactId(), "AC_SCRIPT", "a_script", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "acx", C.toString());
        ul.append(b.artifactId(), "AC_SCRIPT", "b_script", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.FAIL, "", "acx", C.toString());

        var rows = UsageLedger.usageByMemo(ul, "m-A", EnumSet.of(Kind.AC_SCRIPT));
        assertEquals(1, rows.size());
        assertEquals("a_script", rows.get(0).get("name"),
                "★ 必须精确命中自己的 memo，不能因为同前缀就带上别人的");
    }
        @Test
    void memoIdSubstringDoesNotLeakAcrossMemos() throws Exception {
        // m-1 与 m-10 前缀相近：裸子串包含很容易串
        Path d = tmp("prefix");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        UsageLedger ul = new UsageLedger(d);
        ArtifactOutbox.Delivery one = ob.submit(Kind.AC_SCRIPT, C, "rev-1", "m-1", "one", "x");
        ArtifactOutbox.Delivery ten = ob.submit(Kind.AC_SCRIPT, C, "rev-1", "m-10", "ten", "x");
        ul.append(one.artifactId(), "AC_SCRIPT", "one", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "acx", C.toString());
        ul.append(ten.artifactId(), "AC_SCRIPT", "ten", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "acx", C.toString());

        var rows1 = UsageLedger.usageByMemo(ul, "m-1", EnumSet.of(Kind.AC_SCRIPT));
        assertEquals(1, rows1.size());
        assertEquals("one", rows1.get(0).get("name"), "m-1 不该命中 m-10 的产物");
    }
    @Test
    void emptyOrMissingInputs_giveEmptyListNotError() {
        Path d = tmp("empty");
        UsageLedger ul = new UsageLedger(d);
        assertEquals(0, UsageLedger.usageByMemo(ul, "m-1", EnumSet.of(Kind.AC_SCRIPT)).size());
        assertEquals(0, UsageLedger.usageByMemo(ul, "", EnumSet.of(Kind.AC_SCRIPT)).size());
        assertEquals(0, UsageLedger.usageByMemo(ul, null, EnumSet.of(Kind.AC_SCRIPT)).size());
        assertEquals(0, UsageLedger.usageByMemo(null, "m-1", EnumSet.of(Kind.AC_SCRIPT)).size());
        assertEquals(0, UsageLedger.usageByMemo(ul, "m-1", EnumSet.noneOf(Kind.class)).size());
    }

        @Test
    void carrierArtifacts_areAlsoJoinable() throws Exception {
        Path d = tmp("carrier");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        UsageLedger ul = new UsageLedger(d);
        ArtifactOutbox.Delivery c1 = ob.submit(Kind.CARRIER, C, "rev-1", "m-C", "my_carrier", "{}");
        ul.append(c1.artifactId(), "CARRIER", "my_carrier", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "carrier", C.toString());
        var rows = UsageLedger.usageByMemo(ul, "m-C", EnumSet.of(Kind.CARRIER));
        assertEquals(1, rows.size());
        assertEquals("CARRIER", rows.get(0).get("kind"));
    }

    @Test
    void runCountCountsDistinctRunSources() throws Exception {
        Path d = tmp("runs");
        ArtifactOutbox ob = new ArtifactOutbox(d.resolve("outbox"));
        UsageLedger ul = new UsageLedger(d);
        ArtifactOutbox.Delivery del = ob.submit(Kind.AC_SCRIPT, C, "rev-1", "m-R",
                "hp_guard", "x");
        ob.mark(del.artifactId(), ArtifactOutbox.Status.ADOPTED, "ok", "hp_guard@1");
        java.nio.file.Files.writeString(d.resolve("acx.jsonl"),
                "{\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"r1\",\"ac_name\":\"hp_guard\",\"status\":\"SUCCESS\"}}\n"
              + "{\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"r2\",\"ac_name\":\"hp_guard\",\"status\":\"FAIL\"}}\n",
                java.nio.charset.StandardCharsets.UTF_8);
        var refl = new AcxExecutionReflector(ob, ul);
        assertEquals(2, refl.reflect(d.resolve("acx.jsonl"), true).reflected());
        // 再反射一次：幂等，不该把 run 计数灌大
        assertEquals(0, refl.reflect(d.resolve("acx.jsonl"), true).reflected());
        assertEquals(2, ul.runCountOf(del.artifactId()), "跑过两次就是 2，重复反射不加分");
    }

    @Test
    void verdictParse_doesNotLetLlmFillUsageOutcome() {
        // ★ usageOutcome 绝不能由 LLM 填：它手里没有执行事实，让它填就是让它编
        String json = "{\"memo_id\":\"m-1\",\"actions\":[\"USE_AC\"],\"confidence\":0.5,"
                + "\"reasoning\":\"r\",\"usage_outcome\":[{\"kind\":\"AC_SCRIPT\",\"outcome\":\"SUCCESS\"}]}";
        Verdict v = Verdict.parse("m-fallback", json);
        assertNotNull(v, "应当能解析");
        assertTrue(v.usageOutcome().isEmpty(),
                "★ LLM 填的 usageOutcome 必须被丢弃（解析时一律空，由服务端从账本反查）");
    }
}