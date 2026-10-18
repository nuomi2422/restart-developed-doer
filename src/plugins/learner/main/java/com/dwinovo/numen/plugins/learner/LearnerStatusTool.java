package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_status}：查队列深度、上次复盘、以及某次复盘（review_id）的结果。
 *
 * <p>纯只读。因为 {@code learner_review} 是异步的（不能在服务器主线程等 LLM），
 * 结果要靠这个工具取回。
 */
final class LearnerStatusTool implements NumenTool {

    @Override
    public String name() {
        return "learner_status";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "Read-only learner status: pending memo count, last review time, and the verdicts "
                + "of a specific review_id returned by learner_review. Use it to poll an "
                + "asynchronous review until its state is no longer RUNNING.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("review_id", "Which review to fetch results for.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            MemoQueue q = LearnerPlugin.queue(companion.getUUID());
            int depth;
            try {
                depth = q.size();
            } catch (RuntimeException e) {
                // 队列文件坏了：如实报 UNKNOWN，不假装 0（假装 0 会让 AI 以为「没东西可复盘」）
                reply.accept(TaskResult.fail("memo queue unreadable: " + e.getMessage()).toJson());
                return;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("queue_depth", depth);
            data.put("queue_capacity", MemoQueue.MAX_QUEUE);
            data.put("last_review_at", LearnerPlugin.lastReviewAt());
            data.put("last_verdict_count", LearnerPlugin.lastVerdictCount());

            // B6/S2：投递箱现状。**「落地 ≠ 生效」**，所以把消费者情况一并说清楚：
            // 只有计数的话，AI 会把「有一堆 LANDED」误读成「都已被下游采纳」。
            var ob = LearnerPlugin.outbox();
            if (ob != null) {
                Map<String, Object> box = new LinkedHashMap<>(ob.stats());
                box.put("root", ob.root().toString());
                box.put("AC_SCRIPT_consumer", "acx.AcxArtifactAdopter（发布为 GENERATED，仍需人工 approve）");
                // ★ 这行以前写「NONE_YET（只有落点，无消费者）」—— 那是审批流还没建时写的，
                //   审批流建好后它就成了**撒谎的状态**：草稿其实有人接了。
                //   状态项撒谎比注释撒谎更坏（出事时第一个查的就是它），所以必须跟着实现一起改。
                box.put("CARRIER_consumer",
                        "learner.CarrierArtifactAdopter → 登记为候选；**要 learner_carrier approve 才进生效链**");
                box.put("SELF_COMPILE_REQUEST_consumer", "NONE_YET（只有落点，无消费者）");
                box.put("note", "落地 ≠ 生效：以 *_consumer 为准，别把 LANDED 当成已被采纳");
                data.put("artifact_outbox", box);
            } else {
                data.put("artifact_outbox", "UNSET（插件 setup 没跑完？）");
            }

            String reviewId = null;
            try {
                if (args != null && args.has("review_id") && args.get("review_id").isJsonPrimitive()) {
                    reviewId = args.get("review_id").getAsString().trim();
                }
            } catch (RuntimeException ignored) {
                // 参数坏掉就只回概览
            }
            if (reviewId != null && !reviewId.isBlank()) {
                LearnerPlugin.ReviewRecord rec = LearnerPlugin.review(reviewId);
                if (rec == null) {
                    data.put("review_id", reviewId);
                    data.put("state", "NOT_FOUND");
                    reply.accept(TaskResult.fail("unknown review_id: " + reviewId
                            + " (records are capped at 32; older ones are evicted)").toJson());
                    return;
                }
                data.putAll(LearnerPlugin.reviewToMap(rec));
            }

            reply.accept(TaskResult.ok("learner status", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("learner_status failed: " + ex.getMessage()).toJson());
        }
    }
}
