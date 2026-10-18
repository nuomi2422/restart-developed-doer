package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒收回喂的离线检查（2026-10-05 实机三轮踩出来的）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>拒收原因真的进了 prompt 文本（不是空转）；</li>
 *   <li><b>读不到投递箱时要明说</b>，不假装「没有拒收」——否则「模型为什么老是重犯」变无头案；</li>
 *   <li>只带最近的、且每类有上限（prompt 不会被撑爆）。</li>
 * </ol>
 */
class RejectionFeedbackTest {

    private static final UUID C = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "rejfb-" + n + "-" + System.nanoTime());
    }

    @Test
    void rejectedDraft_reasonReachesPrompt() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("a"));
        ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "ac-draft", "trigger: hp < 4");
        ob.mark(d.artifactId(), ArtifactOutbox.Status.REJECTED,
                "学习者交来的是散文，无法解析成 ACX 脚本 JSON", null);

        RejectionFeedback fb = RejectionFeedback.scan(ob, true, C);
        assertFalse(fb.isEmpty(), "有拒收记录时不能是空的");
        assertTrue(fb.count() >= 1, "至少要数到 1 条");

        String block = fb.promptBlock();
        assertTrue(block.contains("ac-draft"), "prompt 里要点名这次被拒的产物: " + block);
        assertTrue(block.contains("散文"), "★ prompt 里必须带**拒收原因** —— 模型靠它改: " + block);
        assertTrue(block.contains("别再犯"), "要明确这是「上轮被拒」而不是闲聊");
    }

    @Test
    void unreadableOutbox_saysSo_insteadOfPretendingClean() {
        // 读不到 ⇒ 必须说出来。返回空串会被当成「没有拒收」，那就是把故障伪装成没问题。
        RejectionFeedback fb = RejectionFeedback.scan(null, false, C);
        String block = fb.promptBlock();
        assertTrue(block.contains("读不到"),
                "读不到投递箱时必须明说，不许静默: " + block);
    }

    @Test
    void onlyRecentOnes_areCarried_andCappedPerKind() {
        ArtifactOutbox ob = new ArtifactOutbox(tmp("b"));
        for (int i = 1; i <= 6; i++) {
            ArtifactOutbox.Delivery d = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-" + i,
                    "draft-" + i, "{\"name\":\"draft-" + i + "\"}");
            ob.mark(d.artifactId(), ArtifactOutbox.Status.REJECTED, "原因-" + i, null);
        }
        RejectionFeedback fb = RejectionFeedback.scan(ob, true, C);
        assertTrue(fb.count() <= RejectionFeedback.MAX_PER_KIND,
                "每类最多带 " + RejectionFeedback.MAX_PER_KIND + " 条，别把 prompt 撑爆: " + fb.count());
        String block = fb.promptBlock();
        assertFalse(block.contains("原因-1"),
                "只带最近的：最早的 1 号不该还在（否则 prompt 越滚越长）");
    }

    @Test
    void adoptedDraft_isNotReportedAsRejection() {
        // 已采纳的不要混进「拒收」里 —— 那会让模型去改一个其实已经成了的东西
        ArtifactOutbox ob = new ArtifactOutbox(tmp("c"));
        ArtifactOutbox.Delivery ok = ob.submit(ArtifactOutbox.Kind.AC_SCRIPT, C, "r", "m-1",
                "good_draft", "{\"name\":\"good_draft\"}");
        ob.mark(ok.artifactId(), ArtifactOutbox.Status.ADOPTED, "已进版本库", "acx#1");

        RejectionFeedback fb = RejectionFeedback.scan(ob, true, C);
        assertFalse(fb.promptBlock().contains("good_draft"),
                "已采纳的产物不该出现在拒收清单里");
    }
}