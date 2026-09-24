package com.dwinovo.numen.ac.api;

import java.util.List;
import java.util.Optional;

/**
 * AC 自己的原子工具注册表。宿主或自编译产出的工具在此注册；主 AI / AI authoring
 * 经 {@link #toolNames()} / {@link #schemas()} 读取稳定目录后再生成 AC。
 *
 * <p>这是 AC 的 registry，<b>不是</b> Numen 的 {@code agent.tool.ToolRegistry}。
 * 二者语义不同，未来通过外部 adapter 转换，不在本接口混用。
 */
public interface ToolRegistry {

    /** 注册工具（无显式 schema → 生成宽松默认 schema）。重复注册抛异常。 */
    void register(String name, AcTool tool);

/**
     * 注册工具并绑定显式 schema；schema 非空且 schema.name() 必须与 name 一致（否则实现层抛
     * {@link IllegalArgumentException}）。
     *
     * <p><b>实现契约：实现类必须覆写本方法。</b>默认实现抛
     * {@link UnsupportedOperationException}。早期版本的默认实现会把 schema 静默丢弃（委托给两参
     * 重载），而 schema 丢失会让 {@link com.dwinovo.numen.ac.core.AcParamValidator} 对一切工具直接
     * 放行，等于 AI 参数校验与工具目录整体失效——属契约级损坏，宁可硬失败也不静默降级。
     *
     * <p>schema 为 null 时默认实现也抛 UOE（诊断字符串自带 null 防御，不会先 NPE），与本接口
     * "schema 非空"的文字契约一致；null/重名/空名由覆写的实现统一按 IAE 拒绝。
     */
    default void register(String name, AcTool tool, ToolSchema schema) {
        throw new UnsupportedOperationException(
                "ToolRegistry 实现必须覆写 register(name, tool, schema)；默认实现会丢弃 schema，"
                        + "使 AcParamValidator 对一切工具放行（AI 参数校验失效）。"
                        + " name=" + name + " schema=" + (schema == null ? "<null>" : schema.name()));
    }

    Optional<AcTool> find(String name);

    /** 工具 schema；未注册或未绑定 schema 返回 empty。 */
    default Optional<ToolSchema> schema(String name) {
        return Optional.empty();
    }

    /** 全部工具名（稳定排序）。 */
    default List<String> toolNames() {
        return List.of();
    }

    /** 全部工具 schema 目录（稳定排序，与 {@link #toolNames()} 一致）。 */
    default List<ToolSchema> schemas() {
        return List.of();
    }

    default boolean contains(String name) {
        return find(name).isPresent();
    }
}
