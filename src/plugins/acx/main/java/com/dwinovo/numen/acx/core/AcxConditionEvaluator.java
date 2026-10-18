package com.dwinovo.numen.acx.core;

import java.util.Map;

import com.dwinovo.numen.acx.api.AcxCondition;
import com.dwinovo.numen.acx.api.Operator;

/**
 * 结构化条件求值。搬自 DD 版 {@code AcRunner.evalCondition}（:494-539）。
 *
 * <p>三路比较，与 DD 完全一致：</p>
 * <ol>
 *   <li>任一侧是 {@code Number} → 数值比较</li>
 *   <li>任一侧是 {@code Boolean} 或字符串 {@code "true"/"false"} → 布尔比较（只支持 {@code ==} / {@code !=}）</li>
 *   <li>否则 → 字符串 / 对象比较（只支持 {@code ==} / {@code !=}）</li>
 * </ol>
 *
 * <p><b>求值异常 → 记 WARN 并返回 false</b>。这是有意的：
 * 条件求不出来时让控制块「不进入」，比抛异常把整条 AC 打断要好，
 * 因为 while 不进入会走终点1（目标达成）并正常结束，异常则会变成 FAIL。</p>
 */
public final class AcxConditionEvaluator {

    private AcxConditionEvaluator() { }

    /** AC-B10：带变量上下文的条件求值。 */
    public static boolean evaluate(AcxCondition condition,
                                   Map<String, Object> lastOutput,
                                   Map<String, Object> input,
                                   Map<String, Map<String, Object>> allOutputs,
                                   Map<String, Object> vars) {
        return evaluate0(condition, lastOutput, input, allOutputs, vars);
    }

    public static boolean evaluate(AcxCondition condition,
                                   Map<String, Object> lastOutput,
                                   Map<String, Object> input,
                                   Map<String, Map<String, Object>> allOutputs) {
        return evaluate0(condition, lastOutput, input, allOutputs, null);
    }

    private static boolean evaluate0(AcxCondition condition,
                                        Map<String, Object> lastOutput,
                                        Map<String, Object> input,
                                        Map<String, Map<String, Object>> allOutputs,
                                        Map<String, Object> vars) {
        // AC-B10：变量感知（循环靠 $var / $loop 收敛时必需）
        AcxValueResolver resolver = new AcxValueResolver(lastOutput, input, allOutputs, vars);
        try {
            Object fieldVal = resolver.resolve(condition.field());
            Object expected = resolver.resolve(condition.value());
            Operator op = condition.op();

            if (fieldVal instanceof Number || expected instanceof Number) {
                double a = AcxValueResolver.toDouble(fieldVal);
                double b = AcxValueResolver.toDouble(expected);
                return switch (op) {
                    case LT -> a < b;
                    case LE -> a <= b;
                    case GT -> a > b;
                    case GE -> a >= b;
                    case EQ -> a == b;
                    case NE -> a != b;
                    case UNKNOWN -> false;
                };
            }

            if (fieldVal instanceof Boolean || expected instanceof Boolean) {
                boolean a = toBool(fieldVal);
                boolean b = toBool(expected);
                return switch (op) {
                    case EQ -> a == b;
                    case NE -> a != b;
                    default -> false;
                };
            }

            boolean eq = java.util.Objects.equals(fieldVal, expected)
                    || String.valueOf(fieldVal).equals(String.valueOf(expected));
            return switch (op) {
                case EQ -> eq;
                case NE -> !eq;
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /**
     * 生成「条件未满足」的诊断文本，带上实际值 vs 期望值。
     *
     * <p>用途：{@code if} 一个分支都没进时、precondition 拦下时、guard 拦下时，
     * AI 拿到的不是「静默跳过 / 静默停住」，而是「条件(实际 X vs 期望 Y) 没满足」——
     * 后者能直接定位问题。</p>
     */
    public static String describe(String label, AcxCondition condition,
                                  Map<String, Object> lastOutput,
                                  Map<String, Object> input,
                                  Map<String, Map<String, Object>> allOutputs) {
        AcxValueResolver resolver = new AcxValueResolver(lastOutput, input, allOutputs);
        Object actual = null;
        Object expected = null;
        try {
            actual = resolver.resolve(condition.field());
        } catch (Exception ignored) {
            // 求值失败用 null 即可
        }
        try {
            expected = resolver.resolve(condition.value());
        } catch (Exception ignored) {
            // 求值失败用 null 即可
        }
        return label + ": 条件(" + condition.field() + " " + condition.op().symbol() + " "
                + condition.value() + ") 未满足(实际 " + actual + " vs 期望 " + expected + ")";
    }

    public static String describeUnmatched(AcxCondition condition,
                                            Map<String, Object> lastOutput,
                                            Map<String, Object> input,
                                            Map<String, Map<String, Object>> allOutputs) {
        return describe("if无匹配", condition, lastOutput, input, allOutputs);
    }
}
