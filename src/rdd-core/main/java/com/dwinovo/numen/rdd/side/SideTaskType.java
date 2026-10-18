package com.dwinovo.numen.rdd.side;

import java.util.Map;
import java.util.UUID;

/**
 * 一类支线的硬编码实现（SPI）。
 *
 * <p><b>以后加一条支线 = 加一个 {@code SideTaskType} + 一个 {@link SideTaskTrigger}。</b>
 * 生命周期、持久化、回执代次校验、主线恢复全部复用通用引擎，不再各写一套。
 */
public interface SideTaskType {

    String typeId();

    /** 保护超时（tick）：创建时刻 + 该值 = deadline。 */
    long deadlineTicks();

    SideTask create(CreationContext ctx);

    SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world);

    /**
     * 开一条支线之前问一句：现在执行 / 推迟 / 本次跳过。默认 {@link SideTaskDecision#EXECUTE}，
     * 旧的支线类型行为完全不变。
     *
     * <p>确定规则优先放这里（例如"正在战斗就推迟""主线明确要夜间活动就跳过"）；
     * 需要取舍时才交给更上层的判断，别每个 tick 都去问模型。
     */
    default SideTaskDecision decide(UUID companionId, SideTaskWorldPort world) {
        return SideTaskDecision.EXECUTE;
    }

    /** 渲染注入执行 AI 的上下文（{@code <side_task>…</side_task>} 形状）。 */
    String renderContext(SideTask task, SideTaskWorldPort world);

    record CreationContext(
            UUID companionId,
            String instanceId,
            long generation,
            long scheduledGameDay,
            long gameTime,
            long deadlineGameTime,
            MainlineResumeToken mainlineToken,
            Map<String, String> data) {
        public CreationContext {
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }
}
