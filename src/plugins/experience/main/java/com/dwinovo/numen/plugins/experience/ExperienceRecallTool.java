package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.PresentationReceipt;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 按当前任务/失败/异常检索相关经验，把命中的“现象/根因/推荐处理/成熟度”带回给 LLM。
 */
final class ExperienceRecallTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /**
     * ★ E7：把「这批需要回报」讲给 AI 听。
     *
     * <p>此前 AI 拿到这批经验就结束了，<b>没有任何地方告诉它「用完了要回来报告」</b>
     * —— 于是 {@code experience_verify} 永远等不到调用，回流通道形同虚设。
     * 这句话是整条回流链路的最后一环。</p>
     *
     * <p>⚠️ 刻意<b>不</b>在这里做批量确认：一次召回可能带回 10 条，
     * 而 AI 往往只对其中两条有结论 ⇒ 自动全应用会给不相干的经验加反例甚至降级。</p>
     */
    private static final String REPORT_HINT =
            "这些经验是刚刚呈现给你的，库要靠你回报才知道它们到底靠不靠谱。"
                    + "当你知道其中某一条在真实世界里的结果时，就用那条的 id 调 experience_verify，"
                    + "并给出 success=true（确实管用）或 success=false（照它做没成）。"
                    + "只回报你真正试过的那几条；不带 id 调用只是列出还在等结论的条目，不会写任何东西。";

    /**
     * maturity 四档的人话解释。
     *
     * <p>★ 为什么召回结果里必须有它：L0 目录层（{@code ExperienceDirectory.ESCAPED_NOTE}）
     * 早就写了一句「maturity 越靠后就越可靠，OBSERVED 只是『学到了』」，但那条只在
     * <b>目录</b>里；一旦真的 {@code experience_recall} 展开正文，这句解释<b>就没了</b> ——
     * 于是模型拿到 {@code maturity=VERIFIED} 只能猜 VERIFIED 到底验证了几次。
     *
     * <p>措辞抄 {@code ExperienceMaturity} 的类注释与目录层那句的调子，不另编一套说法。
     */
    private static final Map<String, String> MATURITY_ZH = Map.of(
            "OBSERVED", "刚「学到」，还没在真实世界里试过",
            "ATTEMPTED", "试过（成或不成），但当时可能只是「看起来能行」",
            "VERIFIED", "在真实世界里成功用过",
            "GENERALIZED", "换了场景也成功过，可以当规律使");

    private static final Map<String, String> TYPE_ZH = Map.of(
            "EXECUTION", "讲「怎么做」：执行动作、工具用法、步骤顺序",
            "FAILURE", "讲「为什么这次没成」：失败的原因与对策",
            "TOOL_DEFECT", "讲「工具/字段本身有问题」：前提写错、字段失效、能力缺失",
            "WORLD_RELATION", "讲「世界里的关系」：物品、生物、地形彼此怎么互相影响",
            "POLICY", "讲「以后必须怎么做」：给同伴的硬约束、铁律、必须先问主人");

    /** 四档 + 怎么升的，一起给出去。判据是「不猜」—— 只读这一条就该知道 VERIFIED 比 OBSERVED 强在哪。 */
    private static Map<String, Object> maturityGuide() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("一句话", "maturity 越靠后越可靠：越靠后说明它在真实世界里被验证的次数越多。");
        for (Map.Entry<String, String> e : MATURITY_ZH.entrySet()) {
            g.put(e.getKey(), e.getValue());
        }
        g.put("怎么升", "只有你调 experience_verify 且 success=true 才升；连续 3 次失败会降一级。");
        return g;
    }

    @Override
    public String name() {
        return "experience_recall";
    }

    @Override
    public String description() {
        return "检索同伴的经验库，取出跟当前情况相关的教训。"
                + "传「你在做什么 / 什么失败了 / 工具名」。"
                + "返回的经验带现象根因与推荐处理，以及它被验证到什么程度（见 maturity_guide）。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "当前任务、失败现象，或想找经验的场景。")
                .optionalInteger("limit", "最多返回几条，1-20（默认 5）。", 1, 20)
                .optionalEnum("min_maturity", "只要至少验证到这个程度的经验："
                        + "OBSERVED|ATTEMPTED|VERIFIED|GENERALIZED。", "OBSERVED", "ATTEMPTED", "VERIFIED", "GENERALIZED")
                .optionalStringArray("tags", "只要带这些标签之一的经验。")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            String query = in == null ? "" : in.query();
            if (query == null || query.isBlank()) {
                reply.accept(TaskResult.fail("experience_recall 需要一个非空的 query（你想做什么/什么失败了）。").toJson());
                return;
            }
            int limit = in != null && in.limit() != null && in.limit() > 0 ? Math.min(in.limit(), 20) : 5;
            ExperienceMaturity min = parseMaturity(in == null ? null : in.min_maturity());
            List<String> tags = in != null && in.tags() != null ? in.tags() : List.of();

// ★ E7：reportable=true —— 这是唯一「AI 主动要」的召回点，
            //   只有它进待回报清单（L0 目录与规划知识是每轮自动印的，AI 可能压根没看，
            //   让它们进清单会产出满屏「本任务没有结论」的噪音）。
            ExperienceMemory memory = ExperiencePlugin.memory(companion.getUUID());
            PresentationReceipt receipts = memory.receipts();
            int before = receipts.pendingCount();
            List<ExperienceHit> hits = memory.recall(
                    query, limit, min, tags, PresentationReceipt.SURFACE_RECALL_TOOL, true);
            List<Map<String, Object>> results = new ArrayList<>();
            for (ExperienceHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", hit.entry().id());
                item.put("title", hit.entry().title());
                item.put("type", hit.entry().type() == null ? "" : hit.entry().type().name());
                item.put("maturity", hit.entry().maturity().name());
                /* ★ 中文标签：英文字段名一律保留（已有解析与面板按它们读），
                   这里只<b>加</b>，不改名。模型读中文解释就够，程序读原来的英文键。 */
                item.put("maturity_zh", MATURITY_ZH.getOrDefault(hit.entry().maturity().name(), ""));
                item.put("类型说明", TYPE_ZH.getOrDefault(
                        hit.entry().type() == null ? "" : hit.entry().type().name(), ""));
                item.put("verified_count", hit.entry().verifiedCount());
                item.put("score", hit.score());
                item.put("matched_terms", hit.matchedTerms());
                item.put("description", hit.entry().description());
                item.put("root_cause", hit.entry().rootCause());
                item.put("recommended_response", hit.entry().recommendedResponse());
                item.put("现象根因_中文键说明", "root_cause 就是「现象/根因」；recommended_response 就是「推荐处理」。");
                // 之前被呈现过几次 —— 让 AI 知道这条是老相识还是这次新翻出来的。
                item.put("presented_before", receipts.presentedCount(hit.entry().id()));
                results.add(item);
            }
            ExperienceMonitor.publish("recalled", Map.of(
                    "companion", companion.getUUID().toString(),
                    "query_chars", query.length(),
                    "hits", hits.size(),
                    "pending_before", before,
                    "pending_after", receipts.pendingCount()));
            reply.accept(TaskResult.ok("经验召回返回 " + results.size() + " 条。",
                    Map.of("hits", results,
                            "maturity_guide", maturityGuide(),
                            "类型说明", TYPE_ZH,
                            "receipt", receipts.readout(),
                            "report_hint", REPORT_HINT)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_recall 失败：" + ex.getMessage()).toJson());
        }
    }

    private static ExperienceMaturity parseMaturity(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        for (ExperienceMaturity m : ExperienceMaturity.values()) {
            if (m.name().equalsIgnoreCase(s)) {
                return m;
            }
        }
        return null;
    }

    private record Input(String query, Integer limit, String min_maturity, List<String> tags) {}
}
