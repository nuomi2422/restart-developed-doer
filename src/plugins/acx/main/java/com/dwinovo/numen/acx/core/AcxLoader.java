package com.dwinovo.numen.acx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.dwinovo.numen.acx.api.AcxCondition;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxPrecondition;
import com.dwinovo.numen.acx.api.AcxLimitsSpec;
import com.dwinovo.numen.acx.api.AcxStep;
import com.dwinovo.numen.acx.api.AcxToolRegistry;
import com.dwinovo.numen.acx.api.Operator;

/**
 * AC 加载器 —— 扫目录、解析 {@code .ac}、三轮校验、坏文件隔离、stable/beta 分级。
 *
 * <p>三轮校验照 DD 版 {@code AcLoader.reloadAll}（{@code :45-124}）：</p>
 * <table>
 *   <caption>三轮校验</caption>
 *   <tr><th>轮</th><th>查什么</th><th>失败处理</th></tr>
 *   <tr><td>1</td><td>step / child 的 block 是否已注册</td><td>宽松（子 AC 可能还没加载）</td></tr>
 *   <tr><td>2</td><td>block 引用能否解析为积木或已加载 AC</td><td>该 AC 不注册，其余照常</td></tr>
 *   <tr><td>3</td><td>{@code $prev.x} / {@code $stepId.x} 字段是否存在</td><td>warning，不阻断</td></tr>
 * </table>
 *
 * <p><b>与 DD 的三处差异</b>：</p>
 * <ol>
 *   <li>DD 只把 {@code name/description/tags/steps} 读进 {@code AcFile}，
 *       {@code planner_notes} / {@code safety_notes} 被<b>静默丢弃</b>
 *       （{@code AcLoader.java:130-152} 用 gson 读成 raw Map 后只取那 4 个键）。
 *       ACX 保留这两个字段 —— 它们是给上游规划器看的，丢了等于白写。</li>
 *   <li>DD 的第 3 轮字段校验只递归一层 children（{@code :218-228}），
 *       {@code if} 套 {@code if} 套 {@code while} 的内层不查。ACX 全深度递归。</li>
 *   <li>DD 没有分级，{@code ac/} 和 {@code ac/beta/} 都由同一个 reloadAll 扫。
 *       ACX 分 {@code stable}/{@code beta}，beta 只在显式点名时才进目录。</li>
 * </ol>
 */
public final class AcxLoader {

    private static final Logger LOG = Logger.getLogger(AcxLoader.class.getName());

    /** 分级。stable 允许进生产；beta 只能显式指定，永不自动生效。 */
    public enum Channel {
        STABLE,
        BETA
    }

    public static final class LoadReport {
        private final Map<String, AcxDefinition> registered = new LinkedHashMap<>();
        private final Map<String, AcxDefinition> beta = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public Map<String, AcxDefinition> registered() { return registered; }
        public Map<String, AcxDefinition> betaOnly() { return beta; }
        public List<String> errors() { return errors; }
        public List<String> warnings() { return warnings; }
        public boolean hasErrors() { return !errors.isEmpty(); }

        public AcxCatalog catalog() {
            return AcxCatalog.of(registered);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("已加载 ").append(registered.size()).append(" 个 stable AC");
            if (!beta.isEmpty()) {
                sb.append("，").append(beta.size()).append(" 个 beta AC（需显式点名）");
            }
            if (!errors.isEmpty()) {
                sb.append("\n跳过 ").append(errors.size()).append(" 个坏文件（不影响其他）: ")
                  .append(String.join("; ", errors));
            }
            if (!warnings.isEmpty()) {
                sb.append("\n字段引用 warning ").append(warnings.size()).append(" 条（不阻断）: ")
                  .append(String.join("; ", warnings));
            }
            return sb.toString();
        }
    }

    private AcxLoader() { }

    /**
     * 扫 {@code dir} 与 {@code dir/stable} 与 {@code dir/beta}。
     *
     * @param includeBeta 是否把 beta 也注册进目录（默认 false —— beta 永不自动生效）
     */
    public static LoadReport loadAll(Path dir, AcxToolRegistry blocks, boolean includeBeta) {
        return loadAll(dir, blocks, includeBeta, false);
    }

    /**
     * @param strictFields 第 3 轮字段引用校验的级别。
     *   <p>{@code false}（默认，等于 DD 行为）：字段不存在只 warning，AC 照常注册。
     *   <p>{@code true}：字段不存在直接拒绝该 AC。
     *   <p>「引用了不存在的步骤 id」任何级别都是 error —— 那个引用必然是错的，
     *   不该只提醒（DD 的第 3 轮连这个都只 warning，真错会被静默带进游戏）。</p>
     */
    public static LoadReport loadAll(Path dir, AcxToolRegistry blocks,
                                     boolean includeBeta, boolean strictFields) {
        LoadReport report = new LoadReport();
        if (dir == null || !Files.exists(dir)) {
            report.errors.add("AC 目录不存在: " + dir);
            return report;
        }

        Map<String, AcxDefinition> stableFiles = new LinkedHashMap<>();
        Map<String, AcxDefinition> betaFiles = new LinkedHashMap<>();

        // stable 目录下的 .ac
        parseDir(dir, blocks, stableFiles, report);
        // stable/ 子目录（显式分级）
        parseDir(dir.resolve("stable"), blocks, stableFiles, report);
        // beta 子目录
        parseDir(dir.resolve("beta"), blocks, betaFiles, report);
        // beta 的 .beta.ac 也可能直接躺在 ac/ 根下
        parseDir(dir, blocks, betaFiles, report, ".beta.ac");

        // 第 2 轮：block 引用能否解析（此时所有 AC 名已知）
        Map<String, Set<String>> badRefs = new LinkedHashMap<>();
        for (Map.Entry<String, AcxDefinition> e : stableFiles.entrySet()) {
            Set<String> bad = badBlockRefs(e.getValue(), blocks, stableFiles);
            if (!bad.isEmpty()) {
                badRefs.put(e.getKey(), bad);
                report.errors.add(e.getKey() + " 引用了不存在的积木或 AC: " + String.join(", ", bad));
            }
        }
        for (Map.Entry<String, AcxDefinition> e : betaFiles.entrySet()) {
            Set<String> bad = badBlockRefs(e.getValue(), blocks, stableFiles);
            if (!bad.isEmpty()) {
                report.errors.add(e.getKey() + "(beta) 引用了不存在的积木或 AC: " + String.join(", ", bad));
                betaFiles.remove(e.getKey());
                badRefs.put(e.getKey(), bad);
            }
        }

        // 第 3 轮：字段引用静态校验。全深度递归（DD 只查一层 children）
        // 硬错 = 引用了 AC 内不存在的步骤 id，任何级别都拒；软警 = 字段不存在，按 strictFields 定级
        Map<String, List<String>> hardBad = new LinkedHashMap<>();
        List<String> softBad = new ArrayList<>();
        for (AcxDefinition def : stableFiles.values()) {
            checkFieldRefs(def, blocks, stableFiles, softBad,
                    hardBad.computeIfAbsent(def.name(), k -> new ArrayList<>()));
        }
        for (AcxDefinition def : betaFiles.values()) {
            checkFieldRefs(def, blocks, stableFiles, softBad,
                    hardBad.computeIfAbsent(def.name(), k -> new ArrayList<>()));
        }
        for (Map.Entry<String, List<String>> e : hardBad.entrySet()) {
            if (!e.getValue().isEmpty()) {
                report.errors.add(e.getKey() + " 引用了不存在的步骤: "
                        + String.join("; ", e.getValue()));
                badRefs.putIfAbsent(e.getKey(), Set.of("(字段引用)"));
            }
        }
        if (strictFields) {
            for (String w : softBad) {
                report.errors.add(w);
                badRefs.putIfAbsent(w.split(" ")[0], Set.of("(字段引用)"));
            }
        } else {
            report.warnings.addAll(softBad);
        }

        // 注册：引用有错的 stable AC 不注册（坏文件不拖累其他）
        for (Map.Entry<String, AcxDefinition> e : stableFiles.entrySet()) {
            if (badRefs.containsKey(e.getKey())) {
                continue;
            }
            report.registered.put(e.getKey(), e.getValue());
        }
        report.beta.putAll(betaFiles);

        // beta 要显式点名才可见
        if (includeBeta) {
            report.registered.putAll(betaFiles);
        }
        return report;
    }

    private static void parseDir(Path dir, AcxToolRegistry blocks,
                                 Map<String, AcxDefinition> into, LoadReport report) {
        parseDir(dir, blocks, into, report, ".ac");
    }

    private static void parseDir(Path dir, AcxToolRegistry blocks,
                                 Map<String, AcxDefinition> into, LoadReport report, String suffix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            report.errors.add(dir + " 列举失败: " + e.getMessage());
            return;
        }
        for (Path f : files) {
            try {
                AcxDefinition def = parse(f, blocks);
                into.put(def.name(), def);
                LOG.info("[acx] +" + def.name() + " (" + def.steps().size() + " steps)");
            } catch (Exception e) {
                // 大抽检原则：坏文件跳过，不影响其他 AC
                report.errors.add(f.getFileName() + ": " + e.getMessage());
                LOG.warning("[acx] 跳过 " + f.getFileName() + " — " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 单文件解析
    // ═══════════════════════════════════════════════════════════════════

    /** 解析一个 {@code .ac} 文件。控制块 children 必须非空；积木存在性第 1 轮宽松。 */
    public static AcxDefinition parse(Path file, AcxToolRegistry blocks) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return parseJson(json, blocks);
    }

    public static AcxDefinition parseJson(String json, AcxToolRegistry blocks) {
        Map<String, Object> raw;
        try {
            raw = JsonlRecordStore.toMap(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
        String name = str(raw.get("name"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("缺少 name 字段");
        }
        String version = str(raw.getOrDefault("version", "1"));
        String description = str(raw.getOrDefault("description", ""));
        // ★ DD 把这两个字段静默丢了（AcLoader.java:130-152 只取 4 个键）
        String plannerNotes = str(raw.getOrDefault("planner_notes", ""));
        String safetyNotes = str(raw.getOrDefault("safety_notes", ""));

        // 起跑前置条件：结构错直接拒整份（写错的守门条件不能静默放行）
        List<AcxPrecondition> preconditions = new ArrayList<>();
        Object rawPre = raw.get("preconditions");
        if (rawPre instanceof List<?> preList) {
            for (Object o : preList) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("precondition 必须是对象");
                }
                preconditions.add(parsePrecondition(m));
            }
        } else if (rawPre != null) {
            throw new IllegalArgumentException("preconditions 必须是数组");
        }

        List<String> tags = new ArrayList<>();
        Object rawTags = raw.get("tags");
        if (rawTags instanceof List<?> l) {
            for (Object o : l) {
                tags.add(str(o));
            }
        }

        List<AcxStep> steps = new ArrayList<>();
        Object rawSteps = raw.get("steps");
        if (rawSteps instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    steps.add(parseStep(castMap(m), blocks, new ArrayDeque<>()));
                }
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps 为空");
        }
        // step id 在整份 AC 内唯一（含嵌套 children）—— 变量引用靠 id 定位，重名会让 $ref 指错步骤
        List<AcxStep> deep = new java.util.ArrayList<>();
        for (AcxStep s : steps) {
            deep.addAll(s.flattenSelfAndDescendants());
        }
        Set<String> ids = new LinkedHashSet<>();
        for (AcxStep s : deep) {
            ids.add(s.id());
        }
        if (ids.size() != deep.size()) {
            List<String> dup = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (AcxStep s : deep) {
                if (!seen.add(s.id())) {
                    dup.add(s.id());
                }
            }
            throw new IllegalArgumentException("step id 重复（含 children）: " + String.join(", ", dup));
        }
        // 单份 AC 自己的运行上限：只认三个键、写错就拒（静默忽略上限等于熔断失效）
        AcxLimitsSpec spec = parseLimits(raw.get("limits"));

        return new AcxDefinition(name, version, description, tags, steps, plannerNotes, safetyNotes,
                preconditions, spec);
    }

    private static AcxLimitsSpec parseLimits(Object raw) {
        if (raw == null) {
            return AcxLimitsSpec.of();
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("limits 必须是对象");
        }
        Integer maxSteps = null;
        Integer maxTimeout = null;
        Integer maxDepth = null;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = str(e.getKey());
            Object v = e.getValue();
            int n;
            if (v instanceof Number num) {
                n = num.intValue();
            } else if (v instanceof String s) {
                try {
                    n = Integer.parseInt(s.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("limits." + k + " 不是整数: " + s);
                }
            } else {
                throw new IllegalArgumentException("limits." + k + " 必须是整数");
            }
            if (n <= 0) {
                throw new IllegalArgumentException("limits." + k + " 必须为正整数（<=0 会让熔断立即触发）");
            }
            switch (k) {
                case "max_steps" -> maxSteps = n;
                case "max_timeout_ms" -> maxTimeout = n;
                case "max_depth" -> maxDepth = n;
                default -> throw new IllegalArgumentException("limits 未知键: " + k
                        + "（可写 max_steps / max_timeout_ms / max_depth）");
            }
        }
        return AcxLimitsSpec.of(maxSteps, maxTimeout == null ? null : (long) maxTimeout, maxDepth);
    }

    @SuppressWarnings("unchecked")
    private static AcxStep parseStep(Map<String, Object> s, AcxToolRegistry blocks, Deque<String> path) {
        String stepId = str(s.getOrDefault("id", ""));
        String blockName = str(s.getOrDefault("block", ""));
        if (stepId.isBlank()) {
            throw new IllegalArgumentException("step 缺少 id");
        }
        if (blockName.isBlank()) {
            throw new IllegalArgumentException("step " + stepId + " 缺少 block");
        }
        if (path.contains(stepId)) {
            throw new IllegalArgumentException("step id 嵌套重复: " + String.join(" → ", path) + " → " + stepId);
        }

        boolean control = AcxStep.BLOCK_IF.equals(blockName) || AcxStep.BLOCK_WHILE.equals(blockName);

        // 第 1 轮：宽松校验（子 AC 可能还没加载，所以只查积木，且查不到不报错）
        if (!control && blocks != null && !blocks.contains(blockName)) {
            LOG.fine("[acx] 第1轮宽松: " + stepId + " 的 block " + blockName + " 暂未注册（可能是子 AC）");
        }

        Map<String, Object> params = s.get("params") instanceof Map<?, ?> m ? castMap(m) : Map.of();

        List<AcxStep> children = null;
        if (s.get("children") instanceof List<?> raw) {
            List<AcxStep> list = new ArrayList<>();
            path.addLast(stepId);
            try {
                for (Object o : raw) {
                    if (o instanceof Map<?, ?> m) {
                        list.add(parseStep(castMap(m), blocks, path));
                    }
                }
            } finally {
                path.removeLast();
            }
            if (!list.isEmpty()) {
                children = list;
            }
        }
        if (AcxStep.BLOCK_GUARD.equals(blockName)) {
            if (children != null && !children.isEmpty()) {
                throw new IllegalArgumentException("guard step " + stepId + " 不接受 children");
            }
            validateGuardConditions(stepId, params);
        }
        // AC-B11：for 必须给 list（不然无从遍历）
        if (AcxStep.BLOCK_FOR.equals(blockName)) {
            if (params == null || !params.containsKey("list")) {
                throw new IllegalArgumentException(
                        "for step " + stepId + " 需要 params.list（要遍历的列表）");
            }
        }

        // AC-B9：set 步 = 变量写回，params 必须非空（没东西可写就是写错了）
        if (AcxStep.BLOCK_SET.equals(blockName)) {
            if (children != null && !children.isEmpty()) {
                throw new IllegalArgumentException("set step " + stepId + " 不接受 children");
            }
            if (params == null || params.isEmpty()) {
                throw new IllegalArgumentException("set step " + stepId + " 需要 params，形如 {\"变量名\": 值}");
            }
        }

        // ★ DD 的硬校验：控制块必须有非空 children（AcLoader.java:193-195）
        if (control && (children == null || children.isEmpty())) {
            throw new IllegalArgumentException(
                    "控制块 step " + stepId + " (" + blockName + ") 必须有非空 children");
        }
        return new AcxStep(stepId, blockName, params, children);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 前置条件 / guard 的结构校验
    // ═══════════════════════════════════════════════════════════════════

    /** 解析一条前置条件 {field,op,value,message?,hint?}；结构不合法直接拒绝整份 AC。 */
    private static AcxPrecondition parsePrecondition(Map<?, ?> raw) {
        Map<String, Object> m = castMap(raw);
        String field = str(m.get("field"));
        if (field.isBlank()) {
            throw new IllegalArgumentException("precondition 缺少 field");
        }
        Operator op = Operator.fromSymbol(str(m.getOrDefault("op", "==")));
        if (op == null) {
            throw new IllegalArgumentException("precondition 算子无法识别: " + m.get("op"));
        }
        return new AcxPrecondition(new AcxCondition(field, op, m.get("value")),
                str(m.getOrDefault("message", "")), str(m.getOrDefault("hint", "")));
    }

    /** guard 的条件结构：condition 单条或非空 conditions 列表，每个必须有 field、算子可识别。 */
    private static void validateGuardConditions(String stepId, Map<String, Object> params) {
        List<Object> specs = new ArrayList<>();
        if (params.get("condition") instanceof Map<?, ?> m) {
            specs.add(m);
        }
        if (params.get("conditions") instanceof List<?> l) {
            specs.addAll(l);
        }
        if (specs.isEmpty()) {
            throw new IllegalArgumentException(
                    "guard step " + stepId + " 必须有 condition 或非空 conditions");
        }
        for (Object o : specs) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("guard step " + stepId + " 的条件必须是对象");
            }
            Map<String, Object> mm = castMap(m);
            if (str(mm.get("field")).isBlank()) {
                throw new IllegalArgumentException("guard step " + stepId + " 的条件缺少 field");
            }
            Operator op = Operator.fromSymbol(str(mm.getOrDefault("op", "==")));
            if (op == null) {
                throw new IllegalArgumentException(
                        "guard step " + stepId + " 的算子无法识别: " + mm.get("op"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 第 2 轮：block 引用解析
    // ═══════════════════════════════════════════════════════════════════

    private static Set<String> badBlockRefs(AcxDefinition def, AcxToolRegistry blocks,
                                            Map<String, AcxDefinition> loaded) {
        Set<String> bad = new LinkedHashSet<>();
        collectBadRefs(def.steps(), blocks, loaded, bad);
        return bad;
    }

    private static void collectBadRefs(List<AcxStep> steps, AcxToolRegistry blocks,
                                       Map<String, AcxDefinition> loaded, Set<String> bad) {
        for (AcxStep s : steps) {
            // 控制块是执行器内置，不查注册表（DD :85-86 / :165 同样处理）
            if (s.isControl() || s.isGuard() || s.isSet() || s.isFor()) {
                if (s.children() != null) {
                    collectBadRefs(s.children(), blocks, loaded, bad);
                }
                continue;
            }
            boolean knownBlock = blocks != null && blocks.contains(s.block());
            boolean knownAc = loaded.containsKey(s.block());
            if (!knownBlock && !knownAc) {
                bad.add(s.id() + "→" + s.block());
            }
            if (s.children() != null) {
                collectBadRefs(s.children(), blocks, loaded, bad);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 第 3 轮：字段引用校验（全深度，warning 不阻断）
    // ═══════════════════════════════════════════════════════════════════

    private static void checkFieldRefs(AcxDefinition def, AcxToolRegistry blocks,
                                       Map<String, AcxDefinition> loaded,
                                       List<String> warnings, List<String> hardErrors) {
        Map<String, Set<String>> stepFields = new LinkedHashMap<>();
        for (AcxStep s : def.allStepsDeep()) {
            stepFields.put(s.id(), outputFields(s.block(), blocks, loaded, new LinkedHashSet<>()));
        }
        checkList(def.name(), def.steps(), blocks, stepFields, warnings, hardErrors);
    }

    /** ★ 全深度递归（DD 只查一层 children）。{@code $prev} 对照同链上一兄弟。 */
    private static void checkList(String acName, List<AcxStep> steps, AcxToolRegistry blocks,
                                  Map<String, Set<String>> stepFields,
                                  List<String> warnings, List<String> hardErrors) {
        for (int i = 0; i < steps.size(); i++) {
            AcxStep step = steps.get(i);
            Set<String> prevFields = i > 0 ? outputFields(steps.get(i - 1).block(), blocks, Map.of(), new LinkedHashSet<>()) : Set.of();
            checkValue(acName, step, step.params(), prevFields, stepFields, warnings, hardErrors);
            if (step.children() != null) {
                checkList(acName, step.children(), blocks, stepFields, warnings, hardErrors);
            }
        }
    }

    /** 该 block / 子 AC 的输出字段集。子 AC 取最后一个顶层 step 的输出（DD :232-242 同样做法）。 */
    private static Set<String> outputFields(String blockName, AcxToolRegistry blocks,
                                            Map<String, AcxDefinition> loaded, Set<String> visiting) {
        if (AcxStep.BLOCK_GUARD.equals(blockName)) {
            return Set.of("_guard_passed");
        }
        if (AcxStep.BLOCK_SET.equals(blockName)) {
            return Set.of("_set");
        }
        if (AcxStep.BLOCK_FOR.equals(blockName)) {
            return Set.of("_for_iterated", "_for_var");
        }
        if (blocks != null) {
            var tool = blocks.find(blockName);
            if (tool.isPresent()) {
                return tool.get().schema().outputFields();
            }
        }
        AcxDefinition sub = loaded.get(blockName);
        if (sub != null && visiting.add(blockName) && !sub.steps().isEmpty()) {
            return outputFields(sub.steps().get(sub.steps().size() - 1).block(), blocks, loaded, visiting);
        }
        return Set.of();
    }

    /**
     * 字段命中判定。只比第一段：运行时才知道嵌套/列表形状，加载期不该猜。
     * <p>输出字段本身可以是点路径（get_self_status 声明 position.x），所以引用只写头（position）也算命中。
     * 例：{@code $scan.matches.0.block} → 首段 matches；{@code $read.position} → 首段 position。
     */
    private static boolean hasField(Set<String> fields, String path) {
        if (fields.contains(path)) {
            return true;
        }
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        if (fields.contains(head)) {
            return true;
        }
        for (String f : fields) {
            if (f.startsWith(head + ".")) {
                return true;
            }
        }
        return false;
    }

    private static void checkValue(String acName, AcxStep step, Object value,
                                   Set<String> prevFields, Map<String, Set<String>> stepFields,
                                   List<String> warnings, List<String> hardErrors) {
        if (value instanceof String s) {
            if (s.startsWith("$prev.")) {
                String key = s.substring("$prev.".length());
                if (!prevFields.isEmpty() && !hasField(prevFields, key)) {
                    warnings.add(acName + " step " + step.id() + " 引用 $prev." + key + " 但上一步无此输出字段");
                }
            } else if (s.startsWith("$var.")) {
                // 运行期变量（AC-B9）：set 步写出来的，加载期还没有，只能跳过
            } else if (s.startsWith("$input.")) {
                // 外部输入，无法静态校验，跳过
            } else if (s.startsWith("$") && s.indexOf('.') > 1) {
                int dot = s.indexOf('.');
                String stepId = s.substring(1, dot);
                String key = s.substring(dot + 1);
                Set<String> fields = stepFields.get(stepId);
                if (fields == null) {
                    hardErrors.add("step " + step.id() + " 引用 $" + stepId + " 但 AC 内无此步骤 id");
                } else if (!fields.isEmpty() && !hasField(fields, key)) {
                    warnings.add(acName + " step " + step.id() + " 引用 $" + stepId + "." + key
                            + " 但步骤 " + stepId + " 无此输出字段");
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                checkValue(acName, step, v, prevFields, stepFields, warnings, hardErrors);
            }
        } else if (value instanceof List<?> list) {
            for (Object v : list) {
                checkValue(acName, step, v, prevFields, stepFields, warnings, hardErrors);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
