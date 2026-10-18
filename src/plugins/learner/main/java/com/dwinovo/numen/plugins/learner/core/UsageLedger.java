package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产物<b>使用账本</b>：把「呈现 → 采用 → 执行 → 结果」串起来（S3，2026-10-05）。
 *
 * <p><b>为什么需要</b>：第二批把「学习者产出」接通之后，缺了另一半 ——
 * 产出被下游拿去用了吗？用成了吗？<b>不接这一段，学习就永远只在自我循环里打转</b>：
 * 复审产出 → 落箱 → 被采纳 → 然后<b>没有任何证据回到下一轮复审</b>。
 *
 * <h3>★ 本类最重要的两条规则（都是踩过才知道的）</h3>
 * <ol>
 *   <li><b>「被呈现后成功」只算候选关联，不许自动加有效性证据</b>。
 *       <p>时间上的先后不是因果。若「呈现过 + 后来成功了」就自动算有效，
 *       那账本会源源不断产出**自我印证**的垃圾证据 —— 而它看起来是数据，不是猜测。
 *       所以有效性证据只能由人<b>显式</b> {@link #claimValidity} 写，且必须给理由。
 *   <li><b>取消之后迟到的成功不许把结论翻回成功</b>。
 *       <p>这条是照着 ACX 已知缺陷 AC-B19 写的（「取消后迟到的成功回执会把这次运行翻回
 *       SUCCESS」）。同一个病会在账本里再犯一次，所以这里<b>先钉死</b>：
 *       迟到事件照样记录（不丢证据），但<b>结论不翻转</b>。
 * </ol>
 *
 * <p>落盘：{@code <configDir>/usage-ledger.jsonl}（append-only，只追加不改写）。
 */
public final class UsageLedger {

    /** 产物在链路上的阶段，<b>只能前进</b>。 */
    public enum Phase {
        /** 摆到同伴面前了（被召回/被注入提示）。 */
        PRESENTED(0),
        /** 被明确采用（人点了采纳 / 调了 approve）。 */
        ADOPTED(1),
        /** 真去执行了（跑了一个 AC / 用了一次携带器）。 */
        EXECUTED(2),
        /** 有了结果。 */
        RESULT(3);

        private final int rank;

        Phase(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        static Phase of(String s) {
            if (s == null) {
                return null;
            }
            for (Phase p : values()) {
                if (p.name().equalsIgnoreCase(s.trim())) {
                    return p;
                }
            }
            return null;
        }
    }

    public enum Outcome {
        SUCCESS, FAIL, CANCELED, UNKNOWN
    }

    /**
     * 证据等级 —— <b>三个概念刻意不共用一个布尔值</b>（DL-10，2026-10-05）。
     *
     * <p><b>为什么</b>：原先只有一个 {@code validity_claimed}，
     * 而声明入口 {@code learner_usage claim_validity} 是<b>模型可调用</b>的。
     * 那就意味着「模型可以自己说这条已经审核过」—— 一旦这样，信任链整个塌掉：
     * 看起来有人审过，实际是自证。
     *
     * <p>三个等级各自的含义与产生方式：
     * <ul>
     *   <li>{@link #MACHINE_VERIFIED} —— 有<b>非自报</b>的执行证据（run_id 可回溯到
     *       监测台）。由 {@link AcxExecutionReflector} 产生，<b>不需要任何人声明</b>。</li>
     *   <li>{@link #HUMAN_REVIEWED} —— <b>人</b>看过并签了字。
     *       ★ <b>当前没有任何工具能产生它</b>：claim_validity 由模型调用，
     *       所以它的产物只记为 {@link #MODEL_ASSERTED}。</li>
     *   <li>{@link #PRODUCTION_VERIFIED} —— 真实世界长期有效。
     *       ★ <b>当前没有任何数据源能产生它</b>（需要长期实机统计），保留占位以免
     *       以后有人拿 MACHINE_VERIFIED 冒充它。</li>
     * </ul>
     */
    public enum EvidenceLevel {
        /** 什么都没有。 */
        NONE,
        /** 有非自报的执行证据（run_id 可回溯）。 */
        MACHINE_VERIFIED,
        /**
         * ★ 模型声称「已审核」。<b>它不是人审</b>，只说明有个模型说过这句话。
         * 保留这个等级是为了让「自证」这件事<b>可见</b>，而不是被抹掉。
         */
        MODEL_ASSERTED,
        /** 人审过（当前无产生入口）。 */
        HUMAN_REVIEWED,
        /** 生产长期验证过（当前无数据源）。 */
        PRODUCTION_VERIFIED;

        /**
         * 这个等级能否当作「人审过」用。
         *
         * <p>★ 只有 {@link #HUMAN_REVIEWED} 和 {@link #PRODUCTION_VERIFIED} 为 true。
         * {@link #MODEL_ASSERTED} 明确为 false —— 这就是防止自证塌陷的那道闸。
         */
        public boolean countsAsHumanReview() {
            return this == HUMAN_REVIEWED || this == PRODUCTION_VERIFIED;
        }
    }

    /** 一条账。 */
    public record Entry(String artifactId, String kind, String name, Phase phase, Outcome outcome,
                         String detail, String source, String at, boolean validityClaimed,
                         String companionId, EvidenceLevel evidence) {

        /**
         * 便捷判定：这条是否<b>可以</b>算作人工审核过。
         *
         * <p>委托给 {@link EvidenceLevel#countsAsHumanReview()}，避免各处自己写
         * {@code validityClaimed == true} —— 那正是塌陷的入口。
         */
        public boolean humanReviewed() {
            return evidence != null && evidence.countsAsHumanReview();
        }
    }

    /** 阶段只能前进：早于已记录阶段的 arriving 事件算「迟到」。 */
    public static final class LateArrival extends RuntimeException {
        public final Phase seen;
        public final Phase arriving;

        LateArrival(Phase seen, Phase arriving) {
            super("迟到事件：已记到 " + seen + "，又收到更早的 " + arriving
                    + "（阶段只前进，不许回退）");
            this.seen = seen;
            this.arriving = arriving;
        }
    }

    private final Path file;

    public UsageLedger(Path configDir) {
        this.file = configDir.resolve("usage-ledger.jsonl");
    }

    public Path file() {
        return file;
    }

    /**
     * 追加一条。
     *
     * <p><b>⚠️ 慎用无同伴的重载</b>：那会写出 {@code companionId=""} 的记录，
     * 之后按同伴过滤时它<b>对任何同伴都不可见</b> —— 症状是「明明记了，AI 却说没记过」。
     * 生产路径请用带 {@code companionId} 的重载（{@link LearnerUsageTool} 已接上）。
     *
     * @throws LateArrival       阶段回退（迟到事件）
     * @throws IllegalArgumentException 阶段/结果自相矛盾（如 RESULT 却带 UNKNOWN 的成功）
     */
    public synchronized Entry append(String artifactId, String kind, String name,
                                     Phase phase, Outcome outcome, String detail, String source) {
        return append(artifactId, kind, name, phase, outcome, detail, source, "");
    }

    /**
     * 追加一条，并记下<b>哪个同伴</b>的（隔离靠它，见 {@link #promptBlock(UsageLedger, java.util.UUID)}）。
     */
    public synchronized Entry append(String artifactId, String kind, String name,
                                     Phase phase, Outcome outcome, String detail, String source,
                                     String companionId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifact_id 不能为空：账本条目必须能追到具体产物");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase 不能为空");
        }
        Outcome o = outcome == null ? Outcome.UNKNOWN : outcome;
        if (phase != Phase.RESULT && o != Outcome.UNKNOWN) {
            throw new IllegalArgumentException("只有 RESULT 阶段才带结果，phase=" + phase
                    + " 却带了 outcome=" + o);
        }
        Entry latest = latestOf(artifactId);
        if (latest != null && phase.rank() < latest.phase().rank()) {
            throw new LateArrival(latest.phase(), phase);
        }
        // ★ 规则 2：取消后迟到的成功 —— 记录，但**不改结论**
        Outcome recorded = o;
        boolean lateAfterCancel = false;
        if (latest != null && latest.outcome() == Outcome.CANCELED && o == Outcome.SUCCESS) {
            recorded = Outcome.CANCELED;
            lateAfterCancel = true;
        }
        Entry e = new Entry(artifactId, nz(kind), nz(name), phase, recorded,
                nz(detail), nz(source), java.time.Instant.now().toString(), false, nz(companionId),
                // ★ 有 run_id 可回溯 = 机器可验证；这不需要任何人声明
                source != null && source.startsWith(AcxExecutionReflector.SOURCE_PREFIX)
                        ? EvidenceLevel.MACHINE_VERIFIED : EvidenceLevel.NONE);
        writeLine(e, lateAfterCancel
                ? "迟到成功：已按取消结论记账，不翻转（AC-B19 同款病的预防）"
                : "");
        return e;
    }

    /**
     * ★ 唯一的有效性证据入口：<b>必须显式调用 + 必须给理由</b>。
     *
     * <p>不存在「自动判定有效性」的路径 —— 这是本类的核心约定。
     */
    public synchronized void claimValidity(String artifactId, String reason, String byWho) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("声明有效性必须给理由：没理由的有效性证据无法追责");
        }
        Entry latest = latestOf(artifactId);
        if (latest == null) {
            throw new IllegalArgumentException("账本里没有这个产物: " + artifactId);
        }
        if (latest.outcome() != Outcome.SUCCESS) {
            throw new IllegalStateException("只有 SUCCESS 的产物才能声明有效性，当前是 "
                    + latest.outcome() + "（不许拿失败/取消/未知当有效证据）");
        }
        Entry marked = new Entry(latest.artifactId(), latest.kind(), latest.name(), latest.phase(),
                latest.outcome(), latest.detail(), latest.source(), java.time.Instant.now().toString(), true,
                latest.companionId(),
                // ★ 关键：claim_validity 由**模型**调用，所以只记 MODEL_ASSERTED，
                //   绝不记 HUMAN_REVIEWED —— 那道闸就是防「模型自己给自己签审核通过」
                latest.evidence() == EvidenceLevel.MACHINE_VERIFIED
                        ? EvidenceLevel.MACHINE_VERIFIED : EvidenceLevel.MODEL_ASSERTED);
        writeLine(marked, "显式声明有效性：" + reason.trim() + "（by " + nz(byWho) + "）");
    }

    /** 某个产物最新的一条；没有则 null。 */
    public synchronized Entry latestOf(String artifactId) {
        Entry best = null;
        for (Entry e : all()) {
            if (!e.artifactId().equals(artifactId)) {
                continue;
            }
            if (best == null || e.phase().rank() >= best.phase().rank()) {
                best = e;
            }
        }
        return best;
    }

    /** 全账（按写入顺序）。 */
    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    // 坏行跳过但**不删**（append-only 账本，坏了要留给人看）
                    continue;
                }
                Phase p = Phase.of(str(o, "phase"));
                if (p == null) {
                    continue;
                }
                out.add(new Entry(str(o, "artifact_id"), str(o, "kind"), str(o, "name"), p,
                        outcomeOf(str(o, "outcome")), str(o, "detail"), str(o, "source"),
                        str(o, "at"), o.has("validity_claim") && o.get("validity_claim").getAsBoolean(),
                        str(o, "companion_id"), parseEvidence(o, source0(o))));
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** 每个产物的最终结论（最新阶段的那条）。 */
    public synchronized Map<String, Entry> conclusions() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : all()) {
            Entry cur = out.get(e.artifactId());
            if (cur == null || e.phase().rank() >= cur.phase().rank()) {
                out.put(e.artifactId(), e);
            }
        }
        return out;
    }

    /**
     * ★ 候选关联清单：呈现过且后来成功了，但<b>还没人声明有效性</b>。
     *
     * <p>这是给人/AI 去<b>审</b>的队列，<b>不是</b>证据本身。
     */
    public synchronized List<Entry> associationCandidates() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : conclusions().values()) {
            if (e.outcome() == Outcome.SUCCESS && !e.validityClaimed()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 摆进复审 prompt 的一段：上轮产物的使用结果。
     *
     * <p>措辞是刻意的：只说「用过、成没成」，<b>不说「所以有效」</b>。
     */
    public String promptBlock() {
        return promptBlock(this, null);
    }

    /**
     * 摆进复审 prompt 的一段：<b>本同伴</b>产物的使用结果。
     *
     * <p>措辞是刻意的：只说「用过、成没成」，<b>不说「所以有效」</b>。
     *
     * @param companionId 只看这个同伴的；<b>null ⇒ 一条都不给</b>
     *                    （不退化成「把全部都喂出去」—— 账本是共享文件，
     *                    那样等于把别的同伴的成败当成自己的经验）
     */
    public static String promptBlock(UsageLedger ledger, java.util.UUID companionId) {
        if (ledger == null || companionId == null) {
            return "（没有本同伴的使用记录可参考。）\n";
        }
        String want = companionId.toString();
        Map<String, Entry> cs = new LinkedHashMap<>();
        for (Entry e : ledger.conclusions().values()) {
            if (want.equals(e.companionId())) {
                cs.put(e.artifactId(), e);
            }
        }
        if (cs.isEmpty()) {
            return "（本同伴还没有产物使用记录。）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== 你自己产物的使用情况（事实，不是结论）==\n");
        int n = 0;
        for (Entry e : cs.values()) {
            if (n++ >= 8) {
                sb.append("…（还有 ").append(cs.size() - 8).append(" 条）\n");
                break;
            }
            sb.append("  - [").append(e.kind()).append(' ').append(e.name()).append("] ");
            sb.append("阶段=").append(e.phase()).append(" 结果=").append(e.outcome());
            if (e.validityClaimed()) {
                sb.append("（已显式声明有效性）");
            } else if (e.outcome() == Outcome.SUCCESS) {
                sb.append("（★只是候选关联，**不等于有效**；要算证据得有人显式声明）");
            }
            if (!e.detail().isBlank()) {
                sb.append(" 说明=").append(clip(e.detail()));
            }
            sb.append('\n');
        }
        sb.append("★ 被用过且成功**本身不构成有效性证据** —— 时间上的先后不是因果。\n");
        return sb.toString();
    }

    private void writeLine(Entry e, String extra) {
        JsonObject o = new JsonObject();
        o.addProperty("artifact_id", e.artifactId());
        o.addProperty("kind", e.kind());
        o.addProperty("name", e.name());
        o.addProperty("phase", e.phase().name());
        o.addProperty("outcome", e.outcome().name());
        o.addProperty("detail", e.detail());
        o.addProperty("source", e.source());
        o.addProperty("at", e.at());
        o.addProperty("validity_claim", e.validityClaimed());
        // ★ 证据等级单独落盘（布尔是历史字段，不能靠它推导等级）
        o.addProperty("evidence", e.evidence() == null ? EvidenceLevel.NONE.name() : e.evidence().name());
        // ★ 同伴 id 必须落盘：隔离全靠它（共享账本文件里混着所有同伴的记录）
        o.addProperty("companion_id", nz(e.companionId()));
        if (extra != null && !extra.isBlank()) {
            o.addProperty("note", extra);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // 变量名不能与上面的 Entry e 重复（catch 参数遮蔽方法参数 = 编译错）
            throw new IllegalStateException("写使用账本失败: " + file + " -> " + ex, ex);
        }
    }

    private static Outcome outcomeOf(String s) {
        for (Outcome o : Outcome.values()) {
            if (o.name().equalsIgnoreCase(s == null ? "" : s.trim())) {
                return o;
            }
        }
        return Outcome.UNKNOWN;
    }

    /**
 * 按 <b>memoId</b> 反查该判定产出物的使用结果（B1 的另一半）。
 *
 * <p><b>为什么这是关键的一步</b>：两侧键空间本来不同源
 * （事实侧 {@code stageKey} vs 使用侧 {@code artifact_id}），
 * 而 {@code ArtifactOutbox} 的 {@code artifact_id = kind + 同伴 + memoId + 摘要}
 * <b>本身含 memoId</b> ⇒ 从 artifact 反推 memo 就能把使用结果挂回具体判定，
 * 于是「判定 → 产物 → 执行结果」有了共同键。
 *
 * <p><b>只给事实</b>：每项只含 kind/name/phase/outcome/artifact_id/run 计数，
 * <b>不含有效性判断</b>。
 *
 * @param ledger   账本
 * @param memoId   要查的判定对应的 memoId
 * @param kinds    只查这几类产物
 * @return 没查到时返回空列表（<b>调用方要区分「没产物」与「查不到账本」</b>）
 */
    public static List<Map<String, Object>> usageByMemo(UsageLedger ledger, String memoId,
                                                        java.util.Set<ArtifactOutbox.Kind> kinds) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ledger == null || memoId == null || memoId.isBlank() || kinds == null || kinds.isEmpty()) {
            return out;
        }
        // artifact_id 形如 <kind>-<companion>-<memoId>-<hash>：
        // memoId 前面隔着同伴 UUID，所以判据是「以 kind 开头」+「含 -<memoId>- 片段」。
        // 用带横线的片段而不是裸 memoId ⇒ m-1 不会命中 m-10 的产物（裸包含会串）。
        for (Map.Entry<String, Entry> e : ledger.conclusions().entrySet()) {
            String artifactId = e.getKey();
            String needle = "-" + memoId + "-";
            for (ArtifactOutbox.Kind k : kinds) {
                if (!artifactId.startsWith(k.wire() + "-") || !artifactId.contains(needle)) {
                    continue;
                }
                Entry v = e.getValue();
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("artifact_id", artifactId);
                one.put("kind", k.wire());
                one.put("name", v.name());
                one.put("phase", v.phase().name());
                one.put("outcome", v.outcome().name());
                one.put("validity_claimed", v.validityClaimed());
                one.put("evidence", v.evidence() == null ? EvidenceLevel.NONE.name() : v.evidence().name());
                one.put("counts_as_human_review",
                        v.evidence() != null && v.evidence().countsAsHumanReview());
                if (v.companionId() != null && !v.companionId().isBlank()) {
                    one.put("companion_id", v.companionId());
                }
                out.add(one);
                break;
            }
        }
        return out;
    }

    /** 某个产物在账本里出现过几个不同来源的执行（粗略的「跑过几次」）。 */
    public int runCountOf(String artifactId) {
        int n = 0;
        for (Entry e : all()) {
            if (artifactId.equals(e.artifactId()) && e.source() != null
                    && e.source().startsWith(AcxExecutionReflector.SOURCE_PREFIX)) {
                n++;
            }
        }
        return n;
    }

        /**
     * 从账本行还原证据等级。
     *
     * <p>★ 兼容历史行：老行只有 {@code validity_claim} 布尔、没有 {@code evidence}。
     * 那种行按「来源是否有 run_id」判定：有 ⇒ 机器可验证；
     * 只有布尔 ⇒ {@link EvidenceLevel#MODEL_ASSERTED}
     *（<b>刻意不还原成 HUMAN_REVIEWED</b> —— 那正是塌陷的入口）。
     */
    private static EvidenceLevel parseEvidence(JsonObject o, String source) {
        JsonElement e = o.get("evidence");
        if (e != null && e.isJsonPrimitive()) {
            for (EvidenceLevel v : EvidenceLevel.values()) {
                if (v.name().equalsIgnoreCase(e.getAsString())) {
                    return v;
                }
            }
        }
        if (source != null && source.startsWith(AcxExecutionReflector.SOURCE_PREFIX)) {
            return EvidenceLevel.MACHINE_VERIFIED;
        }
        boolean claimed = o.has("validity_claim") && o.get("validity_claim").getAsBoolean();
        return claimed ? EvidenceLevel.MODEL_ASSERTED : EvidenceLevel.NONE;
    }

    private static String source0(JsonObject o) {
        return str(o, "source");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s) {
        String t = nz(s).trim().replaceAll("\\s+", " ");
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** 给工具回执用的小地图。 */
    public Map<String, Object> toMap(List<Entry> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rs = new ArrayList<>();
        for (Entry e : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("artifact_id", e.artifactId());
            one.put("kind", e.kind());
            one.put("name", e.name());
            one.put("phase", e.phase().name());
            one.put("outcome", e.outcome().name());
            one.put("validity_claimed", e.validityClaimed());
            one.put("evidence", e.evidence() == null ? EvidenceLevel.NONE.name() : e.evidence().name());
            one.put("counts_as_human_review",
                    e.evidence() != null && e.evidence().countsAsHumanReview());
            if (!e.detail().isBlank()) {
                one.put("detail", e.detail());
            }
            rs.add(one);
        }
        m.put("rows", rs);
        return m;
    }

    /** 供诊断：账本文件是否可读（读不到要明说，不许假装空账本）。 */
    public boolean readable() {
        return !Files.exists(file) || Files.isReadable(file);
    }

    /** 空账本判定（读不到时返回 true 会伪装成「没记录」，所以单独给）。 */
    public boolean exists() {
        return Files.isRegularFile(file);
    }
}