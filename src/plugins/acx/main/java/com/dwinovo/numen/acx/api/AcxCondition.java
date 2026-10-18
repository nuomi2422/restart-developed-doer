package com.dwinovo.numen.acx.api;

import java.util.Objects;

/**
 * 结构化条件 {@code {field, op, value}}。
 *
 * <p>field 与 value 在求值前都先做 {@code $prev} / {@code $<stepId>} / {@code $input} 引用解析，
 * 所以条件两侧都能引用前序步骤的输出。</p>
 */
public final class AcxCondition {

    private final String field;
    private final Operator op;
    private final Object value;

    public AcxCondition(String field, Operator op, Object value) {
        this.field = Objects.requireNonNull(field, "condition.field");
        this.op = Objects.requireNonNull(op, "condition.op");
        this.value = value;
    }

    public String field() {
        return field;
    }

    public Operator op() {
        return op;
    }

    public Object value() {
        return value;
    }

    @Override
    public String toString() {
        return field + " " + op.symbol() + " " + value;
    }
}