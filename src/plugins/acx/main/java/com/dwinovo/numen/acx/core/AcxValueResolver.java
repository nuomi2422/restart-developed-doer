package com.dwinovo.numen.acx.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code $} 引用解析器。搬自 DD 版 {@code AcRunner.resolveValue}（:600-653）。
 *
 * <p>三种写法：</p>
 * <ul>
 *   <li>{@code $prev.xxx} —— 上一步的输出字段</li>
 *   <li>{@code $<stepId>.xxx} —— 跨步引用任意指定步骤的输出字段</li>
 *   <li>{@code $input.xxx} —— 本次 AC 外部传入的参数</li>
 * </ul>
 *
 * <p><b>关键行为</b>：引用解析不到时<b>保留原字符串</b>并记 WARN，不抛异常、不返回 null。
 * 这样「引用写错」的表现是「参数原样传下去让积木自己报错」，
 * 而不是「整条 AC 在加载/运行期炸掉」——后者会掩盖真正的错误位置。</p>
 */
public final class AcxValueResolver {

    private static final Logger LOG = Logger.getLogger(AcxValueResolver.class.getName());

    public static final String PREV = "prev";
    public static final String INPUT = "input";
    /** AC-B9：运行期变量（由 {@code set} 步写入，落在 AcxRunRecord.vars 里跨断点存活）。 */
    public static final String VAR = "var";
    /** AC-B10：循环状态别名（{@code $loop.iter} / {@code $loop.count} / index / total）。 */
    public static final String LOOP = "loop";

    private final Map<String, Object> vars;
    private final Map<String, Object> lastOutput;
    private final Map<String, Object> input;
    private final Map<String, Map<String, Object>> allOutputs;

    public AcxValueResolver(Map<String, Object> lastOutput,
                            Map<String, Object> input,
                            Map<String, Map<String, Object>> allOutputs) {
        this(lastOutput, input, allOutputs, null);
    }

    /** 变量感知构造（AC-B9）。vars 可以就地传入 Ctx.vars，写入立刻对后续步骤可见。 */
    public AcxValueResolver(Map<String, Object> lastOutput,
                            Map<String, Object> input,
                            Map<String, Map<String, Object>> allOutputs,
                            Map<String, Object> vars) {
        this.lastOutput = lastOutput == null ? Map.of() : lastOutput;
        this.input = input == null ? Map.of() : input;
        this.allOutputs = allOutputs == null ? Map.of() : allOutputs;
        this.vars = vars == null ? Map.of() : vars;
    }

    /** 递归解析 params 里所有 {@code $} 引用。 */
    public Map<String, Object> resolveParams(Map<String, Object> params) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (params == null) {
            return resolved;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            resolved.put(e.getKey(), resolve(e.getValue()));
        }
        return resolved;
    }

    /** 递归解析任意值：String 走引用规则，Map / List 逐元素递归，其余原样返回。 */
    public Object resolve(Object value) {
        if (value instanceof String s) {
            return resolveString(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                resolved.put(String.valueOf(e.getKey()), resolve(e.getValue()));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolve(item));
            }
            return resolved;
        }
        return value;
    }

    private Object resolveString(String s) {
        if (s.startsWith("$")) {
            int dot = s.indexOf('.');
            if (dot > 1) {
                String head = s.substring(1, dot);
                String key = s.substring(dot + 1);
                if (PREV.equals(head)) {
                    return lookup(lastOutput, key, "$prev." + key, s);
                }
                if (INPUT.equals(head)) {
                    return lookup(input, key, "$input." + key, s);
                }
                if (LOOP.equals(head) && (key.startsWith("iter") || key.startsWith("index")
                        || key.startsWith("count") || key.startsWith("total"))) {
                    String v = "iter".equals(key) || "index".equals(key)
                            ? "loop_iter" : "loop_count";
                    return lookup(vars, v, "$loop." + key, s);
                }
                if (VAR.equals(head)) {
                    // 顶层未知名要响亮失败：变量名是作者自己起的，拼错还留原串会让
                    // 后面每一步都拿着字面串去跑（真机上就这么踩过）。带点的走点路径。
                    if (key.indexOf('.') < 0 && !vars.containsKey(key)) {
                        throw new IllegalArgumentException("变量未定义: $var." + key
                                + "（本次已定义: " + vars.keySet() + "）");
                    }
                    return lookup(vars, key, "$var." + key, s);
                }
                Map<String, Object> stepOutput = allOutputs.get(head);
                if (stepOutput != null) {
                    return lookup(stepOutput, key, "$" + head + "." + key, s);
                }
                LOG.warning("$" + head + " 步骤不存在，保留原字串");
                return s;
            }
        }
        return s;
    }

    /**
    /**
     * 沿点路径在对象里下钻；段命中 {@link List} 时按数字下标取值。
     * <p>数组下标这条是 2026-10-02 真机实测逼出来的：Numen 的 {@code scan_blocks} 输出是
     * {@code {matches:[{x,y,z,block,distance}]}}，脚本要判「有没有矿」只能写
     * {@code $scan.matches.0.block}，原来只认 Map 的点路径取不到。</p>
     * <p>取不到返回 null（不抛、不保留原串），由调用方决定是记警告还是退回。</p>
     */
    public static Object resolvePath(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(seg);
            } else if (cur instanceof List<?> l) {
                int idx = -1;
                try {
                    idx = Integer.parseInt(seg);
                } catch (NumberFormatException ignored) {
                    return null;
                }
                cur = (idx >= 0 && idx < l.size()) ? l.get(idx) : null;
            } else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /**
     * 字段查找：先整体查，再按点路径逐层下钻。
     *
     * <p>点路径是 Numen 适配层加的需求：{@code get_self_status} 这类工具的输出是
     * 嵌套结构（{@code position:{x,y,z}}），脚本里 {@code $before.position.x} 必须能取到
     * 叶子值，否则「读位置 → 依位置移动」这条最小闭环根本写不出来。</p>
     */
    private static Object lookup(Map<String, Object> root, String key, String label, String original) {
        Object v = root.get(key);
        if (v != null) {
            return v;
        }
        if (key.indexOf('.') >= 0) {
            Object cur = resolvePath(root, key);
            if (cur != null) {
                return cur;
            }
        }

        LOG.warning(label + " 引用的字段不存在，保留原字串");
        return original;
    }

    /**
     * 把字符串转数字，用于条件求值。
     *
     * <p>未解析的 {@code $} 引用会返回 0 —— 这是 DD 的原行为（{@code AcRunner.toDouble} :579-585）：
     * 让 {@code while} 至少能进一次，而不是因为引用写错就永远不循环。</p>
     */
    public static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    public static int toInt(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return fallback;
        }
    }
}