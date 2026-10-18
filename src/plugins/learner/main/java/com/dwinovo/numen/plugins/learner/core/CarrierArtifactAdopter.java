package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRuleStore;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 携带器草稿的消费者（B6/S2，2026-10-05）：投递箱 → 候选 → <b>人工/工具显式审批</b> → 生效链。
 *
 * <p><b>审批流是真的</b>，不是摆设：
 * <ol>
 *   <li>{@link #adoptAll()} 把 {@code CARRIER/PENDING} 草稿校验后登记成<b>候选</b>，
 *       并回写投递记录；</li>
 *   <li>{@link #approve} / {@link #reject} 是<b>两次独立、显式</b>的调用，
 *       都要给理由，回执留痕；</li>
 *   <li>只有 {@code approve} 会让规则进入 {@link CarrierRuleStore} 的生效链。</li>
 * </ol>
 *
 * <p><b>★ 为什么绝不自动批准</b>：携带器决定同伴在危急时刻带什么。
 * 自动批准 = 学习者给自己授权，那和红线 RL-9「单驾驶员」是同一种病。
 * 所以这里<b>没有</b>「成功 N 次就自动过」这种入口 —— 那类阈值要在<b>人工</b>拍板后，
 * 由人显式调用 approve 落地。
 *
 * <p><b>落点</b>：{@code <configDir>/carriers/}（由 {@link CarrierRuleStore} 管），
 * 投递记录回写 {@code LANDED→ADOPTED/REJECTED} + {@code consumer_ref}。
 */
public final class CarrierArtifactAdopter {

    public record Row(String artifactId, String name, String status, String detail) {
    }

    public record Report(int scanned, int submitted, int failed, List<Row> rows) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scanned", scanned);
            m.put("submitted", submitted);
            m.put("failed", failed);
            List<Map<String, Object>> rs = new ArrayList<>();
            for (Row r : rows) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("artifact_id", r.artifactId());
                one.put("name", r.name());
                one.put("status", r.status());
                one.put("detail", r.detail());
                rs.add(one);
            }
            m.put("rows", rs);
            return m;
        }
    }

    private final Path configDir;
    private final ArtifactOutbox outbox;

    public CarrierArtifactAdopter(Path configDir, ArtifactOutbox outbox) {
        this.configDir = configDir;
        this.outbox = outbox;
    }

    /** 扫一遍待处理的携带器草稿，登记成候选。<b>不批准任何东西。</b> */
    public Report adoptAll() {
        List<Row> rows = new ArrayList<>();
        int submitted = 0;
        int failed = 0;
        List<JsonObject> pend = outbox.pending(ArtifactOutbox.Kind.CARRIER);
        for (JsonObject o : pend) {
            String id = str(o, "artifact_id");
            String body = str(o, "body");
            String reviewId = str(o, "review_id");
            String memoId = str(o, "memo_id");
            String name = str(o, "name");
            try {
                CarrierRuleStore.Draft d = CarrierRuleStore.Draft.parse(body);
                CarrierRuleStore.submitCandidate(configDir, id, body, reviewId, memoId);
                outbox.mark(id, ArtifactOutbox.Status.ADOPTED,
                        "已登记为携带器候选，等待显式审批（未生效）",
                        "carriers/candidates.json#" + id);
                rows.add(new Row(id, name.isBlank() ? d.name() : name, "SUBMITTED",
                        "候选已登记；批准后才会进生效链"));
                submitted++;
            } catch (RuntimeException e) {
                outbox.mark(id, ArtifactOutbox.Status.REJECTED, "草稿校验失败: " + e.getMessage(), null);
                rows.add(new Row(id, name, "REJECTED", String.valueOf(e.getMessage())));
                failed++;
            }
        }
        return new Report(pend.size(), submitted, failed, rows);
    }

    /**
     * ★ 审批：批准一条候选。
     *
     * @param reason 必填。回执与 approved.jsonl 里都留这句。
     */
    public CarrierChain.Rule approve(String artifactId, String reason) {
        CarrierChain.Rule r = CarrierRuleStore.approve(configDir, artifactId, reason);
        outbox.mark(artifactId, ArtifactOutbox.Status.ADOPTED,
                "人工/工具显式批准，已进生效链（规则 " + r.name() + "）",
                "carriers/approved.jsonl#" + r.name());
        return r;
    }

    /** 审批：拒收一条候选。只改状态、不删记录（拒了要能回答「当时为什么拒」）。 */
    public void reject(String artifactId, String reason) {
        CarrierRuleStore.reject(configDir, artifactId, reason);
        outbox.mark(artifactId, ArtifactOutbox.Status.REJECTED,
                "已拒收: " + reason, null);
    }

    public List<CarrierRuleStore.Candidate> candidates() {
        return CarrierRuleStore.candidates(configDir);
    }

    public String candidateStatus(String artifactId) {
        return CarrierRuleStore.candidateStatus(configDir, artifactId);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}