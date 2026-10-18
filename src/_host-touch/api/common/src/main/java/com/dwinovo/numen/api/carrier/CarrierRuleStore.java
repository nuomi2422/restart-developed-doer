package com.dwinovo.numen.api.carrier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 携带器规则的<b>持久化 + 审批</b>存储（2026-10-05）。
 *
 * <p><b>为什么需要它</b>：此前 {@link CarrierRules#DEFAULT} 是<b>写死的静态列表</b>，
 * 生产上两个调用点都传它：
 * <ul>
 *   <li>{@code Memo.assessCarrier()} —— 复审时的 what-if</li>
 *   <li>{@code RddCarryHint} —— 真正的运行时携带提示</li>
 * </ul>
 * ⇒ 学习者产出的携带器草稿<b>没有任何地方能装下它</b>，写出来就等于沉底。
 * 本类给出那条缺失的落点：<b>草稿 → 候选（待审批）→ 人工/工具显式批准 → 进生效链</b>。
 *
 * <p><b>★ 红线：绝不自动生效</b>。本类<b>不提供</b>「按数量自动批准」之类的入口 ——
 * 携带器会决定同伴在危急时刻带什么，自动化批准 = 让学习者自己给自己授权。
 * 批准只能由<b>显式的一次调用</b>产生，且回执里带批准人给的理由。
 *
 * <p><b>落盘位置</b>：{@code <configDir>/carriers/}
 * <ul>
 *   <li>{@code candidates.json} —— 待审批候选（覆盖写，条数少）</li>
 *   <li>{@code approved.jsonl} —— 已批准规则，<b>只追加</b>（append-only）</li>
 * </ul>
 * append-only 是有意的：批准过的规则<b>不该被后来的重写悄悄抹掉</b>，
 * 要撤销就追加一条同名同 when 的停用记录，或者人工编辑文件（能被 diff 看见）。
 *
 * <p><b>为什么放在 api 而不是 learner</b>：{@link CarrierChain} 在 api，
 * 而 {@code numen-plugin.gradle} 刻意封死跨插件 import。消费者（rdd 插件的
 * {@code RddCarryHint}）必须能 import 它 ⇒ 只能在 api。
 */
public final class CarrierRuleStore {

    /** 生效链 = 内置 DEFAULT + 已批准规则。 */
    public static volatile List<CarrierChain.Rule> OVERRIDE = null;

    private static volatile Path configDir;

    private CarrierRuleStore() {
    }

    /**
     * 装上配置目录并<b>立刻</b>把生效链刷成 DEFAULT + 已批准。
     *
     * <p>插件 setup 时调一次。装之前 {@link #effective()} 返回 {@link CarrierRules#DEFAULT}，
     * 所以<b>即使忘了装也只是「没有额外规则」，不是崩溃</b> —— 但那样草稿就永远不生效，
     * 所以 {@link #effective()} 在未安装时会返回带警告标记的链（见下）。
     */
    public static synchronized void install(Path dir) {
        configDir = dir;
        reload();
    }

    /** 重读已批准规则（批准/拒收之后调；不必重启游戏就能生效）。 */
    public static synchronized void reload() {
        // ★ 2026-10-06：改用 loadApprovedDetailed 并把诊断留下来 ——
        //   原来坏行被静默跳过，而 approve() 的文案还教用户「要改就手工编辑这个文件」，
        //   于是手改打错一个词 ⇒ 规则静默消失、界面仍显示「已批准」。
        Loaded loaded = loadApprovedDetailed(configDir);
        lastLoad = new LoadReport(loaded.lines(), loaded.rules().size(),
                loaded.broken(), loaded.reasons());
        OVERRIDE = loaded.rules().isEmpty() ? null : append(loaded.rules());
    }

    /**
     * 生效规则链。
     *
     * <p><b>没装目录时</b>：返回 DEFAULT，并把第一条规则名后面挂一个
     * {@code [carrier-store 未安装]} 标记 —— 让「为什么新携带器不生效」在
     * {@code why} 文本里<b>看得见</b>，而不是变成查不到根因的哑故障。
     */
    public static List<CarrierChain.Rule> effective() {
        List<CarrierChain.Rule> o = OVERRIDE;
        if (o != null) {
            return o;
        }
        if (configDir == null) {
            List<CarrierChain.Rule> base = CarrierRules.DEFAULT;
            if (base.isEmpty()) {
                return base;
            }
            CarrierChain.Rule first = base.get(0);
            List<CarrierChain.Rule> marked = new ArrayList<>(base);
            marked.set(0, new CarrierChain.Rule(
                    first.name() + " [carrier-store 未安装，新携带器不会生效]",
                    first.applies(), first.carry(), first.fix()));
            return List.copyOf(marked);
        }
        return CarrierRules.DEFAULT;
    }

    private static List<CarrierChain.Rule> append(List<CarrierChain.Rule> extra) {
        // ★★ 顺序：已批准规则放在**默认链之前**，不是之后。
        //
        // 原因（2026-10-05 核查发现）：CarrierChain.evaluate 遇���某一级不成立就
        // break（短路，stoppedAt=i）。默认链前面几级（「指向谁」「装备」「血量」）
        // 在多数实况下**本来就不成立** ⇒ 若把已批准规则追加在后面，
        // 它们**永远不会被求值** ⇒ 「我批了一条携带器规则，它却从不生效」，
        // 而报告与审批界面都显示「已批准」。
        //
        // 语义后果（刻意如此）：人工批准的规则**优先于**内置默认规则。
        // 这是对的 —— 批准的动作本身就意味着「以这条为准」。
        // 若某条已批准规则不成立，仍会短路后面的（含默认链），并在 why 里说明。
        List<CarrierChain.Rule> all = new ArrayList<>(extra);
        all.addAll(CarrierRules.DEFAULT);
        return List.copyOf(all);
    }

    // ── 候选（待审批）─────────────────────────────────────────────────────────

    /** 一条待审批候选。 */
    public record Candidate(String artifactId, String name, String when, List<String> carry,
                            List<String> fix, String sourceReviewId, String sourceMemoId,
                            String submittedAt) {
    }

    /**
     * 校验一份草稿并<b>登记为候选</b>。
     *
     * @return 候选的 artifactId；失败抛 {@link IllegalArgumentException}，<b>消息里带原因</b>
     */
    public static synchronized String submitCandidate(Path dir, String artifactId, String body,
                                                      String sourceReviewId, String sourceMemoId) {
        Draft d = Draft.parse(body);
        Path f = candidatesFile(dir);
        Map<String, JsonObject> all = readCandidates(dir);
        JsonObject rec = new JsonObject();
        rec.addProperty("artifact_id", artifactId);
        rec.addProperty("name", d.name());
        rec.addProperty("when", d.when());
        rec.addProperty("carry", String.join("\n", d.carry()));
        rec.addProperty("fix", String.join("\n", d.fix()));
        rec.addProperty("source_review_id", nz(sourceReviewId));
        rec.addProperty("source_memo_id", nz(sourceMemoId));
        rec.addProperty("submitted_at", java.time.Instant.now().toString());
        rec.addProperty("status", "PENDING");
        all.put(artifactId, rec);
        writeCandidates(f, all);
        return artifactId;
    }

    /** 列出待审批候选（按 artifactId 有序）。 */
    public static synchronized List<Candidate> candidates(Path dir) {
        List<Candidate> out = new ArrayList<>();
        for (var e : readCandidates(dir).entrySet()) {
            JsonObject o = e.getValue();
            out.add(new Candidate(e.getKey(), str(o, "name"), str(o, "when"),
                    lines(o, "carry"), lines(o, "fix"), str(o, "source_review_id"),
                    str(o, "source_memo_id"), str(o, "submitted_at")));
        }
        return out;
    }

    /**
     * ★ 审批流的动作①：<b>批准</b>一条候选 → 写进 {@code approved.jsonl}。
     *
     * <p><b>批准只接受候选 id</b>，不接受现写一份规则 —— 否则「审批」会退化成
     * 「顺手写了个新规则并当场批准」，那就不是审批了。
     *
     * @param reason 批准理由；<b>必填且不许空白</b> —— 没理由的批准无法追责
     * @throws IllegalArgumentException 候选不存在 / 理由为空
     * @throws IllegalStateException 同名规则已批准过（不许静默重复）
     */
    public static synchronized CarrierChain.Rule approve(Path dir, String artifactId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("批准必须给理由：没理由的批准无法追责");
        }
        Map<String, JsonObject> all = readCandidates(dir);
        JsonObject rec = all.get(artifactId);
        if (rec == null) {
            throw new IllegalArgumentException("没有这条候选: " + artifactId
                    + "（先 learner_carrier submit，或 id 写错了）");
        }
        String name = str(rec, "name");
        String when = str(rec, "when");
        for (CarrierChain.Rule r : loadApproved(dir)) {
            if (r.name().equals(name)) {
                throw new IllegalStateException("同名规则已批准过: " + name
                        + "。要改就人工编辑 " + approvedFile(dir) + "（append-only 有意如此）");
            }
        }
        Predicate<CarrierChain.Facts> p = compile(when);
        CarrierChain.Rule rule = new CarrierChain.Rule(name, p,
                lines(rec, "carry"), lines(rec, "fix"));
        appendLine(approvedFile(dir), toJson(name, when, rule.carry(), rule.fix(), artifactId, reason));
        rec.addProperty("status", "APPROVED");
        rec.addProperty("approved_reason", reason);
        rec.addProperty("approved_at", java.time.Instant.now().toString());
        writeCandidates(candidatesFile(dir), all);
        reload();
        return rule;
    }

    /**
     * ★ 审批流的动作②：<b>拒收</b>一条候选。
     *
     * <p>拒收<b>只改状态、不删记录</b> —— 拒了就要能回答「当时为什么拒」。
     */
    public static synchronized void reject(Path dir, String artifactId, String reason) {
        Map<String, JsonObject> all = readCandidates(dir);
        JsonObject rec = all.get(artifactId);
        if (rec == null) {
            throw new IllegalArgumentException("没有这条候选: " + artifactId);
        }
        rec.addProperty("status", "REJECTED");
        rec.addProperty("reject_reason", reason == null ? "" : reason);
        rec.addProperty("rejected_at", java.time.Instant.now().toString());
        writeCandidates(candidatesFile(dir), all);
    }

    /** 候选当前状态（{@code PENDING/APPROVED/REJECTED}），不存在返回 {@code NOT_FOUND}。 */
    public static synchronized String candidateStatus(Path dir, String artifactId) {
        JsonObject rec = readCandidates(dir).get(artifactId);
        return rec == null ? "NOT_FOUND" : str(rec, "status");
    }

    // ── 已批准规则的读写 ──────────────────────────────────────────────────────

    private static Path dir(Path base) {
        return base.resolve("carriers");
    }

    private static Path candidatesFile(Path base) {
        return dir(base).resolve("candidates.json");
    }

    private static Path approvedFile(Path base) {
        return dir(base).resolve("approved.jsonl");
    }

    private static Map<String, JsonObject> readCandidates(Path base) {
        Path f = candidatesFile(base);
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                return out;
            }
            for (var en : e.getAsJsonObject().entrySet()) {
                if (en.getValue().isJsonObject()) {
                    out.put(en.getKey(), en.getValue().getAsJsonObject());
                }
            }
        } catch (IOException | RuntimeException ex) {
            // 坏文件不许拖垮查询，也不许静默删（人要看）
            return out;
        }
        return out;
    }

    private static void writeCandidates(Path f, Map<String, JsonObject> all) {
        JsonObject root = new JsonObject();
        for (var e : all.entrySet()) {
            root.add(e.getKey(), e.getValue());
        }
        try {
            Files.createDirectories(f.getParent());
            String tmp = f.resolveSibling(f.getFileName() + ".tmp").toString();
            Files.writeString(Path.of(tmp), root.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(Path.of(tmp), f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写候选表失败: " + f + " -> " + e, e);
        }
    }

    private static void appendLine(Path f, String line) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写已批准规则失败: " + f + " -> " + e, e);
        }
    }

    /**
     * 把一条已批准规则写成 approved.jsonl 的一行。
     *
     * <p><b>when 必须显式传进来</b>：谓词是编译出来的 lambda，<b>取不回原文</b> ——
     * 曾试过从 Rule 反查，结果写出空 when、批准后规则静默不生效（正是本工程最怕的那类哑故障）。
     */
private static String toJson(String name, String when, List<String> carry, List<String> fix,
                                 String artifactId, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("when", when);
        o.addProperty("carry", String.join("\n", carry));
        o.addProperty("fix", String.join("\n", fix));
        o.addProperty("from_artifact", artifactId);
        o.addProperty("reason", reason);
        o.addProperty("approved_at", java.time.Instant.now().toString());
        return o.toString();
    }

    private static List<CarrierChain.Rule> loadApproved() {
        return loadApproved(configDir);
    }

    private static List<CarrierChain.Rule> loadApproved(Path base) {
        return loadApprovedDetailed(base).rules();
    }

    /**
     * 最近一次 {@link #reload()} 的加载诊断。
     *
     * <p><b>为什么必须有它</b>（2026-10-06，DL-14/DL-15）：原实现跳过坏行时
     * <b>不计数、不留痕、不报告</b> —— 三处 {@code continue} 全是静默。
     * 而 {@link #approve} 的报错文案还<b>主动教用户</b>「要改就人工编辑 approved.jsonl」，
     * 于是「照提示手改 → 打错一个 when 词 → 规则静默从生效链消失 → 审批界面仍显示已批准」。
     *
     * <p><b>这类失败是本工程最怕的一种：不报错、不崩溃，只是安静地没有生效。</b>
     *
     * @param lines  有效数据行数（空行与 {@code #} 注释不算）
     * @param loaded 真的进了生效链的规则数
     * @param broken 被跳过的坏行数
     * @param reasons 每条坏行的原因（带行号与规则名，可直接给人看）
     */
    public record LoadReport(int lines, int loaded, int broken, List<String> reasons) {

        public LoadReport {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public String summary() {
            return broken == 0
                    ? "approved=" + loaded + " broken=0"
                    : "approved=" + loaded + " / broken=" + broken
                      + "（有已批准的规则没生效！）";
        }
    }

    private record Loaded(List<CarrierChain.Rule> rules, int lines, int broken, List<String> reasons) {
    }

    private static volatile LoadReport lastLoad = new LoadReport(0, 0, 0, List.of());

    /** 最近一次加载诊断（供 {@code learner_carrier list} 等只读出口展示）。 */
    public static LoadReport lastLoad() {
        return lastLoad;
    }

    private static Loaded loadApprovedDetailed(Path base) {
        List<CarrierChain.Rule> out = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int lines = 0;
        int broken = 0;
        Path f = approvedFile(base);
        if (!Files.isRegularFile(f)) {
            // 文件不存在 ≠ 文件是空的；这里如实返回 0/0（调用方按「还没批过」理解）。
            return new Loaded(List.of(), 0, 0, List.of());
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                lines++;
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    broken++;
                    reasons.add("第 " + lines + " 行不是 JSON 对象: " + clip(ex.getMessage()));
                    continue;
                }
                String name = str(o, "name");
                String when = str(o, "when");
                List<String> carry = lines(o, "carry");
                List<String> fix = lines(o, "fix");
                if (name.isBlank() || carry.isEmpty()) {
                    broken++;
                    reasons.add("第 " + lines + " 行缺 " + (name.isBlank() ? "name" : "carry")
                            + "（规则名: " + (name.isBlank() ? "(无)" : name) + "）");
                    continue;
                }
                Predicate<CarrierChain.Facts> p;
                try {
                    p = compile(when);
                } catch (RuntimeException ex) {
                    // ★ 这里原来是一条完全静默的 continue：用户照 approve() 的提示手改文件、
                    //   打错一个词，规则就从生效链消失，而界面仍显示「已批准」。
                    broken++;
                    reasons.add("第 " + lines + " 行 when 编译失败（" + name + "，when=\"" + when
                            + "\"）: " + clip(ex.getMessage()));
                    continue;
                }
                out.add(new CarrierChain.Rule(name, p, carry, fix));
            }
        } catch (IOException e) {
            // 读不到 ≠ 空。如实报告，别把「读不到」当「没有规则」。
            return new Loaded(List.of(), lines, broken, List.of("approved.jsonl 读取失败: " + e));
        }
        return new Loaded(List.copyOf(out), lines, broken, List.copyOf(reasons));
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }

    // ── when 表达式：编译成谓词 ─────────────────────────────────────────────

    /** 草稿形状：{@code {"name":..,"when":..,"carry":[..],"fix":[..]}}。 */
    public record Draft(String name, String when, List<String> carry, List<String> fix) {
        public static Draft parse(String body) {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("草稿正文为空：没有内容就不该声称「已产出」");
            }
            JsonObject o;
            try {
                o = JsonParser.parseString(body).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("草稿不是 JSON 对象，写不成携带器规则: " + e.getMessage());
            }
            String name = str(o, "name");
            String when = str(o, "when");
            List<String> carry = arr(o, "carry");
            List<String> fix = arr(o, "fix");
            if (name.isBlank()) {
                throw new IllegalArgumentException("草稿缺 name：没有名字的规则在 why 里不可读");
            }
            if (carry.isEmpty()) {
                throw new IllegalArgumentException("草稿 " + name + " 的 carry 为空："
                        + "一条什么都不带的规则没有意义");
            }
            if (when.isBlank()) {
                throw new IllegalArgumentException("草稿 " + name + " 缺 when："
                        + "没条件的规则会对所有情况生效 —— 那不是携带器，是全局开关");
            }
            compile(when);
            return new Draft(name, when, carry, fix);
        }
    }

    /**
     * 编译 when 表达式。
     *
     * <p>语法（<b>刻意极简</b>，能表达携带器真正需要的判断，别的都拒）：
     * <ul>
     *   <li>{@code hp<=4} / {@code hp>=10} —— 血量数值比较</li>
     *   <li>{@code band=CRITICAL} —— 血量档</li>
     *   <li>{@code hostile=1} —— 「附近有敌对生物」开关</li>
     *   <li>{@code passive=1}</li>
     *   <li>{@code has=weapon} —— 身上有某类装备（武器/护甲/手上手持）</li>
     *   <li>用 {@code ,} 连接表示 <b>AND</b></li>
     * </ul>
     *
     * <p><b>遇到不认识的词就抛错</b>，绝不忽略它 —— 忽略一个条件会让规则在
     * 「本该不生效」的时候生效，比报错危险得多。
     */
    public static Predicate<CarrierChain.Facts> compile(String when) {
        if (when == null || when.isBlank()) {
            throw new IllegalArgumentException("when 为空");
        }
        List<Predicate<CarrierChain.Facts>> parts = new ArrayList<>();
        for (String rawTok : when.split(",")) {
            String tok = rawTok.trim().toLowerCase(Locale.ROOT);
            if (tok.isEmpty()) {
                continue;
            }
            parts.add(compileOne(tok));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("when 里没有一个条件: " + when);
        }
        return f -> {
            for (Predicate<CarrierChain.Facts> p : parts) {
                if (!p.test(f)) {
                    return false;
                }
            }
            return true;
        };
    }

/**
     * 编译<b>一个</b>条件词。
     *
     * <p>★★ 修复前这段有<b>两个静默算错</b>的 bug（都是「文档写着支持、代码其实不支持」）：
     * <ol>
     *   <li>第一层把 {@code "="} 与 {@code "<=" ">=" "<" ">"} 放<b>同一个数组</b>里循环。
     *       对 {@code "hp<=4"}，{@code indexOf("=")} 会命中那个等号 —— 于是它被当成相等式
     *       「键 {@code hp<=} 等于 4」，而 {@code f.get("hp<=")} 恒为空串 ⇒
     *       <b>条件恒为假，规则永不生效</b>（静默失灵，比报错危险）。
     *       同时下面专门处理 {@code hostile=1 / passive=1 / has=weapon} 的分支<b>永远到不了</b>，
     *       它们全被上面的相等分支截走。</li>
     *   <li>比较类没有 {@code ">" 分支 ⇒ {@code hp>4} 落到
     *       {@code default -> f.hp() == n}，变成 <b>「血量正好等于 4」</b>。</li>
     * </ol>
     * 修法（按语义分三段，段与段互不吞并）：
     * <ol>
     *   <li><b>相等/开关</b>：只认<b>单个</b>等号（{@link #indexOfPlainEquals} 排除
     *       {@code <=} {@code >=}），含 {@code band=X} {@code hostile=1} {@code has=weapon}；</li>
     *   <li><b>数值比较</b>：{@code hp<=N >=N <N >N}，四个算子各有分支，
     *       <b>非 hp 键明确报错</b>，不猜「别的东西也支持比较」；</li>
     *   <li><b>开关单词</b>：{@code hostile} {@code low_hp} …，不认识就抛错。</li>
     * </ol>
     */
    private static Predicate<CarrierChain.Facts> compileOne(String tok) {
        // ── 1) 相等/开关类（先判，且只认单个等号）
        int eq = indexOfPlainEquals(tok);
        if (eq > 0) {
            String key = tok.substring(0, eq).trim();
            String val = tok.substring(eq + 1).trim();
            switch (key) {
                case "band":
                    return f -> f.hpBand().equalsIgnoreCase(val);
                case "hostile": {
                    // ★ 立即求值：把 truthy 放进 lambda 会让非法值在**编译期**不报错，
                    //   而那时规则已经批准进生效链了 —— 报错来得太晚。
                    boolean want = truthy(val);
                    return f -> want == f.hostileNearby();
                }
                case "passive": {
                    boolean want = truthy(val);
                    return f -> want == f.passiveNearby();
                }
                case "has":
                    return hasItem(val);
                default:
                    return f -> f.get(key).equalsIgnoreCase(val);
            }
        }

        // ── 2) 数值比较类：四个算子各自成支，hp>4 不再退化成 hp==4
        for (String op : new String[]{"<=", ">=", "<", ">"}) {
            int i = tok.indexOf(op);
            if (i <= 0) {
                continue;
            }
            String key = tok.substring(0, i).trim();
            String val = tok.substring(i + op.length()).trim();
            if (!isNumericKey(key)) {
                // 明确报错，不静默猜：'foo<=3' 该不该支持要人工定，不能默认按字符串比
                throw new IllegalArgumentException("数值比较只认这几个键: hp / food / food_items，收到 '"
                        + tok + "'。其他条件用相等式，如 has=weapon / band=LOW / hostile=1。");
            }
            int n;
            try {
                n = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数值比较的右边不是整数: " + tok);
            }
            return switch (op) {
                case "<=" -> f -> num(f, key) >= 0 && num(f, key) <= n;
                case ">=" -> f -> num(f, key) >= 0 && num(f, key) >= n;
                case "<" -> f -> num(f, key) >= 0 && num(f, key) < n;
                default -> f -> num(f, key) >= 0 && num(f, key) > n;
            };
        }

        // ── 3) 开关型单词
        switch (tok) {
            case "hostile":
                return CarrierChain.Facts::hostileNearby;
            case "passive":
                return CarrierChain.Facts::passiveNearby;
            case "low_hp":
                return f -> f.hasHp() && f.hp() <= 4;
            case "critical":
                return f -> "CRITICAL".equals(f.hpBand());
            case "has_weapon":
                return hasItem("weapon");
            case "has_armor":
                return hasItem("armor");
            case "unknown_hand":
                return hasItem("hand");
            default:
                throw new IllegalArgumentException("不认识的 when 条件: '" + tok + "'。"
                        + "支持: hp<=N / hp>=N / hp<N / hp>N /"
                        + " food<=N / food>=N / food<N / food>N /"
                        + " food_items<=N / food_items>=N / food_items<N / food_items>N /"
                        + " band=X / hostile=1 / passive=1 /"
                        + " has=weapon|armor|hand / hostile / passive / low_hp / critical /"
                        + " has_weapon / has_armor / unknown_hand（用 , 连接）"
                        + "｜" + FOOD_IS_SATIATION_NOT_INVENTORY);
        }
    }

    /**
     * ★ <b>food 是饱食度，不是「背包里还有多少吃的」</b>。
     *
     * <p>这一点很容易被误读并导致「缺食物提醒」这类规则被高估：
     * 快照里的 {@code food=} 来自 {@code PlayerFoodData.getFoodLevel()}（0..20 饥饿条），
     * 与背包里的食物数量<b>毫无关系</b>。真正的「背包食物数」目前<b>没有被采集</b>。
     *
     * <p>所以 {@code food<=5} 的语义是「快饿死了」，**不是**「没东西吃了」。
     * 要做「没食物」的判断，快照端先得采到背包食物数 —— 那是另一个采集缺口，不在本类范围。
     */
    public static final String FOOD_IS_SATIATION_NOT_INVENTORY =
            "food 是饱食度(饥饿条 0..20)，**不是**背包里的食物数量。"
                    + "food<=5 是「快饿死了」而不是「没东西吃了」；后者需要先采集背包食物数。";

    private static boolean isNumericKey(String key) {
        // ★ 2026-10-06 加 food_items：**饱食度（food）≠ 背包里有没有吃的（food_items）**。
        //   「饿了」要提醒吃东西、「没食物了」要提醒去找食物 —— 两件事，两个键。
        //   food_items 由快照生产端写（RddCarryHint / EnvSnapshot），读不到时是 -1（未知），
        //   而 num() 的 -1 不满足任何比较 ⇒ 不会在「读不到」时误触发。
        return "hp".equals(key) || "food".equals(key) || "food_items".equals(key);
    }

    /** 取数值事实；取不到返回 -1（**不是 0** —— 0 是个合法的饱食度/库存值）。 */
    private static int num(CarrierChain.Facts f, String key) {
        if ("hp".equals(key)) {
            return f.hasHp() ? f.hp() : -1;
        }
        // food / food_items：快照里都是纯数字（写 <key>=<int>），不是 "6/20" 那种
        String raw = f.get(key);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 找<b>单个</b>等号的位置；跳过 {@code <=} {@code >=} 里的等号。没有则返回 -1。
     *
     * <p>★ 单独立一个方法的原因：把「相等」和「比较」混在一次 {@code indexOf} 里，
     * 就会让 {@code hp<=4} 的等号被当成相等号 —— 条件恒为假、规则静默永不生效。
     */
    private static int indexOfPlainEquals(String tok) {
        for (int i = 0; i < tok.length(); i++) {
            if (tok.charAt(i) != '=') {
                continue;
            }
            boolean prevIsCompare = i > 0 && (tok.charAt(i - 1) == '<' || tok.charAt(i - 1) == '>');
            boolean nextIsCompare = i + 1 < tok.length() && tok.charAt(i + 1) == '=';
            if (!prevIsCompare && !nextIsCompare) {
                return i;
            }
        }
        return -1;
    }

    private static Predicate<CarrierChain.Facts> hasItem(String what) {
        return switch (what.toLowerCase(Locale.ROOT)) {
            case "weapon" -> CarrierChain.Facts::hasRealWeapon;
            case "armor" -> CarrierChain.Facts::hasRealArmor;
            case "hand" -> CarrierChain.Facts::handItemUnrecognized;
            default -> f -> f.get("item_" + what).equalsIgnoreCase("1");
        };
    }

    private static boolean truthy(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("1") || s.equals("true") || s.equals("yes")) {
            return true;
        }
        if (s.equals("0") || s.equals("false") || s.equals("no") || s.equals("")) {
            return false;
        }
        throw new IllegalArgumentException("开关值只认 1/0/true/false/yes/no，收到: " + v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static List<String> lines(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        String s = str(o, k);
        for (String p : s.split("\n")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> arr(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement x : a) {
                String t = x.getAsString().trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        return lines(o, k);
    }
}