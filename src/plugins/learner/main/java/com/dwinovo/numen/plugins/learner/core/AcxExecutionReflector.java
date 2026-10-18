package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 ACX 的<b>真实执行结果</b>回灌进使用账本（B1，2026-10-05）。
 *
 * <p><b>为什么必须有它</b>：到此为止的使用反馈全是「AI 自己调用
 * {@code learner_usage record} 手写上去的」—— 那是<b>自报</b>，不是证据。
 * 真正的事实源在 ACX 那边：{@code config/numen/acx/records.jsonl} 每次运行都写一行，
 * 带 {@code run_id / ac_name / ac_version / status / failed_step / error_message}。
 * <b>把那里读过来，学习闭环才第一次拿到「自己产出的东西到底跑成没成」的真实信号。</b>
 *
 * <p><b>★ 两侧怎么对上（B1 的核心）</b>：
 * <ul>
 *   <li>outbox 里已采纳的产物带 {@code consumer_ref = "<name>@<version>"}；</li>
 *   <li>ACX 运行记录带 {@code ac_name + ac_version}；</li>
 *   <li>于是 {@code consumer_ref} 就是<b>共同键</b> —— 事实侧（stageKey）与使用侧
 *       （artifact_id）本就不同源，但<b>产物 ↔ 运行</b>这一段是真能 join 的。</li>
 * </ul>
 * 只回流<b>能从 outbox 追到产物</b>的运行；别人的 AC 运行不进学习者账本
 * （那不��它学到的）。
 *
 * <p><b>★ 依赖策略</b>：只按<b>文件</b>读 ACX 的记录（跨插件 import 被
 * {@code numen-plugin.gradle} 封死），与投递箱同一套做法。
 *
 * <p><b>★ 幂等</b>：每条账的 {@code source} 写成 {@code acx-run:<run_id>}，
 * 反射前先看这个 run_id 有没有入过账 ⇒ 重复反射不会把账本灌成流水账。
 *
 * <p><b>★ 不把「跑过」当「有效」</b>：这里只记事实（跑成/跑挂），
 * 有效性仍然只能由 {@link UsageLedger#claimValidity} 显式声明。
 */
public final class AcxExecutionReflector {

    /** 单次反射最多处理多少条运行记录（防 records.jsonl 变很大时一次读太多）。 */
    public static final int MAX_SCAN = 2000;

    /** 账本 source 前缀：用来做幂等去重与人工追溯。 */
    public static final String SOURCE_PREFIX = "acx-run:";

    /** 一次运行在 ACX 侧的状态 → 我们的阶段/结果。 */
    public record Run(String runId, String acName, String status, String failedStep,
                      String errorMessage) {
    }

    /** 一次反射的结果（供回执/监测台用）。 */
    public record Report(int runsSeen, int matched, int reflected, int alreadyKnown,
                         int skippedUnmatched, int skippedNotFinal, int ambiguous, List<String> notes) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runs_seen", runsSeen);
            m.put("matched", matched);
            m.put("reflected", reflected);
            m.put("already_known", alreadyKnown);
            m.put("skipped_unmatched", skippedUnmatched);
            m.put("skipped_not_final", skippedNotFinal);
            m.put("ambiguous", ambiguous);
            m.put("notes", notes);
            m.put("note", "★ 这里只回流**事实**（跑成/跑挂）；有效性仍要人显式声明");
            return m;
        }
    }

    private final ArtifactOutbox outbox;
    private final UsageLedger ledger;

    public AcxExecutionReflector(ArtifactOutbox outbox, UsageLedger ledger) {
        this.outbox = outbox;
        this.ledger = ledger;
    }

    /**
     * 反射一次。
     *
     * @param monitorAcxJsonl <b>监测台</b>的 {@code monitor/acx.jsonl}（活的真源，见 {@link #readRuns}）
     * @param readable         投递箱是否读得到
     */
    public Report reflect(Path monitorAcxJsonl, boolean readable) {
        List<String> notes = new ArrayList<>();
        if (!readable || outbox == null || ledger == null) {
            notes.add("投递箱或账本不可用，本次没有反射任何运行（不是「没有运行」）");
            return new Report(0, 0, 0, 0, 0, 0, 0, List.copyOf(notes));
        }
        if (monitorAcxJsonl == null || !Files.isRegularFile(monitorAcxJsonl)) {
            notes.add("监测台 acx.jsonl 不存在 ⇒ 无法判断有没有运行过（不是「没有运行」）");
            return new Report(0, 0, 0, 0, 0, 0, 0, List.copyOf(notes));
        }

        // ★ 2026-10-06：一次扫描建索引（原来这里只判空、下面每条 run 都重扫一遍投递箱）。
        List<Adopted> adopted = adoptedAc();
        if (adopted.isEmpty()) {
            notes.add("投递箱里没有已采纳的 AC 产物 ⇒ 无从把运行归属到自己的产物"
                    + "（这不是「没有运行」）");
        }
        // name → 全部候选 artifactId；artifact_id → 产物来源同伴（执行者可能不是它）。
        Map<String, List<String>> idsByName = new LinkedHashMap<>();
        Map<String, String> companionById = new LinkedHashMap<>();
        for (Adopted a : adopted) {
            idsByName.computeIfAbsent(a.name(), k -> new ArrayList<>()).add(a.artifactId());
            companionById.putIfAbsent(a.artifactId(), a.companionId());
        }
        Set<String> knownSources = knownRunSources();

        List<Run> runs = readRuns(monitorAcxJsonl);
        int matched = 0;
        int reflected = 0;
        int already = 0;
        int unmatched = 0;
        int notFinal = 0;
        int ambiguous = 0;
        for (Run r : runs) {
            // 监测台的 RUN_FINISHED **不带 ac_version**（实机核实），只有 ac_name。
            // ⇒ 只能按名字归属。这里<b>刻意不猜版本</b>：同名命中多个已采纳产物时
            // 记成「歧义」并跳过 —— 猜错一次就会把别人的成败记到自己头上。
            //
            // ★ 2026-10-06：`idsByName` 用**一次扫描**建，且同名下面**保留全部**产物。
            //   原来按 `consumer_ref` 折成单值 Map ⇒ 同一个 ref 的后一个产物覆盖前一个 ⇒
            //   这里永远只拿到 1 个候选 ⇒ 下面那段歧义守卫**永远进不去**（见 adoptedAc 的注释）。
            List<String> candidates = idsByName.getOrDefault(r.acName(), List.of());
            if (candidates.isEmpty()) {
                unmatched++;
                continue;
            }
            if (candidates.size() > 1) {
                ambiguous++;
                notes.add("run " + r.runId() + " 对应脚本 " + r.acName() + " 有 "
                        + candidates.size() + " 个已采纳产物，无法判定是哪一个"
                        + "（监测台不带版本号）—— 已跳过，不猜");
                continue;
            }
            String artifactId = candidates.get(0);
            matched++;
            String source = SOURCE_PREFIX + r.runId();
            if (knownSources.contains(source)) {
                already++;
                continue;
            }
            UsageLedger.Phase phase;
            UsageLedger.Outcome outcome;
            switch (r.status() == null ? "" : r.status().toUpperCase(java.util.Locale.ROOT)) {
                case "SUCCESS", "COMPLETED" -> {
                    phase = UsageLedger.Phase.RESULT;
                    outcome = UsageLedger.Outcome.SUCCESS;
                }
                case "FAIL", "FAILED" -> {
                    phase = UsageLedger.Phase.RESULT;
                    outcome = UsageLedger.Outcome.FAIL;
                }
                case "CANCELED", "CANCELLED", "ABORTED" -> {
                    phase = UsageLedger.Phase.RESULT;
                    outcome = UsageLedger.Outcome.CANCELED;
                }
                case "PAUSED", "RUNNING", "PENDING", "STEP_FAILED", "STEP_OK" -> {
                    // ★ 进行中/暂停**不记 RESULT**：半截结论会被当成最终结果，
                    //   而账本是 append-only，事后没法收回 —— 宁可只记 EXECUTED。
                    phase = UsageLedger.Phase.EXECUTED;
                    outcome = UsageLedger.Outcome.UNKNOWN;
                    notFinal++;
                }
                default -> {
                    phase = UsageLedger.Phase.EXECUTED;
                    outcome = UsageLedger.Outcome.UNKNOWN;
                    notFinal++;
                    notes.add("run " + r.runId() + " 的状态看不懂(" + r.status()
                            + ")，只记「跑过」不记结果");
                }
            }
            String detail = buildDetail(r);
            ledger.append(artifactId, "AC_SCRIPT", r.acName(), phase, outcome, detail, source,
                    companionById.getOrDefault(artifactId, ""));
            knownSources.add(source);
            reflected++;
        }
        return new Report(runs.size(), matched, reflected, already, unmatched, notFinal, ambiguous,
                List.copyOf(notes));
    }

    /**
     * 一条已采纳的 AC 产物（<b>一次扫描</b>出来的事实，供建索引）。
     *
     * @param artifactId 产物 id（账本主键）
     * @param name       脚本名（从 {@code consumer_ref = "<name>@<version>"} 里取）
     * @param companionId 产物<b>来源</b>同伴；执行它的同伴不一定相同
     */
    private record Adopted(String artifactId, String name, String companionId) {
    }

    /**
     * 一次扫描读出所有已采纳的 AC 产物。
     *
     * <p><b>★ 为什么必须保留「同 ref 的全部产物」</b>（2026-10-06 修，P0）：
     * 原实现建的是 {@code Map<consumer_ref, artifact_id>}，第 196 行
     * {@code out.put(ref, id)} —— <b>同一个 ref 的后一个产物会覆盖前一个</b>。
     * 于是同一个脚本名被采纳两次时，按名字查只得到 <b>1</b> 个候选，
     * {@code candidates.size() > 1} 的<b>歧义守卫永远进不去</b> ⇒
     * <b>别人的 AC 运行成败被静默记到自己账本上</b>，而报告一切正常。
     * （最坏的一类：守卫存在、还有一条测试，而那条测试测的恰好是<b>能工作的那一种</b>。）
     *
     * <p>触发路径三步在代码里都现成：{@code AcxLoader} 对缺失的 {@code version}
     * 一律落到 {@code "1"}（不报错、不递增）⇒ 两次同名草稿拿到同一个
     * {@code consumer_ref = "X@1"} ⇒ {@code FileAcxLibrary.publish} 的
     * {@code versions.put(def.version(), v)} 没有重载保护。
     *
     * <p>顺带去掉「每条 run 重扫一遍投递箱」（原 {@code artifactIdsByName} 每次调用
     * 都全量 {@code outbox.list()}，2000 条 run 就是 2000 次目录遍历）。
     */
    private List<Adopted> adoptedAc() {
        List<Adopted> out = new ArrayList<>();
        for (JsonObject o : outbox.list(ArtifactOutbox.Kind.AC_SCRIPT)) {
            if (!ArtifactOutbox.Status.ADOPTED.name().equals(str(o, "status"))) {
                continue;
            }
            String ref = str(o, "consumer_ref");
            String id = str(o, "artifact_id");
            if (ref.isBlank() || id.isBlank()) {
                continue;
            }
            out.add(new Adopted(id, nameOfRef(ref), str(o, "companion_id")));
        }
        return out;
    }

    /** {@code consumer_ref = "<name>@<version>"} → 脚本名；没有 {@code @} 时整串就是名字。 */
    private static String nameOfRef(String ref) {
        int at = ref.lastIndexOf('@');
        return at > 0 ? ref.substring(0, at) : ref;
    }

    /** 账本里已入过的 run source（幂等依据）。 */
    private Set<String> knownRunSources() {
        Set<String> out = new HashSet<>();
        for (UsageLedger.Entry e : ledger.all()) {
            if (e.source() != null && e.source().startsWith(SOURCE_PREFIX)) {
                out.add(e.source());
            }
        }
        return out;
    }

    /**
     * ★ 读<b>监测台</b>的 acx.jsonl，而不是 {@code acx/records.jsonl}（2026-10-05 实机纠正）。
     *
     * <p><b>实测事实</b>：实机跑完一个 AC（run_id {@code 4b3dbf7d}，SUCCESS）后，
     * {@code config/numen/acx/records.jsonl} <b>仍是 101 行、没有这条</b> ——
     * 那个文件<b>不追加新运行</b>（是历史存量/会话级存储）。
     * 而 {@code config/numen/monitor/acx.jsonl} 里有：
     * <pre>
     * {"kind":"STEP_SUCCEEDED","run_id":"4b3dbf7d","ac_name":"low_hp_flee_before_drink",...}
     * {"kind":"RUN_FINISHED","run_id":"4b3dbf7d","ac_name":"low_hp_flee_before_drink","status":"SUCCESS",...}
     * </pre>
     * ⇒ <b>监测台才是活的真源</b>。这也符合外层 AGENTS.md 那条：判功能看监测台。
     *
     * <p>只认 {@code kind=RUN_FINISHED}（终态）；{@code STEP_*} 是中间步骤，
     * 拿它当结论会把半截当终局。
     */
    public static List<Run> readRuns(Path monitorAcxJsonl) {
        List<Run> out = new ArrayList<>();
        if (monitorAcxJsonl == null || !Files.isRegularFile(monitorAcxJsonl)) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(monitorAcxJsonl, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - MAX_SCAN);
            for (String line : lines.subList(from, lines.size())) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    continue;
                }
                JsonElement d = o.get("data");
                if (d == null || !d.isJsonObject()) {
                    continue;
                }
                JsonObject data = d.getAsJsonObject();
                if (!"RUN_FINISHED".equals(str(data, "kind"))) {
                    continue;
                }
                String runId = str(data, "run_id");
                String name = str(data, "ac_name");
                if (runId.isBlank() || name.isBlank()) {
                    continue;
                }
                out.add(new Run(runId, name, str(data, "status"),
                        str(data, "failed_step"), str(data, "error_message")));
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    private static String buildDetail(Run r) {
        StringBuilder sb = new StringBuilder("ACX 运行 ").append(r.runId())
                .append(" 状态=").append(r.status());
        if (r.failedStep() != null && !r.failedStep().isBlank()) {
            sb.append(" 失败步骤=").append(r.failedStep());
        }
        if (r.errorMessage() != null && !r.errorMessage().isBlank()) {
            String e = r.errorMessage().trim().replaceAll("\\s+", " ");
            sb.append(" 错误=").append(e.length() > 160 ? e.substring(0, 160) + "…" : e);
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}