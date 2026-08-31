package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
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

    @Override
    public String name() {
        return "experience_recall";
    }

    @Override
    public String description() {
        return "Search the maid's experience memory for lessons relevant to the current situation. "
                + "Pass what you are trying to do / what failed / the tool name. "
                + "Returns experience hits with root cause and recommended response.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "The current task, failure symptom, or situation to search for.")
                .optionalInteger("limit", "Max results 1-20 (default 5).", 1, 20)
                .optionalEnum("min_maturity", "Only return experiences at least this verified: "
                        + "OBSERVED|ATTEMPTED|VERIFIED|GENERALIZED.", "OBSERVED", "ATTEMPTED", "VERIFIED", "GENERALIZED")
                .optionalStringArray("tags", "Only return experiences carrying one of these tags.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            String query = in == null ? "" : in.query();
            if (query == null || query.isBlank()) {
                reply.accept(TaskResult.fail("experience_recall requires a non-empty query").toJson());
                return;
            }
            int limit = in != null && in.limit() != null && in.limit() > 0 ? Math.min(in.limit(), 20) : 5;
            ExperienceMaturity min = parseMaturity(in == null ? null : in.min_maturity());
            List<String> tags = in != null && in.tags() != null ? in.tags() : List.of();

            List<ExperienceHit> hits = ExperiencePlugin.memory(companion.getUUID())
                    .recall(query, limit, min, tags);
            List<Map<String, Object>> results = new ArrayList<>();
            for (ExperienceHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", hit.entry().id());
                item.put("title", hit.entry().title());
                item.put("type", hit.entry().type() == null ? "" : hit.entry().type().name());
                item.put("maturity", hit.entry().maturity().name());
                item.put("verified_count", hit.entry().verifiedCount());
                item.put("score", hit.score());
                item.put("matched_terms", hit.matchedTerms());
                item.put("description", hit.entry().description());
                item.put("root_cause", hit.entry().rootCause());
                item.put("recommended_response", hit.entry().recommendedResponse());
                results.add(item);
            }
            reply.accept(TaskResult.ok("experience recall returned " + results.size() + " hit(s)",
                    Map.of("hits", results)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_recall failed: " + ex.getMessage()).toJson());
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
