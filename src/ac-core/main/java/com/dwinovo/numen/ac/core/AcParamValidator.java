package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.ToolSchema;

import java.util.List;
import java.util.Map;

/**
 * 执行前参数校验。保证 AI / AI authoring 生成的参数不经检查就交给宿主工具：
 * 校验 required / 类型 / 未知参数 / 枚举 / 嵌套深度 / 字符串与数组大小上限。
 * 返回 {@code null} 表示通过，否则返回人类可读的失败原因。
 */
public final class AcParamValidator {

    /** 最大嵌套深度（Map/List 递归）。 */
    public static final int MAX_DEPTH = 8;
    /** 单个字符串参数的最大长度。 */
    public static final int MAX_STRING_LEN = 4096;
    /** 单个数组参数的最大元素数。 */
    public static final int MAX_LIST_SIZE = 1024;

    private AcParamValidator() {}

    /**
     * 校验参数。通过返回 {@code null}；失败返回失败原因（含参数名）。
     */
    public static String validate(ToolSchema schema, Map<String, Object> raw) {
        if (schema == null) return null;
        Map<String, Object> params = raw == null ? Map.of() : raw;

        if (!schema.allowUnknown()) {
            for (String key : params.keySet()) {
                if (schema.parameters().stream().noneMatch(p -> p.name().equals(key))) {
                    return "未知参数: " + key;
                }
            }
        }
        for (ToolSchema.Param p : schema.parameters()) {
            boolean present = params.containsKey(p.name());
            if (!present) {
                if (p.required()) return "缺少必填参数: " + p.name();
                continue;
            }
            Object v = params.get(p.name());
            if (v == null) {
                if (p.nullable()) continue;
                return "参数 " + p.name() + " 不能为 null";
            }
            if (!p.allowedValues().isEmpty() && !p.allowedValues().contains(v)) {
                return "参数 " + p.name() + " 值不在允许列表: " + v;
            }
            String typeErr = checkType(p.type(), v);
            if (typeErr != null) return "参数 " + p.name() + typeErr;
            String boundErr = checkBounds(v, 0);
            if (boundErr != null) return "参数 " + p.name() + boundErr;
        }
        return null;
    }

    private static String checkType(ToolSchema.Type type, Object v) {
        if (type == null || type == ToolSchema.Type.ANY) return null;
        return switch (type) {
            case STRING -> v instanceof String ? null : " 应为字符串";
            case INTEGER -> isInteger(v) ? null : " 应为整数";
            case NUMBER -> v instanceof Number ? null : " 应为数字";
            case BOOLEAN -> v instanceof Boolean ? null : " 应为布尔值";
            case OBJECT -> v instanceof Map<?, ?> ? null : " 应为对象";
            case ARRAY -> v instanceof List<?> ? null : " 应为数组";
            case ANY -> null;
        };
    }

    private static boolean isInteger(Object v) {
        if (v instanceof Integer || v instanceof Long) return true;
        if (v instanceof Double d) return d == Math.floor(d) && Double.isFinite(d);
        return false;
    }

    private static String checkBounds(Object v, int depth) {
        if (depth > MAX_DEPTH) return " 嵌套过深";
        if (v instanceof String s && s.length() > MAX_STRING_LEN) return " 字符串过长";
        if (v instanceof List<?> list) {
            if (list.size() > MAX_LIST_SIZE) return " 数组过大";
            for (Object item : list) {
                String e = checkBounds(item, depth + 1);
                if (e != null) return e;
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object val : m.values()) {
                String e = checkBounds(val, depth + 1);
                if (e != null) return e;
            }
        }
        return null;
    }
}
