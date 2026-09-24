package com.dwinovo.numen.rdd.fact;

import com.dwinovo.numen.rdd.api.Goal;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 完成事实仓库（P0）：回答唯一一个问题——"这件事过去是否已经被可靠完成过？"
 *
 * <p>它是<b>执行事实层</b>，不是第二个 Planner，也不持有任何运行态。规则：
 * <ul>
 *   <li>主事实 {@link StageFact}：键 = 血缘 + 归一阶段键，决定某个阶段是否可继承/跳过。</li>
 *   <li>辅助 {@link SubtaskFact}：仅留档（有界），供后续 Context/Planner 参考，不用于恢复。</li>
 *   <li>查询 {@link #satisfiedStageKeys(Goal)} 先按血缘精确匹配；血缘不同再按"同 goalId +
 *       目标指纹近似"（{@link GoalLineage#similar}）降级继承；否则视为新战役、不继承。</li>
 *   <li>纯 JVM、可 JSON 序列化；磁盘 IO 由宿主（plugins:rdd）负责。</li>
 * </ul>
 *
 * <p>线程安全：所有读写 synchronized（仓库小、调用频率低）。
 */
public final class CompletedFactStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_SUBTASK_FACTS = 256;
    private static final String COMPOSITE_SEP = "\u0001";

    private final Map<String, StageFact> stages = new LinkedHashMap<>();
    private final Map<String, SubtaskFact> subtasks = new LinkedHashMap<>();

    public CompletedFactStore() {}

    /** 记录"某血缘下某战略阶段已完成"。同(血缘,键)重复记录以最新覆盖。 */
    public synchronized void recordStage(Goal goal, String primaryDescription, long atMillis, String evidence) {
        if (goal == null || primaryDescription == null) {
            throw new IllegalArgumentException("goal and stage description required");
        }
        String lineage = GoalLineage.of(goal);
        String key = StageKeyNormalizer.normalize(primaryDescription);
        stages.put(lineage + COMPOSITE_SEP + key, new StageFact(
                lineage, goal.id(), GoalLineage.fingerprint(goal.description()),
                key, primaryDescription, atMillis,
                evidence == null ? "completed" : evidence));
    }

    /** 留档一条二级完成细节（有界，超出淘汰最旧）。 */
    public synchronized void recordSubtask(Goal goal, String primaryDescription, String subtaskDescription, long atMillis) {
        if (goal == null || primaryDescription == null || subtaskDescription == null) {
            return;
        }
        String lineage = GoalLineage.of(goal);
        String key = StageKeyNormalizer.normalize(primaryDescription) + COMPOSITE_SEP + subtasks.size();
        subtasks.put(lineage + COMPOSITE_SEP + key, new SubtaskFact(
                lineage, StageKeyNormalizer.normalize(primaryDescription), subtaskDescription, atMillis));
        while (subtasks.size() > MAX_SUBTASK_FACTS) {
            String oldest = subtasks.keySet().iterator().next();
            subtasks.remove(oldest);
        }
    }

    /**
     * 查询该目标可继承的已完成阶段键集合：
     * ① 血缘精确匹配 → 返回其全部阶段键；
     * ② 否则在同 goalId 下找目标指纹近似的血缘 → 返回并集；
     * ③ 都没有 → 空集（新战役不继承）。
     */
    public synchronized Set<String> satisfiedStageKeys(Goal goal) {
        if (goal == null) {
            return Set.of();
        }
        String lineage = GoalLineage.of(goal);
        Set<String> exact = keysOfLineage(lineage);
        if (!exact.isEmpty()) {
            return exact;
        }
        String fp = GoalLineage.fingerprint(goal.description());
        Set<String> similar = new LinkedHashSet<>();
        for (StageFact f : stages.values()) {
            if (!f.goalId().equals(goal.id())) {
                continue;
            }
            if (GoalLineage.similar(fp, f.objective())) {
                similar.add(f.stageKey());
            }
        }
        return similar;
    }

    private Set<String> keysOfLineage(String lineage) {
        Set<String> out = new LinkedHashSet<>();
        for (StageFact f : stages.values()) {
            if (f.lineageId().equals(lineage)) {
                out.add(f.stageKey());
            }
        }
        return out;
    }

    public synchronized int stageCount() {
        return stages.size();
    }

    /** 导出为 JSON（持久化用）。 */
    public synchronized String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        JsonArray sa = new JsonArray();
        for (StageFact f : stages.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("lineageId", f.lineageId());
            j.addProperty("goalId", f.goalId());
            j.addProperty("objective", f.objective());
            j.addProperty("stageKey", f.stageKey());
            j.addProperty("rawStage", f.rawStage());
            j.addProperty("at", f.completedAtMillis());
            j.addProperty("evidence", f.evidence());
            sa.add(j);
        }
        o.add("stages", sa);
        JsonArray ua = new JsonArray();
        for (SubtaskFact f : subtasks.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("lineageId", f.lineageId());
            j.addProperty("stageKey", f.stageKey());
            j.addProperty("rawSubtask", f.rawSubtask());
            j.addProperty("at", f.completedAtMillis());
            ua.add(j);
        }
        o.add("subtasks", ua);
        return GSON.toJson(o);
    }

    /** 从 JSON 恢复；空/坏 JSON 返回空仓库（不抛，交给宿主记录告警）。 */
    public static CompletedFactStore fromJson(String json) {
        CompletedFactStore store = new CompletedFactStore();
        if (json == null || json.isBlank()) {
            return store;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonArray sa = o.getAsJsonArray("stages");
            if (sa != null) {
                for (JsonElement el : sa) {
                    JsonObject j = el.getAsJsonObject();
                    String lineage = str(j, "lineageId");
                    String stageKey = str(j, "stageKey");
                    if (lineage == null || stageKey == null) {
                        continue;
                    }
                    store.stages.put(lineage + COMPOSITE_SEP + stageKey, new StageFact(
                            lineage, str(j, "goalId"), str(j, "objective"), stageKey,
                            str(j, "rawStage"), num(j, "at"), str(j, "evidence")));
                }
            }
            JsonArray ua = o.getAsJsonArray("subtasks");
            if (ua != null) {
                for (int i = 0; i < ua.size(); i++) {
                    JsonObject j = ua.get(i).getAsJsonObject();
                    String lineage = str(j, "lineageId");
                    if (lineage == null) {
                        continue;
                    }
                    store.subtasks.put(lineage + COMPOSITE_SEP + i, new SubtaskFact(
                            lineage, str(j, "stageKey"), str(j, "rawSubtask"), num(j, "at")));
                }
            }
        } catch (RuntimeException ex) {
            return new CompletedFactStore();
        }
        return store;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }

    /** 只读快照（测试/观测用）。 */
    public synchronized List<StageFact> stageFacts() {
        return List.copyOf(new ArrayList<>(stages.values()));
    }
}
