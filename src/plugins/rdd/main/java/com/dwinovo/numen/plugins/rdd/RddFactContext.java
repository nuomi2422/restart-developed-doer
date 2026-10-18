package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.InventoryGroups;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fact.FactSnapshot;
import com.dwinovo.numen.rdd.fact.StageFact;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import com.dwinovo.numen.rdd.policy.RequirementView;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共同事实的最小生产接线（第三批 N1 消费侧，2026-10-06）。
 *
 * <p><b>为什么要有这个类</b>：第三批的模型（{@link FactSnapshot}/{@link RequirementManifest}/
 * {@link RequirementView}）此前只有测试调用 —— 需求清单在 bind 时算完即丢（只发一条计数事件），
 * 「需求是否满足」从来没有在真机上被持续检测过（{@code RddRequirementDetector.detectAndPublish}
 * 是死代码）。本类把它们接进生产的三条真实出口：
 *
 * <ol>
 *   <li><b>需求清单落地</b>：一级目标生成时缓存清单（按 primaryId 失效），并保留原有
 *       {@code requirement_manifest} 事件；</li>
 *   <li><b>粗粒度需求检测</b>：每 {30 拍 ≈ 30 秒} 用<b>真实背包计数</b>检测当前一级的需求，
 *       <b>只在状态变化时</b>发事件（满足 / 重新不满足 / 缺口集合变化）——
 *       只产事实，不做裁决（检测 ≠ 重规划）；</li>
 *   <li><b>事实进入 LLM 上下文</b>：重规划提示词追加「需求 × 持有对账 + 近期阶段事实」只读块；
 *       执行 AI 的 {@code <rdd>} 上下文追加当前未凑齐的需求（{@code <needs>}）。</li>
 * </ol>
 *
 * <p><b>★ 三个口径（都写死在类型里，不靠调用方自觉）</b>：
 * <ul>
 *   <li>检测读的是 {@link #tick} 传入的<b>真实背包计数</b>（countInventory），
 *       不是 {@link AssetRegistry} 的缓存折叠 —— 规划侧文档早已定调
 *       「实时扫描是持有唯一真相，注册表只作提示」（{@code PlanningAssetSnapshot}）；
 *       注册表口径只用于 {@link RequirementView} 的旁证列。</li>
 *   <li>事件只报<b>状态变化</b>（未满足集合），不报「缺口文字变化」——
 *       缺口文字含现有数量，捡一个苹果就会变一次，那是刷屏而不是信号。</li>
 *   <li>一切失败 fail-soft 并留日志：事实接线是增强，不许拖垮检测 tick / 上下文构建 / 重规划。</li>
 * </ul>
 *
 * <p><b>线程</b>：本类的缓存全部 {@link ConcurrentHashMap}。{@link #tick} 与
 * {@link #onPrimaryBound} 在服务端线程；{@link #executorBlock} 可能在任何构建上下文的线程
 * （只读缓存，不读世界）；{@link #replanBlock} 在重规划路径（服务端线程）。
 */
final class RddFactContext {

    private static final Logger LOG = LoggerFactory.getLogger("rdd-facts");

    /**
     * 快照 TTL：24 小时。
     *
     * <p>快照里的条目是<b>阶段完成事实</b>（里程碑）——「三天前挖到过钻石」不会因为过期而变假。
     * 这里的 TTL 只防「很久以前的血缘记录被当本轮现况」，<b>不是新鲜度指标</b>；
     * 新鲜度看每条自己的 {@code observedAt}（投影行里带）。
     */
    private static final long SNAPSHOT_TTL_MILLIS = 24L * 60 * 60 * 1000;

    /** 重规划事实块最多列几条需求（按关注度排序取前 N，其余只报条数）。 */
    private static final int MAX_REPLAN_ROWS = 8;
    /** 重规划事实块最多列几条近期阶段事实。 */
    private static final int MAX_RECENT_FACTS = 5;
    /** 执行 AI 上下文里最多列几条未凑齐需求。 */
    private static final int MAX_EXECUTOR_GAPS = 3;
    /** 监测事件里最多列几条缺口。 */
    private static final int MAX_EVENT_GAPS = 6;

    /** 已缓存的需求清单：键 = primaryId，清单的 goalId 必须是 true Goal.id（见 forPrimary 注释）。 */
    private record Cached(String primaryId, String goalId, RequirementManifest.Manifest manifest) {}

    /** 需求检测状态：按 primaryId 隔离；换 primary 视为新观察（不报跨 primary 的变化）。 */
    private record Watch(String primaryId, Set<String> unmet) {}

    private static final Map<UUID, Cached> MANIFESTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Watch> WATCH = new ConcurrentHashMap<>();
    private static final Map<UUID, FactSnapshot> LATEST = new ConcurrentHashMap<>();

    private RddFactContext() {
    }

    // ───────────────────────── 纯逻辑（可离线单测） ─────────────────────────

    /**
     * 一条「被跟踪的需求」：{@code asset_key} 或 {@code group}。
     *
     * <p>{@code group} 需求只有三个已知组（food/wood/blocks，见 {@link InventoryGroups}）——
     * 组计数是<b>组内物品件数之和</b>，不是配方等价；未知组名直接不跟踪（不猜）。
     */
    record Need(String key, int minimum, boolean group) {
        String display() {
            return group ? "group:" + key : key;
        }
    }

    /**
     * 从一级目标提取被跟踪需求：{@code waitFor} 前置 + 各二级 condition 的
     * {@code asset_key}/{@code group}。
     *
     * <p>同键取<b>更大</b>的 minimum（同一级的两个二级要 2 个和 5 个铁，跟踪 5 个才诚实）；
     * {@code waitFor} 排在前面（它是进入本一级的前置，语义上最先约束）。
     */
    static List<Need> needsOf(PrimaryGoal primary) {
        Map<String, Need> out = new LinkedHashMap<>();
        if (primary == null) {
            return List.of();
        }
        for (AssetRequirement w : primary.waitFor()) {
            if (w != null && w.assetKey() != null && !w.assetKey().isBlank()) {
                mergeNeed(out, w.assetKey(), w.minimum(), false);
            }
        }
        for (Subtask s : primary.subtasks()) {
            Map<String, Object> c = s.condition();
            if (c == null || c.isEmpty()) {
                continue;
            }
            Object key = c.get("asset_key");
            Object group = c.get("group");
            Object m = c.get("minimum");
            int min = m instanceof Number n ? Math.max(0, n.intValue()) : 1;
            if (key instanceof String ks && !ks.isBlank()) {
                mergeNeed(out, ks, min, false);
            } else if (group instanceof String gs && InventoryGroups.known(gs)) {
                mergeNeed(out, gs, min, true);
            }
        }
        return List.copyOf(out.values());
    }

    private static void mergeNeed(Map<String, Need> out, String key, int minimum, boolean group) {
        int min = Math.max(1, minimum);   // 需求至少 1 件；0 按 1 计（与 AssetRequirement 口径一致）
        String k = group ? "group:" + key : key;
        Need prev = out.get(k);
        if (prev == null || min > prev.minimum()) {
            out.put(k, new Need(key, min, group));
        }
    }

    /** 该需求在给定持有计数下现有多少（group 走组内求和；held 为 null 视为空背包）。 */
    static int have(Need need, Map<String, Integer> held) {
        if (need == null || held == null) {
            return 0;
        }
        if (need.group()) {
            return (int) Math.min(Integer.MAX_VALUE, InventoryGroups.count(need.key(), held));
        }
        Integer v = held.get(need.key());
        return v == null ? 0 : v;
    }

    /** 未满足的需求（display 键集合，保持声明顺序）。 */
    static LinkedHashSet<String> unmetKeys(List<Need> needs, Map<String, Integer> held) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Need need : needs) {
            if (have(need, held) < need.minimum()) {
                out.add(need.display());
            }
        }
        return out;
    }

    /** 需求状态迁移；只对「同一 primary 内」的两次观察有意义。 */
    enum Transition {
        /** 无变化（含两次都满足）。 */
        NONE,
        /** 从「有未满足」变成「全部满足」。 */
        MET,
        /** 从「全部满足」变成「有未满足」（东西用掉了/丢了 —— 必须也说）。 */
        UNMET,
        /** 未满足集合换了内容（得 A 丢 B）。 */
        GAPS_CHANGED
    }

    static Transition transition(Set<String> prev, Set<String> next) {
        if (prev == null || next == null) {
            return Transition.NONE;
        }
        if (!prev.isEmpty() && next.isEmpty()) {
            return Transition.MET;
        }
        if (prev.isEmpty() && !next.isEmpty()) {
            return Transition.UNMET;
        }
        return prev.equals(next) ? Transition.NONE : Transition.GAPS_CHANGED;
    }

    /** 事件里的缺口短文本（有界；只在事件帧用，不用于逐轮上下文）。 */
    static String unmetText(List<Need> needs, Map<String, Integer> held) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Need need : needs) {
            int now = have(need, held);
            if (now >= need.minimum()) {
                continue;
            }
            if (n == MAX_EVENT_GAPS) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append("；");
            }
            sb.append(need.display()).append(" 需").append(need.minimum()).append(" 有").append(now);
            n++;
        }
        return sb.toString();
    }

    /** 执行 AI 上下文块：只列未凑齐的需求（≤3 条），全凑齐就整块不出现。 */
    static String renderNeedsBlock(List<Need> needs, Map<String, Integer> held) {
        List<Need> unmet = new ArrayList<>();
        for (Need need : needs) {
            if (have(need, held) < need.minimum()) {
                unmet.add(need);
            }
        }
        if (unmet.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<needs>一级目标需求还差：");
        int n = 0;
        for (Need need : unmet) {
            if (n == MAX_EXECUTOR_GAPS) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append("；");
            }
            sb.append(need.display()).append(" ×").append(need.minimum())
                    .append("（现有 ").append(have(need, held)).append("）");
            n++;
        }
        sb.append("。凑齐即消失；这只有数量，不是指令。</needs>");
        return sb.toString();
    }

    /**
     * 重规划事实块：需求 × 持有对账（{@link RequirementView}）+ 近期阶段事实。
     *
     * <p>只读陈述：缺口数字 ≠「没做」；「持有未知」= 没扫过登记表，也不代表 0。
     * 无需求清单时返回空串（不加噪音）。
     */
    static String renderReplanBlock(RequirementManifest.Manifest manifest, CompletedFactStore facts,
                                    AssetRegistry assets, FactSnapshot snapshot, long now) {
        if (manifest == null || manifest.requirements().isEmpty()) {
            return "";
        }
        RequirementView.View view = RequirementView.build(manifest, facts, assets, null);
        if (view.rows().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【需求 × 持有对账（只读；共同事实快照 ").append(ageText(now, snapshot.capturedAt()))
                .append("采样，维度 ").append(snapshot.dimensionId()).append("）】\n");
        int n = 0;
        for (RequirementView.Row row : view.rows()) {
            if (n == MAX_REPLAN_ROWS) {
                sb.append("- ……其余 ").append(view.rows().size() - MAX_REPLAN_ROWS).append(" 条略（关注项已在前）\n");
                break;
            }
            n++;
            sb.append("- ").append(row.requirementKey()).append(" 需要 ").append(row.minimum());
            if (row.held() == null) {
                sb.append("，持有未知（登记表没这个键，不等于 0）");
            } else {
                sb.append("，现有 ").append(row.held());
            }
            if (row.heldGap() > 0) {
                sb.append(" → 缺口 ").append(row.heldGap());
            }
            if (row.factCount() > 0) {
                sb.append("｜事实记录 ").append(row.factCount()).append(" 条");
            }
            // ★ 持有未知时**不能**再渲染 attention 文案：RequirementView 对 UNKNOWN_HELD
            //   刻意给 NONE（"判断不了 ≠ 不用看"），而 attentionText(NONE) 是"持有已达标" ——
            //   实机抓到的错标：同一行同时写「持有未知」与「持有已达标」。
            //   未知行只保留上面那句"持有未知（登记表没这个键，不等于 0）"。
            if (row.held() != null) {
                String attention = attentionText(row.attention());
                if (!attention.isEmpty()) {
                    sb.append("｜").append(attention);
                }
            }
            sb.append('\n');
        }
        String recent = renderRecentFacts(facts, manifest.goalId(), now);
        if (!recent.isEmpty()) {
            sb.append("近期已完成的阶段（共同事实）：\n").append(recent);
        }
        if (view.notes().stream().anyMatch(t -> t != null && t.startsWith("REGISTRY_EMPTY"))) {
            sb.append("（注：资产登记表为空 = 还没扫过背包；上列「持有未知」不代表 0）\n");
        }
        sb.append("（只读陈述：缺口数字 ≠「没做」；对账列不改变任何一侧。）");
        return sb.toString();
    }

    private static String attentionText(RequirementView.Attention attention) {
        return switch (attention) {
            case DONE_BUT_NOT_HELD -> "关注：事实有完成记录但持有不足（疑似消耗/采集口径）";
            case NOT_DONE_AND_NOT_HELD -> "持有不足";
            case HELD_BUT_NO_FACT -> "持有已达标";
            case NONE -> "持有已达标";
        };
    }

    /** 近期阶段事实（按 manifest.goalId = 真 Goal.id 过滤，时间倒序，≤5 条）。 */
    private static String renderRecentFacts(CompletedFactStore facts, String goalId, long now) {
        if (facts == null || goalId == null || goalId.isBlank()) {
            return "";
        }
        List<StageFact> matched = new ArrayList<>();
        for (StageFact f : facts.stageFacts()) {
            if (f != null && goalId.equals(f.goalId()) && f.rawStage() != null && !f.rawStage().isBlank()) {
                matched.add(f);
            }
        }
        if (matched.isEmpty()) {
            return "";
        }
        matched.sort(Comparator.comparingLong(StageFact::completedAtMillis).reversed());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StageFact f : matched) {
            if (n == MAX_RECENT_FACTS) {
                sb.append("- ……\n");
                break;
            }
            sb.append("- 《").append(f.rawStage()).append("》 ").append(ageText(now, f.completedAtMillis())).append('\n');
            n++;
        }
        return sb.toString();
    }

    private static String ageText(long now, long at) {
        long ms = Math.max(0L, now - at);
        if (ms < 60_000L) {
            return "刚刚";
        }
        if (ms < 60L * 60_000L) {
            return (ms / 60_000L) + " 分钟前";
        }
        return (ms / (60L * 60_000L)) + " 小时前";
    }

    // ───────────────────────── 宿主侧（生产出口） ─────────────────────────

    /**
     * 一级目标生成（或推进到新 primary）时缓存需求清单 —— 对应「架构概念 2/4」的
     * 「生成时同步产出需求清单」。事件字段与旧实现逐字一致（只是不再算完即丢）。
     *
     * @param goalId 真 {@code Goal.id}（事实库的 goalId 就是它；传 primary.id 会让对账列永远 join 不上）
     */
    static void onPrimaryBound(UUID companionId, PrimaryGoal primary, String goalId) {
        if (companionId == null) {
            return;
        }
        try {
            RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(primary, goalId);
            MANIFESTS.put(companionId, new Cached(primary == null ? "" : primary.id(), manifest.goalId(), manifest));
            WATCH.remove(companionId);   // 换目标 = 检测状态重来（不报跨目标的"变化"）
            RddMonitor.publish("requirement_manifest", Map.of(
                    "companionId", companionId.toString(),
                    "primary", primary == null ? "unknown" : primary.id(),
                    "requirements", String.valueOf(manifest.requirements().size())));
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 需求清单生成失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 同伴任务清空时一并清掉事实接线缓存（防内存缓慢增长）。 */
    static void forget(UUID companionId) {
        if (companionId == null) {
            return;
        }
        MANIFESTS.remove(companionId);
        WATCH.remove(companionId);
        LATEST.remove(companionId);
    }

    /** 最近一次快照（观测/测试用；没有就是没采集过，不造空快照）。 */
    static FactSnapshot latest(UUID companionId) {
        return companionId == null ? null : LATEST.get(companionId);
    }

    /**
     * 每 30 秒一跳：缓存清单（primary 推进时补发事件）→ 采集快照 → 需求检测（只在状态变化时发事件）。
     *
     * <p>{@code counts} 必须是<b>本拍真实背包扫描</b>。全部包在 try 里：
     * 事实接线失败不许拖垮检测 tick。
     */
    static void tick(UUID companionId, String dimensionId, RddRuntime rt, Map<String, Integer> counts) {
        if (companionId == null || rt == null) {
            return;
        }
        try {
            PrimaryGoal primary = rt.chain().currentPrimary();
            if (primary == null) {
                return;
            }
            String goalId = rt.chain().goal() == null ? null : rt.chain().goal().id();
            RequirementManifest.Manifest manifest = ensureManifest(companionId, primary, goalId);
            long now = System.currentTimeMillis();
            FactSnapshot snapshot = FactSnapshot.capture(companionId, dimensionId,
                    RddPlugin.facts(companionId), rt.assets(), null, now, SNAPSHOT_TTL_MILLIS);
            LATEST.put(companionId, snapshot);

            List<Need> needs = needsOf(primary);
            Set<String> unmet = Collections.unmodifiableSet(unmetKeys(needs, counts));
            Watch prev = WATCH.get(companionId);
            WATCH.put(companionId, new Watch(primary.id(), unmet));
            if (prev != null && prev.primaryId().equals(primary.id()) && !needs.isEmpty()) {
                switch (transition(prev.unmet(), unmet)) {
                    case MET -> RddMonitor.publish("requirements_met", Map.of(
                            "companionId", companionId.toString(),
                            "primary", primary.id(),
                            "requirements", String.valueOf(needs.size())));
                    case UNMET -> RddMonitor.publish("requirements_unmet", Map.of(
                            "companionId", companionId.toString(),
                            "primary", primary.id(),
                            "gaps", unmetText(needs, counts)));
                    case GAPS_CHANGED -> RddMonitor.publish("requirement_gaps_changed", Map.of(
                            "companionId", companionId.toString(),
                            "primary", primary.id(),
                            "gaps", unmetText(needs, counts)));
                    case NONE -> { }
                }
            }

            Map<String, Object> event = new LinkedHashMap<>(snapshot.describe());
            event.put("primary", primary.id());
            event.put("needs", needs.size());
            event.put("unmet", unmet.size());
            RddMonitor.publish("fact_snapshot", event);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 共同事实 tick 失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 缓存清单；primary/目标变了才重建（并补发一份清单事件——bind 之外的第二个出口）。 */
    private static RequirementManifest.Manifest ensureManifest(UUID companionId, PrimaryGoal primary, String goalId) {
        String gid = goalId == null || goalId.isBlank() ? primary.id() : goalId;
        Cached cached = MANIFESTS.get(companionId);
        if (cached != null && primary.id().equals(cached.primaryId()) && gid.equals(cached.goalId())) {
            return cached.manifest();
        }
        RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(primary, gid);
        MANIFESTS.put(companionId, new Cached(primary.id(), gid, manifest));
        RddMonitor.publish("requirement_manifest", Map.of(
                "companionId", companionId.toString(),
                "primary", primary.id(),
                "requirements", String.valueOf(manifest.requirements().size())));
        return manifest;
    }

    /**
     * 执行 AI 的 {@code <rdd>} 上下文块（只读缓存，不读世界）。
     *
     * <p>从未扫过背包（{@code lastInventoryAtMillis == null}）时返回空串 ——
     * 「没扫过」不能让 AI 以为「什么都没有」。清单与当前 primary 对不上时也返回空
     * （不拿上一个阶段的需求误导）。
     */
    static String executorBlock(UUID companionId) {
        try {
            if (companionId == null) {
                return "";
            }
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return "";
            }
            PrimaryGoal primary = rt.chain().currentPrimary();
            Cached cached = MANIFESTS.get(companionId);
            if (primary == null || cached == null || !primary.id().equals(cached.primaryId())) {
                return "";
            }
            if (RddPlugin.lastInventoryAtMillis(companionId) == null) {
                return "";
            }
            return renderNeedsBlock(needsOf(primary), RddPlugin.lastInventory(companionId));
        } catch (RuntimeException ex) {
            return "";   // 增强块，绝不能拖崩上下文构建
        }
    }

    /**
     * 重规划提示词事实块（只读）。同伴不在线 / 拿不到维度时返回空串 —— 不猜。
     *
     * <p>清单在缓存里对不上当前 primary 时<b>现算一份</b>（用真 Goal.id），不改缓存。
     */
    static String replanBlock(UUID companionId) {
        try {
            if (companionId == null) {
                return "";
            }
            RddRuntime rt = RddPlugin.runtime(companionId);
            if (rt == null) {
                return "";
            }
            PrimaryGoal primary = rt.chain().currentPrimary();
            if (primary == null) {
                return "";
            }
            net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            NumenPlayer ap = server == null ? null : NumenPlayer.findByUuid(server, companionId);
            if (ap == null) {
                return "";   // 同伴不在线：维度与快照都不猜
            }
            String dimensionId = ap.level().dimension().location().toString();
            String goalId = rt.chain().goal() == null ? null : rt.chain().goal().id();
            Cached cached = MANIFESTS.get(companionId);
            RequirementManifest.Manifest manifest =
                    cached != null && primary.id().equals(cached.primaryId())
                            ? cached.manifest()
                            : RddRequirementDetector.forPrimary(primary, goalId);
            long now = System.currentTimeMillis();
            FactSnapshot snapshot = FactSnapshot.capture(companionId, dimensionId,
                    RddPlugin.facts(companionId), rt.assets(), null, now, SNAPSHOT_TTL_MILLIS);
            return renderReplanBlock(manifest, RddPlugin.facts(companionId), rt.assets(), snapshot, now);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 重规划事实块生成失败 {}: {}", companionId, ex.toString());
            return "";
        }
    }
}
