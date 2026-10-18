package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投递箱的契约测试（B6/S2）。
 *
 * <p>钉的是<b>行为</b>，不是字段存在：幂等要真的不写第二份、回填要保留轨迹、
 * 坏文件不许被当成「没有产物」、同伴之间不许串。
 */
class ArtifactOutboxTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void submit_writesOneFile_andReportsLanded() throws IOException {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob1"));
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "rev-1", "m-1",
                "eat_when_hungry", "{\"name\":\"eat_when_hungry\"}");
        assertTrue(d.landed(), "首次提交应当落地: " + d);
        assertEquals("LANDED", d.status());
        Path f = Path.of(d.path());
        assertTrue(Files.exists(f), "回执给了路径，文件必须在");
        JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("PENDING", o.get("status").getAsString());
        assertEquals("eat_when_hungry", o.get("name").getAsString());
        assertEquals(A.toString(), o.get("companion_id").getAsString());
        assertEquals("rev-1", o.get("review_id").getAsString());
        assertEquals("m-1", o.get("memo_id").getAsString());
        assertEquals(1, o.getAsJsonArray("history").size(), "初始轨迹一条");
    }

    @Test
    void submit_sameBodyTwice_isDuplicate_notSecondFile() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob2"));
        ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "rev-1", "m-1", "n", "body-A");
        ArtifactOutbox.Delivery again = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "rev-2", "m-1", "n", "body-A");
        assertTrue(again.duplicate(), "同一份草稿重投必须是 DUPLICATE: " + again.status());
        assertEquals(1, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size(), "不许写第二份");
    }

    @Test
    void submit_differentBody_isNotDuplicate() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob3"));
        ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "rev-1", "m-1", "n", "body-A");
        ArtifactOutbox.Delivery other = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "rev-1", "m-1", "n", "body-B");
        assertFalse(other.duplicate(), "内容不同就不是重复");
        assertEquals(2, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size());
    }

    @Test
    void companions_areIsolated() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob4"));
        ob.submit(ArtifactOutbox.Kind.CARRIER, A, "rev-1", "m-1", "hp", "x");
        ob.submit(ArtifactOutbox.Kind.CARRIER, B, "rev-1", "m-1", "hp", "x");
        assertEquals(2, ob.pending(ArtifactOutbox.Kind.CARRIER).size(), "两个同伴各一条，不互相覆盖");
    }

    @Test
    void pending_isolatesByKind() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob5"));
        ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "a");
        ob.submit(ArtifactOutbox.Kind.CARRIER, A, "r", "m-1", "n", "b");
        ob.submit(ArtifactOutbox.Kind.SELF_COMPILE_REQUEST, A, "r", "m-1", "n", "c");
        assertEquals(1, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size());
        assertEquals(1, ob.pending(ArtifactOutbox.Kind.CARRIER).size());
        assertEquals(1, ob.pending(ArtifactOutbox.Kind.SELF_COMPILE_REQUEST).size());
    }

    @Test
    void blankBody_isRejected_notSilentlyAccepted() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob6"));
        assertThrows(IllegalArgumentException.class,
                () -> ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "   "));
        assertEquals(0, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size());
    }

    @Test
    void nullCompanion_isRejected() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob7"));
        assertThrows(IllegalArgumentException.class,
                () -> ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, null, "r", "m-1", "n", "b"));
    }

    @Test
    void oversizedBody_isRejectedWithReason_notTruncated() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob8"));
        String huge = "x".repeat(ArtifactOutbox.MAX_BODY_CHARS + 1);
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", huge);
        assertEquals("REJECTED", d.status());
        assertTrue(d.detail().contains("上限"), "要说清为什么拒收: " + d.detail());
        assertEquals(0, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size());
    }

    @Test
    void mark_writesTerminalStatus_andKeepsHistory() throws IOException {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob9"));
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "body");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.ADOPTED, "已进版本库", "eat@1");
        JsonObject o = JsonParser.parseString(Files.readString(Path.of(d.path()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("ADOPTED", o.get("status").getAsString());
        assertEquals("eat@1", o.get("consumer_ref").getAsString());
        assertEquals(2, o.getAsJsonArray("history").size(), "提交一条 + 回填一条，轨迹不能被覆盖");
        assertEquals(0, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size(), "已回填的不再是待处理");
    }

    @Test
    void mark_refusesPendingAsTerminalState() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob10"));
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "body");
        assertThrows(IllegalArgumentException.class,
                () -> ob.mark(d.artifactId(), ArtifactOutbox.Status.PENDING, "x", ""));
    }

    @Test
    void mark_unknownArtifact_failsLoudly() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob11"));
        assertThrows(IllegalArgumentException.class,
                () -> ob.mark("AC_SCRIPT-none-none-deadbeef", ArtifactOutbox.Status.ADOPTED, "x", ""));
    }

    @Test
    void pending_skipsCorruptFile_withoutDeletingIt() throws IOException {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob12"));
        ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "good");
        Path dir = ob.root().resolve("AC_SCRIPT").resolve(A.toString());
        Path bad = dir.resolve("broken.json");
        Files.writeString(bad, "{not json", StandardCharsets.UTF_8);
        List<JsonObject> pend = ob.pending(ArtifactOutbox.Kind.AC_SCRIPT);
        assertEquals(1, pend.size(), "坏文件不许被当成产物，也不许拖垮整目录");
        assertTrue(Files.exists(bad), "坏文件要留着等人看，不许静默删");
    }

    // ── OutboxArtifactSink：B16 的投放口现在有真实现了（不再是死占位）──────────────

    @Test
    void sink_publishAcScript_actuallyLandsInOutbox() throws IOException {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("sink1"));
        ArtifactSink sink = new OutboxArtifactSink(ob);
        assertTrue(sink.available(), "有投递箱就应当 available —— 留着 false 就是假红线");
        String ref = sink.publishAcScript("eat_when_hungry", "{\"name\":\"eat_when_hungry\"}");
        assertTrue(Files.exists(Path.of(ob.root().toString(), "AC_SCRIPT")), "AC 草稿应当真的落了盘");
        assertEquals(1, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size(), "应当有一条待采纳");
        assertTrue(ref != null && !ref.isBlank(), "接口承诺返回可引用标识，不许返回空");
    }

    @Test
    void sink_publishCarrier_landsUnderCarrierKind() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("sink2"));
        new OutboxArtifactSink(ob).publishCarrier("my_carrier", "rules: ...");
        assertEquals(1, ob.pending(ArtifactOutbox.Kind.CARRIER).size(), "携带器草稿也要落盘");
    }

    @Test
    void sink_publishExperience_throwsAndNamesTheRealExit() {
        // 关键：经验不许从学习者这里写库（两套并存必然漂移），但必须**显式**抛错并指出真出口
        ArtifactOutbox ob = new ArtifactOutbox(tmp("sink3"));
        ArtifactSink sink = new OutboxArtifactSink(ob);
        var e = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishExperience("e-1", java.util.Map.of()));
        assertTrue(e.getMessage().contains("experience_learn"),
                "报错要指名既有出口，否则调用方不知道该去哪写: " + e.getMessage());
        assertEquals(0L, ob.stats().getOrDefault("total", 0L),
                "抛错了就不许偷偷落一份到投递箱");
    }

    // ── list()：读侧必须看得见「被拒的」，否则哑故障 ──────────────────────────────

    @Test
    void list_showsRejectedToo_notJustPending() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("list1"));
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "x");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.REJECTED, "解析失败", null);
        assertEquals(0, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size(), "拒收后不该还在待处理队列里");
        List<JsonObject> all = ob.list(ArtifactOutbox.Kind.AC_SCRIPT);
        assertEquals(1, all.size(), "但全量列表必须看得见它 —— 不然『AI 写了但被拒』没人知道");
        assertEquals("REJECTED", all.get(0).get("status").getAsString());
        assertTrue(all.get(0).has("_path"), "全量列表要带路径，人才能直接打开看");
    }

    @Test
    void list_ofEmptyKind_isEmptyNotError() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("list2"));
        assertEquals(0, ob.list(ArtifactOutbox.Kind.CARRIER).size(),
                "没有该类目录时返回空列表，不许抛错 —— 状态查询不该成为故障源");
    }

    @Test
    void stats_countsPerKind() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob13"));
        ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "a");
        ob.submit(ArtifactOutbox.Kind.SELF_COMPILE_REQUEST, B, "r", "m-2", "n", "b");
        var st = ob.stats();
        assertEquals(1L, st.get("AC_SCRIPT"));
        assertEquals(0L, st.get("CARRIER"));
        assertEquals(1L, st.get("SELF_COMPILE_REQUEST"));
        assertEquals(2L, st.get("total"));
    }

    @Test
    void sanitize_stripsPathSeparators() {
        String s = ArtifactOutbox.sanitize("../../etc/passwd");
        assertNotEquals("..", s);
        assertFalse(s.contains("/"), "文件名不许带路径分隔符: " + s);
        assertFalse(s.contains("\\"), "文件名不许带路径分隔符: " + s);
    }

    @Test
    void pending_missingDir_isEmptyNotError() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("ob14").resolve("never-created"));
        assertEquals(0, ob.pending(ArtifactOutbox.Kind.AC_SCRIPT).size());
    }

    /** 每个用例一个干净目录：重跑不许被上一轮的残留影响（残留会伪装成「重复提交」）。 */
    private static Path tmp(String name) {
        Path p = Path.of(System.getProperty("java.io.tmpdir"), "rdd-outbox-test", name);
        if (Files.exists(p)) {
            try (var w = Files.walk(p)) {
                w.sorted(Comparator.reverseOrder()).forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // 清不掉的残留不阻塞：断言用的是本轮新建的产物
                    }
                });
            } catch (IOException ignored) {
                // 同上
            }
        }
        return p;
    }
}
