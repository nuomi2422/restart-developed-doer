package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_usage}：产物<b>使用账本</b>的读写口（S3，2026-10-05）。
 *
 * <p>四个动作：
 * <ul>
 *   <li>{@code record} —— 记一条「呈现/采用/执行/结果」；</li>
 *   <li>{@code report} —— 看结论 + <b>候选关联清单</b>（成功但还没人声明有效性的）；</li>
 *   <li>{@code claim_validity} —— <b>唯一的有效性证据入口</b>，必须给理由；</li>
 *   <li>{@code phase}/{@code outcome} 的取值见下（写错直接报，不静默纠正）。</li>
 * </ul>
 *
 * <p><b>★ 这个工具刻意不给「自动判定有效性」的口子</b>。
 * 时间上的先后不是因果：「被呈现过 + 后来成功了」只进<b>候选关联</b>清单等审。
 */
final class LearnerUsageTool implements NumenTool {

    @Override
    public String name() {
        return "learner_usage";
    }

    @Override
    public String description() {
        return "产物使用账本：学习产出被下游拿去用之后，把「呈现→采用→执行→结果」记下来，"
                + "让下一轮复审看得见上轮产出到底有没有用、结果如何。"
                + "★ reflect=从 ACX 运行记录反射**真实执行结果**（非自报的事实源，优先用这个）。"
                + "★ 被用过且成功**只是候选关联，不构成有效性证据** —— "
                + "只有 claim_validity（必须给理由）才算证据。";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.RESIDENT;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action",
                        "record | report | claim_validity | reflect | by_memo",
                        "record", "report", "claim_validity", "reflect", "by_memo")
                .optionalString("artifact_id", "Which artifact (from report). Required except for report.")
                .optionalEnum("kind", "Artifact kind: AC_SCRIPT | CARRIER | SELF_COMPILE_REQUEST | EXPERIENCE",
                        "AC_SCRIPT", "CARRIER", "SELF_COMPILE_REQUEST", "EXPERIENCE")
                .optionalString("name", "Artifact name, for readability in the ledger.")
                .optionalEnum("phase", "PRESENTED | ADOPTED | EXECUTED | RESULT",
                        "PRESENTED", "ADOPTED", "EXECUTED", "RESULT")
                .optionalEnum("outcome", "Only for phase=RESULT: SUCCESS | FAIL | CANCELED | UNKNOWN",
                        "SUCCESS", "FAIL", "CANCELED", "UNKNOWN")
                .optionalString("detail", "Short factual note. Facts only, no conclusions.")
                .optionalString("reason", "REQUIRED for claim_validity: why you call it valid evidence.")
                .optionalString("memo_id",
                        "REQUIRED for by_memo: which verdict's memo_id (from learner_review / learner_status).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String action = str(args, "action", "report").trim().toUpperCase(java.util.Locale.ROOT);
        try {
            UsageLedger ul = LearnerPlugin.usageLedger();
            if (ul == null) {
                reply.accept(TaskResult.fail("使用账本未初始化（插件 setup 没跑完？）").toJson());
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            switch (action) {
                case "RECORD" -> {
                    String id = str(args, "artifact_id", "").trim();
                    if (id.isBlank()) {
                        reply.accept(TaskResult.fail("record 需要 artifact_id").toJson());
                        return;
                    }
                    UsageLedger.Phase phase = phase(str(args, "phase", ""));
                    if (phase == null) {
                        reply.accept(TaskResult.fail("phase 只认 PRESENTED/ADOPTED/EXECUTED/RESULT，收到: "
                                + str(args, "phase", "")).toJson());
                        return;
                    }
                    UsageLedger.Outcome outcome = outcome(str(args, "outcome", ""));
                    // ★ 同伴 id 由服务端取，不接受调用方传（传了也无法验证）
                    String companionId = companion == null ? "" : companion.getUUID().toString();
                    UsageLedger.Entry e = ul.append(id, str(args, "kind", ""), str(args, "name", ""),
                            phase, outcome, str(args, "detail", ""), "learner_usage", companionId);
                    data.putAll(ul.toMap(List.of(e)));
                    if (outcome == UsageLedger.Outcome.SUCCESS) {
                        data.put("note", "★ 成功只记为**候选关联**；要算有效性证据请另调 claim_validity（要给理由）");
                    }
                    reply.accept(TaskResult.ok("usage recorded: " + id + " " + e.phase() + "/" + e.outcome(), data).toJson());
                }
                case "REPORT" -> {
                    // ★ 只列本同伴的：账本是共享文件，列出别人的会让 AI 以为那是自己的成败
                    String cid = companion == null ? "" : companion.getUUID().toString();
                    java.util.List<UsageLedger.Entry> mine = new java.util.ArrayList<>();
                    for (UsageLedger.Entry e : ul.conclusions().values()) {
                        if (cid.equals(e.companionId())) {
                            mine.add(e);
                        }
                    }
                    data.putAll(ul.toMap(List.copyOf(mine)));
                    List<UsageLedger.Entry> cands = ul.associationCandidates();
                    int mineCands = 0;
                    for (UsageLedger.Entry e : cands) {
                        if (cid.equals(e.companionId())) {
                            mineCands++;
                        }
                    }
                    data.put("association_candidates", mineCands);
                    data.put("association_candidates_meaning",
                            "成功但还没人声明有效性 —— 待审清单，**不是**证据");
                    data.put("scope", "只列本同伴（账本是所有同伴共享的文件）");
                    reply.accept(TaskResult.ok("learner usage report", data).toJson());
                }
                case "CLAIM_VALIDITY" -> {
                    String id = str(args, "artifact_id", "").trim();
                    String reason = str(args, "reason", "").trim();
                    if (id.isBlank() || reason.isBlank()) {
                        reply.accept(TaskResult.fail(
                                "claim_validity 需要 artifact_id 与 reason（没理由的有效性证据无法追责）").toJson());
                        return;
                    }
                    // NumenPlayer 没有 getName()，用同伴 UUID 当「谁声明的」——
                    // 有 UUID 才追得到人，写 "unknown" 会让追责断线
                    ul.claimValidity(id, reason, companion == null ? "unknown"
                            : companion.getUUID().toString());
                    data.putAll(ul.toMap(List.of(ul.latestOf(id))));
                    reply.accept(TaskResult.ok("validity claimed: " + id, data).toJson());
                }
                case "REFLECT" -> {
                    // B1：把 ACX 的真实运行结果读进账本。**这是非自报的事实源**，
                    //   与 record（AI 自己写）性质不同，回执里要说清。
                    var r = LearnerPlugin.reflectAcxRunsOnce("TOOL");
                    if (r == null) {
                        reply.accept(TaskResult.fail(
                                "反射不可用：投递箱或账本未初始化（插件 setup 没跑完？）").toJson());
                        return;
                    }
                    data.putAll(r.toMap());
                    data.put("source_of_truth", "ACX config/numen/acx/records.jsonl");
                    reply.accept(TaskResult.ok("已反射 ACX 运行结果", data).toJson());
                }
                case "BY_MEMO" -> {
                    // ★ 为什么需要这个动作：判定一旦复审完，memo 就已从队列 commit 掉，
                    //   再想复审同一条是做不到的（队列里没有它了）。
                    //   而「这条判定产出的东西后来跑成没成」恰恰是**事后**才发生的事 ——
                    //   没有这个只读查询，正向的挂载就永远只能在单测里看到。
                    String memoId = str(args, "memo_id", "").trim();
                    if (memoId.isBlank()) {
                        reply.accept(TaskResult.fail("by_memo 需要 memo_id").toJson());
                        return;
                    }
                    var rows = com.dwinovo.numen.plugins.learner.core.UsageLedger.usageByMemo(
                            ul, memoId, java.util.EnumSet.of(
                                    com.dwinovo.numen.plugins.learner.core.ArtifactOutbox.Kind.AC_SCRIPT,
                                    com.dwinovo.numen.plugins.learner.core.ArtifactOutbox.Kind.CARRIER));
                    // usageByMemo 返回的是**已渲染好的行**（Map），不是 Entry ——
                    // 直接塞回 data，别再过一次 toMap（那是给 Entry 用的）
                    data.put("memo_id", memoId);
                    data.put("rows", rows);
                    if (rows.isEmpty()) {
                        data.put("usage_outcome_note",
                                "这条判定的产物没有执行记录（未执行、或已被清理）");
                    } else {
                        data.put("note", "★ 这些是**事实**（跑成/跑挂），不代表已被判为有效");
                    }
                    reply.accept(TaskResult.ok("learner_usage by_memo", data).toJson());
                }
                default -> reply.accept(TaskResult.fail(
                        "action 只认 record/report/claim_validity/reflect/by_memo，收到: " + action).toJson());
            }
        } catch (UsageLedger.LateArrival e) {
            // 迟到事件要**显式**告诉调用方，别让它看起来像普通失败
            reply.accept(TaskResult.fail("迟到事件：" + e.getMessage()
                    + "（阶段只前进；要记取消后的迟到结果请用 outcome=CANCELED 记事实）").toJson());
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("learner_usage " + action.toLowerCase(java.util.Locale.ROOT)
                    + " 失败: " + e.getMessage()).toJson());
        }
    }

    private static UsageLedger.Phase phase(String s) {
        for (UsageLedger.Phase p : UsageLedger.Phase.values()) {
            if (p.name().equalsIgnoreCase(s.trim())) {
                return p;
            }
        }
        return null;
    }

    private static UsageLedger.Outcome outcome(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return UsageLedger.Outcome.UNKNOWN;
        }
        for (UsageLedger.Outcome o : UsageLedger.Outcome.values()) {
            if (o.name().equalsIgnoreCase(t)) {
                return o;
            }
        }
        return UsageLedger.Outcome.UNKNOWN;
    }

    private static String str(JsonObject args, String k, String def) {
        if (args == null || !args.has(k) || args.get(k).isJsonNull() || !args.get(k).isJsonPrimitive()) {
            return def;
        }
        return args.get(k).getAsString();
    }
}