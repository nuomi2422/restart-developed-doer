package com.dwinovo.numen.acx.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AC-B10：{@code $calc} 表达式求值（递归下降）。
 *
 * <p>为什么必须有：AC 原来只能「取值/筛选/选最近」，没有算术。写「挖通道」这种
 * 算法脚本时，每轮都要把游标 +1、算两点距离、算「到目标还差几步」——
 * 没有算术就只能把坐标硬编码，弯道与斜通道立刻写不出来。</p>
 *
 * <p>支持的语法：</p>
 * <ul>
 *   <li>四则运算 {@code + - * / %}、括号、一元负号</li>
 *   <li>引用：{@code $var.cursor.x} / {@code $prev.hp} / {@code $input.count}
 *       （走 {@link AcxValueResolver}，所以和别处的引用语义完全一致）</li>
 *   <li>函数：{@code max(a,b)} {@code min(a,b)} {@code abs(x)} {@code floor(x)}
 *       {@code ceil(x)} {@code round(x)} {@code sqrt(x)} {@code pow(a,b)}
 *       {@code dist(ax,ay,az,bx,by,bz)}（两点欧氏距离）、{@code len(x)}（列表长度）</li>
 * </ul>
 *
 * <p><b>失败哲学</b>：除零、未知函数、引用不是数字、括号不配对 —— 一律
 * {@link IllegalArgumentException} 响亮失败（runner 会转成 STEP_FAILED），
 * 绝不返回 0 假装成功。0 是合法结果，静默 0 会让脚本一路错下去。</p>
 */
public final class AcxExpr {

    private final String src;
    private final List<Object> tokens = new ArrayList<>();
    private int pos;
    private final AcxValueResolver resolver;

    private AcxExpr(String src, AcxValueResolver resolver) {
        this.src = src;
        this.resolver = resolver;
        tokenize();
    }

    /**
     * 求值入口。
     *
     * @param expr     表达式文本
     * @param resolver 引用解析器（可为 null，此时只允许纯字面量算术）
     * @return 整数结果给 {@link Long}，小数给 {@link Double}
     */
    public static Object eval(String expr, AcxValueResolver resolver) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("$calc 表达式为空");
        }
        AcxExpr e = new AcxExpr(expr, resolver);
        double v = e.parseExpr();
        if (e.pos < e.tokens.size()) {
            throw new IllegalArgumentException("$calc 表达式尾部有多余内容: " + expr);
        }
        return tidy(v);
    }

    /** 整数值回 Long（避免 1.0 让整数参数校验失败），其余回 Double。 */
    private static Object tidy(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("$calc 结果不是有限数: " + v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 9.007199254740992E15) {
            return (long) v;
        }
        return v;
    }

    // ── 词法 ──────────────────────────────────────────────────────────

    private void tokenize() {
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < src.length()
                    && Character.isDigit(src.charAt(i + 1)))) {
                int j = i;
                while (j < src.length() && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(Double.valueOf(src.substring(i, j)));
                i = j;
            } else if (c == '$') {
                // 引用吃到空白/运算符/括号/逗号为止
                int j = i;
                while (j < src.length() && " \t+-*/%(),".indexOf(src.charAt(j)) < 0) {
                    j++;
                }
                if (j == i) {
                    throw new IllegalArgumentException("$calc 引用起始不完整: " + src);
                }
                tokens.add(src.substring(i, j));
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < src.length() && (Character.isLetterOrDigit(src.charAt(j))
                        || src.charAt(j) == '_')) {
                    j++;
                }
                tokens.add(src.substring(i, j));
                i = j;
            } else if ("+-*/%(),".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                throw new IllegalArgumentException("$calc 出现无法识别的字符 '" + c + "'（表达式: " + src + "）");
            }
        }
    }

    // ── 语法 ──────────────────────────────────────────────────────────

    private Object peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private boolean eat(String op) {
        if (op.equals(peek())) {
            pos++;
            return true;
        }
        return false;
    }

    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            if (eat("+")) {
                v += parseTerm();
            } else if (eat("-")) {
                v -= parseTerm();
            } else {
                return v;
            }
        }
    }

    private double parseTerm() {
        double v = parseUnary();
        while (true) {
            if (eat("*")) {
                v *= parseUnary();
            } else if (eat("/")) {
                double d = parseUnary();
                if (d == 0.0) {
                    throw new IllegalArgumentException("$calc 除零: " + src);
                }
                v /= d;
            } else if (eat("%")) {
                double d = parseUnary();
                if (d == 0.0) {
                    throw new IllegalArgumentException("$calc 取模零: " + src);
                }
                v %= d;
            } else {
                return v;
            }
        }
    }

    private double parseUnary() {
        if (eat("-")) {
            return -parseUnary();
        }
        if (eat("+")) {
            return parseUnary();
        }
        return parsePrimary();
    }

    private double parsePrimary() {
        Object t = peek();
        if (t == null) {
            throw new IllegalArgumentException("$calc 表达式不完整: " + src);
        }
        if (t instanceof Double d) {
            pos++;
            return d;
        }
        String s = (String) t;
        if (s.startsWith("$")) {
            pos++;
            return num(resolveRef(s));
        }
        if ("(".equals(s)) {
            pos++;
            double v = parseExpr();
            if (!eat(")")) {
                throw new IllegalArgumentException("$calc 括号不配对: " + src);
            }
            return v;
        }
        // 函数调用
        pos++;
        if (!eat("(")) {
            throw new IllegalArgumentException("$calc 未知标识符 '" + s + "'（可用函数: "
                    + "max/min/abs/floor/ceil/round/sqrt/pow/dist/len）");
        }
        List<Double> args = new ArrayList<>();
        if (!eat(")")) {
            do {
                args.add(parseExpr());
            } while (eat(","));
            if (!eat(")")) {
                throw new IllegalArgumentException("$calc 函数 " + s + " 的括号不配对: " + src);
            }
        }
        return call(s, args);
    }

    private Object resolveRef(String ref) {
        if (resolver == null) {
            throw new IllegalArgumentException("$calc 里出现引用 " + ref + "，但当前没有引用解析器");
        }
        return resolver.resolve(ref);
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        // 真机实测：宿主工具的数字字段可能以字符串形态到达（裸 JSON 通路 + 上游转换），
        // 所以算术层必须能吃 "63.0"。解析不了才响亮失败（这与 AcxPortSchema.asNumber 同策略）。
        if (o instanceof String s) {
            String v = s.trim();
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("$calc 引用结果不是数字: " + o
                        + "（数字字符串 " + v + " 也解析不了）");
            }
        }
        throw new IllegalArgumentException("$calc 引用结果不是数字: " + o
                + "（类型 " + (o == null ? "null" : o.getClass().getSimpleName()) + "）");
    }

    private double call(String fn, List<Double> a) {
        switch (fn) {
            case "max": return arity(fn, a, 2, Math.max(a.get(0), a.get(1)));
            case "min": return arity(fn, a, 2, Math.min(a.get(0), a.get(1)));
            case "abs": return arity(fn, a, 1, Math.abs(a.get(0)));
            case "floor": return arity(fn, a, 1, Math.floor(a.get(0)));
            case "ceil": return arity(fn, a, 1, Math.ceil(a.get(0)));
            case "round": return arity(fn, a, 1, Math.round(a.get(0)));
            case "sqrt": return arity(fn, a, 1, Math.sqrt(nonNeg(fn, a.get(0))));
            case "pow": return arity(fn, a, 2, Math.pow(a.get(0), a.get(1)));
            case "dist": return dist(a);
            default:
                throw new IllegalArgumentException("$calc 未知函数 '" + fn + "'（可用: "
                        + "max/min/abs/floor/ceil/round/sqrt/pow/dist）");
        }
    }

    private static double arity(String fn, List<Double> a, int n, double v) {
        if (a.size() != n) {
            throw new IllegalArgumentException("$calc 函数 " + fn + " 需要 " + n + " 个参数，收到 " + a.size());
        }
        return v;
    }

    private static double nonNeg(String fn, double v) {
        if (v < 0) {
            throw new IllegalArgumentException("$calc " + fn + " 参数不能为负: " + v);
        }
        return v;
    }

    /** {@code dist(ax,ay,az,bx,by,bz)} —— 挖通道算「还差几步」就用它。 */
    private static double dist(List<Double> a) {
        if (a.size() != 6) {
            throw new IllegalArgumentException("$calc dist 需要 6 个参数（ax,ay,az,bx,by,bz），收到 " + a.size());
        }
        double dx = a.get(3) - a.get(0);
        double dy = a.get(4) - a.get(1);
        double dz = a.get(5) - a.get(2);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 列表长度：{@code len($scan.matches)}（AC-B10 顺带能做，不用另开 for 也能数个数）。 */
    public static Object len(Object listLike) {
        if (listLike instanceof List<?> l) {
            return (long) l.size();
        }
        if (listLike instanceof Map<?, ?> m) {
            return (long) m.size();
        }
        if (listLike instanceof String s) {
            return (long) s.length();
        }
        throw new IllegalArgumentException("$calc len() 只接受列表/映射/字符串，收到: " + listLike);
    }
}