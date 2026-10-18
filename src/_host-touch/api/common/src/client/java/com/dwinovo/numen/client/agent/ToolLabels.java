package com.dwinovo.numen.client.agent;

import net.minecraft.client.resources.language.I18n;

/**
 * 工具名 → 人话的唯一出口:约定键 {@code numen.tool.<name>}(见
 * {@code ModLanguageData} 的工具 chip 段)。没有译文的(MCP 外部工具、
 * 自编译新工具)原样显示——不猜、不编。
 *
 * <p>聊天栏 chip、头顶气泡、同伴面板都走这一处,免得三处各译各的。
 * 客户端专用(依赖 {@link I18n})。
 */
public final class ToolLabels {

    private ToolLabels() {}

    /** {@code name} 为空时原样返回(null/空)。 */
    public static String label(String name) {
        if (name == null || name.isBlank()) return name;
        String key = "numen.tool." + name;
        return I18n.exists(key) ? I18n.get(key) : name;
    }
}
