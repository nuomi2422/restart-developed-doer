package com.dwinovo.numen.rdd.side;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 唯一当前目标选择器：有活跃支线返回支线，否则返回调用方给的主线引用。
 *
 * <p>执行、监督、上下文、回执都必须从这里取"现在是谁"；它也是回执归属校验的落点。
 */
public final class ActiveTaskSelector {

    private final SideTaskEngine engine;

    public ActiveTaskSelector(SideTaskEngine engine) {
        this.engine = Objects.requireNonNull(engine);
    }

    public ActiveTask select(UUID companionId, ActiveTask.Mainline mainline) {
        SideTask side = engine.current(companionId).filter(task -> !task.terminal()).orElse(null);
        if (side != null) {
            return new ActiveTask.Side(side.companionId(), side.instanceId(), side.typeId(), side.generation());
        }
        return mainline;
    }

    /**
     * 校验一份回执是否属于"当前"任务；不属于则丢弃并发布 {@code side_task_stale_result}。
     */
    public boolean acceptsResult(UUID companionId, TaskScope scope, String instanceId, long generation,
                                 ActiveTask.Mainline mainline) {
        ActiveTask current = select(companionId, mainline);
        boolean ok = current.scope() == scope
                && Objects.equals(current.instanceId(), instanceId)
                && current.generation() == generation;
        if (!ok) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId.toString());
            data.put("expectedScope", current.scope().name());
            data.put("expectedInstanceId", current.instanceId());
            data.put("expectedGeneration", current.generation());
            data.put("receivedScope", scope == null ? "" : scope.name());
            data.put("receivedInstanceId", instanceId == null ? "" : instanceId);
            data.put("receivedGeneration", generation);
            engine.events().publish("side_task_stale_result", data);
        }
        return ok;
    }
}
