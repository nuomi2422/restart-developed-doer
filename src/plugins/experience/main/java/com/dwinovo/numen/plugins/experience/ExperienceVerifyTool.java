package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.OutcomeCorrelator;
import com.dwinovo.numen.experience.core.PresentationReceipt;
import com.dwinovo.numen.experience.core.TaskOutcomeLog;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 报告一条经验在真实世界是否得到印证。成功会推动成熟度升级
 * （OBSERVED/ATTEMPTED → VERIFIED → GENERALIZED）；失败只加反例，不伪造降级。
 *
 * <p><b>★ E7：两段式 —— 不给 {@code id} 只读清单，给了才改数据。</b>
 * 回流链路此前断在这里：这条工具要 AI 自己记住并抄对 {@code id}，
 * 而代码里从没记录过「哪条经验被呈现过」⇒ AI 根本无从知道该回报谁。
 * 现在 {@code id} 可省略，省略时返回「还没被回报过的呈现」清单（<b>不写盘</b>），
 * 每一条都带上<b>它被呈现的那次任务后来成没成</b>。</p>
 *
 * <p><b>⚠️ 为什么「不给 id 就批量应用」是错的</b>：一次召回可能带回 10 条，
 * 而 AI 往往只对其中 2 条有结论 ⇒ 一刀切会给不相干的经验加反例、
 * 连续 3 次还能把它降级。那比不回流糟得多 —— 库被自己污染，
 * 而且注入侧照样把它当可信经验喂回给同伴。</p>
 *
 * <p><b>⚠️ 「查不到任务结果」不等于「经验没问题」</b>：
 * {@code task_finished} 只有内置大脑派的任务才有（MCP/外部驱动结构性地不产生），
 * 主人叫停（{@code stopped}）也不算证据。所以清单里用
 * {@code outcome_state} 四态分开，只有 {@code OUTCOME_KNOWN} 带真判据 ——
 * 见 {@link OutcomeCorrelator}。</p>
 */
final class ExperienceVerifyTool implements NumenTool {

    private static final Gson GSON = new Gson();
    /** 只读清单时回看多少行任务收尾（够覆盖最近几次任务，又不至于把上下文撑爆）。 */
    private static final int OUTCOME_TAIL_LINES = 120;

    @Override
    public String name() {
        return "experience_verify";
    }

    @Override
    public String description() {
        return "Report whether a previously learned experience was confirmed or refuted in the real "
                + "world. Pass the experience id from experience_recall (or the pending list when you "
                + "omit the id). success=true confirms it (raises maturity), success=false adds a "
                + "counterexample. With no id this only LISTS what is still waiting for a verdict, "
                + "each with the outcome of the task it was shown in - it changes nothing. Only rows "
                + "marked outcome_state=OUTCOME_KNOWN have a real verdict; NO_OUTCOME_YET means the "
                + "task has not finished (or was not started by the built-in brain), which is NOT "
                + "evidence that the experience is fine.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("id", "The experience id to verify. Omit to just list pending reports.")
                // ★ B9 实机修：原来这里是必填的 .bool(...)。后果是「只读列清单」这条路
                //   在 schema 层也被堵死：schema 说 success 必填，AI 想看一眼待回报清单
                //   就必须先编一个 success 出来 —— 等于逼它为了读而撒谎。
                //   现在 success 只在**真的给了 id 要落数据时**才需要。
                .optionalBool("success", "true if the experience held up in the real world, false otherwise. "
                        + "Required only when you pass an id; omit both id and success to list pending reports.")
                .optionalString("note", "Short note on what happened (kept as a counterexample on failure; "
                        + "on success it is recorded in monitor/expmem.jsonl instead of the entry).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            ExperienceMemory memory = ExperiencePlugin.memory(companion.getUUID());
            PresentationReceipt receipts = memory.receipts();
            String id = in == null || in.id() == null ? "" : in.id().trim();

            // ★ E7 第一段：不给 id = 只读清单，一个字都不写。
            //
            // ★ B9 实机修：这段原来排在 `success == null` 的校验**之后**，于是
            //   「只读列清单」被一个跟它毫无关系的必填参数挡死 —— 实机调
            //   experience_verify（不带任何参数）拿回的是 "requires success"，
            //   整条只读路径根本走不到。而且要走到它就必须先编一个 success 出来，
            //   那等于逼调用方为了看一眼清单而撒谎。
            //   顺序反过来：**先看有没有 id**（id 才是「要不要落数据」的决定项），
            //   没有 id 就直接只读返回；只有真的要落数据时才要求 success。
            if (id.isEmpty()) {
                List<PresentationReceipt.Shown> pending = receipts.pendingReports();
                // ★ E7 第二段：给每条呈现配上「那次任务后来成没成」。
                //   回流链路原本缺的就是这一段 —— 上游知道谁被呈现过、
                //   下游会执行升级降级，中间没有一样东西记录任务结局，
                //   于是 AI 拿到清单也无从判断该回报什么，只能不回报。
                TaskOutcomeLog.Pull outcomes = TaskOutcomeLog.tail(
                        TaskOutcomeLog.pathOf(ExperiencePlugin.configDir()),
                        companion.getUUID(),
                        OUTCOME_TAIL_LINES);
                List<OutcomeCorrelator.Matched> matched =
                        OutcomeCorrelator.match(pending, outcomes.outcomes());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (OutcomeCorrelator.Matched m : matched) {
                    rows.add(m.toMap());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                Map<String, Object> correlation = OutcomeCorrelator.readout(matched);
                out.put("listed_only", true);
                out.put("pending", rows);
                out.put("correlation", correlation);
                out.put("outcome_source", outcomes.readout());
                out.put("receipt", receipts.readout());
                ExperienceMonitor.publish("verify_pending_listed", Map.of(
                        "companion", companion.getUUID().toString(),
                        "pending", rows.size(),
                        "outcome_known", correlation.get("outcome_known")));
                reply.accept(TaskResult.ok("no id given: listed " + rows.size()
                        + " experience(s) waiting for a verdict (nothing was changed). "
                        + "Only rows with outcome_state=OUTCOME_KNOWN carry a real verdict; "
                        + OutcomeCorrelator.CAVEAT, out).toJson());
                return;
            }

            // 第二段：给了 id 才落数据 —— 这时才要求 success。
            if (in == null || in.success() == null) {
                reply.accept(TaskResult.fail("experience_verify requires success"
                        + " (or omit id entirely to just list what is waiting for a verdict)").toJson());
                return;
            }

            // 第二段：给了 id 才落数据。
            ExperienceEntry updated = memory.recordEvidence(id, in.success(), in.note());
            if (updated == null) {
                // ★ 如实说「没找到」，而不是假装成功、也不是偷偷留一条假记录。
                ExperienceMonitor.publish("verify_unknown_id", Map.of(
                        "companion", companion.getUUID().toString(),
                        "id", id,
                        "success", in.success()));
                reply.accept(TaskResult.fail("no experience with id: " + id).toJson());
                return;
            }
            PresentationReceipt.Shown shown = receipts.lastShown(id);
            ExperienceMonitor.publish("verified", Map.of(
                    "companion", companion.getUUID().toString(),
                    "id", updated.id(),
                    "success", in.success(),
                    "note", in.note() == null ? "" : in.note(),
                    "maturity", updated.maturity().name(),
                    "verified_count", updated.verifiedCount(),
                    "presented_before", receipts.presentedCount(id)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", updated.id());
            out.put("maturity", updated.maturity().name());
            out.put("verified_count", updated.verifiedCount());
            out.put("counterexamples", updated.counterexamples().size());
            // ★ E7：这三条是「这条经验到底有没有被用过」的连接键读数。
            //   实机之前 66 条经验里 retracted / supersedes / consecutive_failures 全是 0，
            //   根本原因就是这三个数无从产生 —— 没人知道谁被用过。
            out.put("presented_count", receipts.presentedCount(updated.id()));
            out.put("was_presented", shown != null);
            out.put("presented_surface", shown == null ? "" : shown.surface());
            out.put("receipt", receipts.readout());
            reply.accept(TaskResult.ok("experience verified", out).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_verify failed: " + ex.getMessage()).toJson());
        }
    }

    private record Input(String id, Boolean success, String note) {}
}
