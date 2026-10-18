package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回喂两条通道的<b>同伴隔离</b>测试（2026-10-05 加固）。
 *
 * <p><b>为什么必须隔离</b>：投递箱与使用账本都是<b>共享文件</b>
 * （{@code config/numen/artifact-outbox/} 与 {@code usage-ledger.jsonl}），
 * 里面<b>混着所有同伴的记录</b>。若回喂时不分同伴：
 * <ul>
 *   <li>A 的失败经验会作为「上轮情况」喂给 B ⇒ B 学会「别做 A 做过的事」，可能纯属误会；</li>
 *   <li>更糟：A 被拒的草稿会<b>挤掉</b> B 自己的拒收记录（每类只留 N 条）。</li>
 * </ul>
 * 而「上轮回顾」这条通道本来就是**同伴私有**的（每个同伴有自己的队列与历史），
 * 把别人的混进来会破坏这个前提。
 */
class FeedbackIsolationTest {

    private static final UUID A = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID B = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "iso-" + n + "-" + System.nanoTime());
    }

    private static String companionId(JsonObject o) {
        return o.has("companion_id") && !o.get("companion_id").isJsonNull()
                ? o.get("companion_id").getAsString() : "";
    }

    // ── 拒收回喂：只喂本同伴 ────────────────────────────────────────────────

    @Test
    void rejectionFeedback_onlyContainsOwnCompanionsRejections() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("rej"));
        ArtifactOutbox.Delivery da = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1",
                "a_draft", "{\"name\":\"a_draft\"}");
        ob.mark(da.artifactId(), ArtifactOutbox.Status.REJECTED, "A 的失败原因", null);
        ArtifactOutbox.Delivery db = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, B, "r", "m-1",
                "b_draft", "{\"name\":\"b_draft\"}");
        ob.mark(db.artifactId(), ArtifactOutbox.Status.REJECTED, "B 的失败原因", null);

        RejectionFeedback mine = RejectionFeedback.scan(ob, true, A);
        String block = mine.promptBlock();
        assertTrue(block.contains("A 的失败原因"), "A 的回喂里必须有 A 自己的原因: " + block);
        assertFalse(block.contains("B 的失败原因"),
                "★ A 的回喂里绝不能出现 B 的记录（共享文件 + 忘了过滤 = 跨同伴污染）");
        assertFalse(block.contains("b_draft"));
    }

    @Test
    void rejectionFeedback_otherCompanionGetsItsOwnView() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("rej2"));
        ArtifactOutbox.Delivery da = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1",
                "a_draft", "x");
        ob.mark(da.artifactId(), ArtifactOutbox.Status.REJECTED, "A 的失败原因", null);
        ArtifactOutbox.Delivery db = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, B, "r", "m-1",
                "b_draft", "x");
        ob.mark(db.artifactId(), ArtifactOutbox.Status.REJECTED, "B 的失败原因", null);

        RejectionFeedback theirs = RejectionFeedback.scan(ob, true, B);
        String block = theirs.promptBlock();
        assertTrue(block.contains("B 的失败原因"));
        assertFalse(block.contains("A 的失败原因"));
    }

    @Test
    void rejectionFilter_isCompanionIdNotPathPrefixGuess() {
        // 守卫：过滤必须按记录里的 companion_id 字段，而不是靠文件名/路径猜
        ArtifactOutbox ob = new ArtifactOutbox(tmp("rej3"));
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, A, "r", "m-1", "n", "x");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.REJECTED, "原因", null);
        for (JsonObject rec : ob.list(ArtifactOutbox.Kind.AC_SCRIPT)) {
            assertEquals(A.toString(), companionId(rec),
                    "投递记录必须带 companion_id 字段，否则过滤只能靠猜路径");
        }
    }

    // ── 使用账本：只喂本同伴 ────────────────────────────────────────────────

    @Test
    void usagePrompt_onlyContainsOwnCompanionsArtifacts() {
        Path dir = tmp("usage");
        UsageLedger ul = new UsageLedger(dir);
        ul.append("artifact-a", "AC_SCRIPT", "A 的脚本", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "A 的说明", "acx", A.toString());
        ul.append("artifact-b", "AC_SCRIPT", "B 的脚本", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.FAIL, "B 的说明", "acx", B.toString());

        String block = UsageLedger.promptBlock(ul, A);
        assertTrue(block.contains("A 的脚本"), "A 的回喂里要有 A 自己的产物: " + block);
        assertFalse(block.contains("B 的脚本"),
                "★ A 的回喂里绝不能出现 B 的产物（学习闭环不该被别的同伴污染）");
    }

    @Test
    void usageLedger_entriesCarryCompanionSoFilterIsPossible() throws Exception {
        Path dir = tmp("usage2");
        UsageLedger ul = new UsageLedger(dir);
        ul.append("artifact-a", "AC_SCRIPT", "n", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "acx", A.toString());
        // 账本行里必须能读到同伴，否则过滤只能靠字符串猜
        String first = Files.readAllLines(ul.file(), StandardCharsets.UTF_8).get(0);
        assertTrue(first.contains(A.toString()),
                "账本行必须记下同伴 id（否则无法隔离）: " + first);
    }

@Test
    void unknownCompanion_hasEmptyView_notEverything() {
        Path dir = tmp("usage3");
        UsageLedger ul = new UsageLedger(dir);
        ul.append("artifact-a", "AC_SCRIPT", "A 的脚本", UsageLedger.Phase.RESULT,
                UsageLedger.Outcome.SUCCESS, "", "acx", A.toString());
        // 传 null 同伴 ⇒ 什么都看不到，而不是「看到全部」
        assertFalse(UsageLedger.promptBlock(ul, null).contains("A 的脚本"),
                "★ 同伴为 null 时不许退化成「把全部都喂出去」");
    }
}