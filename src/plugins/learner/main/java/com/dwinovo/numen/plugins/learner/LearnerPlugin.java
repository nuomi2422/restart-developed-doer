package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习者插件（第二批）。
 *
 * <p>宿主适配器：注册工具、按同伴隔离备忘录队列、把队列深度挂进运行时状态。
 *
 * <p><b>红线不变（RL-9 单驾驶员）</b>：学习者<b>不执行任何身体动作</b>，
 * 不发 mine/goto/attack/build 指令，也不自动提交身体工具
 * （bodySubmissionEnabled 默认 false，RddPlugin.java:69）。
 *
 * <p><b>但「只出建议」已经过时了（B6/S2，2026-10-05）</b>：现在复审产出的三类载荷
 * 会真的落到 {@code config/numen/artifact-outbox/}，其中 <b>AC 已由 acx 插件真实消费</b>
 * （发布为 {@code GENERATED}，<b>仍要人工 approve 才会真跑</b>）。
 * 落地 ≠ 生效：只有落点没有下游的那几类，在状态里会如实标出来，不假装已生效。
 *
 * <p>跨插件通信：与 rdd / experience / ac 三个插件互不可见（numen-plugin.gradle:37-44
 * 刻意封死跨插件 import）。所以学习者<b>不写经验库</b>，只把「建议写经验」的草稿
 * 交给 AI，由 AI 调既有的 experience_learn 落库；AC/携带器则走<b>共享文件契约</b>。
 */
public final class LearnerPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerPlugin.class);

    private static final Map<UUID, MemoQueue> QUEUES = new ConcurrentHashMap<>();

    /** reviewId → 复盘状态（异步复盘在后台线程跑完，AI 靠这里查结果）。 */
    private static final Map<String, ReviewRecord> REVIEWS = new ConcurrentHashMap<>();
    private static final int MAX_REVIEW_RECORDS = 32;

    private static volatile Path configDir;

    /**
     * B16：产物投放口。第 1 批是 {@code UnsupportedArtifactSink}（三个方法都显式抛错）；
     * <b>B6/S2 起换成 {@link com.dwinovo.numen.plugins.learner.core.OutboxArtifactSink}</b> ——
     * AC 与携带器真落地（只进投递箱，不自动上线），经验仍显式抛错并指向既有
     * {@code experience_learn}，不另开第二条写库通道。
     */
    private static volatile com.dwinovo.numen.plugins.learner.core.ArtifactSink artifactSink;

    /**
     * B6/S2（2026-10-04）：三个载荷位的<b>真实落点</b>。
     *
     * <p>此前 {@code acScriptDraft}/{@code carrierDraft}/{@code selfCompileRequest}
     * 只出现在回执与 learner.jsonl 里，没有任何下游 —— 「学习者说要写，写完就没下文」。
     * 现在它们进 {@code config/numen/artifact-outbox/}，下游插件按文件契约读取。
     */
    private static volatile com.dwinovo.numen.plugins.learner.core.ArtifactOutbox outbox;

    /**
     * B6/S2：携带器审批流（B6/CARRIER，2026-10-05）。
     *
     * <p>草稿 → 候选 → <b>显式 approve</b> → 进 {@code CarrierRuleStore} 生效链。
     * 运行时携带提示（rdd 的 {@code RddCarryHint}）与复审 what-if
     * （{@code Memo.assessCarrier()}）读的是<b>同一处</b>生效链，
     * 所以「批准了却没生效」这种状态在结构上就不可能悄悄发生。
     */
    private static volatile com.dwinovo.numen.plugins.learner.core.CarrierArtifactAdopter carrierAdopter;

    /**
     * S3：产物<b>使用账本</b>（2026-10-05）。产出被下游用了吗、用成了吗 —— 不接这段，
     * 学习就只在自我循环里打转。
     */
    private static volatile com.dwinovo.numen.plugins.learner.core.UsageLedger usageLedger;

    /**
     * 自编译请求的<b>取件契约</b>（B6/S2 收口，2026-10-05）。
     *
     * <p>此前 {@code SELF_COMPILE_REQUEST} 只有落点、没有下游：既没人领，
     * 也没人知道是否被处理过。现在有列出 / 认领 / 标注三步，且都留痕。
     * ★ 它<b>不写代码</b> —— 写码仍归外层工程流（B11）。
     */
    private static volatile com.dwinovo.numen.plugins.learner.core.SelfCompileRequests selfCompileRequests;

    static Path configDir() {
        return configDir;
    }

    static com.dwinovo.numen.plugins.learner.core.ArtifactSink artifactSink() {
        return artifactSink;
    }

    static com.dwinovo.numen.plugins.learner.core.ArtifactOutbox outbox() {
        return outbox;
    }

    static com.dwinovo.numen.plugins.learner.core.CarrierArtifactAdopter carrierAdopter() {
        return carrierAdopter;
    }

    static com.dwinovo.numen.plugins.learner.core.UsageLedger usageLedger() {
        return usageLedger;
    }

    static com.dwinovo.numen.plugins.learner.core.SelfCompileRequests selfCompileRequests() {
        return selfCompileRequests;
    }

    /**
     * 从 ACX 的运行记录反射一次真实执行结果到使用账本（B1）。
     *
     * <p>★ <b>失败不许影响插件启动</b>：反射只是「补充事实」，不是主功能。
     * 但必须留痕 —— 否则「为什么账本一直是空的」会变成无头案。
     *
     * @param when 触发时机（写进日志，便于分辨是启动时还是手动触发的）
     * @return 反射报告；不可用时返回 null（调用方要能区分「没反射」与「没有可反射的」）
     */
    static com.dwinovo.numen.plugins.learner.core.AcxExecutionReflector.Report reflectAcxRunsOnce(String when) {
        var ob = outbox;
        var ul = usageLedger;
        var dir = configDir;
        if (ob == null || ul == null || dir == null) {
            return null;
        }
        try {
            var refl = new com.dwinovo.numen.plugins.learner.core.AcxExecutionReflector(ob, ul);
            var r = refl.reflect(dir.resolve("monitor").resolve("acx.jsonl"), true);
            if (r.reflected() > 0) {
                LOG.info("[learner] 从 ACX 运行记录反射 {} 条结果（{}；扫到 {} 条运行，{} 条不属于我们）",
                        r.reflected(), when, r.runsSeen(), r.skippedUnmatched());
            }
            return r;
        } catch (RuntimeException e) {
            LOG.warn("[learner] ACX 运行反射失败（{}，不影响其他功能）: {}", when, e.toString());
            return null;
        }
    }
    private static volatile String lastReviewAt = "";
    private static volatile int lastVerdictCount;

    /** 一次复盘的可查询状态。 */
    record ReviewRecord(String reviewId, UUID companionId, String state, int reviewing,
                        int verdictCount, String reason, List<Map<String, Object>> verdicts) {}

    @Override
    public void setup(NumenApi numen) {
        configDir = numen.configDir();
        // E1 shadow：学习角色的预算策略（与客户端同一份文件，幂等）。
        com.dwinovo.numen.agent.llm.BudgetPolicy.install(numen.configDir());
        numen.registerTool(new LearnerNoteTool());
        numen.registerTool(new LearnerReviewTool());
        numen.registerTool(new LearnerStatusTool());
        // 第 4 个工具：只读反馈通道（38号v3 B14 / v3.2 B22）。**纯只读**——
        // B2 约束的是「不得发身体指令」不是工具总数；tools/list 里仍无 mine/goto/attack/build。
        // 结果**不进 contributeState**（那是主 AI 的上下文，注进去就变成「叙述变授权」）。
        numen.registerTool(new LearnerFeedbackTool());
        // 第 5 个工具：入队判定（E1，59 §4.1 的 T1–T7）。读 instrumentation.jsonl（只读），
        // 用 CandidateGate 判「该不该记经验」——这段判定过去只活在提示词里，
        // 60 号 §3.2 记的 E1 就是「有记录，无判定」。
        // **纯只读事件源 + 只写学习者自己的队列**：不碰世界、不写经验库（同 59 D3）。
        numen.registerTool(new LearnerIntakeTool());
        // B6/S2：投递箱放在共享 configDir 下 —— 跨插件不能 import（见 NumenApi 的 javadoc），
        // 文件是这三条既有通道里唯一「不依赖谁记得调用」的一条。
        outbox = new com.dwinovo.numen.plugins.learner.core.ArtifactOutbox(
                configDir.resolve("artifact-outbox"));
        // B16 的投放口换成基于投递箱的真实现。顺序有讲究：sink 依赖 outbox，先建 outbox。
        artifactSink = new com.dwinovo.numen.plugins.learner.core.OutboxArtifactSink(outbox);
        // 携带器审批流。顺序讲究：store 先装目录（生效链 = DEFAULT + 已批准），
        // adopter 再建（它读写 <configDir>/carriers/），最后扫一次草稿登记成候选。
        com.dwinovo.numen.api.carrier.CarrierRuleStore.install(configDir);
        carrierAdopter = new com.dwinovo.numen.plugins.learner.core.CarrierArtifactAdopter(configDir, outbox);
        try {
            var rep = carrierAdopter.adoptAll();
            if (rep.scanned() > 0) {
                LOG.info("携带器草稿登记成候选 {} 条（待显式审批，未生效）: {}",
                        rep.submitted(), rep.rows());
            }
        } catch (RuntimeException e) {
            // 登记失败不许静默：学习者写了携带器却没人看见 = 哑故障
            LOG.warn("携带器草稿登记失败（不影响其他功能）: {}", e.toString());
        }
        // 第 6 个工具：携带器审批流（submit/list/approve/reject）。**approve 必须给理由**。
        numen.registerTool(new LearnerCarrierTool());
        // S3：使用账本（record/report/claim_validity）。**claim_validity 必须给理由**，
        // 且刻意没有「自动判定有效性」的口子。
        usageLedger = new com.dwinovo.numen.plugins.learner.core.UsageLedger(configDir);
        numen.registerTool(new LearnerUsageTool());
        // 自编译请求的取件契约：把「没人消费」这条边补上。
        // ★ 只做流程（列出/认领/标注），**不写代码** —— 写码归外层工程流（B11）。
        selfCompileRequests = new com.dwinovo.numen.plugins.learner.core.SelfCompileRequests(outbox, configDir);
        numen.registerTool(new LearnerRequestTool());
        // B1：ACX 真实执行结果 → 使用账本。**只回流能从投递箱追到自己产物的运行**。
        //   这是「自己写的东西到底跑成没成」第一次有了非自报的事实源。
        //   启动时先反射一次；ACX 运行是异步的，之后由 learner_usage reflect 触发。
        reflectAcxRunsOnce("STARTUP");
        // 回话：默认开（用户明确要「不是一直裸着写」），但必须能一键闭嘴 ⇒ 做成工具。
        numen.registerTool(new LearnerAnnounceTool());
        // N1：共同事实 vs 使用账本的只读对账（事实文件归 rdd、账本归 learner，
        // 两边都是共享 configDir 下的普通文件，所以只靠路径就能读，无需跨插件 import）。
        numen.registerTool(new LearnerFactShadowTool());
        // 运行时状态：让主 AI 知道「有多少条待复盘的备忘录」，从而自己决定何时调 learner_review
        numen.contributeState(companion -> {
            MemoQueue q = QUEUES.get(companion);
            if (q == null) {
                return "";
            }
            int depth;
            try {
                depth = q.size();
            } catch (RuntimeException e) {
                // 队列读失败（文件损坏）：如实报，不假装是 0
                return "<learner><pending_memos>UNKNOWN</pending_memos></learner>";
            }
            if (depth == 0) {
                return "";
            }
            return "<learner><pending_memos>" + depth + "</pending_memos></learner>";
        });
        // ★ 这行日志以前写「plan-only v1」——已经不诚实了（现在有投递箱 + 携带器审批流）。
        //   日志撒谎比注释撒谎更坏：出事时第一个被查的就是它。
        LOG.info("[learner] plugin ready; 不执行身体动作（RL-9 单驾驶员），"
                + "但三类草稿真落 config/numen/artifact-outbox，携带器走显式审批（learner_carrier approve）");
    }

    /** 取（或惰性创建）某同伴的备忘录队列。 */
    static MemoQueue queue(UUID companionId) {
        Path dir = configDir;
        if (dir == null) {
            throw new IllegalStateException("learner plugin not set up");
        }
        return QUEUES.computeIfAbsent(companionId,
                uuid -> new MemoQueue(dir.resolve("learner-memos-" + uuid + ".json")));
    }

    static void beginReview(String reviewId, UUID companionId, String state, int reviewing) {
        REVIEWS.put(reviewId, new ReviewRecord(reviewId, companionId, state, reviewing, 0, "", List.of()));
        evictOld();
    }

    static void finishReview(String reviewId, String state, int verdictCount) {
        REVIEWS.computeIfPresent(reviewId, (id, rec) ->
                new ReviewRecord(rec.reviewId(), rec.companionId(), state, rec.reviewing(),
                        verdictCount, state.startsWith("NO_") || state.startsWith("FAILED") ? state : rec.reason(),
                        rec.verdicts()));
    }

    static void setReviewVerdicts(String reviewId, List<Map<String, Object>> verdicts) {
        REVIEWS.computeIfPresent(reviewId, (id, rec) ->
                new ReviewRecord(rec.reviewId(), rec.companionId(), rec.state(), rec.reviewing(),
                        verdicts.size(), rec.reason(), List.copyOf(verdicts)));
    }

    static ReviewRecord review(String reviewId) {
        return REVIEWS.get(reviewId);
    }

    /** 复盘记录有界，防止长跑内存涨。 */
    private static void evictOld() {
        while (REVIEWS.size() > MAX_REVIEW_RECORDS) {
            String oldest = null;
            for (String k : REVIEWS.keySet()) {
                if (oldest == null || k.compareTo(oldest) < 0) {
                    oldest = k;
                }
            }
            if (oldest == null || REVIEWS.remove(oldest) == null) {
                return;
            }
        }
    }

    static void recordReview(String at, int verdictCount) {
        lastReviewAt = at;
        lastVerdictCount = verdictCount;
    }

    static String lastReviewAt() {
        return lastReviewAt;
    }

    static int lastVerdictCount() {
        return lastVerdictCount;
    }

    static Map<String, Object> reviewToMap(ReviewRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("review_id", r.reviewId());
        m.put("state", r.state());
        m.put("reviewing", r.reviewing());
        m.put("verdict_count", r.verdictCount());
        if (!r.reason().isBlank()) {
            m.put("reason", r.reason());
        }
        if (!r.verdicts().isEmpty()) {
            m.put("verdicts", r.verdicts());
        }
        return m;
    }
}
