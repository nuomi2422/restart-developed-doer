package com.dwinovo.numen.rdd.api;

import java.util.Map;

/**
 * 二级目标的身体执行指令：把这一小步交给宿主身体去做（例如 mine / craft / equip_item / collect_items）。
 *
 * <p>纯 JVM 契约：{@code taskType} 只是宿主工具注册表里的一个名字（字符串），
 * 本层不解释它、不依赖任何 NUMEN/MC 类；真正映射到身体任务由宿主适配器完成。
 */
public record BodyInstruction(String taskType, Map<String, Object> args) {
    public BodyInstruction {
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("body task type required");
        }
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
