package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_carrier}：携带器规则的<b>审批流</b>（2026-10-05）。
 *
 * <p><b>四个动作</b>，全部显式：
 * <ul>
 *   <li>{@code submit} —— 把投递箱里的携带器草稿登记成候选（校验不通过就报原因）</li>
 *   <li>{@code list} —— 看候选与当前生效链</li>
 *   <li>{@code approve} —— <b>批准</b>：进生效链。<b>理由必填</b></li>
 *   <li>{@code reject} —— 拒收：只改状态、留记录</li>
 * </ul>
 *
 * <p><b>★ 这个工具就是审批流本身</b>。没有「自动批准」「达到 N 次就生效」这类入口 ——
 * 携带器决定同伴危急时刻带什么，自动批准等于让学习者自己给自己授权。
 * 用户明确要审批流，那就让每一次生效都必须是一次人能看见、能追责的调用。
 *
 * <p>无参数动作（{@code list}）不需要同伴；需要同伴的动作在服务端解析，
 * 避免把 UUID 交给模型自己编。
 */
final class LearnerCarrierTool implements NumenTool {

    @Override
    public String name() {
        return "learner_carrier";
    }

    @Override
    public String description() {
        return "携带器规则的审批流。submit=把投递箱里的携带器草稿登记成待审批候选；"
                + "list=看候选与生效链（含「已批准但加载失败」的诊断）；"
                + "approve=批准（理由必填，批准后规则才在运行时生效）；reject=拒收（留记录）。"
                + "★ 没有自动批准：每一次生效都必须是一次显式的 approve 调用。\n"
                + "★ 草稿形状（JSON，别自创字段）："
                + "{\"name\":\"低血带食物\",\"when\":\"hp<=6\","
                + "\"carry\":[\"食物/治疗类经验\"],\"fix\":[\"先找食物\"]}。"
                + "when 只认这些写法（用「,」连接表示 AND）："
                + "hp<=N / hp>=N / hp<N / hp>N（N 为整数）；band=CRITICAL|LOW|MID|HIGH；"
                + "hostile=1 / passive=1（附近有无敌对/被动实体）；has=weapon|armor|hand；"
                + "hostile / passive / low_hp / critical / has_weapon / has_armor / unknown_hand。"
                + "★ 不认识的词会被**拒收**（不忽略）—— 忽略一个条件会让规则在本该不生效时生效。"
                + "★ carry 是真正要带的东西（写具体经验/物品名，不要写「带食物」这种同义反复）。"
                + "★ 已批准规则**排在默认链之前**：它不成立时会短路掉后面的默认级，"
                + "所以一条规则要么写准 when，要么把 fix 写全（不成立时缺什么）。";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.RESIDENT;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "submit | list | approve | reject",
                        "submit", "list", "approve", "reject")
                .optionalString("artifact_id",
                        "Which candidate to approve/reject (from list). Required for approve/reject.")
                .optionalString("reason",
                        "Why you approve/reject. REQUIRED for approve (no reason = no approval).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String action = str(args, "action", "list").trim().toLowerCase(java.util.Locale.ROOT);
        String artifactId = str(args, "artifact_id", "").trim();
        String reason = str(args, "reason", "").trim();
        try {
            var adopter = LearnerPlugin.carrierAdopter();
            if (adopter == null) {
                reply.accept(TaskResult.fail("携带器审批流未初始化（插件 setup 没跑完？）").toJson());
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            switch (action) {
                case "submit" -> {
                    var rep = adopter.adoptAll();
                    data.putAll(rep.toMap());
                    data.put("note", "submit 只是登记候选，规则尚未生效；要生效请显式 approve");
                    reply.accept(TaskResult.ok("携带器候选登记完成", data).toJson());
                }
                case "list" -> {
                    data.put("candidates", candidateRows(adopter));
                    data.put("effective_rules", effectiveNames());
                    // ★ 2026-10-06（DL-14/DL-15）：把「有几条已批准但加载失败」摆出来。
                    //   原实现跳过坏行是静默的，而 approve() 的文案还教用户手工编辑
                    //   approved.jsonl —— 手改打错一个词，规则静默消失而界面仍显示「已批准」。
                    var load = com.dwinovo.numen.api.carrier.CarrierRuleStore.lastLoad();
                    data.put("approved_loaded", load.loaded());
                    data.put("approved_broken", load.broken());
                    if (load.broken() > 0) {
                        data.put("approved_issues", load.reasons());
                        data.put("approved_note",
                                "★ 有 " + load.broken() + " 行已批准规则**没能加载**（下面 approved_issues 有点名）。"
                                + "在修好之前它们不会生效，而候选表里它们的状态仍然显示 APPROVED。");
                    }
                    reply.accept(TaskResult.ok("learner_carrier list", data).toJson());
                }
                case "approve" -> {
                    if (artifactId.isBlank()) {
                        reply.accept(TaskResult.fail("approve 需要 artifact_id（先 list 看候选）").toJson());
                        return;
                    }
                    if (reason.isBlank()) {
                        // 不静默代填默认理由：没理由的批准无法追责
                        reply.accept(TaskResult.fail("approve 必须给 reason：没理由的批准无法追责").toJson());
                        return;
                    }
                    var rule = adopter.approve(artifactId, reason);
                    data.put("approved", rule.name());
                    data.put("carry", rule.carry());
                    data.put("fix", rule.fix());
                    data.put("reason", reason);
                    data.put("note", "已进生效链，运行时携带提示与复审 what-if 都会用它");
                    reply.accept(TaskResult.ok("携带器规则已批准并生效：" + rule.name(), data).toJson());
                }
                case "reject" -> {
                    if (artifactId.isBlank()) {
                        reply.accept(TaskResult.fail("reject 需要 artifact_id（先 list 看候选）").toJson());
                        return;
                    }
                    adopter.reject(artifactId, reason.isBlank() ? "(未给理由)" : reason);
                    data.put("rejected", artifactId);
                    data.put("note", "只改状态、保留记录 —— 拒了要能回答「当时为什么拒」");
                    reply.accept(TaskResult.ok("携带器候选已拒收", data).toJson());
                }
                default -> reply.accept(TaskResult.fail("action 只认 submit/list/approve/reject，收到: " + action).toJson());
            }
        } catch (RuntimeException e) {
            // 失败不许静默：把原因原样带给调用方
            reply.accept(TaskResult.fail("learner_carrier " + action + " 失败: " + e.getMessage()).toJson());
        }
    }

    private static List<Map<String, Object>> candidateRows(
            com.dwinovo.numen.plugins.learner.core.CarrierArtifactAdopter adopter) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : adopter.candidates()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("artifact_id", c.artifactId());
            one.put("name", c.name());
            one.put("when", c.when());
            one.put("carry", c.carry());
            one.put("fix", c.fix());
            one.put("status", adopter.candidateStatus(c.artifactId()));
            out.add(one);
        }
        return out;
    }

    private static List<String> effectiveNames() {
        List<String> out = new ArrayList<>();
        for (var r : com.dwinovo.numen.api.carrier.CarrierRuleStore.effective()) {
            out.add(r.name());
        }
        return out;
    }

    private static String str(JsonObject args, String k, String def) {
        if (args == null || !args.has(k) || args.get(k).isJsonNull()) {
            return def;
        }
        return args.get(k).getAsString();
    }
}