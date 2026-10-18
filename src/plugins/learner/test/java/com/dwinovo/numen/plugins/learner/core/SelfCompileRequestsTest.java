package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自编译请求取件契约的离线检查。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>认领必须有责任人</b> —— 无主的认领等于这条请求仍然没人负责；</li>
 *   <li><b>坏 ack 不许当成「没认领」</b> —— 否则一条已被人领走的请求会重新摆回待办池；</li>
 *   <li>★ <b>ack 绝不改产物本身</b>（产物档案是 append-only 的，回执走旁路）。</li>
 * </ol>
 */
class SelfCompileRequestsTest {

    private static final UUID C = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "screq-" + n + "-" + System.nanoTime());
    }

    private static SelfCompileRequests storeWithOneRequest(Path dir) {
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        ob.submit(ArtifactOutbox.Kind.SELF_COMPILE_REQUEST, C, "rev-1", "m-1",
                "creeper_blast", "现象: 被 creepere 贴脸炸死\n最小复现: y=-12 挖矿\n快照: hp=2");
        return new SelfCompileRequests(ob, dir);
    }

    @Test
    void list_showsRequestAsUnclaimed() {
        var s = storeWithOneRequest(tmp("a"));
        assertEquals(1, s.list().size());
        assertEquals(SelfCompileRequests.AckState.UNCLAIMED, s.list().get(0).state());
        assertEquals(1, s.unclaimed().size());
    }

    @Test
    void take_requiresAnOwner() {
        // ★ 无主的认领等于没人认领 —— 不静默代填 unknown
        var s = storeWithOneRequest(tmp("b"));
        var e = assertThrows(IllegalArgumentException.class,
                () -> s.ack(s.list().get(0).artifactId(), "   ", SelfCompileRequests.AckState.TAKEN, ""));
        assertTrue(e.getMessage().contains("by"), "要说清缺的是责任人: " + e.getMessage());
    }

    @Test
    void take_thenList_showsTaken() {
        var s = storeWithOneRequest(tmp("c"));
        var id = s.list().get(0).artifactId();
        var r = s.ack(id, "outer-agent", SelfCompileRequests.AckState.TAKEN, "开始看");
        assertEquals(SelfCompileRequests.AckState.TAKEN, r.state());
        assertEquals("outer-agent", r.takenBy());
        assertEquals(0, s.unclaimed().size(), "认领后不该还在待办池里");
        assertEquals(1, s.list().size(), "★ 认领后仍要在清单里（否则会以为请求凭空消失）");
    }

    @Test
    void resolve_recordsNote() {
        var s = storeWithOneRequest(tmp("d"));
        var id = s.list().get(0).artifactId();
        s.ack(id, "outer-agent", SelfCompileRequests.AckState.TAKEN, "");
        var r = s.ack(id, "outer-agent", SelfCompileRequests.AckState.RESOLVED, "已提 mutation-123");
        assertEquals(SelfCompileRequests.AckState.RESOLVED, r.state());
        assertTrue(r.note().contains("mutation-123"), "处理结果要留下可追的串: " + r.note());
    }

    @Test
    void ackOnUnknownId_failsLoudly() {
        var s = storeWithOneRequest(tmp("e"));
        var e = assertThrows(IllegalArgumentException.class,
                () -> s.ack("no-such-id", "me", SelfCompileRequests.AckState.TAKEN, ""));
        assertTrue(e.getMessage().contains("SELF_COMPILE_REQUEST"),
                "要说清这类请求归谁管，避免拿错 id: " + e.getMessage());
    }

    @Test
    void ackStateRejectsUnclaimed() {
        var s = storeWithOneRequest(tmp("f"));
        var id = s.list().get(0).artifactId();
        assertThrows(IllegalArgumentException.class,
                () -> s.ack(id, "me", SelfCompileRequests.AckState.UNCLAIMED, ""));
    }

    @Test
    void ack_doesNotTouchTheOutboxRecord() throws IOException {
        // ★ 产物档案 append-only：认领只写旁路 ack，不改投递记录
        Path dir = tmp("g");
        var s = storeWithOneRequest(dir);
        String id = s.list().get(0).artifactId();
        Path artifactFile = dir.resolve("outbox").resolve("SELF_COMPILE_REQUEST")
                .resolve(C.toString()).resolve(id + ".json");
        long before = Files.getLastModifiedTime(artifactFile).toMillis();
        String textBefore = Files.readString(artifactFile, StandardCharsets.UTF_8);

        s.ack(id, "me", SelfCompileRequests.AckState.TAKEN, "");

        assertEquals(before, Files.getLastModifiedTime(artifactFile).toMillis(),
                "★ 认领不许改产物记录");
        assertEquals(textBefore, Files.readString(artifactFile, StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(s.ackDir().resolve(id + ".ack.json")),
                "回执应写在旁路目录: " + s.ackDir());
    }

    @Test
    void corruptAckFile_isNotTreatedAsUnclaimed() throws IOException {
        // ★ 坏 ack 若当成「没认领」，会把已被人领走的请求重新摆回待办池 ⇒ 两个人同时开工
        Path dir = tmp("h");
        var s = storeWithOneRequest(dir);
        String id = s.list().get(0).artifactId();
        Files.createDirectories(s.ackDir());
        Files.writeString(s.ackDir().resolve(id + ".ack.json"), "{not json",
                StandardCharsets.UTF_8);

        var r = s.list().get(0);
        assertFalse(r.state() == SelfCompileRequests.AckState.UNCLAIMED,
                "★ 坏 ack 不许当成「没认领」");
        assertEquals("UNREADABLE", r.state().name());
        assertEquals(0, s.unclaimed().size(), "读不出来的回执不该流回待办池");
    }

    @Test
    void onlySelfCompileKindIsListed() {
        // 混进 AC_SCRIPT 时不该被当成自编译请求列出
        Path dir = tmp("i");
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1", "ac", "x");
        var s = new SelfCompileRequests(ob, dir);
        assertEquals(0, s.list().size(), "只管 SELF_COMPILE_REQUEST");
    }
}