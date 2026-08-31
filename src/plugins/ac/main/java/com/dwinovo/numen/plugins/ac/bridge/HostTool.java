package com.dwinovo.numen.plugins.ac.bridge;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 宿主原子工具的最小抽象（纯 JVM）。让 AC 桥（{@link NumenToolBridge}）能同步等待
 * 一次宿主工具调用结果，而不直接依赖 NumenTool / Minecraft。生产实现
 * {@link NumenHostAdapter} 把真实 NumenTool 包装进来；测试用 fake 工具。
 */
public interface HostTool {

    /** 工具名（= AC 步骤引用的 tool 名）。 */
    String name();

    /**
     * 发起一次宿主工具调用。
     *
     * @param callId      唯一调用 id（宿主工具回执需贯穿）
     * @param argsJson    参数 JSON
     * @param anchorUuid  执行上下文中的宿主实体 UUID；无实体上下文时为 null
     * @param completion  结果 JSON 回调（同步或任意线程，只应回调一次）
     */
    void invoke(String callId, String argsJson, UUID anchorUuid, Consumer<String> completion);
}
