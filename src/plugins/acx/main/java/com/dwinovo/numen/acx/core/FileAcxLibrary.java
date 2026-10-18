package com.dwinovo.numen.acx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxLifecycleState;
import com.dwinovo.numen.acx.api.AcxStep;
import com.dwinovo.numen.acx.api.AcxToolRegistry;

/**
 * 版本库：{@code publish}（写新版本，状态 GENERATED）→ {@code markPending}（有运行标记后）→
 * {@code approve}（人工批准切 active，状态 STABLE）→ {@code rollback}（切回旧 STABLE 版本）。
 * 另提供 {@code reject}（否决，不清库）与 {@code reopen}（重开重写）。
 *
 * <p><b>与现有 AC 的关键差别</b>：现有 {@code FileAcVersionStore.latest(name)} 取版本号最大的
 * 即生效，{@code ac_publish} 一发就上线，没有人工闸门、没有回滚、没有状态。ACX 把
 * {@code 晋级门与状态机.md} 里那句「进入生产仍需人工批准」落到代码上，并给每个版本
 * 独立状态（{@link AcxLifecycleState}）。</p>
 *
 * <p>落盘格式（{@code ac-library.json}）：</p>
 * <pre>
 * {
 *   "defs": {
 *     "mine_iron": {
 *       "active_version": "2",
 *       "approved_by": "user",
 *       "approved_at": 1767300000000,
 *       "versions": {
 *         "2": { "definition": { ... }, "published_at": ..., "note": "...",
 *                "status": "STABLE", "status_at": ..., "status_note": "",
 *                "rejected_reason": "" }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>向后兼容：旧档没有 {@code status} 键时，active 版本视为 STABLE，其余视为 GENERATED。</p>
 */
public final class FileAcxLibrary {

    private static final Logger LOG = Logger.getLogger(FileAcxLibrary.class.getName());

    /** 发布期静态校验上限（与现有 AC 的 AcAuthoringService 同量级，但数值放宽）。 */
    public static final int MAX_STEPS = 80;
    public static final int MAX_PARAMS_PER_STEP = 40;
    public static final int MAX_NEST_DEPTH = 4;

    private final Path file;
    private final AcxToolRegistry blocks;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FileAcxLibrary(Path file, AcxToolRegistry blocks) {
        this.file = file;
        this.blocks = blocks;
    }

    /**
     * 可用 block 注册表（只读）。
     *
     * <p>2026-10-05 加：写 AC 草稿的模型需要知道<b>合法 block 名单</b>，
     * 而这份名单只有运行时知道。ACX 把它放进拒收原因回喂给模型，
     * 否则模型只能自创 block 名（实测连错三轮：{@code move} 之类）。
     * 写死一份名单在这里是错的 —— 必然与运行时漂移。
     */
    public AcxToolRegistry blocks() {
        return blocks;
    }

    static final class Version {
        AcxDefinition definition;
        long publishedAt;
        String note;
        AcxLifecycleState status = AcxLifecycleState.GENERATED;
        long statusAt;
        String statusNote;
        String rejectedReason;
    }

    static final class Entry {
        String activeVersion;
        String approvedBy;
        Long approvedAt;
        final Map<String, Version> versions = new LinkedHashMap<>();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 静态校验
    // ═══════════════════════════════════════════════════════════════════

    /** 返回问题列表，空列表 = 通过。 */
    public List<String> validate(AcxDefinition def) {
        return validate(def, Set.of());
    }

    /**
     * 静态校验（发布前），可显式给出「库里已有哪些 AC」。
     *
     * <p>子 AC 委托（{@code step.block} 写另一个 AC 的名）是合法写法，但它的样子像积木名。
     * 真机实测踩过：loader 那边按「积木 ∪ 已加载 AC」放行了 {@code subac_nesting}，
     * 版本库这边只查积木表 → 「未注册的积木」把发布挡下，<b>两边判据不一致</b>，
     * 于是内置脚本自动上线时唯独子 AC 那条上不去。给它一份名单就一致了。</p>
     */
    public List<String> validate(AcxDefinition def, Set<String> knownAcNames) {
        List<String> problems = new ArrayList<>();
        if (def.name().isBlank()) {
            problems.add("name 为空");
        }
        if (def.version().isBlank()) {
            problems.add("version 为空");
        }
        if (def.steps().isEmpty()) {
            problems.add("steps 为空");
        }
        List<AcxStep> deep = def.allStepsDeep();
        if (deep.size() > MAX_STEPS) {
            problems.add("步骤总数 " + deep.size() + " 超过上限 " + MAX_STEPS);
        }
        HashSet<String> dupCheck = new HashSet<>();
        for (AcxStep s : deep) {
            if (!dupCheck.add(s.id())) {
                problems.add("step id 重复: " + s.id());
            }
            if (s.params().size() > MAX_PARAMS_PER_STEP) {
                problems.add("step " + s.id() + " 参数数 " + s.params().size() + " 超过上限 " + MAX_PARAMS_PER_STEP);
            }
            if (s.isControl() && (s.children() == null || s.children().isEmpty())) {
                problems.add("控制块 " + s.id() + " 缺少 children");
            }
            if (!s.isControl() && !s.isGuard() && !s.isSet() && !s.isFor() && blocks != null && !blocks.contains(s.block())
                    && !knownAcNames.contains(s.block())) {
                problems.add("step " + s.id() + " 的 block " + s.block()
                        + " 既不是已注册积木也不是库里的 AC");
            }
        }
        int depth = 0;
        for (AcxStep s : def.steps()) {
            depth = Math.max(depth, depthOf(s, 1));
        }
        if (depth > MAX_NEST_DEPTH) {
            problems.add("控制块嵌套深度 " + depth + " 超过上限 " + MAX_NEST_DEPTH);
        }
        return problems;
    }

    private static int depthOf(AcxStep s, int cur) {
        int max = cur;
        if (s.children() != null) {
            for (AcxStep c : s.children()) {
                max = Math.max(max, depthOf(c, cur + 1));
            }
        }
        return max;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 发布 / 状态 / 批准 / 回滚
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 发布一个新版本，状态 {@link AcxLifecycleState#GENERATED}。<b>只写入 versions，不切 active</b>。
     *
     * @return 新版本号
     * @throws IllegalArgumentException 静态校验不过时抛出，且不动任何已有状态
     */
    public String publish(AcxDefinition def, String note) {
        return publish(def, note, Set.of());
    }

    /** 发布（可给出「同批已加载的 AC 名」：子 AC 委托需要它才能过静态校验）。 */
    public String publish(AcxDefinition def, String note, Set<String> knownAcNames) {
        List<String> problems = validate(def, knownAcNames);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("静态校验未通过: " + String.join("; ", problems));
        }
        Entry e = entries.computeIfAbsent(def.name(), k -> new Entry());
        Version v = new Version();
        v.definition = def;
        v.publishedAt = System.currentTimeMillis();
        v.note = note == null ? "" : note;
        v.status = AcxLifecycleState.GENERATED;
        v.statusAt = v.publishedAt;
        v.statusNote = "";
        v.rejectedReason = "";
        e.versions.put(def.version(), v);
        persist();
        LOG.info("[acx] 已发布 " + def.name() + " v" + def.version()
                + "（GENERATED，待验证/待批准，active 仍是 " + e.activeVersion + "）");
        return def.version();
    }

    /** 运行标记出现后把版本从 GENERATED 抬到 PENDING（等人工看质量统计）。 */
    public void markPending(String name, String version, String note) {
        Version v = requireVersion(name, version);
        if (!v.status.canTransitionTo(AcxLifecycleState.PENDING)) {
            throw new IllegalStateException(name + " v" + version + " 当前状态 " + v.status
                    + " 不能转 PENDING（被否决要先 reopen）");
        }
        v.status = AcxLifecycleState.PENDING;
        v.statusAt = System.currentTimeMillis();
        v.statusNote = note == null ? "" : note;
        persist();
        LOG.info("[acx] " + name + " v" + version + " → PENDING");
    }

    /** 人工批准并切 active。生产上线的唯一入口。 */
    public void approve(String name, String version, String approver, String note) {
        Entry e = require(name);
        Version v = requireVersion(name, version);
        if (v.status.isRejected()) {
            throw new IllegalStateException(name + " v" + version + " 已被否决，先 reopen 再批准");
        }
        v.status = AcxLifecycleState.STABLE;
        v.statusAt = System.currentTimeMillis();
        v.statusNote = note == null ? "" : note;
        e.activeVersion = version;
        e.approvedBy = approver;
        e.approvedAt = System.currentTimeMillis();
        persist();
        LOG.info("[acx] 已批准 " + name + " v" + version + "（by " + approver + "）");
    }

    /** 回滚到已是 STABLE 的版本（不需要再次批准 —— 它已经批过一次）。 */
    public void rollback(String name, String version, String reason) {
        Entry e = require(name);
        Version v = e.versions.get(version);
        if (v == null) {
            throw new IllegalArgumentException(name + " 没有版本 " + version + "，可回滚的版本: " + e.versions.keySet());
        }
        if (!v.status.isActive()) {
            throw new IllegalStateException("只能回滚到 STABLE 版本；" + name + " v" + version
                    + " 当前是 " + v.status);
        }
        String from = e.activeVersion;
        e.activeVersion = version;
        persist();
        LOG.info("[acx] 回滚 " + name + ": v" + from + " → v" + version + "（" + reason + "）");
    }

    /** 否决一个版本：保留在库里可回查，但绝不 active；若它正是 active 会被撤下。 */
    public void reject(String name, String version, String reason) {
        Entry e = require(name);
        Version v = requireVersion(name, version);
        v.status = AcxLifecycleState.REJECTED;
        v.statusAt = System.currentTimeMillis();
        v.rejectedReason = reason == null ? "" : reason;
        if (version.equals(e.activeVersion)) {
            e.activeVersion = null;
            LOG.warning("[acx] " + name + " v" + version + " 被否决，同时从 active 撤下");
        }
        persist();
        LOG.info("[acx] " + name + " v" + version + " → REJECTED（" + reason + "）");
    }

    /** 重开被否决的版本（回到 GENERATED，等作者重写/重发）。 */
    public void reopen(String name, String version) {
        Version v = requireVersion(name, version);
        if (!v.status.canTransitionTo(AcxLifecycleState.GENERATED)) {
            throw new IllegalStateException(name + " v" + version + " 当前状态 " + v.status + " 不能 reopen");
        }
        v.status = AcxLifecycleState.GENERATED;
        v.statusAt = System.currentTimeMillis();
        v.rejectedReason = "";
        persist();
        LOG.info("[acx] " + name + " v" + version + " → GENERATED（reopen）");
    }

    public AcxLifecycleState status(String name, String version) {
        return requireVersion(name, version).status;
    }

    /** 该 AC 所有版本的状态快照（版本号 → 状态）。 */
    public Map<String, AcxLifecycleState> lifecycle(String name) {
        Entry e = require(name);
        Map<String, AcxLifecycleState> out = new LinkedHashMap<>();
        for (Map.Entry<String, Version> v : e.versions.entrySet()) {
            out.put(v.getKey(), v.getValue().status);
        }
        return out;
    }

    /** 取当前生效版本。没批准过就抛「等人工批准」，不偷偷拿最新版。 */
    public AcxDefinition active(String name) {
        Entry e = require(name);
        if (e.activeVersion == null) {
            throw new IllegalStateException(name + " 已发布但未人工批准上线（待批准版本: " + e.versions.keySet() + "）");
        }
        Version v = e.versions.get(e.activeVersion);
        if (v == null || !v.status.isActive()) {
            throw new IllegalStateException(name + " 的 active 版本 " + e.activeVersion + " 状态异常，拒绝加载");
        }
        return v.definition;
    }

    /** 取任意版本（不限是否生效），供对拍 / 回溯用。 */
    public AcxDefinition version(String name, String version) {
        return requireVersion(name, version).definition;
    }

    public List<String> versions(String name) {
        return new ArrayList<>(require(name).versions.keySet());
    }

    public String activeVersion(String name) {
        return require(name).activeVersion;
    }

    public boolean isApproved(String name) {
        Entry e = entries.get(name);
        return e != null && e.activeVersion != null;
    }

    /** 该名字是否已有任何版本（未知名字返回 false，<b>不抛</b>：<b>versions(name)</b> 对未知名字是抛的，
     *  拿来判「首次见到」会炸）。 */
    public boolean hasAnyVersion(String name) {
        Entry e = entries.get(name);
        return e != null && !e.versions.isEmpty();
    }

    /** 该名字当前是否已上线（active 且状态 STABLE）。 */
    public boolean isOnline(String name) {
        Entry e = entries.get(name);
        if (e == null || e.activeVersion == null) {
            return false;
        }
        Version v = e.versions.get(e.activeVersion);
        return v != null && v.status.isActive();
    }

    public List<String> names() {
        return new ArrayList<>(entries.keySet());
    }

    private Entry require(String name) {
        Entry e = entries.get(name);
        if (e == null) {
            throw new IllegalArgumentException("库里没有 AC: " + name + "（已有: " + entries.keySet() + "）");
        }
        return e;
    }

    private Version requireVersion(String name, String version) {
        Entry e = require(name);
        Version v = e.versions.get(version);
        if (v == null) {
            throw new IllegalArgumentException(name + " 没有版本 " + version + "（已有: " + e.versions.keySet() + "）");
        }
        return v;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 落盘（tmp + ATOMIC_MOVE，与现有 FileAcVersionStore 同策略）
    // ═══════════════════════════════════════════════════════════════════

    public void persist() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> defs = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("active_version", e.getValue().activeVersion);
            em.put("approved_by", e.getValue().approvedBy);
            em.put("approved_at", e.getValue().approvedAt);
            Map<String, Object> vs = new LinkedHashMap<>();
            for (Map.Entry<String, Version> v : e.getValue().versions.entrySet()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("definition", v.getValue().definition.toMap());
                vm.put("published_at", v.getValue().publishedAt);
                vm.put("note", v.getValue().note);
                vm.put("status", v.getValue().status.name());
                vm.put("status_at", v.getValue().statusAt);
                vm.put("status_note", v.getValue().statusNote);
                vm.put("rejected_reason", v.getValue().rejectedReason);
                vs.put(v.getKey(), vm);
            }
            em.put("versions", vs);
            defs.put(e.getKey(), em);
        }
        root.put("defs", defs);

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JsonlRecordStore.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("[acx] 版本库落盘失败: " + e.getMessage());
            throw new IllegalStateException("版本库落盘失败: " + e.getMessage(), e);
        }
    }

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, Object> root = JsonlRecordStore.toMap(Files.readString(file, StandardCharsets.UTF_8));
            Object defsObj = root.get("defs");
            if (!(defsObj instanceof Map<?, ?> defs)) {
                return;
            }
            for (Map.Entry<?, ?> de : defs.entrySet()) {
                String name = String.valueOf(de.getKey());
                if (!(de.getValue() instanceof Map<?, ?> em)) {
                    continue;
                }
                Entry e = new Entry();
                e.activeVersion = em.get("active_version") == null ? null : String.valueOf(em.get("active_version"));
                e.approvedBy = em.get("approved_by") == null ? null : String.valueOf(em.get("approved_by"));
                e.approvedAt = em.get("approved_at") == null ? null : asLong(em.get("approved_at"));
                if (em.get("versions") instanceof Map<?, ?> vs) {
                    for (Map.Entry<?, ?> ve : vs.entrySet()) {
                        if (!(ve.getValue() instanceof Map<?, ?> vm)) {
                            continue;
                        }
                        String ver = String.valueOf(ve.getKey());
                        Version v = new Version();
                        v.definition = definitionFromMap(vm.get("definition"));
                        v.publishedAt = vm.get("published_at") == null ? 0 : asLong(vm.get("published_at"));
                        v.note = vm.get("note") == null ? "" : String.valueOf(vm.get("note"));
                        v.status = parseStatus(vm.get("status"), ver.equals(e.activeVersion));
                        v.statusAt = vm.get("status_at") == null ? 0 : asLong(vm.get("status_at"));
                        v.statusNote = vm.get("status_note") == null ? "" : String.valueOf(vm.get("status_note"));
                        v.rejectedReason = vm.get("rejected_reason") == null ? "" : String.valueOf(vm.get("rejected_reason"));
                        e.versions.put(ver, v);
                    }
                }
                if (!e.versions.isEmpty()) {
                    entries.put(name, e);
                }
            }
        } catch (Exception e) {
            LOG.warning("[acx] 版本库读取失败（按空库处理）: " + e.getMessage());
        }
    }

    /** 旧档无 status：active 的视为 STABLE，其余 GENERATED（不猜 PENDING/REJECTED）。 */
    private static AcxLifecycleState parseStatus(Object raw, boolean isActive) {
        if (raw == null) {
            return isActive ? AcxLifecycleState.STABLE : AcxLifecycleState.GENERATED;
        }
        try {
            return AcxLifecycleState.valueOf(String.valueOf(raw));
        } catch (Exception e) {
            return isActive ? AcxLifecycleState.STABLE : AcxLifecycleState.GENERATED;
        }
    }

    private static AcxDefinition definitionFromMap(Object o) {
        // 走 loader 的完整解析器，不在这里手工重建字段。
        // 真机踩过：手工重建只填了 7 个构造参数，preconditions / limits 直接消失 ——
        // 于是「发布 → 落盘 → 按名执行」这条路拿回来的定义丢了限额，回落成全局默认值，
        // 表现为 timeout_demo 明明写了 max_timeout_ms=300 却按 200 步熔断。
        String json = JsonlRecordStore.toJson(o);
        try {
            return AcxLoader.parseJson(json, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("版本库里的定义无法解析（存档损坏）: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AcxStep stepFromMap(Map<String, Object> s) {
        Map<String, Object> params = s.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
        List<AcxStep> children = null;
        if (s.get("children") instanceof List<?> l && !l.isEmpty()) {
            children = new ArrayList<>();
            for (Object c : l) {
                children.add(stepFromMap((Map<String, Object>) c));
            }
        }
        return new AcxStep(
                s.get("id") == null ? "" : String.valueOf(s.get("id")),
                s.get("block") == null ? "" : String.valueOf(s.get("block")),
                params,
                children);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
