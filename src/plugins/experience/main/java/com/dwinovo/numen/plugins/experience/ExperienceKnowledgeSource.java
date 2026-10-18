package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.core.PresentationReceipt;
import com.dwinovo.numen.experience.core.TaskFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规划知识召回的宿主门面：把「攻略」与「该同伴的经验库」凑成一次
 * {@link PlanningKnowledge.Selection}，并留一条可审计的观测记录。
 *
 * <p>复用 {@link ExperiencePlugin#memory(UUID)} / {@code recall(...)} 的既有契约，
 * 不另造持久化经验库、不把 experience-core 再打包一份、不引入向量服务。
 * 经验按同伴隔离：只用传入 {@code companionId} 对应那一份文件。
 *
 * <h2>为什么要留接缝</h2>
 * {@link ItemSupplier} 把「经验从哪来」抽出来，于是选择/渲染/降级逻辑可以用假数据单测，
 * 不必起游戏、不必有真实经验文件。
 *
 * <h2>降级而不是炸</h2>
 * 攻略缺失、经验库为空、召回抛异常，都只会让这次注入变小或变空并留下缺口标记；
 * <b>规划流程绝不因为知识不可用而失败</b>。
 */
public final class ExperienceKnowledgeSource {

    /** 经验候选来源接缝：给定同伴与查询，返回候选知识条目。 */
    public interface ItemSupplier {
        List<PlanningKnowledge.Item> itemsFor(UUID companionId, String query, PlanningKnowledge.Request req);
    }

    private ExperienceKnowledgeSource() {}

    /**
     * 生产路径（2026-10-07 用户裁决）：只召回<b>该同伴自己写的经验</b>（主人路线）。
     *
     * <p>内置出厂经验 + 静态攻略（mc-guide）不再默认注入 —— 固定旧债，白烧 token（烧钱），
     * 而且在几个字的短目标上会抢走主人的路线条目。要恢复：把下面换回
     * {@code fromBuiltinAndMemory} + {@code GuideKnowledge.load()}。
     */
    public static PlanningKnowledge.Selection recall(UUID companionId, PlanningKnowledge.Request req) {
        return recall(companionId, req, ExperienceKnowledgeSource::fromMemory, List.of());
    }

    /**
     * 生产用经验来源：<b>内置只读层</b>（出厂知识）与<b>该同伴亲历经验</b>同池。
     *
     * <p>两者分开读：同伴文件坏了不该把出厂知识一起丢掉，所以这里单独兜住同伴侧的异常，
     * 内置层仍照常参与召回。
     */
    private static List<PlanningKnowledge.Item> fromBuiltinAndMemory(UUID companionId, String query,
                                                                    PlanningKnowledge.Request req) {
        List<PlanningKnowledge.Item> out = new ArrayList<>(BuiltinExperience.load());
        try {
            out.addAll(fromMemory(companionId, query, req));
        } catch (Throwable ignored) {
            // 同伴经验读不出来时，内置层仍然可用（观测上表现为没有 experience-<uuid> 来源）
        }
        return out;
    }

    /**
     * 可测路径：知识来源由调用方注入。
     *
     * @param supplier 经验来源（可为 null → 视为没有经验知识）
     * @param guide    攻略候选（可为 null → 视为没有攻略知识）
     */
    public static PlanningKnowledge.Selection recall(UUID companionId, PlanningKnowledge.Request req,
                                                     ItemSupplier supplier, List<PlanningKnowledge.Item> guide) {
        PlanningKnowledge.Request safe = req == null ? PlanningKnowledge.Request.of("", "", List.of()) : req;
        String query = buildQuery(safe);
        List<PlanningKnowledge.Item> experience = List.of();
        boolean failed = false;
        if (supplier != null) {
            try {
                List<PlanningKnowledge.Item> got = supplier.itemsFor(companionId, query, safe);
                if (got != null) {
                    experience = got;
                }
            } catch (Throwable ex) {
                // 知识来源坏了不能拖垮规划：降级为空，并在缺口里如实标注。
                // 这里连 Error 一起兜（如缺类 NoClassDefFoundError）：知识是可选增强，
                // 它挂掉不该让主人的目标规划跟着失败。
                failed = true;
            }
        }

        PlanningKnowledge.Selection selection = PlanningKnowledge.select(safe, guide, experience);
        if (failed) {
            selection = withExtraGap(selection, "error");
        }
        publish(companionId, safe, selection);
        return selection;
    }

    /**
     * 生产用经验来源：走经验插件的召回契约，<b>外加主人亲授条目无条件补全</b>。
     *
     * <p>2026-10-07 用户裁决：主人亲授的条目「直接全部注入，无条件」—— 不看字面命中、
     * 不占条目预算。兜底理由：4 个字的目标（如「安身立命」）字面命中不到任何条目，
     * 旧的召回会给 0 条，规划器只能空手猜（实测 chosen=0）。
     */
    private static List<PlanningKnowledge.Item> fromMemory(UUID companionId, String query,
                                                           PlanningKnowledge.Request req) {
        if (companionId == null || query.isBlank()) {
            return List.of();
        }
        var mem = ExperiencePlugin.memory(companionId);
        // ★ E7：reportable=false —— 规划知识也是每轮自动取用的，
        //   同伴不一定照做 ⇒ 不进待回报清单（只计「被呈现过」）。
        List<ExperienceHit> hits = mem.recall(query, req.maxItems(), null, req.tags(),
                PresentationReceipt.SURFACE_PLANNING, false);
        List<PlanningKnowledge.Item> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ExperienceHit hit : hits) {
            PlanningKnowledge.Item item = itemOf(hit.entry(), companionId, hit.score());
            if (item != null && seen.add(item.id())) {
                out.add(item);
            }
        }
        // 兜底：主人亲授的条目全部补进来（按 priority 降序，读起来就是路线顺序）。
        List<ExperienceEntry> usable = new ArrayList<>(mem.usable());
        usable.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        for (ExperienceEntry e : usable) {
            if (e == null || !seen.add(e.id()) || !ownerTaught(e)) {
                continue;
            }
            PlanningKnowledge.Item item = itemOf(e, companionId, e.priority());
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** 主人亲授判定：标题 / 标签 / 触发词里带主人路线标记（2026-10-07 用户口径）。 */
    private static boolean ownerTaught(ExperienceEntry e) {
        String blob = e.title() + " " + String.join(" ", e.tags()) + " " + String.join(" ", e.triggerStrings());
        return blob.contains("owner-taught") || blob.contains("主人亲授")
                || blob.contains("主人指定") || blob.contains("主人路线");
    }

    /** 单条经验 → 候选知识；字段缺失时用现象兜底，仍无内容则丢弃（不注入空壳）。 */
    private static PlanningKnowledge.Item itemOf(ExperienceEntry entry, UUID companionId, double score) {
        if (entry == null) {
            return null;
        }
        String problem = entry.rootCause().isBlank() ? entry.description() : entry.rootCause();
        if (entry.title().isBlank() && problem.isBlank() && entry.recommendedResponse().isBlank()) {
            return null;
        }
        ExperienceMaturity maturity = entry.maturity();
        List<String> tags = new ArrayList<>(entry.tags());
        tags.addAll(entry.triggerStrings());
        if (ownerTaught(entry)) {
            tags.add(PlanningKnowledge.ALWAYS_TAG);
        }
        return new PlanningKnowledge.Item(
                entry.id(),
                PlanningKnowledge.Kind.EXPERIENCE,
                entry.title(),
                maturity == null ? "OBSERVED" : maturity.name(),
                problem,
                entry.recommendedResponse(),
                // 出处带上同伴 UUID：跨同伴串味时能在监测台一眼看出来
                "experience-" + companionId + ".jsonl",
                score,
                tags);
    }

    /** 召回查询 = 目标 + 阶段 + 已知失败。已知失败是重试/回退时最该想起的经验依据。 */
    private static String buildQuery(PlanningKnowledge.Request req) {
        StringBuilder sb = new StringBuilder(req.objective());
        if (!req.stage().isBlank()) {
            sb.append(' ').append(req.stage());
        }
        for (String failure : req.knownFailures()) {
            if (failure != null && !failure.isBlank()) {
                sb.append(' ').append(failure);
            }
        }
        return sb.toString().trim();
    }

    /**
     * ★ B4：这次规划的<b>任务指纹</b>（任务侧，不是经验条目的去重键 —— 那是 stableKey）。
     *
     * <p>把 objective / stage / knownFailures 一起喂进去算一个指纹：同一类任务再次发生
     * ⇒ 指纹相同 ⇒ 可以拿它核对「那条经验是不是又被命中了」（60 号 A6 判据）。
     * 修之前 {@link #buildQuery} 只是整句拼起来做字面匹配，<b>没有任何稳定标识</b>，
     * 于是「命中了」与「碰巧命中」分不开。</p>
     *
     * <p>⚠️ 指纹相同<b>只代表「大概同一类场景」</b>，不代表经验一定适用 ——
     * 所以它只进观测（{@code planning_knowledge} 事件），不参与命中判定。</p>
     */
    static String taskFingerprint(PlanningKnowledge.Request req) {
        if (req == null) {
            return TaskFingerprint.UNKNOWN;
        }
        List<String> parts = new ArrayList<>();
        if (req.objective() != null && !req.objective().isBlank()) {
            parts.add(req.objective());
        }
        if (req.stage() != null && !req.stage().isBlank()) {
            parts.add(req.stage());
        }
        if (req.knownFailures() != null) {
            parts.addAll(req.knownFailures());
        }
        return TaskFingerprint.of(parts);
    }

    /**
     * 本次规划涉及的几类场景指纹（每个片段各算一个，去重排序）。
     *
     * <p>用途：一次规划往往同时涉及挖矿 / 搭桥 / 搬箱子，合并后能看出「这次跨了几类场景」，
     * 而不是糊成一个说不清的串。</p>
     */
    static List<String> sceneFingerprints(PlanningKnowledge.Request req) {
        if (req == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        if (req.objective() != null && !req.objective().isBlank()) {
            parts.add(req.objective());
        }
        if (req.stage() != null && !req.stage().isBlank()) {
            parts.add(req.stage());
        }
        return TaskFingerprint.sceneFingerprints(parts);
    }

    private static PlanningKnowledge.Selection withExtraGap(PlanningKnowledge.Selection base, String gap) {
        List<String> gaps = new ArrayList<>(base.gaps());
        if (!gaps.contains(gap)) {
            gaps.add(gap);
        }
        String note = "知识注入降级：" + String.join(", ", gaps);
        return new PlanningKnowledge.Selection(base.text(), base.chosen(), gaps, note);
    }

    /**
     * 观测出口：记录这次选了什么、来自哪，便于监测台核对数据流。
     * 只记清单（id/成熟度/来源/字符数），正文本身由请求采集记录，避免重复膨胀。
     */
    private static void publish(UUID companionId, PlanningKnowledge.Request req,
                                PlanningKnowledge.Selection selection) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companion", companionId == null ? "" : companionId.toString());
            data.put("stage", req.stage());
            data.put("objective_chars", req.objective().length());
            data.put("known_failures", req.knownFailures().size());
            // ★ B4：任务指纹 + 场景指纹进观测。A6 判据「同类任务再次发生 → 同一条经验
            //   被再次命中」需要靠它才判得出来；之前这里只有一个 objective_chars，
            //   压根没法回答「这次和上次是不是同一类任务」。
            data.put("task_fingerprint", taskFingerprint(req));
            data.put("scene_fingerprints", sceneFingerprints(req));
            data.put("chosen", selection.chosen().size());
            data.put("text_chars", selection.text().length());
            data.put("gaps", List.copyOf(selection.gaps()));
            List<Map<String, Object>> sources = new ArrayList<>();
            for (PlanningKnowledge.Item item : selection.chosen()) {
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("id", item.id());
                src.put("kind", item.kind().name());
                src.put("maturity", item.maturity());
                src.put("origin", item.origin());
                sources.add(src);
            }
            data.put("sources", sources);
            ExperienceMonitor.publish("planning_knowledge", data);
        } catch (Throwable ignored) {
            // 观测失败不影响规划（同样连 Error 一起兜，见 recall 里的说明）
        }
    }
}
