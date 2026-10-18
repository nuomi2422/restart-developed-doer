package com.dwinovo.numen.rdd.side;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 支线引擎：驱动触发器 + 推进活跃支线 + 发事件 + 恢复主线。纯 JVM，可无游戏单测。
 *
 * <p>每拍（宿主约每 20 tick 调一次）：
 * <ol>
 *   <li>有活跃支线 → 让它的 {@link SideTaskType#evaluate} 给结论并应用；</li>
 *   <li>没有 → 按触发器判断是否开一条新的（同一游戏日 + 同一维度只开一次；世界无昼夜循环
 *       或死亡支线占用时不抢）。</li>
 * </ol>
 */
public final class SideTaskEngine {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final SideTaskRuntime runtime = new SideTaskRuntime();
    private final Map<String, SideTaskType> types = new LinkedHashMap<>();
    private final List<SideTaskTrigger> triggers = new ArrayList<>();
    /** 去重键(companion|world|typeId) → 已处理过的游戏日。 */
    private final Map<String, Long> handledGameDay = new HashMap<>();
    /** 去重键 → 推迟到该 gameTime 之前不再重新决策（"推迟到安全停顿点"）。用单调 gameTime，不受 /time set 影响。 */
    private final Map<String, Long> deferredUntil = new HashMap<>();
    /** 宿主约每 20 tick 调一次 tick；推迟冷却不得小于一拍，否则每拍都会重新决策。 */
    private static final int MIN_DEFER_TICKS = 20;
    private final SideTaskEvents events;
    private final MainlineValidator mainlineValidator;

    public SideTaskEngine(SideTaskEvents events, MainlineValidator mainlineValidator) {
        this.events = events == null ? SideTaskEvents.NOOP : events;
        this.mainlineValidator = mainlineValidator;
    }

    public SideTaskEvents events() {
        return events;
    }

    public SideTaskRuntime runtime() {
        return runtime;
    }

    public void registerType(SideTaskType type) {
        types.put(type.typeId(), type);
    }

    public void registerTrigger(SideTaskTrigger trigger) {
        triggers.add(trigger);
    }

    public SideTaskType typeOf(String typeId) {
        return types.get(typeId);
    }

    public Optional<SideTask> current(UUID companionId) {
        return runtime.current(companionId);
    }

    public boolean isActive(UUID companionId) {
        return runtime.isActive(companionId);
    }

    public void tick(UUID companionId, SideTaskWorldPort world,
                     MainlineResumeToken mainlineToken, boolean deathSideTaskOccupying) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(world, "world");

        Optional<SideTask> active = runtime.current(companionId);
        if (active.isPresent()) {
            advance(active.get(), world);
            return;
        }
        if (!world.hasDayNightCycle() || deathSideTaskOccupying) {
            return;
        }
        long now = world.gameTime();
        long day = world.gameDay();
        for (SideTaskTrigger trigger : triggers) {
            String key = handledKey(companionId, world.worldId(), trigger.typeId());
            Long handled = handledGameDay.get(key);
            if (handled != null && handled == day) {
                continue;
            }
            // 推迟冷却：未到点不重新决策（避免每拍重复决策）。
            Long until = deferredUntil.get(key);
            if (until != null && now < until) {
                continue;
            }
            if (!trigger.fires(new SideTaskTrigger.Context(
                    companionId, world.worldId(), day, world.dayTime(), world.hasDayNightCycle()))) {
                continue;
            }
            SideTaskType type = types.get(trigger.typeId());
            if (type == null) {
                continue;
            }
            SideTaskDecision decision = type.decide(companionId, world);
            if (decision == null) {
                decision = SideTaskDecision.EXECUTE;
            }
            if (decision.kind() == SideTaskDecision.Kind.DEFER) {
                deferredUntil.put(key, now + Math.max(decision.deferTicks(), MIN_DEFER_TICKS));
                publishOccurrence(companionId, "side_task_deferred", trigger.typeId(), day,
                        decision.reason(), Math.max(decision.deferTicks(), MIN_DEFER_TICKS));
                continue;
            }
            if (decision.kind() == SideTaskDecision.Kind.SKIP) {
                deferredUntil.remove(key);
                handledGameDay.put(key, day);
                publishOccurrence(companionId, "side_task_skipped_occurrence", trigger.typeId(), day,
                        decision.reason(), 0L);
                continue;
            }
            // EXECUTE：清掉可能残留的推迟，照旧创建。
            deferredUntil.remove(key);
            MainlineResumeToken token = mainlineValidator == null ? null : mainlineValidator.validate(mainlineToken);
            long generation = runtime.nextGeneration();
            String instanceId = trigger.typeId() + "-" + generation + "-" + shortId(companionId);
            SideTask task = type.create(new SideTaskType.CreationContext(
                    companionId, instanceId, generation, day, now,
                    now + type.deadlineTicks(), token, Map.of()));
            if (runtime.activate(task)) {
                handledGameDay.put(key, day);
                events.publish("side_task_created", base(task));
                events.publish("side_task_activated", base(task));
            }
            return;
        }
    }

    /** 发布"推迟/跳过本次"这类开之前的决策事件（不含实例，因为还没创建实例）。 */
    private void publishOccurrence(UUID companionId, String event, String typeId, long day,
                                   String reason, long retryTicks) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("scope", TaskScope.SIDE.name());
        data.put("typeId", typeId);
        data.put("gameDay", day);
        data.put("reason", reason == null ? "" : reason);
        if (retryTicks > 0) {
            data.put("retryTicks", retryTicks);
        }
        events.publish(event, data);
    }

    private void advance(SideTask task, SideTaskWorldPort world) {
        SideTaskType type = types.get(task.typeId());
        if (type == null) {
            finish(task, SideTaskState.CANCELLED, "unknown side task type");
            return;
        }
        SideTaskVerdict verdict = type.evaluate(task, world);
        switch (verdict.kind()) {
            case CONTINUE -> {
                SideTask updated = task.withPhase(verdict.nextPhase()).withData(verdict.data());
                runtime.replace(updated);
                if (!Objects.equals(task.phase(), updated.phase())) {
                    Map<String, Object> data = base(updated);
                    data.put("phase", updated.phase());
                    events.publish("side_task_progress", data);
                }
            }
            case COMPLETE -> finish(task, SideTaskState.COMPLETED, verdict.evidence());
            case SKIP -> finish(task, SideTaskState.SKIPPED_TIMEOUT, verdict.evidence());
            case CANCEL -> finish(task, SideTaskState.CANCELLED, verdict.evidence());
        }
    }

    private void finish(SideTask task, SideTaskState state, String reason) {
        runtime.remove(task.companionId());
        String event = switch (state) {
            case COMPLETED -> "side_task_completed";
            case SKIPPED_TIMEOUT -> "side_task_skipped";
            default -> "side_task_cancelled";
        };
        Map<String, Object> data = base(task);
        data.put("state", state.name());
        data.put("reason", reason == null ? "" : reason);
        events.publish(event, data);
        resumeMainline(task);
    }

    private void resumeMainline(SideTask task) {
        MainlineResumeToken token = task.mainlineToken();
        boolean valid = token != null && (mainlineValidator == null || mainlineValidator.validate(token) != null);
        Map<String, Object> data = base(task);
        data.put("mainlineResumed", valid);
        events.publish("side_task_resumed_mainline", data);
    }

    /** 外部取消（主人暂停/换目标/清目标/世界切换）。 */
    public void cancel(UUID companionId, String reason) {
        runtime.current(companionId).ifPresent(task -> {
            runtime.remove(companionId);
            Map<String, Object> data = base(task);
            data.put("reason", reason == null ? "" : reason);
            events.publish("side_task_cancelled", data);
        });
    }

    public void onServerStopped() {
        runtime.clearAll();
        handledGameDay.clear();
        deferredUntil.clear();
    }

    private static String handledKey(UUID companionId, String worldId, String typeId) {
        return companionId + "|" + worldId + "|" + typeId;
    }

    private static String shortId(UUID companionId) {
        return companionId.toString().substring(0, 8);
    }

    private Map<String, Object> base(SideTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", task.companionId().toString());
        data.put("scope", TaskScope.SIDE.name());
        data.put("typeId", task.typeId());
        data.put("instanceId", task.instanceId());
        data.put("generation", task.generation());
        data.put("gameDay", task.scheduledGameDay());
        data.put("state", task.state().name());
        data.put("phase", task.phase());
        if (task.mainlineToken() != null) {
            data.put("mainlineGoalId", task.mainlineToken().goalId());
            data.put("mainlinePrimaryId", task.mainlineToken().primaryId());
            data.put("mainlineSubtaskId", task.mainlineToken().subtaskId());
        }
        return data;
    }

    // ---- 持久化（整个引擎共享一份；宿主自行决定文件位置） ----

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.add("runtime", runtime.toJsonObject());
        JsonObject handled = new JsonObject();
        for (Map.Entry<String, Long> e : handledGameDay.entrySet()) {
            handled.addProperty(e.getKey(), e.getValue());
        }
        root.add("handledGameDay", handled);
        JsonObject deferred = new JsonObject();
        for (Map.Entry<String, Long> e : deferredUntil.entrySet()) {
            deferred.addProperty(e.getKey(), e.getValue());
        }
        root.add("deferredUntil", deferred);
        return GSON.toJson(root);
    }

    public void restore(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("runtime")) {
            runtime.restore(root.getAsJsonObject("runtime"));
        }
        handledGameDay.clear();
        if (root.has("handledGameDay")) {
            for (var entry : root.getAsJsonObject("handledGameDay").entrySet()) {
                handledGameDay.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
        deferredUntil.clear();
        if (root.has("deferredUntil")) {
            for (var entry : root.getAsJsonObject("deferredUntil").entrySet()) {
                deferredUntil.put(entry.getKey(), entry.getValue().getAsLong());
            }
        }
    }
}
