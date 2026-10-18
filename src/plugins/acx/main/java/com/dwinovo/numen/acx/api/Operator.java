package com.dwinovo.numen.acx.api;

/** 结构化条件的比较算子。对应 DD 版 {@code AcRunner.evalCondition} 支持的 6 个算子。 */
public enum Operator {
    EQ("=="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
    /** 未知算子的落点：条件恒为 false，并让 {@code symbol()} 打成 {@code ?} 便于诊断。 */
    UNKNOWN("?");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** 未知算子返回 {@code null}，由调用方决定是报错还是当条件不成立。 */
    public static Operator fromSymbol(String raw) {
        if (raw == null) {
            return EQ;
        }
        String s = raw.trim();
        for (Operator op : values()) {
            if (op.symbol.equals(s)) {
                return op;
            }
        }
        if ("=".equals(s)) {
            return EQ;
        }
        if ("=<".equals(s)) {
            return LE;
        }
        if ("=>".equals(s)) {
            return GE;
        }
        return null;
    }
}