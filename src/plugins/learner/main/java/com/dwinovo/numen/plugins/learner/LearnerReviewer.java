package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.BudgetPolicy;
import com.dwinovo.numen.agent.llm.LlmObservation;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 学习者的「复盘」：拿一批备忘录去问 LLM，拿回结构化判定。
 *
 * <p>调用方式照抄 {@code RddDecomposer.llmAsk}（plugins/rdd/.../RddDecomposer.java:55-82）：
 * 从 {@link Services#CONFIG} 取端点 → {@link NumenLlmClient#forEndpoint} →
 * {@code chatStreaming}（无工具、纯文本，抄 EntityAgentLoop.java:1424 的用法）。
 *
 * <p><b>降级契约</b>：没配 apiKey / 端点失败 / 回复解析不出来，
 * 一律返回带 <b>ok=false</b> 的 {@link ReviewOutcome}，由工具层如实回报
 * {@code LLM_UNAVAILABLE} 或 {@code UNPARSEABLE}。
 * 绝不返回空判定冒充成功（规格 §3 质量门）。
 */
final class LearnerReviewer {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerReviewer.class);

    /** 复盘结果：要么有判定且 ok=true，要么 ok=false 且带明确原因。 */
    record ReviewOutcome(boolean ok, String reason, List<Verdict> verdicts) {

        static ReviewOutcome fail(String reason) {
            return new ReviewOutcome(false, reason, List.of());
        }

        static ReviewOutcome ok(List<Verdict> verdicts) {
            return new ReviewOutcome(true, "OK", verdicts);
        }
    }

    private static final String SYSTEM_TEMPLATE = """
            你是 Numen 的「学习者」。你的唯一职责是复盘干活 AI 留下的原始备忘录，判断这些经历
            以后该不该沉淀成经验、该不该用原子编排(AC)重做一遍、要不要先做携带分级、要不要自编译出新工具，
            或者根本不需要动作。

            硬规则：
            1. 只输出 JSON，不要任何解释性文字、不要 markdown 代码块之外的内容。
            2. 每条备忘录单独输出一个对象。
            3. actions 是数组，可多选，取值只能是：
               WRITE_EXPERIENCE / USE_AC / USE_CARRIER / SELF_COMPILE / NO_ACTION / NEEDS_HUMAN
            4. 只有真正可复用的教训才给 WRITE_EXPERIENCE；一次性意外给 NO_ACTION。
            5. SELF_COMPILE 只在「明确缺少工具能力」时给；能靠现有工具解决就不要给。
            6. confidence 取 0 到 1。
            7. rewritten_query 给出 1 到 3 个用于检索经验库的改写检索词（这是替代向量检索的检索键）。
            8. ★ 经验必须是**结构化对象**，不是一段话。%s
            9. 给 WRITE_EXPERIENCE 就**必须**同时给出 experience 对象；给 NO_ACTION 时不要给。
            10. 不要输出 experience_draft 这个键（旧格式，已废弃；一段散文不算经验）。
            11. ★ experienceType 是**分类事实**，从 5 个里挑**一个**；挑不出来说明还没想清楚
            这是哪一类 → 给 NO_ACTION，**不要拿近义词顶**（分类错等于把这条经验劈成两条）。
            12. ★ 2026-10-03 补了三个载荷位。声明了就必须给，可以给空串表示「这次不写」，
            但**不许声明了又不给理由**：声明 USE_AC 必须给 ac_script_draft，
            声明 USE_CARRIER 必须给 carrier_draft，声明 SELF_COMPILE 必须给 self_compile_request。
            （以前「声明了却没地方写」——学习者说要写却交不出内容，回执里也没线索说明它本该写什么。）
            13. 同一条记录可以**同时**给多种产物（如既 WRITE_EXPERIENCE 又 USE_AC）。
            它们是**并列**的，不是流水线（架构 owner 2026-10-03 明确）。
            14. self_compile_request 写的是**现象 + 最小复现 + 环境快照**（结构化待办），
            不是写代码 —— 下游不自动写码，由外层工程流接手（B11）。
            14b. ★★ ac_script_draft **必须是 ACX 库的 JSON**，不是散文（2026-10-05 实机教训）。
                 下游 AcxArtifactAdopter 会解析 + 静态校验，失败就**显式 REJECTED** 并把原文
                 留在投递箱里等人改。实测两种真实失败：交散文 ⇒ JSON 解析失败整条被拒；
                 交 JSON 但字段名猜错 ⇒ 校验失败被拒。材料都被复审、被 commit，
                 却什么产物都没留下 —— 看起来成功、实际白跑。
                 ★ 字段名**不许自创**，照抄这个形状（真实 .ac 脚本就是这样）：
                 {"name":"low_hp_disengage","version":"1","description":"...",
                  "steps":[{"id":"read","block":"get_self_status","params":{}},
                           {"id":"gate","block":"guard",
                            "params":{"conditions":[{"field":"$read.hp","op":"<","value":4}]}},
                           {"id":"retreat","block":"goto","params":{"x":0,"y":0,"z":0}}]}
                 硬要求：
                   - 每个 step 必须有 **id**（唯一）、**block**（工具名）、**params**（对象）
                   - 用的是 block/params，**不是** action/args
                   - **没有 trigger 字段**：条件写成 `block:"guard"` 的 step，
                     conditions 里用 `$<上一步 id>.<字段>` 引用上一步的输出
                   - version 固定 "1"；name 用小写下划线
                 ★★ **不许交「看起来能过、其实什么都不做」的空壳**：
                   若这条脚本的意图是移动 / 采集 / 战斗 / 合成这类**动作**，
                   它的 steps 里就必须有对应的**动作积木**；只放一个 get_self_status 的动作脚本
                   就是空壳 —— 它会真的被执行、真的报 SUCCESS，而世界一点没变。
                   实测踩过：名字叫「低血量逃跑再喝奶」，跑了 1 步读状态就 RUN_FINISHED SUCCESS。
                   （**纯诊断/观察**意图的脚本只放只读积木是对的，不受这条限制。）
                 ★★ 积木名只能从下面【可用积木】表里挑。**表里没有的名字 = 不存在**，
                   不许猜、不许自创、不许照抄别的项目的名字（旧提示词的示例里写过不存在的
                   `block:"move"`，AI 照抄 ⇒ 必然被拒 ⇒ 连拒之后学会只交空壳）。
                   凑不出可执行的动作序列时，**不要**声明 USE_AC ——
                   改给 NO_ACTION / NEEDS_HUMAN 并写清缺哪一项能力，那比交空壳诚实得多。

            输出格式：
            {"verdicts":[{"memo_id":"...","actions":["WRITE_EXPERIENCE"],"confidence":0.7,
              "reasoning":"...","experience":{"mechanism":"...","preconditions":"...",
              "failureConditions":"...","observableSignal":"...","derivation":"...",
              "efficiency":"...","evidence":"...","experienceType":"FAILURE"},"ac_script_draft":"",
              "carrier_draft":"","self_compile_request":"","rewritten_query":["..."]}]}
            """;

    // 2026-10-01：把七字段格式说明（%s）填进 SYSTEM。**格式不要求，模型就不会给** ——
    // V4 实机失败的直接原因就是 prompt 里只说了 "experience_draft":"..."，
    // 模型于是交了一段散文。这里把格式说明作为 prompt 的一部分显式注入。
    private static final String SYSTEM = SYSTEM_TEMPLATE
            .formatted(com.dwinovo.numen.plugins.learner.core.Experience.promptSpec());

    private LearnerReviewer() {}

    /**
     * 复盘一批备忘录。<b>不带</b>上轮回顾的入口（见 {@link #withPriorRound}）。
     *
     * <p>不阻塞调用线程：返回的 future 完成后才算完。失败时
     * {@link ReviewOutcome#ok} 为 false，队列条目应被 restore。
     */
    static CompletableFuture<ReviewOutcome> review(List<Memo> memos, int timeoutSeconds) {
        return withPriorRound(memos, null, timeoutSeconds);
    }

    /**
     * 复盘一批备忘录，<b>并把上轮产出摆进 prompt</b>。
     *
     * <p>架构 owner 2026-10-03 第 3 条 + 答 (a)：「循环由 AI 自己在下一轮
     * {@code learner_review} 里看着上轮结果再改」。那「上轮结果」就得在 prompt 里看得见 ——
     * 否则每轮都被当成第一次见面，改的其实是不同的东西。
     *
     * <p>★ <b>两段文本的先后是有意的</b>：上轮回顾放在<b>本轮备忘录之前</b>。
     * 放后面会被本轮那批备忘录的细节冲淡，而它恰恰是本轮判断的<b>参照系</b>
     * （「这条跟上轮那条说的是同一件事吗」这个问题，只在先看到上轮时才问得出来）。
     *
     * @param prior 上轮回顾；{@code null} 或「没有上轮」都走同一句明确的话，
     *              <b>不留空</b> —— 复盘方必须知道「没有上轮」是一条结论，不是消息漏了
     */
    static CompletableFuture<ReviewOutcome> withPriorRound(List<Memo> memos,
                                                           com.dwinovo.numen.plugins.learner.core.PriorRound.Summary prior,
                                                           int timeoutSeconds) {
        return withPriorRound(memos, prior, null, null, timeoutSeconds);
    }

    /**
     * 再加一条通道：把<b>被下游拒收的产物 + 拒收原因</b>也摆进 prompt。
     *
     * <p>★ 这是 2026-10-05 实机三轮踩出来的：AI 连拒三次且<b>每次都不知道自己错在哪</b>，
     * 因为拒收原因只写在投递箱里、没人回喂给它。详见 {@link com.dwinovo.numen.plugins.learner.core.RejectionFeedback}。
     */
    static CompletableFuture<ReviewOutcome> withPriorRound(List<Memo> memos,
                                                           com.dwinovo.numen.plugins.learner.core.PriorRound.Summary prior,
                                                           com.dwinovo.numen.plugins.learner.core.RejectionFeedback rejections,
                                                           String usageText,
                                                           int timeoutSeconds) {
        return withPriorRound(memos, prior, rejections, usageText, null, timeoutSeconds);
    }

    /**
     * 同上，外加<b>归属同伴</b>（只为观测计量用；{@code null} = 不知道，不编造）。
     *
     * <p>★ 2026-10-06 E0：学习者的 LLM 调用此前**没有挂 observation** ——
     * 三个脑里只有它花钱不留痕（llm_usage 事件缺失）。补传后 learner.jsonl 里
     * 同样能看到每次复盘的耗时与四元用量。
     */
    static CompletableFuture<ReviewOutcome> withPriorRound(List<Memo> memos,
                                                           com.dwinovo.numen.plugins.learner.core.PriorRound.Summary prior,
                                                           com.dwinovo.numen.plugins.learner.core.RejectionFeedback rejections,
                                                           String usageText,
                                                           java.util.UUID companionId,
                                                           int timeoutSeconds) {
        INumenConfig cfg;
        try {
            cfg = Services.CONFIG;
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(ReviewOutcome.fail("CONFIG_UNAVAILABLE: " + t));
        }
        if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            return CompletableFuture.completedFuture(ReviewOutcome.fail("LLM_UNAVAILABLE: no apiKey configured"));
        }
        // 预算闸（E1 enforce）：learning 限额用尽 → 本轮复盘推迟，返回 fail
        // （fail 语义 = 队列条目 restore，下一轮唤醒时重查预算，窗口一过自动恢复）。
        // shadow 模式恒为 null；学习预算只拦学习调用，不影响执行/生存路径。
        BudgetPolicy.Advice block = BudgetPolicy.checkBlock("review", 0);
        if (block != null) {
            if (BudgetPolicy.notifyBlock(block)) {
                LOG.warn("[learner] 预算拦截: role={} exceeded={} usedTokens={} limitTokens={}",
                        block.role(), block.exceeded(), block.used().tokens(), block.limit().tokensPerWindow());
                LearnerMonitor.publish("budget_block", java.util.Map.ofEntries(
                        java.util.Map.entry("actor", "learner"),
                        java.util.Map.entry("companionId", companionId == null ? "" : companionId.toString()),
                        java.util.Map.entry("status", "blocked"),
                        java.util.Map.entry("role", block.role()),
                        java.util.Map.entry("exceeded", String.join("+", block.exceeded())),
                        java.util.Map.entry("usedTokens", block.used().tokens()),
                        java.util.Map.entry("usedCalls", block.used().calls()),
                        java.util.Map.entry("usedMillis", block.used().millis()),
                        java.util.Map.entry("projectedTokens", block.projectedTokens()),
                        java.util.Map.entry("limitTokens", block.limit().tokensPerWindow()),
                        java.util.Map.entry("limitCalls", block.limit().callsPerWindow()),
                        java.util.Map.entry("limitMinutes", block.limit().minutesPerWindow()),
                        java.util.Map.entry("mode", block.mode())));
            }
            return CompletableFuture.completedFuture(
                    ReviewOutcome.fail("BUDGET_BLOCKED: learning 预算已用尽，本轮复盘推迟"));
        }

        // ★ 拼装下沉到 core.PriorRound：这条要能用单测钉住（见那边的方法注释）。
        //   本方法只负责拿到配置、发出请求、解析回复。
        String user = com.dwinovo.numen.plugins.learner.core.PriorRound.buildUserPrompt(
                memos, prior, rejections, usageText);

        LlmEndpoint ep = new LlmEndpoint(cfg.getProvider(), cfg.getModel(), cfg.getApiKey(),
                cfg.getBaseUrl(), cfg.getProxy(), "auto");

        String system = SYSTEM + "\n\n"
                // ★ 2026-10-06：把「真的有哪些积木」摆进 system。
                //   此前 AI 只能照抄模板里那个手写示例，而示例里写着不存在的 block "move"；
                //   连拒三次后它学会交只读空壳（实测：low_hp_flee_before_drink 只跑 1 步就 SUCCESS）。
                //   清单由 acx 插件从**真实注册表**生成（见 AcxPlugin.writeBlockCatalog），
                //   所以「AI 看到的」与「执行器认的」不可能漂。
                + com.dwinovo.numen.plugins.learner.core.AcxBlockReference
                        .promptBlock(LearnerPlugin.configDir());
        // E0 观测：actor=learner，相位=review；同伴未知时空串（不编造身份）。
        LlmObservation observation = new LlmObservation("learner",
                companionId == null ? "" : companionId.toString(), "review",
                LearnerMonitor::publish);
        try {
            return NumenLlmClient.forEndpoint(ep)
                    .chatStreaming(List.of(new ConvoState.Msg.User(user)),
                            List.of(), system, null, observation)
                    .orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .handle((res, err) -> {
                        if (err != null) {
                            LOG.warn("[learner] LLM 复盘失败: {}", err.toString());
                            return ReviewOutcome.fail("LLM_FAILED: " + err);
                        }
                        String text = res.turn() == null ? null : res.turn().content();
                        if (text == null || text.isBlank()) {
                            return ReviewOutcome.fail("LLM_EMPTY: empty turn content");
                        }
                        return parseAll(memos, text);
                    });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(ReviewOutcome.fail("LLM_DISPATCH_FAILED: " + t));
        }
    }

    /** 把 LLM 的单个 JSON 回复拆成每条 memo 的判定。 */
    private static ReviewOutcome parseAll(List<Memo> memos, String rawText) {
        String cleaned = stripFence(rawText.trim());
        try {
            var root = com.google.gson.JsonParser.parseString(cleaned);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("verdicts")) {
                // 兜底：模型可能直接输出单个 verdict 对象（只在这一条 memo 时才认）
                return parseSingle(memos, cleaned);
            }
            var arr = root.getAsJsonObject().getAsJsonArray("verdicts");
            java.util.Set<String> known = new java.util.HashSet<>();
            for (Memo m : memos) {
                known.add(m.id());
            }
            List<Verdict> verdicts = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (var e : arr) {
                // P1 修复：memo_id 由 Verdict.parse 从 JSON 里读；空 id 直接判为不合法，
                // 绝不再用 "?" 占位把「同一份判定」复制给整批。
                Verdict v = Verdict.parse("", e.toString());
                if (v == null) {
                    continue;
                }
                if (!known.contains(v.memoId())) {
                    // 陌生 id（模型编的）不采纳
                    continue;
                }
                if (!seen.add(v.memoId())) {
                    // 同一条 memo 重复判定：只认第一次
                    continue;
                }
                verdicts.add(v);
            }
            if (verdicts.isEmpty()) {
                return ReviewOutcome.fail("UNPARSEABLE: no valid verdict with a known memo_id");
            }
            return ReviewOutcome.ok(verdicts);
        } catch (RuntimeException e) {
            return ReviewOutcome.fail("UNPARSEABLE: " + e);
        }
    }

    private static ReviewOutcome parseSingle(List<Memo> memos, String cleaned) {
        if (memos.size() != 1) {
            // 批量请求不接受「一份单条判定」冒充整批
            return ReviewOutcome.fail("UNPARSEABLE: single verdict returned for a batch of "
                    + memos.size());
        }
        Verdict v = Verdict.parse("", cleaned);
        if (v == null || !v.memoId().equals(memos.get(0).id())) {
            return ReviewOutcome.fail("UNPARSEABLE: single verdict memo_id does not match the memo");
        }
        return ReviewOutcome.ok(List.of(v));
    }

    private static String stripFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int nl = s.indexOf('\n');
        if (nl < 0) {
            return s;
        }
        int last = s.lastIndexOf("```");
        return last <= nl ? s.substring(nl + 1) : s.substring(nl + 1, last).trim();
    }
}
