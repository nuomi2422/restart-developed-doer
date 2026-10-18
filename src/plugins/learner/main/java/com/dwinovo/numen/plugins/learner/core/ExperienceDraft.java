package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 学习者七字段 → <b>经验库条目形状</b>的映射（{@code 60} 号 §四 B2：这条映射代码从来没写过）。
 *
 * <p><b>为什么是「产 JSON」而不是「产 ExperienceEntry」</b>：
 * {@code plugins/learner} <b>刻意不依赖</b> {@code experience-core}
 * （{@code plugins/learner/build.gradle} 注释：嵌套会与 ac/rdd 一样出 JPMS ResolutionException，
 * 而 {@code plugins/experience} 把 experience-core 内嵌进自己的 fat jar，
 * 两个 mod 各带一份同名类 = 运行时炸）。
 * 所以这里只保证<b>键名与 {@code ExperienceEntry.toJson()} 完全一致</b>，
 * 由 {@link #ENTRY_KEYS} 把这份契约写死；单测
 * {@code ExperienceDraftKeysBindToRealEntryTest} 会<b>直接读 experience-core 的源文件</b>
 * 提取 {@code toJson()} 的真实键做集合相等断言 —— 那边改键名，这边会红。</p>
 *
* <p><b>刻意不编造的字段</b>（B21 / Codex 审核 P1-1）：
 * <ul>
 *   <li><b>{@code type} 只在<b>学习者自己交了</b> {@code experienceType} 时才写</b>
 *       （E4）。七字段里没有「类型」这一项，所以从 {@code actions} 推断是
 *       <b>把操作建议当成分类事实</b> —— 同一条经验仅因附带建议不同就会换分类，
 *       进而换掉 {@code stableKey} 与指纹。红线不变：<b>没交就不写</b>，
 *       {@link #suggestedType()} 永远只是建议。
 *       E4 之前这里是「永远不写」；改动的理由是实测它<b>会</b>交（2026-10-02 查
 *       live 实例 {@code learner.jsonl}：3/3 七字段满且 acceptable=true，
 *       {@code evidence} 引的是真实 memo id 与数字），
 *       而 {@code ExperienceStore.learn()} 对 type==null 直接抛 ⇒ 不接住就一条都落不了库。</li>
 *   <li><b>没有 {@code tool_names}</b>。映射不出来就不放这个键，不用「无」「未知」冒充。</li>
 *   <li><b>不截断 {@code title}</b>。title 是 id 的一部分，截断会让两条本该不同的长标题
 *       撞成一条（Codex 审核 P1-4）。展示用的短版另放 {@code title_display}。</li>
 * </ul>
 *
 * <p><b>★ B5：{@code rewritten_query} 现在接进检索了（此前它是僵尸字段）</b>。
 * 断点 {@code gap-rewritten-query-unused} 记的是「解析了但零调用方」。真因不是架构上
 * 没法参与，而是<b>映射里少了一行</b> —— 写端（{@code LearnerReviewer} §7 要 LLM 给
 * 「1 到 3 个改写检索词，替代向量检索的检索键」）和读端（{@code LexicalExperienceRetriever}
 * 读 {@code triggerStrings}，+3.0/词 = 全字段最强信号）<b>两端都是通的</b>，
 * 断的只是中间。和类注释里 B2 那句「映射代码从来没写过」是同一族洞。</p>
 *
 * <p><b>⚠️ 诚实边界：它注定帮不到「触发它的那次检索」。</b>
 * {@code rewritten_query} 是<b>回顾性</b>的（学完之后才产出），而检索是<b>前瞻性</b>的
 * （规划期就要召回）。所以它只能让**下一次**同类场景命中，让不了当时那一次 ——
 * 这是「学习」本身的性质，不是缺陷。判据 A6（触发得回来）要的就是这个「下一次」，
 * 仍然欠一条实机证据（见 60 号文档 A6）。</p>
 *
 * <p><b>不改身份</b>：{@code stableKey}/{@code fingerprint} 只用 {@code (type, title)}，
 * {@code trigger_strings} 不参与 ⇒ 补检索线索<b>不会</b>让任何老条目换 id、重新去重。</p>
 */
public record ExperienceDraft(
        JsonObject entry,
        String suggestedType,
        String suggestedTypeWhy,
        String titleDisplay,
        String evidenceNote,
        String typeResolution,
        int rewrittenQueryIntoTriggers,
        int rewrittenQueryDuplicates
) {

    /**
     * {@code ExperienceEntry.toJson()} 里的键（不含 {@code id} / {@code v} —— 那是记录层自己写的）。
     *
     * <p>真源是 {@code experience-core/.../api/ExperienceEntry.java} 的 {@code toJson()}。
     * 本列表与它的一致性由 {@code ExperienceDraftKeysBindToRealEntryTest} 读源文件核对。</p>
     *
     * <p>⚠️ <b>末尾 5 个 E8 键是「声明但刻意不写」的</b>（{@code retracted} / {@code retracted_at} /
     * {@code retracted_reason} / {@code supersedes} / {@code consecutive_failures}）。
     * 声明它们是为了满足「键集合相等」这道契约门；<b>不许</b>因为「在列表里」就去
     * {@link #from} 里写它们 —— 一条刚产出的经验<b>本来就没被验证过、没被撤回过</b>，
     * 写出来等于宣称「这些事发生过」。{@code ExperienceEntry.Builder} 的默认值就是
     * 「没发生过」，草稿省掉这几个键由落盘层补默认值，语义完全一样。</p>
     *
     * <p>★ 这 5 个键是 E8（{@code aba150dc}）加进 {@code toJson()} 后补进来的。
     * 漏补的后果不是这条测试变红那么简单：<b>整个 {@code :plugins:learner:test} 从 E8 那批次起
     * 一直是红的</b>，而 E8 的验收记录写的是「全绿」—— 说明那批只跑了 experience 侧，
     * 没跑 learner 侧。</p>
     */
    public static final List<String> ENTRY_KEYS = List.of(
            "type", "title", "description", "rationale", "root_cause", "recommended_response",
            "trigger_strings", "tool_names", "tags",
            // ★ 任务 4（2026-10-03）：七字段专用槽位。
            //   之前七字段被拼成散文塞进 description/rationale/recommended_response，
            //   于是「门禁判七字段全填」与「落盘找不到七个独立位置」对不上，
            //   消费侧也只能按自然语言猜哪句是前置条件、哪句是失效条件。
            //   **键名必须与 ExperienceEntry.toJson() 逐字相同** ——
            //   ExperienceDraftKeysBindToRealEntryTest 直接读源文件做集合相等断言。
            "mechanism", "preconditions", "failure_conditions", "observable_signal",
            "derivation", "efficiency", "evidence",
            "maturity", "verified_count", "priority", "counterexamples",
            "created_at", "verified_at", "last_accessed_at",
            // 和 E8 五个键一样声明，**但刻意不写**：刚产出的经验本来就没被验证过/撤回过，
            // 写出来等于宣称这些事发生过；Builder 的默认值就是「没发生过」。
            "retracted", "retracted_at", "retracted_reason", "supersedes", "consecutive_failures");

    /** 本映射实际会写的键（其余由记录层落盘时补，或刻意不写）。 */
    public static final List<String> WRITTEN_KEYS = List.of(
            "type", "title", "description", "rationale", "root_cause", "recommended_response",
            "trigger_strings", "tags", "maturity", "priority",
            // ★ 任务 4：七字段槽位现在**真的写**（之前是拼进散文里，槽位是空的）。
            //   两者同时保留：槽位给机器按字段取，散文给人读。
            "mechanism", "preconditions", "failure_conditions", "observable_signal",
            "derivation", "efficiency", "evidence");

    /** 展示用标题上限（<b>只影响展示，不影响落盘</b>）。 */
    public static final int DISPLAY_TITLE_CHARS = 60;

    /**
     * {@code trigger_strings} 里每个检索线索的字符上限（B5）。
     *
     * <p>⚠️ <b>截断只会少召回，不会造错命中</b>，方向是安全的：线索变短 ⇒
     * {@code LexicalExperienceRetriever.containsAny} 更不容易命中 ⇒ 打分只降不升。
     * 反向的「整串出现在查询里 +2.0」在截断后会失效，那也只是丢一个加分项，
     * 不是把不相干的经验拉进来。
     *
     * <p>为什么不整句留着：{@code trigger_strings} 的契约是<b>短线索</b>，
     * LLM 偶尔会把检索词写成整句话；一条 200 字的「触发词」会盖掉其余线索，
     * 也会让人在面板上读不出这条经验到底靠什么被找到。</p>
     */
    public static final int TRIGGER_CLIP_CHARS = 40;

    /**
     * 从判定 + 备忘录产出草稿。
     *
     * @param v 判定（七字段在 {@code v.experience()}）
     * @param m 对应的备忘录（提供 stage 作为出处；可为 {@code null}）
     */
    public static ExperienceDraft from(Verdict v, Memo m) {
        if (v == null || v.experience() == null) {
            throw new IllegalArgumentException("verdict carries no structured experience");
        }
        Experience x = v.experience();
        JsonObject o = new JsonObject();

        // ★ E4：type 现在由**学习者自己交**（experienceType），不是由 actions 推断。
        //   实测依据 2026-10-02（live 实例 learner.jsonl）：七字段那条链 3/3
        //   filled=7 acceptable=true，evidence 引的是真实 memo id 与真实数字 ——
        //   它**会判断**。所以「替它判」是多余的，「把它交的接住」才是缺的。
        //   ⚠️ 红线不变：**没交就不写**（不许拿 actions 猜，见类注释 Codex P1-1）。
        String tn = x.typeName();
        if (!tn.isEmpty()) {
            o.addProperty("type", tn);
        }

        // 完整标题**不截断** —— title 是 id 的一部分，截断会让两条本该不同的长标题撞成一条。
        o.addProperty("title", x.mechanism());
        o.addProperty("title_display", clip(x.mechanism(), DISPLAY_TITLE_CHARS));

        // description 是 learn() 的必填项：现象 + 因果链，缺一就没法检索。
        // 带标签写，别把前置条件拼成「因果链的下一步」—— Codex 审核 P1-3 提到这点。
        String description = joinNonBlank("；",
                x.mechanism(),
                x.derivation().isBlank() ? "" : "应对步骤：" + x.derivation(),
                x.observableSignal().isBlank() ? "" : "判据：" + x.observableSignal());
        o.addProperty("description", description);

        // ★ evidence 在 ExperienceEntry 里没有对应槽位。不丢的办法是并进 rationale，
        //   并把原始值单列（explain().experience_evidence）供监测台核对。
        String rationale = joinNonBlank("；",
                x.efficiency().isBlank() ? "" : "收益：" + x.efficiency(),
                x.evidence().isBlank() ? "" : "证据：" + x.evidence());
        o.addProperty("rationale", rationale);

        o.addProperty("root_cause", x.mechanism());

        // 适用 / 不适用条件必须有标签，且不许被拼成一步因果链的尾巴。
        String recommended = joinNonBlank("；",
                x.preconditions().isBlank() ? "" : "适用条件：" + x.preconditions(),
                x.failureConditions().isBlank() ? "" : "禁用条件：" + x.failureConditions(),
                x.derivation().isBlank() ? "" : "应对步骤：" + x.derivation());
        o.addProperty("recommended_response", recommended);

        // ★ 任务 4（2026-10-03）：七字段**原值进槽位**，一个都不改写。
        //
        //   上面那三段散文**全部保留** —— 它们是给人读的（目录层、L1/L2 展开、监测台肉眼核对），
        //   而下面是给机器按字段取的。两条路并存，不是二选一：
        //     · 消费侧要判「这条经验的前置条件是什么」⇒ 读槽位，不用在自然语言里找分句；
        //     · 检索侧按 trigger_strings 计分 ⇒ 散文里重复出现的机制/推导步骤会重复命中，
        //       但那已经是既有行为（E4 起就是这样），本次不趁机改打分口径。
        //
        //   ★ 为什么**原值**而不是裁剪过的：`Experience.acceptable()` 判的是这七个原值非空，
        //   槽位里放裁剪版就会与门禁判据对不上（那正是本任务要修的毛病本身）。
        o.addProperty("mechanism", nz(x.mechanism()));
        o.addProperty("preconditions", nz(x.preconditions()));
        o.addProperty("failure_conditions", nz(x.failureConditions()));
        o.addProperty("observable_signal", nz(x.observableSignal()));
        o.addProperty("derivation", nz(x.derivation()));
        o.addProperty("efficiency", nz(x.efficiency()));
        o.addProperty("evidence", nz(x.evidence()));

        // trigger_strings 只承担「检索线索」，不承担「适用条件校验」（Codex 审核 P1-3）。
        // 所以这里放的是短线索，不放整句正文。
        JsonArray triggers = new JsonArray();
        Set<String> tset = new LinkedHashSet<>();
        addIfPresent(tset, x.preconditions());
        addIfPresent(tset, x.failureConditions());
        addIfPresent(tset, x.observableSignal());
        if (m != null) {
            addIfPresent(tset, m.stage());
        }
        // ★ B5：rewritten_query 此前解析了、也渲染进 reviewed 输出了，但**没有落进这里**
        //   ⇒ 检索侧永远看不到它。通道两端本来都通：
        //     写端：LearnerReviewer §7 明确要 LLM 给「1 到 3 个改写检索词（替代向量检索的检索键）」
        //     读端：LexicalExperienceRetriever 读 triggerStrings，给 +3.0/词（全字段最强信号）
        //           外加「整串出现在查询里」再 +2.0/个
        //   断的是中间这行映射。跟 B2（映射代码从来没写过）同一族洞，不是架构问题。
        //
        // ⚠️ 顺序刻意放最后：它是**回顾性**的检索键（学完之后才产出），
        //   而 preconditions / observableSignal 是**当场就成立**的条件。
        //   前者更具体、后者更稳定，冲突时让稳定的有优先解释权。
        //   LinkedHashSet 按插入序去重，所以先来的那条会挡住后来的重复值。
        int rewrittenIn = 0;
        int rewrittenDupes = 0;
        if (v.rewrittenQuery() != null) {
            for (String q : v.rewrittenQuery()) {
                // 逐个收而不是整表 addAll：clip 要按条做。
                // ⚠️ 这个 null/blank 守卫对**当前唯一的生产者是死代码** ——
                //   Verdict.parse（Verdict.java:129-132）已经把 null 与 blank 全滤掉了，
                //   到这里拿到的每条都非空。留着是因为 Verdict 是 record：
                //   任何调用方都能 new 出来绕过 parse 塞个 null 进来。
                //   写清楚它当下防的是什么，别让人以为「实测拦到过 null」。
                //   真要防的那件事是：clip(null) 返回空串，add("") 会把**空线索**塞进
                //   trigger_strings —— 检索器那边 containsAny(field, "") 恒 true，
                //   等于给每个查询白送 +3.0。空串比缺线索更坏。
                // ⚠️ 计数用 add() 的返回值（**真正新增**的条数），不是「处理了几条」——
                //   tset 是 LinkedHashSet，检索词和 preconditions 撞了就该只留一条；
                //   若按处理条数计，面板会在有重复时**高报**「接进了 2 条」而实际只新增 1 条。
                if (q == null || q.isBlank()) {
                    continue;
                }
                if (tset.add(clip(q.trim(), TRIGGER_CLIP_CHARS))) {
                    rewrittenIn++;
                } else {
                    rewrittenDupes++;
                }
            }
        }
        for (String t : tset) {
            triggers.add(t);
        }
        o.add("trigger_strings", triggers);

        JsonArray tags = new JsonArray();
        tags.add("learner");
        if (m != null && m.id() != null && !m.id().isBlank()) {
            tags.add("memo:" + m.id());   // 出处可回溯到具体那条备忘录
        }
        o.add("tags", tags);

        // maturity：一次成功未验证 —— doc 59 D5「允许进目录但必须标待验证」。
        // OBSERVED 就是「只被观察过一次」；VERIFIED 要 3 次真实成功（TBD-1）。
        o.addProperty("maturity", "OBSERVED");
        o.addProperty("priority", priorityFrom(v.confidence()));

        // ★ tool_names / counterexamples / *_at 依旧刻意不写（见类注释）
        //   type 的去处在上面：学习者交了才写，没交就是没有。
        String resolution = describeTypeResolution(x, tn);
        return new ExperienceDraft(o, suggestType(v), describeSuggestion(v),
                clip(x.mechanism(), DISPLAY_TITLE_CHARS), x.evidence(), resolution, rewrittenIn, rewrittenDupes);
    }

    /**
     * type 是怎么定下来的 —— 如实说出来，别让下游以为「反正有 type」。
     *
     * <p>三种情况：学习者交了合法的 / 交了但不是合法值 / 压根没交。
     * 第三种时草稿<b>没有 type 键</b>，送进 {@code experience_learn} 必被
     * {@code ExperienceStore.learn()} 拒（它对 type==null 直接抛）——
     * 这是<b>有意的</b>：让缺失在还有上下文的地方暴露，而不是落库后才炸。</p>
     */
    private static String describeTypeResolution(Experience x, String normalizedType) {
        if (!normalizedType.isEmpty()) {
            return "由学习者自己交的经验分类定：" + normalizedType;
        }
        return "学习者没交（或交的不是合法分类）⇒ 草稿**没有 type**，不能直接落库；"
                + x.typeProblem();
    }

    /**
     * <b>建议</b>类型，不是结论 —— 它<b>永远不进 {@code entry}</b>。
     *
     * <p>七字段整套围绕「某条件下会出什么问题 / 怎么判断 / 怎么应对」，
     * 形状上更接近失败经验；同时建议用携带器时更像一条「以后怎么办」的规则。
     * 但这只是形状线索，<b>不足以断言分类</b>，所以只进 {@code mapping.suggested_type}。</p>
     *
     * <p><b>E4 之后它更没用了</b>：学习者自己会交 {@code experienceType}，
     * 交了就以它为准。这条建议只在<b>它没交</b>时有参考价值，
     * 而那时 {@code entry} 本来就没有 type ⇒ 它进不了库，只给人看。</p>
     */
    private static String suggestType(Verdict v) {
        if (v.actions() != null && v.actions().contains(Verdict.Action.USE_CARRIER)) {
            return "POLICY";
        }
        return "FAILURE";
    }

    private static String describeSuggestion(Verdict v) {
        List<String> names = new ArrayList<>();
        if (v.actions() != null) {
            for (Verdict.Action a : v.actions()) {
                names.add(a.name());
            }
        }
        return "只是建议，来自 actions=" + names + "（分类事实以学习者交的 experienceType 为准；"
                + "它没交时这条才作参考，且**不会**被写进 entry）";
    }

    /**
     * 置信度 → priority。
     *
     * <p>⚠️ 这是<b>排序策略</b>，不是事实映射：检索器会把 priority 直接当权重乘。
     * 说清楚它的来源，别让下游以为「80 分」意味着「这条经验 80% 对」。</p>
     */
    static int priorityFrom(double confidence) {
        double c = Math.max(0.0, Math.min(1.0, confidence));
        return (int) Math.round(1 + c * 99);
    }

    private static void addIfPresent(Set<String> into, String s) {
        if (s != null && !s.isBlank()) {
            into.add(s.trim());
        }
    }

    /**
     * null → 空串，别的原样 trim 一下（任务 4 的七字段槽位用）。
     *
     * <p>★ 为什么不复用 {@link #clip}：那个是展示用的、带省略号，
     * 落到槽位里就会与 {@code Experience.acceptable()} 的「非空即算填」判据对不上。</p>
     */
    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> keep = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                keep.add(p.trim());
            }
        }
        return String.join(sep, keep);
    }

    /**
     * 展示用截断（加省略号）。
     *
     * <p><b>只允许用在展示字段上</b>。落到 {@code title} 就是拿身份键开刀 ——
     * 两条前 60 字相同的长经验会撞成一条，谁也分不出来。</p>
     */
    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 本草稿的 JSON（{@code ExperienceEntry} 形状），直接可被 {@code fromJson} 吃。 */
    public JsonObject toJson() {
        return entry;
    }

    /** 给监测台/人看的映射决策（不含 ExperienceEntry 字段）。 */
    public JsonObject explain() {
        JsonObject o = new JsonObject();
        // needs_classification 现在说的是「**type 键有没有**」，不是「有没有人该去分」。
        // E4 之前它的意思是「type 只能由发布方来分」；现在学习者自己交了就是定了。
        o.addProperty("needs_classification", !entry.has("type"));
        o.addProperty("entry_ready_for_store", entry.has("type"));
        o.addProperty("type_resolution", typeResolution);
        o.addProperty("suggested_type", suggestedType);
        o.addProperty("suggested_type_why", suggestedTypeWhy);
        o.addProperty("title_display_truncated_to", DISPLAY_TITLE_CHARS);
        // B5 可观测：rewritten_query 到底有没有真的进检索槽位。报 0 时人能一眼看出
        //   「学习者这轮没给检索词」，而不是以为接上了但检索侧一直没反应。
        o.addProperty("rewritten_query_into_triggers", rewrittenQueryIntoTriggers);
        o.addProperty("rewritten_query_duplicates", rewrittenQueryDuplicates);
        o.addProperty("trigger_clip_chars", TRIGGER_CLIP_CHARS);
        if (evidenceNote != null && !evidenceNote.isBlank()) {
            o.addProperty("experience_evidence", evidenceNote);
        }
        return o;
    }
}