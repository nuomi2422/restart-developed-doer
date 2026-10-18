package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据等级三分法的离线检查（DL-10，2026-10-05）。
 *
 * <p><b>要挡住的塌陷</b>：原先只有一个 {@code validity_claimed} 布尔，
 * 而声明入口是<b>模型可调用</b>的工具 ⇒ 模型可以自己说「这条已审核通过」。
 * 那样一来「有人审过」这件事就只剩模型的一句自证，信任链整个塌掉。
 *
 * <p>所以：{@code model_asserted ≠ human_reviewed}，且这件事要**可见**而不是被抹掉。
 */
class EvidenceLevelTest {

    private static final UUID C = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "evl-" + n + "-" + System.nanoTime());
    }

    @Test
    void reflectedExecutionIsMachineVerified_withoutAnybodyClaimingIt() throws Exception {
        Path dir = tmp("a");
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        UsageLedger ul = new UsageLedger(dir);
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "hp_guard", "x");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.ADOPTED, "ok", "hp_guard@1");
        java.nio.file.Files.writeString(dir.resolve("acx.jsonl"),
                "{\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"r1\",\"ac_name\":\"hp_guard\","
                        + "\"status\":\"SUCCESS\"}}\n", java.nio.charset.StandardCharsets.UTF_8);
        new AcxExecutionReflector(ob, ul).reflect(dir.resolve("acx.jsonl"), true);

        var e = ul.all().get(0);
        assertEquals(UsageLedger.EvidenceLevel.MACHINE_VERIFIED, e.evidence(),
                "有 run_id 可回溯 ⇒ 机器可验证，不需要任何人声明");
        assertFalse(e.humanReviewed(), "★ 机器可验证**不是**人工审核");
    }

    @Test
    void modelClaim_isRecordedAsModelAsserted_neverHumanReviewed() throws Exception {
        // ★ 这是本文件最重要的一条：模型调用 claim_validity 之后，等级必须是 MODEL_ASSERTED
        Path dir = tmp("b");
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        UsageLedger ul = new UsageLedger(dir);
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "hp_guard", "x");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.ADOPTED, "ok", "hp_guard@1");
        java.nio.file.Files.writeString(dir.resolve("acx.jsonl"),
                "{\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"r1\",\"ac_name\":\"hp_guard\","
                        + "\"status\":\"SUCCESS\"}}\n", java.nio.charset.StandardCharsets.UTF_8);
        new AcxExecutionReflector(ob, ul).reflect(dir.resolve("acx.jsonl"), true);
        String id = d.artifactId();

        // 模拟模型调用 claim_validity
        ul.claimValidity(id, "我检查过了", "companion-uuid");

        var latest = ul.latestOf(id);
        assertEquals(UsageLedger.EvidenceLevel.MACHINE_VERIFIED, latest.evidence(),
                "★ 模型声明**不得**把等级抬到 HUMAN_REVIEWED；"
                        + "已有的机器证据等级保持不变");
        assertFalse(latest.humanReviewed(),
                "★ 模型说「我检查过了」绝不能算作人审通过");
        assertTrue(latest.validityClaimed(), "布尔历史字段仍如实记录「有人声明过」");
    }

    @Test
    void plainRecordWithoutExecution_hasNoEvidence() {
        Path dir = tmp("c");
        UsageLedger ul = new UsageLedger(dir);
        ul.append("a1", "AC_SCRIPT", "x", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "learner_usage", C.toString());
        assertEquals(UsageLedger.EvidenceLevel.NONE, ul.all().get(0).evidence(),
                "AI 自己 record 的成功 = 没有非自报证据 ⇒ NONE");
        assertFalse(ul.all().get(0).humanReviewed());
    }

    @Test
    void onlyHumanAndProductionCountAsHumanReview() {
        // 这道闸本身：枚举语义不能被改坏
        assertTrue(UsageLedger.EvidenceLevel.HUMAN_REVIEWED.countsAsHumanReview());
        assertTrue(UsageLedger.EvidenceLevel.PRODUCTION_VERIFIED.countsAsHumanReview());
        assertFalse(UsageLedger.EvidenceLevel.MODEL_ASSERTED.countsAsHumanReview(),
                "★ 自证不得算人审");
        assertFalse(UsageLedger.EvidenceLevel.MACHINE_VERIFIED.countsAsHumanReview(),
                "★ 机器证据不得算人审（它证明「跑过」，不证明「对」）");
        assertFalse(UsageLedger.EvidenceLevel.NONE.countsAsHumanReview());
    }

    @Test
    void evidenceRoundTripsThroughDisk() throws Exception {
        Path dir = tmp("d");
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        UsageLedger ul = new UsageLedger(dir);
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "hp_guard", "x");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.ADOPTED, "ok", "hp_guard@1");
        java.nio.file.Files.writeString(dir.resolve("acx.jsonl"),
                "{\"data\":{\"kind\":\"RUN_FINISHED\",\"run_id\":\"r1\",\"ac_name\":\"hp_guard\","
                        + "\"status\":\"SUCCESS\"}}\n", java.nio.charset.StandardCharsets.UTF_8);
        new AcxExecutionReflector(ob, ul).reflect(dir.resolve("acx.jsonl"), true);

        // 重新打开账本（模拟重启后读盘）
        UsageLedger reopened = new UsageLedger(dir);
        var e = reopened.latestOf(d.artifactId());
        assertEquals(UsageLedger.EvidenceLevel.MACHINE_VERIFIED, e.evidence(),
                "证据等级必须落盘并能还原");
    }
}