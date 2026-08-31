package com.dwinovo.numen.ac.api;

import java.util.List;
import java.util.Map;

/**
 * 可注入工具的描述契约（纯 JVM，不暴露宿主实现）。供主 AI / AI authoring 读取
 * 工具目录后生成合法 AC，并由执行器在执行前做参数校验。
 *
 * <p>{@code parameters} 是有限的纯 Java 参数契约（类型/必填/可空/默认值/枚举/描述），
 * 不引入外部 JSON Schema 依赖；{@code allowUnknown=false} 时未知参数在执行前被拒绝。
 */
public record ToolSchema(String name, String version, String description,
                         List<Param> parameters, boolean allowUnknown) {

    /** 参数值类型。{@code ANY} 表示不做类型约束。 */
    public enum Type { STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY, ANY }

    /** 单个参数的契约。 */
    public record Param(String name, Type type, boolean required, boolean nullable,
                        Object defaultValue, List<Object> allowedValues, String description) {
        public Param {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("param name required");
            type = type == null ? Type.ANY : type;
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }

        /** 便捷：必填参数。 */
        public static Param req(String name, Type type) {
            return new Param(name, type, true, false, null, List.of(), null);
        }

        /** 便捷：可选参数。 */
        public static Param opt(String name, Type type) {
            return new Param(name, type, false, false, null, List.of(), null);
        }
    }

    public ToolSchema {
        if (name == null || name.isBlank() || version == null || version.isBlank())
            throw new IllegalArgumentException("tool name/version required");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** 便捷：严格模式（未知参数拒绝）。 */
    public ToolSchema(String name, String version, String description, List<Param> parameters) {
        this(name, version, description, parameters, false);
    }

    /** 兼容旧 Map&lt;String,String&gt; 描述构造：参数一律 ANY 可选、未知参数宽松。 */
    public ToolSchema(String name, String version, String description, Map<String, String> legacyParameters) {
        this(name, version, description,
                legacyParameters == null ? List.of()
                        : legacyParameters.entrySet().stream()
                                .map(e -> new Param(e.getKey(), Type.ANY, false, false, null, List.of(), e.getValue()))
                                .toList(),
                true);
    }
}
