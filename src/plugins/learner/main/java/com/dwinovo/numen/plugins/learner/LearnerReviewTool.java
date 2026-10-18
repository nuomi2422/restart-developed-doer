package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.ArtifactOutbox;
import com.dwinovo.numen.plugins.learner.core.Experience;
import com.dwinovo.numen.plugins.learner.core.ExperienceDraft;
import com.dwinovo.numen.plugins.learner.core.ExperienceQualityGate;
import com.dwinovo.numen.plugins.learner.core.PriorRound;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@code learner_review}：学习者领取队列里的备忘录、逐条复盘、出结构化判定。
 *
 * <p><b>plan-only 的是「身体动作」，不是「落盘」（B6/S2 已放开落盘，2026-10-05）</b>：
 * 本工具<b>不执行任何身体动作</b>，也不自己写经验库、<b>不自编译</b>
 * —— 那是为了不成为第二个驾驶员（红线 RL-9 / RL-20）。
 * 但它现在会把三类草稿真的落进 {@code config/numen/artifact-outbox/}：
 * <ul>
 *   <li><b>AC 草稿</b> —— 已有真实下游（acx 插件采纳后发布为 {@code GENERATED}，
 *       仍需人工 approve）；</li>
 *   <li><b>携带器草稿</b> / <b>自编译请求</b> —— <b>目前只有落点、没有消费者</b>，
 *       回执与状态里如实标 {@code LANDED} 并注明无下游，不假装已生效。</li>
 * </ul>
 * <b>落地 ≠ 上线</b>：AC 那条链路也只到 {@code GENERATED} 为止。
 *
 * <p><b>但它<b>产出</b>可直接落库的草稿</b>（2026-10-02 B2/E3）：每条判定多给两个键 ——
 * {@code experience_draft}（七字段映射成的 {@code ExperienceEntry} 形状）+
 * {@code quality_gate}（硬质量门结论）。此前只给七字段原文，
 * 下游得自己猜怎么变成 {@code experience_learn} 的参数，
 * 而<b>那段映射代码从来没写过</b>（这就是 {@code 60} 号 B2）。
 * 映射做了 ≠ 有权发布：<b>落盘仍由 AI 调 experience_learn 决定</b>（doc 59 D3）。
 *
 * <p><b>必须异步</b>（2026-09-29 P0 修复）：本工具在 body tool 的
 * {@code onServerCall} 里跑，而那是 <b>服务器主线程入口</b>
 * （{@code ExecuteToolPayload.handleCompanion()} → {@code tool.onServerCall}）。
 * 在这里 {@code .join()} 等 LLM 会把服务器主线程卡住最多 120 秒 —— 实机表现为
 * 整局卡死。照抄 {@code AcExecuteTool} 的后台线程模式：立刻回执 RUNNING，
 * 复盘在后台线程跑完，再按原服务器身份回主线程投递结果。
 *
 * <p><b>世界代际校验</b>：发起时捕获 server 实例，完成时校验
 * {@code ServerLifecycleHooks.getCurrentServer() == captured}；换档/重连后
 * 迟到的回调直接丢弃，绝不把旧世界的判定投递给新世界。
 *
 * <p>取走语义：领取只读快照，<b>复盘成功且判定与 memoId 严格一一对应</b>才 commit；
 * 否则 restore 回队列（不丢数据）。
 */
final class LearnerReviewTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MAX = 5;
    private static final int HARD_MAX = 20;
    private static final int LLM_TIMEOUT_SEC = 120;

    @Override
    public String name() {
        return "learner_review";
    }

    @Override
    public NumenTool.Residency residency() {
        // 低频动作（复盘），不占每轮请求位置
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "Ask the learner to review queued memos and return structured verdicts. "
                + "Takes up to max_memos pending memos and reviews them asynchronously. "
                + "Returns review_id immediately; poll learner_status (or learner_review_result) for the "
                + "verdicts: actions (WRITE_EXPERIENCE / USE_AC / USE_CARRIER / SELF_COMPILE / "
                + "NO_ACTION / NEEDS_HUMAN) plus draft text. "
                + "It only PRODUCES suggestions; you decide whether to act on them "
                + "(e.g. call experience_learn or ac_execute yourself).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("max_memos", "How many memos to review now (1-20).", 1, HARD_MAX)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        final UUID id;
        try {
            id = companion.getUUID();
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("cannot resolve companion: " + e.getMessage()).toJson());
            return;
        }

        int max = DEFAULT_MAX;
        try {
            if (args != null && args.has("max_memos") && args.get("max_memos").isJsonPrimitive()) {
                int requested = args.get("max_memos").getAsInt();
                if (requested > 0) {
                    max = Math.min(requested, HARD_MAX);
                }
            }
        } catch (RuntimeException ignored) {
            // 参数坏掉就用默认值，不因此拒绝整次复盘
        }

        // 必须在主线程上读队列；此刻我们确实在主线程
        MemoQueue queue = LearnerPlugin.queue(id);
        List<Memo> taken = queue.drain(max);
        if (taken.isEmpty()) {
            reply.accept(TaskResult.ok("no pending memos to review", Map.of(
                    "reviewed", 0, "queue_depth", queue.size())).toJson());
            return;
        }

        MinecraftServer capturedServer = ServerLifecycleHooks.getCurrentServer();
        if (capturedServer == null) {
            // 没有服务器上下文就没法安全回主线程；诚实拒绝并把备忘录放回去
            int restored = queue.restore(taken);
            reply.accept(TaskResult.fail("no server context; memos restored (queue_depth=" + restored + ")")
                    .toJson());
            return;
        }

        final List<Memo> batch = taken;
        final String reviewId = "rev-" + System.currentTimeMillis() + "-" + Math.abs(batch.get(0).id().hashCode() % 1000);
        LearnerPlugin.beginReview(reviewId, id, "RUNNING", batch.size());

        // ★ 上轮回顾（架构 owner 2026-10-03 第 3 条 + 答 (a)）：
        //   「循环由 AI 自己在下一轮 learner_review 里看着上轮结果再改」。
        //   在**回执之前**读 —— 这样回执里就带得上「上轮回顾读到了没有 / 几轮 / 几件产物」，
        //   观测侧不必猜它到底看没看上轮（又是那种「从外面问不出来」的观测缺口）。
        final PriorRoundReader.Read priorRead = PriorRoundReader.read(
                PriorRound.DEFAULT_ROUNDS, PriorRound.DEFAULT_PRODUCT_CHARS);

        // 立刻回执，绝不在主线程等 LLM
        java.util.Map<String, Object> ack = new java.util.LinkedHashMap<>();
        ack.put("review_id", reviewId);
        ack.put("status", "RUNNING");
        ack.put("reviewing", batch.size());
        ack.put("queue_depth_after", queue.size());
        ack.putAll(priorRead.toMap());
        ack.put("next", "poll learner_status with review_id=" + reviewId);
        reply.accept(TaskResult.ok("learner review started; it runs in background", ack).toJson());

        // 后台线程做 LLM 复盘（阻塞等待只发生在这里，不在服务器主线程）
        Thread.ofVirtual().name("learner-review-" + reviewId).start(() -> {
            // ★ 拒收回喂（2026-10-05 实机教训）：投递箱读得到吗？读不到就传 null，
            //   RejectionFeedback 会**明说「读不到」**，不假装「没有拒收」。
            var ob = LearnerPlugin.outbox();
            boolean readable = ob != null;
            // ★ 先反射一次再回喂：ACX 的运行是异步的，复审这一刻可能有刚跑完的结果，
            //   而 prompt 里「上轮产出跑成没成」的价值全靠这些**非自报**的事实。
            //   反射失败只丢事实、不影响复审（Reflector 内部已 catch 并留痕）。
            LearnerPlugin.reflectAcxRunsOnce("REVIEW");
            var rej = com.dwinovo.numen.plugins.learner.core.RejectionFeedback.scan(ob, readable, id);
            // S3：本同伴产物的使用结果也进 prompt —— 不接这段，学习只在自我循环里打转。
            // ★ 必须传 id：投递箱与账本都是所有同伴共享的文件，不过滤就把别人的成败当成自己的。
            var usage = LearnerPlugin.usageLedger();
            LearnerReviewer.ReviewOutcome outcome =
                    LearnerReviewer.withPriorRound(batch, priorRead.summary(), rej,
                            com.dwinovo.numen.plugins.learner.core.UsageLedger.promptBlock(usage, id),
                            id, LLM_TIMEOUT_SEC).join();

            // 回主线程前校验世界代际：换档后迟到结果直接丢弃
            MinecraftServer current = ServerLifecycleHooks.getCurrentServer();
            if (current != capturedServer) {
                queue.restore(batch);
                LearnerPlugin.finishReview(reviewId, "STALE_WORLD", 0);
                LearnerMonitor.publish("review_stale", Map.of(
                        "review_id", reviewId, "companion", id.toString(), "restored", batch.size()));
                return;
            }
        capturedServer.execute(() -> completeOnServer(queue, id, reviewId, batch, outcome, priorRead));
    });
}

/** 在主线程收尾：commit 或 restore，然后把可查状态交给 learner_status。 */
    private void completeOnServer(MemoQueue queue, UUID id, String reviewId,
            List<Memo> batch, LearnerReviewer.ReviewOutcome outcome, PriorRoundReader.Read priorRead) {
        if (!outcome.ok()) {
            int restored = queue.restore(batch);
            LearnerPlugin.finishReview(reviewId, outcome.reason(), 0);
            LearnerMonitor.publish("review_failed", Map.of(
                    "review_id", reviewId, "companion", id.toString(),
                    "reason", outcome.reason(), "restored", restored,
                    "queue_depth", restored));
            return;
        }

        // P1 修复：只 commit「确实拿到判定」的条目，其余 restore 回去。
        // 绝不能因为「至少解析出一条」就删掉整批。
        java.util.Set<String> judged = new java.util.HashSet<>();
        for (Verdict v : outcome.verdicts()) {
            if (v.memoId() != null && !v.memoId().isBlank()) {
                judged.add(v.memoId());
            }
        }
        List<Memo> toCommit = new ArrayList<>();
        List<Memo> toRestore = new ArrayList<>();
        for (Memo m : batch) {
            if (judged.contains(m.id())) {
                toCommit.add(m);
            } else {
                toRestore.add(m);
            }
        }

        int removed = toCommit.isEmpty() ? 0 : queue.commitDrain(toCommit);
        int restored = toRestore.isEmpty() ? 0 : queue.restore(toRestore);
        int depth = queue.size();

        List<Map<String, Object>> rendered = new ArrayList<>();
        // ★★ 实机抓到的 bug（2026-10-05）：这里原来写的是
        //    `if (v.memoId() == null || !v.memoId().startsWith("m-")) continue;`
        //    用意是挡掉 "?" 这类占位 id —— 但它把 **learner_intake 造的 `ci-*` memo 全挡掉了**。
        //    后果：摄入来的材料被复审、被 commit（真从队列删了），
        //    而 reviewed 事件里 `reviewed:0 / verdicts:[]`，监测台与 learner_status 全看不见
        //    ⇒ 「看起来成功、实际丢了」，本工程最怕的那一类。
        //    正确判据不是前缀猜测，而是**是不是这批里的真实 memo id** —— 直接复用上面那个
        //    `judged`（它就是本批真实 id 集合）。"?" 不在其中，仍会被挡住。
        for (Verdict v : outcome.verdicts()) {
            if (v.memoId() == null || !judged.contains(v.memoId())) {
                // 防御：绝不允许 "?" 这类占位 id 流进监测台/下游
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("memo_id", v.memoId());
            // ★ B1：把这条判定产物的**使用结果**挂回来（从账本按 memoId 反查）。
            //   这是把「判定」与「执行事实」缝在一起的唯一键 —— 产物 id 里带 memoId。
            //   ★ 只挂事实：不含任何有效性判断（有效性仍要人显式声明）。
            var ul0 = LearnerPlugin.usageLedger();
            if (ul0 != null) {
                var usageRows = com.dwinovo.numen.plugins.learner.core.UsageLedger.usageByMemo(
                        ul0, v.memoId(), java.util.EnumSet.of(
                                ArtifactOutbox.Kind.AC_SCRIPT,
                                ArtifactOutbox.Kind.CARRIER));
                if (!usageRows.isEmpty()) {
                    one.put("usage_outcome", usageRows);
                } else {
                    // ★ 空与「查不到」要分开：没有产物 ≠ 查不到账本
                    one.put("usage_outcome_note", "该判定的产物尚无使用记录（未执行或账本里没有）");
                }
            } else {
                one.put("usage_outcome_note", "使用账本未初始化，查不到执行结果");
            }
            one.put("actions", v.actions().stream().map(Enum::name).toList());
            one.put("confidence", v.confidence());
            one.put("reasoning", v.reasoning());
            Memo source = batch.stream().filter(m -> v.memoId().equals(m.id())).findFirst().orElse(null);
            if (v.experience() != null) {
                // B21：只放真值；缺失的字段不出现；关键字段不齐要**说出来**
                Map<String, Object> ex = new java.util.LinkedHashMap<>();
                for (String f : Experience.FIELDS) {
                    String val = v.experience().field(f);
                    if (!val.isBlank()) {
                        ex.put(f, val);
                    }
                }
                one.put("experience", ex);
                one.put("experience_fields_filled", v.experience().filledCount());
                one.put("experience_acceptable", v.experience().acceptable());
                // ★ E4：分类**不进** experience 那个 map（那是七字段视图，混进去就看不出
                //   「这是正文还是分类」）。原样值与归一后值都放：原样才能看出它交了什么、
                //   归一后才能直接和 ExperienceType 对得上。
                one.put("experience_type_raw", v.experience().experienceType());
                one.put("experience_type", v.experience().typeName());
                if (!v.experience().typeComplete()) {
                    one.put("experience_type_problem", v.experience().typeProblem());
                }
                if (!v.experience().acceptable()) {
                    one.put("experience_unacceptable_reason", v.experience().unacceptableReason());
                    one.put("experience_missing_fields",
                            String.join(",", v.experience().missingFields()));
                }
            }
            // ★ B2/E3：七字段 → 经验库条目形状的映射 + 硬质量门。
            //   之前这里只给七字段原文，下游要自己猜怎么变成 experience_learn 的参数
            //   —— 「映射代码从来没写过」。现在直接给出可落库的条目形状与门禁结论，
            //   发布权仍在 AI/门禁（RL-20：学习者不自己执行，见类注释）。
            ExperienceDraft draft = null;
            String body = null;
            if (v.experience() != null) {
                try {
                    draft = ExperienceDraft.from(v, source);
                    body = draft.toJson().get("description").getAsString();
                    one.put("experience_draft", draftJson(draft));
                } catch (RuntimeException ex) {
                    one.put("experience_draft_error", "mapping failed: " + ex.getMessage());
                }
            } else if (v.actions().contains(Verdict.Action.WRITE_EXPERIENCE)) {
                // 说了要写经验却没交草稿 —— 明说出来，不给一个空壳
                one.put("experience_draft_missing", "actions 含 WRITE_EXPERIENCE 但没有结构化 experience");
            }
            // ⚠️ 门禁**无条件**跑（Codex 审核 P1-4）：「WRITE_EXPERIENCE 但没交七字段」
            //   必须被门禁拒绝，跳过它等于这种判定永远拿不到拒绝意见。
            //   body 传的是**映射后的 description**（机制+步骤+判据）而不是只 mechanism，
            //   否则「长机制 + derivation 写空话」能绕过去。
            ExperienceQualityGate.Result gate =
                    ExperienceQualityGate.evaluate(v.experience(), source, body);
            one.put("quality_gate", GSON.fromJson(gate.toMap(), Map.class));
            // 映射失败/无草稿 ⇒ 即使门禁没硬失败，也不给「可直接发布」的入口形态
            if (draft == null) {
                one.put("publishable", false);
            }
            if (!v.acScriptDraft().isBlank()) {
                one.put("ac_script_draft", v.acScriptDraft());
            }
            // ★ 2026-10-03：另两个载荷位也进回执。
            //   形状照 ac_script_draft：**非空才写键**（空串写进去等于说「这里有个空的」，
            //   而实际是「这次没写」—— 两者含义不同，别混）。
            //   ⚠️ 与 ac_script_draft 的区别：**这两类的下游仍然不存在**
            //   （USE_AC 已归 AC 线消费；携带器与自编译请求**只有落点没有消费者**）。
            //   所以回执只保证「学习者写出来了、且落盘了、调用方看得见」，
            //   **不假装已经生效** —— 真要生效得等下游接上。
            if (!v.carrierDraft().isBlank()) {
                one.put("carrier_draft", v.carrierDraft());
            }
            if (!v.selfCompileRequest().isBlank()) {
                one.put("self_compile_request", v.selfCompileRequest());
            }
            // 声明了某个产物却没给载荷位 —— 显式报出来，不让调用方以为「它写了」
            List<String> declaredButEmpty = new ArrayList<>();
            if (v.actions() != null) {
                if (v.actions().contains(Verdict.Action.USE_AC) && v.acScriptDraft().isBlank()) {
                    declaredButEmpty.add("USE_AC/ac_script_draft");
                }
                if (v.actions().contains(Verdict.Action.USE_CARRIER) && v.carrierDraft().isBlank()) {
                    declaredButEmpty.add("USE_CARRIER/carrier_draft");
                }
                if (v.actions().contains(Verdict.Action.SELF_COMPILE) && v.selfCompileRequest().isBlank()) {
                    declaredButEmpty.add("SELF_COMPILE/self_compile_request");
                }
            }
            if (!declaredButEmpty.isEmpty()) {
                one.put("declared_but_no_draft", declaredButEmpty);
            }
            if (!v.rewrittenQuery().isEmpty()) {
                one.put("rewritten_query", v.rewrittenQuery());
            }
            // ★ B6/S2（2026-10-04 夜间施工）：三个载荷位**落进投递箱**。
            //   此前它们只出现在这个 map 与 learner.jsonl 里，没有任何下游读 ——
            //   「学习者说要写，写完就没下文」。现在写盘 + 逐条投递回执。
            //   ⚠️ 投递 ≠ 生效：采纳/否决由下游回填 status，这里绝不写成「已上线」。
            one.put("artifact_delivery", deliverArtifacts(id, reviewId, v));
            rendered.add(one);

            // 携带器只携带不存储：把三段分级结论写进观测，不落任何状态文件
            batch.stream().filter(m -> v.memoId().equals(m.id())).findFirst().ifPresent(m -> {
                // B21（缺失的表达方式）：与 LearnerNoteTool 同一个边界的两处泄漏，一起修。
                // 契约在 core 的 Memo.carrierSignal()（纯 JVM，单测跑得到），本类只做拼装 + 换键名。
                // 旧写法无条件发 hp=-1 / target="UNKNOWN" / carry=[]，
                // 下游分不清「真判出 UNKNOWN」与「压根没快照可判」。
                Map<String, Object> sig = m.carrierSignal();
                Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("memo_id", m.id());
                ev.put("snapshot_present", sig.get("snapshot_present"));
                if (Boolean.TRUE.equals(sig.get("snapshot_present"))) {
                    ev.put("target", sig.get("carrier_target"));
                    ev.put("carry", sig.get("carry_list"));
                    if (sig.containsKey("carrier_hp")) {
                        ev.put("hp", sig.get("carrier_hp"));
                    }
                    ev.put("why", m.carrierPreview());
                } else {
                    // 刻意不发 why：它含内部措辞「无环境快照，无法分级」，对下游没有可执行信息
                    ev.put("carry", sig.get("carry_list"));
                    ev.put("carry_meaning", sig.get("carry_list_meaning"));
                }
                LearnerMonitor.publish("carrier_assessment", ev);
            });
        }

        String at = Instant.now().toString();
        String state = rendered.isEmpty() ? "NO_VERDICT" : "DONE";
        LearnerPlugin.finishReview(reviewId, state, rendered.size());
        LearnerPlugin.recordReview(at, rendered.size());

        java.util.Map<String, Object> reviewedEv = new java.util.LinkedHashMap<>();
        reviewedEv.put("review_id", reviewId);
        reviewedEv.put("companion", id.toString());
        reviewedEv.put("reviewed", rendered.size());
        reviewedEv.put("committed", removed);
        reviewedEv.put("restored", restored);
        reviewedEv.put("queue_depth", depth);
        reviewedEv.put("verdicts", rendered);
        // ★ 上轮回读的读数也进这条事件：E2 那次「它到底有没有去看」的判断之所以要靠人工翻日志，
        //   就是因为观测侧完全看不到这一步。现在它在 learner.jsonl 里就有。
        reviewedEv.put("prior_round", priorRead.toMap());
        LearnerMonitor.publish("reviewed", reviewedEv);
        LearnerPlugin.setReviewVerdicts(reviewId, rendered);

        // ★ 2026-10-05：把上面这些数字**说给主人听**。
        //   此前学习者一直在写、从不说话（用户原话「不是一直裸着写」）。
        //   措辞与限频都在 Announce/AnnounceText 里，那两个类是纯 Java、可离线测；
        //   这里只在服务端线程收尾处喊一句（发字必须走 resolveOwnerPlayer）。
        //   ★ 只说事实（学了几条/落了什么/拒了什么），不写「我学会了」这类自评 ——
        //     学习者自己打分会立刻变成自我印证。
        announceToOwner(id, rendered.size(), removed, restored);
    }

    /**
     * 复审收尾时说一句。
     *
     * <p><b>失败不许影响复审</b>：说话只是锦上添花，绝不能因为发字失败把一次
     * 已经成功的复审判成失败 —— 那是把观测手段当成业务逻辑。
     */
    private static void announceToOwner(java.util.UUID companionId, int verdictCount,
                                        int committed, int restored) {
        try {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            NumenPlayer companion = NumenPlayer.findByUuid(server, companionId);
            if (companion == null) {
                // 换档/同伴已卸载：明说不能，不静默假装说过
                LearnerMonitor.publish("announce_skipped", java.util.Map.of(
                        "reason", "COMPANION_NOT_FOUND",
                        "companion", companionId.toString()));
                return;
            }
            int acLanded = 0;
            int acRejected = 0;
            int acAdopted = 0;
            int carrierPending = 0;
            var ob = LearnerPlugin.outbox();
            if (ob != null) {
                // ★ 刻意不用 stats().get("AC_SCRIPT")：那是**文件总数**，
                //   把已采纳的、已拒收的全算进来。发出去的话会说成「5 条已落箱待采纳」
                //   而实际只有一部分在等 —— **措辞不诚实比不说更坏**。
                //   这里按状态分开数：还在等的 / 已被拒的。
                for (var o : ob.list(ArtifactOutbox.Kind.AC_SCRIPT)) {
                    String st = o.has("status") ? o.get("status").getAsString() : "";
                    if (ArtifactOutbox.Status.REJECTED.name().equals(st)) {
                        acRejected++;
                    } else if (ArtifactOutbox.Status.ADOPTED.name().equals(st)) {
                        acAdopted++;
                    } else {
                        acLanded++;
                    }
                }
                for (var o : ob.list(ArtifactOutbox.Kind.CARRIER)) {
                    String st = o.has("status") ? o.get("status").getAsString() : "";
                    if (!ArtifactOutbox.Status.REJECTED.name().equals(st)
                            && !ArtifactOutbox.Status.ADOPTED.name().equals(st)) {
                        carrierPending++;
                    }
                }
            }
            boolean said = com.dwinovo.numen.plugins.learner.core.Announce.announceReview(
                    companion, verdictCount, committed, restored, acLanded, carrierPending, acRejected,
                    acAdopted);
            // ★ 说出去 / 被限频吞掉 / 被关掉 —— 三种都要落到监测台。
            //   否则「它到底说了没有」又变成一件查不到的事。
            java.util.Map<String, Object> annEv = new java.util.LinkedHashMap<>();
            annEv.put("companion", companionId.toString());
            annEv.put("said", said);
            annEv.put("enabled", com.dwinovo.numen.plugins.learner.core.Announce.enabled());
            annEv.put("min_gap_seconds",
                    com.dwinovo.numen.plugins.learner.core.Announce.minGapSeconds());
            annEv.put("reason", said ? "OK"
                    : (com.dwinovo.numen.plugins.learner.core.Announce.enabled()
                    ? "RATE_LIMITED_OR_OWNER_OFFLINE" : "DISABLED"));
            annEv.put("verdicts", verdictCount);
            annEv.put("committed", committed);
            annEv.put("restored", restored);
            annEv.put("ac_landed", acLanded);
            annEv.put("ac_adopted", acAdopted);
            annEv.put("carrier_pending", carrierPending);
            annEv.put("ac_rejected", acRejected);
            LearnerMonitor.publish("announce", annEv);
        } catch (RuntimeException e) {
            LearnerMonitor.publish("announce_failed", java.util.Map.of(
                    "companion", companionId.toString(),
                    "error", String.valueOf(e.getMessage())));
        }
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 把这条判定的产物投进投递箱（B6/S2）。
     *
     * <p>逐产物独立成败：某一类投失败<b>不</b>影响其余两类，也不影响本轮 review 的 commit —
     * 「全成功或全失败」在这里是错的语义（回滚会把已经投出去的东西变成没投过）。
     *
     * <p><b>经验不在这里投</b>：学习者不自己落经验库（RL-20 / 59 D3），仍由 AI 调
     * {@code experience_learn}。这里显式记一条 {@code DELEGATED}，免得观测侧以为
     * 「投递箱里有四条产物」或「经验这条没交」——两种误读都会把问题引到错的地方。
     */
    private static List<Map<String, Object>> deliverArtifacts(UUID companionId, String reviewId, Verdict v) {
        List<Map<String, Object>> out = new ArrayList<>();
        ArtifactOutbox ob = LearnerPlugin.outbox();
        if (ob == null) {
            out.add(deliveryRow("OUTBOX", "FAILED", "学习者插件未完成 setup，投递箱不存在（产物只留在回执里）", ""));
        } else {
            submit(ob, out, ArtifactOutbox.Kind.AC_SCRIPT, companionId, reviewId, v,
                    v.acScriptDraft(), Verdict.Action.USE_AC);
            submit(ob, out, ArtifactOutbox.Kind.CARRIER, companionId, reviewId, v,
                    v.carrierDraft(), Verdict.Action.USE_CARRIER);
            submit(ob, out, ArtifactOutbox.Kind.SELF_COMPILE_REQUEST, companionId, reviewId, v,
                    v.selfCompileRequest(), Verdict.Action.SELF_COMPILE);
        }
        if (v.experience() != null || v.actions().contains(Verdict.Action.WRITE_EXPERIENCE)) {
            out.add(deliveryRow("EXPERIENCE", "DELEGATED",
                    "学习者不自己落经验库（RL-20 / 59 D3）：experience_draft 由 AI 调 experience_learn 落库，投递箱不代投",
                    ""));
        }
        return out;
    }

    private static void submit(ArtifactOutbox ob, List<Map<String, Object>> out, ArtifactOutbox.Kind kind,
                               UUID companionId, String reviewId, Verdict v, String body,
                               Verdict.Action declaredBy) {
        String memoId = v.memoId();
        if (body == null || body.isBlank()) {
            if (v.actions().contains(declaredBy)) {
                // 声明了却没内容：这不是投递失败，是学习者交不出东西，必须看得见
                out.add(deliveryRow(kind.wire(), "REJECTED",
                        "声明了 " + declaredBy + " 但 " + kind.wire() + " 为空，没有可投的内容", ""));
            }
            return;
        }
        try {
            String name = kind == ArtifactOutbox.Kind.AC_SCRIPT ? acName(body) : kind.wire();
            ArtifactOutbox.Delivery d = ob.submit(kind, companionId, reviewId, memoId, name, body);
            out.add(deliveryRow(kind.wire(), d.status(), d.detail(), d.path()));
        } catch (RuntimeException e) {
            // 一类产物投失败不许连坐别的，也不许把整轮 review 变成「失败」
            out.add(deliveryRow(kind.wire(), "FAILED",
                    "投递箱写入异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), ""));
        }
    }

    /** AC 草稿名：能解析出 name 就用它（便于下游按名字归档），解析不出就用摘要占位。 */
    private static String acName(String body) {
        try {
            var e = com.google.gson.JsonParser.parseString(body);
            if (e.isJsonObject()) {
                var o = e.getAsJsonObject();
                for (String k : new String[]{"name", "ac_name", "script_name"}) {
                    if (o.has(k) && o.get(k).isJsonPrimitive()) {
                        String n = o.get(k).getAsString().trim();
                        if (!n.isEmpty()) {
                            return n;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON 是合法情况（模型可能交了散文）：名字退化不影响下游校验
        }
        return "ac-draft";
    }

    private static Map<String, Object> deliveryRow(String kind, String status, String detail, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("status", status);
        m.put("detail", detail);
        if (path != null && !path.isBlank()) {
            m.put("path", path);
        }
        return m;
    }

    /**
     * 草稿的对外形状：{@code {entry, mapping}}。
     * 映射失败在调用处已经单独记了 {@code experience_draft_error}，这里不重复包装。
     */
    private static Map<String, Object> draftJson(ExperienceDraft draft) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry", GSON.fromJson(draft.toJson(), Map.class));
        out.put("mapping", GSON.fromJson(draft.explain(), Map.class));
        return out;
    }
}
