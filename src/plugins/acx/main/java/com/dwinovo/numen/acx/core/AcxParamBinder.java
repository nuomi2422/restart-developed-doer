package com.dwinovo.numen.acx.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.dwinovo.numen.acx.api.AcxCondition;
import com.dwinovo.numen.acx.api.Operator;

/**
 * 参数绑定层 —— 声明式「字段映射 / 类型转换 / 默认值 / 筛选 / 最近选择」。
 *
 * <p>积木参数有两种写法，两者可以混用：</p>
 * <ol>
 *   <li><b>普通值</b>：与原来一样，字符串里的 {@code $prev.x / $stepId.x / $input.x}
 *       直接解析，解析不到保留原串 + 提示事件（{@code REF_UNRESOLVED}）。</li>
 *   <li><b>绑定描述符</b>：值是一个带 {@code $from} 键的对象，做一次参数加工：</li>
 * </ol>
 *
 * <pre>
 * {"x": {"$from":"$scan.nearest_x", "$as":"int",  "$default":0},
 *  "y": {"$from":"$scan.nearest_y", "$as":"int",  "$default":0},
 *  "target": {"$from":"$scan.blocks",
 *             "$filter":{"field":"type","op":"==","value":"iron_ore"},
 *             "$pick":"nearest",
 *             "$origin":"$input.position",
 *             "$pick_fields":"x,y,z"},
 *  "count": {"$from":"$prev.gathered", "$as":"string"}}
 * </pre>
 *
 * <table>
 *   <caption>描述符键</caption>
 *   <tr><th>键</th><th>作用</th></tr>
 *   <tr><td>{@code $from}</td><td>来源：引用串或字面量（字符串引用解析失败时走 {@code $default}）</td></tr>
 *   <tr><td>{@code $as}</td><td>类型转换：{@code int / number / string / bool / string_array / int_array}</td></tr>
 *   <tr><td>{@code $default}</td><td>{@code $from} 解析不到时的兜底值（不产生 unresolved 提示）</td></tr>
 *   <tr><td>{@code $filter}</td><td>对列表做元素筛选：{@code {field,op,value}}，field 相对元素本身；
 *       可用列表表示多条件（全过才留下）</td></tr>
 *   <tr><td>{@code $pick}</td><td>从列表选一个：{@code first}（默认）/ {@code last} / {@code nearest}</td></tr>
 *   <tr><td>{@code $origin}</td><td>{@code nearest} 的距离原点（{@code {x,y,z}} 映射或 {@code [x,y,z]} 列表），
 *       通常引 {@code $input.position} 或 {@code $prev}</td></tr>
 *   <tr><td>{@code $pick_fields}</td><td>元素里的坐标字段名，默认 {@code x,y,z}（逗号分隔）</td></tr>
 * </table>
 *
 * <p><b>提示而不炸</b>：任何一步加工失败都只记 warning（runner 转成事件）并让值尽量保持原样，
 * 由积木自己的参数校验去响亮拒绝 —— 保持与 {@link AcxValueResolver} 相同的失败哲学。</p>
 */
public final class AcxParamBinder {

    private static final Logger LOG = Logger.getLogger(AcxParamBinder.class.getName());

    /** 一条「引用解析不到」提示。runner 会把它转成 REF_UNRESOLVED 事件。 */
    public record Warning(String param, String ref, String reason, String hint) { }

    /** 提示里最多列几个可用字段（防止把一个巨大的输出全塞进事件）。 */
    private static final int MAX_HINT_FIELDS = 12;

    public static final String FROM = "$from";
    public static final String AS = "$as";
    public static final String DEFAULT = "$default";
    public static final String FILTER = "$filter";
    public static final String PICK = "$pick";
    public static final String ORIGIN = "$origin";
    public static final String PICK_FIELDS = "$pick_fields";
    public static final String TAKE = "$take";
    /** AC-B10：算术表达式，值是表达式文本（可含 $var.* / $prev.* 引用）。 */
    public static final String CALC = "$calc";
    /** AC-B10：取列表/映射长度（"$len": "$scan.matches"）。 */
    public static final String LEN = "$len";

    private AcxParamBinder() { }

    /** 兼容入口：没有变量上下文（AC-B9 之前的所有调用点）。 */
    public static Map<String, Object> bind(Map<String, Object> params,
                                         Map<String, Object> lastOutput,
                                         Map<String, Object> input,
                                         Map<String, Map<String, Object>> allOutputs,
                                         List<Warning> warnings) {
        return bind(params, lastOutput, input, allOutputs, Map.of(), warnings);
    }

/** 变量感知入口（AC-B9）：{@code $from} 描述符内部也能引用 {@code $var.*}。 */
    public static Map<String, Object> bind(Map<String, Object> params,
                                           Map<String, Object> lastOutput,
                                           Map<String, Object> input,
                                           Map<String, Map<String, Object>> allOutputs,
                                           Map<String, Object> vars,
                                           List<Warning> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (params == null) {
            return out;
        }
        AcxValueResolver resolver = new AcxValueResolver(lastOutput, input, allOutputs, vars);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            out.put(e.getKey(), bindValue(e.getKey(), e.getValue(), resolver,
                    lastOutput, input, allOutputs, warnings));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════

    private static Object bindValue(String path, Object value, AcxValueResolver resolver,
                                    Map<String, Object> lastOutput, Map<String, Object> input,
                                    Map<String, Map<String, Object>> allOutputs,
                                    List<Warning> warnings) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> m = cast(raw);
            if (m.containsKey(FROM)) {
                return bindDescriptor(path, m, resolver, lastOutput, input, allOutputs, warnings);
            }
            // AC-B10：算术 / 取长度。解析失败一律抛（runner 转 STEP_FAILED），不返回 0 假装成功。
            if (m.containsKey(CALC)) {
                return AcxExpr.eval(String.valueOf(m.get(CALC)), resolver);
            }
            if (m.containsKey(LEN)) {
                Object lenArg = m.get(LEN);
                Object v = (lenArg instanceof String sv && sv.startsWith("$"))
                        ? resolver.resolve(sv)
                        : lenArg;
                return AcxExpr.len(v);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                out.put(e.getKey(), bindValue(path + "." + e.getKey(), e.getValue(), resolver,
                        lastOutput, input, allOutputs, warnings));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            int i = 0;
            for (Object item : list) {
                out.add(bindValue(path + "[" + i + "]", item, resolver,
                        lastOutput, input, allOutputs, warnings));
                i++;
            }
            return out;
        }
        if (value instanceof String s && s.startsWith("$")) {
            Object resolved = resolver.resolve(s);
            if (isUnresolved(s, resolved)) {
                warnings.add(new Warning(path, s, "引用解析不到，已保留原串",
                        hintFor(s, lastOutput, input, allOutputs)));
            }
            return resolved;
        }
        return value;
    }

    private static Object bindDescriptor(String path, Map<String, Object> m, AcxValueResolver resolver,
                                         Map<String, Object> lastOutput, Map<String, Object> input,
                                         Map<String, Map<String, Object>> allOutputs,
                                         List<Warning> warnings) {
        Object from = m.get(FROM);
        Object resolved;
        if (from instanceof String fs && fs.startsWith("$")) {
            resolved = resolver.resolve(fs);
            if (isUnresolved(fs, resolved)) {
                if (m.containsKey(DEFAULT)) {
                    resolved = resolver.resolve(m.get(DEFAULT));
                } else {
                    warnings.add(new Warning(path, fs, "引用解析不到，已保留原串",
                            hintFor(fs, lastOutput, input, allOutputs)));
                }
            }
        } else {
            resolved = resolver.resolve(from);
        }

        Object filter = m.get(FILTER);
        if (filter != null && resolved instanceof List<?> list) {
            resolved = applyFilter(path, list, filter, resolver, input, allOutputs, warnings);
        }

        Object pick = m.get(PICK);
        if (pick != null && resolved instanceof List<?> list) {
            resolved = applyPick(path, list, String.valueOf(pick), m, resolver,
                    input, allOutputs, warnings);
        }

        Object take = m.get(TAKE);
        if (take != null) {
            resolved = applyTake(path, resolved, take, warnings);
        }

        Object as = m.get(AS);
        if (as != null) {
            resolved = convert(path, resolved, String.valueOf(as), warnings);
        }
        return resolved;
    }

    // ── $filter ─────────────────────────────────────────────────────

    private static Object applyFilter(String path, List<?> list, Object filter,
                                      AcxValueResolver resolver, Map<String, Object> input,
                                      Map<String, Map<String, Object>> allOutputs,
                                      List<Warning> warnings) {
        List<Map<String, Object>> specs = new ArrayList<>();
        if (filter instanceof Map<?, ?> m) {
            specs.add(cast(m));
        } else if (filter instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    specs.add(cast(m));
                }
            }
        }
        if (specs.isEmpty()) {
            warnings.add(new Warning(path, "$filter", "$filter 写法无法识别，未筛选", ""));
            return list;
        }
        List<Object> out = new ArrayList<>();
        boolean warnedNonMap = false;
        for (Object elem : list) {
            if (!(elem instanceof Map<?, ?> em)) {
                if (!warnedNonMap) {
                    warnings.add(new Warning(path, "$filter", "列表里有非对象元素，已跳过", ""));
                    warnedNonMap = true;
                }
                continue;
            }
            boolean keep = true;
            for (Map<String, Object> spec : specs) {
                if (!matchesSpec(spec, cast(em), resolver, input, allOutputs)) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                out.add(elem);
            }
        }
        return out;
    }

    /**
     * 单条件匹配一个元素。
     *
     * <p>field 相对元素本身（{@code "type"} 取元素里的 type），也可以继续用 {@code $} 引用；
     * 比较复用 {@link AcxConditionEvaluator} —— 把实际值转成字符串当 field、
     * 期望值保持原类型，于是三种比较路径（数值 / 布尔 / 字符串）与主求值器完全一致。</p>
     */
    private static boolean matchesSpec(Map<String, Object> spec, Map<String, Object> element,
                                       AcxValueResolver outerResolver, Map<String, Object> input,
                                       Map<String, Map<String, Object>> allOutputs) {
        Object field = spec.get("field");
        if (field == null) {
            return false;
        }
        Operator op = Operator.fromSymbol(String.valueOf(spec.getOrDefault("op", "==")));
        if (op == null) {
            op = Operator.UNKNOWN;
        }
        Object value = spec.get("value");
        AcxValueResolver elemResolver = new AcxValueResolver(element, input, allOutputs);
        Object actual = field instanceof String fs && fs.startsWith("$")
                ? elemResolver.resolve(fs)
                : (field instanceof String fs ? element.get(fs) : field);
        Object expected = value instanceof String vs && vs.startsWith("$")
                ? outerResolver.resolve(vs)
                : value;
        AcxCondition synthetic = new AcxCondition(String.valueOf(actual), op, expected);
        return AcxConditionEvaluator.evaluate(synthetic, element, input, allOutputs);
    }

    // ── $pick ───────────────────────────────────────────────────────

    private static Object applyPick(String path, List<?> list, String pick, Map<String, Object> m,
                                    AcxValueResolver resolver, Map<String, Object> input,
                                    Map<String, Map<String, Object>> allOutputs,
                                    List<Warning> warnings) {
        if (list.isEmpty()) {
            return list;
        }
        String mode = pick.trim().toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case "last":
                return list.get(list.size() - 1);
            case "nearest": {
                int idx = nearestIndex(path, list, m, resolver, warnings);
                return list.get(idx);
            }
            case "first":
            default:
                return list.get(0);
        }
    }

    private static int nearestIndex(String path, List<?> list, Map<String, Object> m,
                                    AcxValueResolver resolver, List<Warning> warnings) {
        Object originRaw = m.get(ORIGIN);
        if (originRaw == null) {
            warnings.add(new Warning(path, "$pick", "$pick=nearest 缺少 $origin，退回第一个", ""));
            return 0;
        }
        Object origin = resolver.resolve(originRaw);
        if (isUnresolvedValue(originRaw, origin)) {
            warnings.add(new Warning(path, String.valueOf(originRaw),
                    "$origin 解析不到，退回第一个", ""));
            return 0;
        }
        double[] o = coords(origin, splitFields(m.get(PICK_FIELDS), "x,y,z"));
        if (o == null) {
            // ★ 回退：`$pick_fields` 描述的是<b>列表元素</b>的坐标字段名（如
            // `position.x,position.y,position.z`），而 `$origin` 常常是裸 `{x,y,z}`
            // （例如 `$var.origin` 存的就是 get_self_status 的 position）。
            // 只按 pick_fields 去读 origin 会取不到 → 永远退回"第一个"，于是
            // "最近优先"静默退化成"列表第一个"（2026-10-09 引羊 AC 实测就是这个：
            // 它想选最近的羊，实际选中了扫描列表第一个，而列表按距离排序，
            // 于是可能选到猪/鸡/掉落物）。这里再用默认 x,y,z 试一次，两种写法都支持。
            o = coords(origin, splitFields(null, "x,y,z"));
        }
        if (o == null) {
            warnings.add(new Warning(path, String.valueOf(originRaw),
                    "$origin 不是 {x,y,z} 或 [x,y,z]，退回第一个", ""));
            return 0;
        }
        String[] fields = splitFields(m.get(PICK_FIELDS), "x,y,z");
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            double[] p = coords(elem, fields);
            if (p == null) {
                continue;
            }
            double d = Math.sqrt(Math.pow(p[0] - o[0], 2) + Math.pow(p[1] - o[1], 2)
                    + Math.pow(p[2] - o[2], 2));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static String[] splitFields(Object pickFields, String fallback) {
        String s = pickFields == null ? fallback : String.valueOf(pickFields);
        String[] parts = s.split(",");
        if (parts.length != 3) {
            return fallback.split(",");
        }
        for (int i = 0; i < 3; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * 从 {@code {x,y,z}} 映射或 {@code [x,y,z]} 列表取三坐标；取不到返回 null。
     *
     * <p>★ 字段名支持<b>点路径</b>（{@code position.x}）：Numen 的实体/方块条目是嵌套的
     * （{@code {"position":{"x":..,"y":..,"z":..}}}），而 {@code $pick_fields} 本来就写成
     * {@code position.x,position.y,position.z}。此前这里用 {@code m.get(field)} 平铺取，
     * 点路径必然取不到 → 每个元素都被跳过 → {@code best} 停在 0 → <b>nearest 静默退化成
     * "列表第一个"</b>（2026-10-09 引羊 AC 实测：想选最近的羊，实际选中扫描列表第一条，
     * 而那可能是猪/鸡/掉落物）。改用 {@link AcxValueResolver#resolvePath} 同时支持两种写法。
     */
    private static double[] coords(Object v, String[] fields) {
        if (v instanceof Map<?, ?> m) {
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                Object c = m.get(fields[i]);
                if (!(c instanceof Number) && fields[i].indexOf('.') >= 0) {
                    // 点路径：按 path 下钻（resolvePath 只认 Map/List，正合这里的结构）
                    c = AcxValueResolver.resolvePath(v, fields[i]);
                }
                if (!(c instanceof Number n)) {
                    return null;
                }
                out[i] = n.doubleValue();
            }
            return out;
        }
        if (v instanceof List<?> l && l.size() >= 3) {
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                if (!(l.get(i) instanceof Number n)) {
                    return null;
                }
                out[i] = n.doubleValue();
            }
            return out;
        }
        return null;
    }


    // ── $take ─────────────────────────────────────────────────────────

    /**
     * 从已筛已选的对象里按字段取叶子值（{@code $take:"x"}），或投影多个字段（{@code $take:["x","y","z"]}）。
     * <p>为什么必须有它：{@code $filter}+{@code $pick} 选出的是<b>元素对象</b>（Numen 的 scan_blocks
     * 每条 match 是 {@code {x,y,z,block,distance}}），而 {@code goto}/{@code inspect_block} 要的是
     * {@code x/y/z} 三个标量参数。没有 $take，筛选链路就到工具门口断掉。</p>
     */
    private static Object applyTake(String path, Object value, Object take,
                                     List<Warning> warnings) {
        if (take instanceof String s) {
            Object got = AcxValueResolver.resolvePath(value, s);
            if (got == null) {
                warnings.add(new Warning(path, "$take=" + s,
                        "$take 取不到字段（值类型 " + typeName(value) + "，可用键 " + availableKeys(value) + "）",
                        "字段藏在下层就写点路径，例如 $take=\"position.x\"；元素本身是列表要先用 `$pick` 取出单个（`$filter` 只筛不选）"));
            }
            return got;
        }
        if (take instanceof List<?> fields && value instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Object f : fields) {
                if (f == null) {
                    continue;
                }
                String key = String.valueOf(f);
                Object got = AcxValueResolver.resolvePath(value, key);
                if (got == null) {
                    warnings.add(new Warning(path, "$take=" + key,
                            "$take 取不到字段（可用键 " + availableKeys(value) + "）",
                            "字段藏在下层就写点路径，例如 $take=\"position.x\""));
                }
                out.put(key, got);
            }
            return out;
        }
        warnings.add(new Warning(path, "$take", "$take 写法无法识别（要字段名或字段名数组）", ""));
        return value;
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    /** 失败提示里列出实际可用的键 —— 真机撞出来的形状（平铺 vs 嵌套）不一眼列出来没法自诊断。 */
    private static String availableKeys(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            for (Object k : m.keySet()) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(k);
                if (sb.length() > 120) {
                    sb.append("...");
                    break;
                }
            }
            return sb.length() == 0 ? "(空)" : sb.toString();
        }
        if (v instanceof List<?> l) {
            return "List(" + l.size() + " 项)";
        }
        return "无键";
    }

    // ── $as ─────────────────────────────────────────────────────────

    private static Object convert(String path, Object v, String as, List<Warning> warnings) {
        String kind = as.trim().toLowerCase(java.util.Locale.ROOT);
        if (v == null) {
            return null;
        }
        switch (kind) {
            case "int":
            case "integer": {
                Double d = toNumber(v);
                if (d == null) {
                    warnings.add(new Warning(path, as, "int 转换失败，保留原值", ""));
                    return v;
                }
                return (int) Math.round(d);
            }
            case "number":
            case "double":
            case "float": {
                Double d = toNumber(v);
                if (d == null) {
                    warnings.add(new Warning(path, as, "number 转换失败，保留原值", ""));
                    return v;
                }
                return d;
            }
            case "string":
                return String.valueOf(v);
            case "bool":
            case "boolean": {
                if (v instanceof Boolean b) {
                    return b;
                }
                if (v instanceof Number n) {
                    return n.doubleValue() != 0;
                }
                String s = String.valueOf(v).trim();
                return "true".equalsIgnoreCase(s) || "1".equals(s);
            }
            case "string_array": {
                List<Object> out = new ArrayList<>();
                if (v instanceof List<?> l) {
                    for (Object o : l) {
                        out.add(String.valueOf(o));
                    }
                } else {
                    out.add(String.valueOf(v));
                }
                return out;
            }
            case "int_array": {
                List<Object> out = new ArrayList<>();
                if (v instanceof List<?> l) {
                    for (Object o : l) {
                        Double d = toNumber(o);
                        out.add(d == null ? o : (int) Math.round(d));
                    }
                } else {
                    Double d = toNumber(v);
                    out.add(d == null ? v : (int) Math.round(d));
                }
                return out;
            }
            default:
                warnings.add(new Warning(path, as, "未知的 $as 类型，保留原值",
                        "可用: int / number / string / bool / string_array / int_array"));
                return v;
        }
    }

    private static Double toNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ── 提示 ─────────────────────────────────────────────────────────

    private static boolean isUnresolved(Object ref, Object resolved) {
        return ref instanceof String s && s.startsWith("$")
                && resolved instanceof String r && r.equals(s);
    }

    private static boolean isUnresolvedValue(Object ref, Object resolved) {
        return isUnresolved(ref, resolved);
    }

    /** 给出该引用「本可以取哪些字段」的提示。 */
    private static String hintFor(String ref, Map<String, Object> lastOutput,
                                  Map<String, Object> input,
                                  Map<String, Map<String, Object>> allOutputs) {
        String head;
        Set<String> fields = Set.of();
        if (ref.startsWith("$prev.")) {
            head = "$prev";
            fields = lastOutput == null ? Set.of() : lastOutput.keySet();
        } else if (ref.startsWith("$input.")) {
            head = "$input";
            fields = input == null ? Set.of() : input.keySet();
        } else if (ref.startsWith("$")) {
            int dot = ref.indexOf('.');
            head = dot > 1 ? "$" + ref.substring(1, dot) : ref;
            Map<String, Object> out = allOutputs == null ? null : allOutputs.get(ref.substring(1, dot));
            fields = out == null ? Set.of() : out.keySet();
        } else {
            return "";
        }
        if (fields.isEmpty()) {
            return head + " 当前没有输出字段";
        }
        List<String> list = new ArrayList<>(fields);
        StringBuilder sb = new StringBuilder("可用字段: ");
        for (int i = 0; i < list.size() && i < MAX_HINT_FIELDS; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i));
        }
        if (list.size() > MAX_HINT_FIELDS) {
            sb.append(" …(共 ").append(list.size()).append(" 个)");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
