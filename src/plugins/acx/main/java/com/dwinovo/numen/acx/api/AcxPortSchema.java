package com.dwinovo.numen.acx.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿主工具端口的参数 / 输出 schema。
 *
 * <p><b>为什么不用 {@link AcxToolSchema}</b>：那个只有 5 个粗类型、没有必填区间枚举，
 * 兼容现有执行器够了，但接真实 Numen 工具时会丢 enum / min / max / nullable
 * （现有 AC 的 {@code NumenSchemaAdapter} 就是这么丢的，参数校验等于没校）。
 * 端口 schema 保留完整信息：执行前真实校验在适配层做；加载期再降级映射成
 * {@link AcxToolSchema} 给 loader 用（字段引用 / 粗类型检查）。</p>
 */
public final class AcxPortSchema {

    public enum Type {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        STRING_ARRAY, INT_ARRAY,
        OBJECT,
        OBJECT_ARRAY,
        ANY
    }

    /**
     * 单个参数约束。{@code nullableAllowed} = 必填但允许 null（Numen 的 nullableNumber 联合类型）。
     *
     * <p>组件不叫 {@code nullable} 是因为 record 自动生成的访问器会与流式方法
     * {@link #nullable()} 撞名撞类型（同名不同返回类型 = record 非法）。</p>
     */
    public record Param(Type type, boolean required, boolean nullableAllowed,
                        List<String> enumValues, Double min, Double max, String description) {

        public Param {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }

        public static Param of(Type type, boolean required) {
            return new Param(type, required, false, List.of(), null, null, "");
        }

        public static Param req(Type type) {
            return of(type, true);
        }

        public static Param opt(Type type) {
            return of(type, false);
        }

        public Param nullable() {
            return new Param(type, required, true, enumValues, min, max, description);
        }

        public Param withEnum(String... values) {
            return new Param(type, required, nullableAllowed, List.of(values), min, max, description);
        }

        public Param range(double lo, double hi) {
            return new Param(type, required, nullableAllowed, enumValues, lo, hi, description);
        }

        public Param desc(String d) {
            return new Param(type, required, nullableAllowed, enumValues, min, max, d);
        }
    }

    private final Map<String, Param> params;
    private final Set<String> outputFields;
    private final boolean allowUnknown;

    public AcxPortSchema(Map<String, Param> params, Set<String> outputFields) {
        this(params, outputFields, true);
    }

    public AcxPortSchema(Map<String, Param> params, Set<String> outputFields, boolean allowUnknown) {
        Map<String, Param> p = new LinkedHashMap<>();
        if (params != null) {
            p.putAll(params);
        }
        this.params = Collections.unmodifiableMap(p);
        this.outputFields = outputFields == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(outputFields));
        this.allowUnknown = allowUnknown;
    }

    public Map<String, Param> params() {
        return params;
    }

    public Set<String> outputFields() {
        return outputFields;
    }

    public boolean allowUnknown() {
        return allowUnknown;
    }

    public Set<String> required() {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Param> e : params.entrySet()) {
            if (e.getValue().required()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * 执行前校验。返回错误列表（空 = 通过）。
     *
     * <p>未知参数默认拒绝（{@code allowUnknown=false} 时）—— 宁可响亮失败，
     * 也不要像 DD 那样把不认识的参数静默吞掉，最后表现为「工具跑了个寂寞」。</p>
     */
    public List<String> validate(Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> v = values == null ? Map.of() : values;

        for (Map.Entry<String, Param> e : params.entrySet()) {
            String name = e.getKey();
            Param p = e.getValue();
            Object val = v.get(name);
            if (val == null) {
                if (p.required() && !p.nullableAllowed()) {
                    errors.add("缺少必填参数 " + name);
                }
                continue;
            }
            checkType(name, p, val, errors);
            if (!p.enumValues().isEmpty() && !p.enumValues().contains(String.valueOf(val))) {
                errors.add("参数 " + name + " 的取值 " + val + " 不在允许枚举 " + p.enumValues());
            }
            boolean arrayType = p.type() == Type.STRING_ARRAY || p.type() == Type.INT_ARRAY
                    || p.type() == Type.OBJECT_ARRAY;
            Double d = asNumber(val);
            if (d != null && !arrayType) {
                if (p.min() != null && d < p.min()) {
                    errors.add("参数 " + name + " = " + val + " 小于下限 " + p.min());
                }
                if (p.max() != null && d > p.max()) {
                    errors.add("参数 " + name + " = " + val + " 超过上限 " + p.max());
                }
            } else if (arrayType && (p.min() != null || p.max() != null)) {
                if (val instanceof List<?> lst) {
                    if (p.min() != null && lst.size() < p.min()) {
                        errors.add("参数 " + name + " 至少要 " + fmt(p.min()) + " 个（实际 " + lst.size() + "）");
                    }
                    if (p.max() != null && lst.size() > p.max()) {
                        errors.add("参数 " + name + " 至多 " + fmt(p.max()) + " 个（实际 " + lst.size() + "）");
                    }
                } else {
                    errors.add("参数 " + name + " 是数组类型，需要 List 才能校验长度（实际 " + val + "）");
                }
            }
        }

        if (!allowUnknown) {
            for (String k : v.keySet()) {
                if (!params.containsKey(k)) {
                    errors.add("未知参数 " + k + "（该端口不接受）");
                }
            }
        }
        return errors;
    }

    private static void checkType(String name, Param p, Object val, List<String> errors) {
        switch (p.type()) {
            case STRING -> {
                if (!(val instanceof String)) {
                    errors.add("参数 " + name + " 应为字符串（实际 " + val.getClass().getSimpleName() + "）");
                }
            }
            case INTEGER -> {
                Double d = asNumber(val);
                if (d == null || d % 1 != 0) {
                    errors.add("参数 " + name + " 应为整数（实际 " + val + "）");
                }
            }
            case NUMBER -> {
                if (asNumber(val) == null) {
                    errors.add("参数 " + name + " 应为数字（实际 " + val + "）");
                }
            }
            case BOOLEAN -> {
                if (!(val instanceof Boolean) && asBool(val) == null) {
                    errors.add("参数 " + name + " 应为布尔（实际 " + val + "）");
                }
            }
            case STRING_ARRAY -> {
                if (!(val instanceof List)) {
                    errors.add("参数 " + name + " 应为数组（实际 " + val + "）");
                }
            }
            case INT_ARRAY -> {
                if (!(val instanceof List<?> lst)) {
                    errors.add("参数 " + name + " 应为整数数组（实际 " + val + "）");
                } else {
                    for (Object o : lst) {
                        Double od = asNumber(o);
                        if (od == null || od % 1 != 0) {
                            errors.add("参数 " + name + " 的元素应为整数（实际 " + o + "）");
                            break;
                        }
                    }
                }
            }
            case OBJECT -> {
                if (!(val instanceof Map)) {
                    errors.add("参数 " + name + " 应为对象（实际 " + val + "）");
                }
            }
            case OBJECT_ARRAY -> {
                if (!(val instanceof List)) {
                    errors.add("参数 " + name + " 应为对象数组（实际 " + val + "）");
                }
            }
            case ANY -> {
                // 不校验
            }
            default -> throw new IllegalStateException("未覆盖的类型: " + p.type());
        }
    }

    private static String fmt(Double d) {
        return d == null ? "?" : (d % 1 == 0 ? String.valueOf(d.longValue()) : String.valueOf(d));
    }

    public static Double asNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Boolean asBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Param> params = new LinkedHashMap<>();
        private final Set<String> outputs = new LinkedHashSet<>();
        private boolean allowUnknown = true;

        public Builder param(String name, Param p) {
            params.put(name, p);
            return this;
        }

        public Builder output(String... fields) {
            for (String f : fields) {
                outputs.add(f);
            }
            return this;
        }

        public Builder allowUnknown(boolean v) {
            this.allowUnknown = v;
            return this;
        }

        public AcxPortSchema build() {
            return new AcxPortSchema(params, outputs, allowUnknown);
        }
    }
}
