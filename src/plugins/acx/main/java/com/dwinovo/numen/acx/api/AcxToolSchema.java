package com.dwinovo.numen.acx.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 积木参数 schema。用途与现有 AC 的 {@code ToolSchema} 一致：执行前静态校验 + 字段引用校验的字段来源。
 */
public final class AcxToolSchema {

    private final Map<String, ParamType> params;
    private final Set<String> required;

    public AcxToolSchema(Map<String, ParamType> params, Set<String> required) {
        Map<String, ParamType> copy = new LinkedHashMap<>();
        if (params != null) {
            copy.putAll(params);
        }
        this.params = Collections.unmodifiableMap(copy);
        this.required = required == null ? Set.of() : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(required));
    }

    public Map<String, ParamType> params() {
        return params;
    }

    public Set<String> required() {
        return required;
    }

    /** 该积木成功时可能输出哪些字段 —— 供加载期校验 {@code $prev.x} 引用。 */
    public Set<String> outputFields() {
        return params.keySet();
    }

    public enum ParamType {
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN,
        ANY
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, ParamType> params = new LinkedHashMap<>();
        private final Set<String> required = new java.util.LinkedHashSet<>();

        public Builder param(String name, ParamType type) {
            params.put(name, type);
            return this;
        }

        public Builder required(String name) {
            required.add(name);
            return this;
        }

        public AcxToolSchema build() {
            return new AcxToolSchema(params, required);
        }
    }
}