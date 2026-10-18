package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「这条经验被呈现过」的短期回执表 —— E7（使用结果回流）的连接键。
 *
 * <p><b>它补的是一个真洞，不是一个锦上添花的功能。</b>回流链路上游的四个执行器
 * （{@code recordEvidence / retract / reinstate / supersede}）一直都在，
 * 但它们<em>唯一</em>的生产触发器是「AI 自己想起来调 {@code experience_verify}」，
 * 而 AI 凭什么知道该回报哪一条？—— 此前<b>代码里没有任何地方记录「这条经验被用过」</b>：
 * 没有 usage / hit / applied 计数，{@code last_accessed_at} 名不符实
 * （写入点全在变更方法里，检索器只读不写），三个召回点也都不埋点。
 * ⇒ 连「哪条经验被用过」都答不出来，回流自然无从判定对错。</p>
 *
 * <p><b>为什么不落盘</b>：跨重启的「那次任务」早已结束，回报已无意义
 * （与 {@code CandidateGate} 的 T3 计数同口径：session-only）。
 * 落盘只会把「上一局的残留」当成「本局待回报」，那是假事实。</p>
 *
 * <p><b>⚠️ 只有「AI 主动要」的召回才进待回报清单</b>。L0 目录注入与规划知识是
 * 每轮自动印在 prompt 里的，同伴很可能压根没看 —— 让它们进清单会产出
 * 满屏「本任务没有结论」的噪音，等于把回流通道重新变成红色。
 * 这两种呈现仍然计进「被用过几次」这个长期读数，只是不进待回报。</p>
 *
 * <p><b>⚠️ 待回报不等于「必须全应用」。</b>一次召回可能带回 10 条，
 * 而 AI 往往只对其中 2 条有结论 ⇒ 把清单设计成「只读提示」，
 * 由 AI 自己挑要回报哪几条。批量一刀切会往不相干的经验身上加反例甚至降级，
 * 那比不回流糟得多。</p>
 *
 * <p><b>本类不落盘、无副作用、纯记账。</b>它不判定成功也不判定失败 ——
 * 判定永远由 AI 回报后走 {@code recordEvidence} 的状态机，
 * 这里只负责回答「哪些还没被回报」。</p>
 */
public final class PresentationReceipt {

    /** 一次召回最多回报的条数（待回报清单上限；超出就拒收并如实报，不静默丢）。 */
    public static final int MAX_PENDING = 32;
    /** 长期计数上限（防长时间运行内存无界；超出不再加，记进 readout）。 */
    public static final int MAX_TRACKED = 200;

    /** AI 主动召回（进待回报）。 */
    public static final String SURFACE_RECALL_TOOL = "recall_tool";
    /** L0 目录注入（每轮自动，不进待回报）。 */
    public static final String SURFACE_DIRECTORY = "directory_l0";
    /** 规划知识（每轮自动，不进待回报）。 */
    public static final String SURFACE_PLANNING = "planning";
    /** 未标注来源的老调用点（不进待回报 —— 不知道是不是自动的，就当自动的）。 */
    public static final String SURFACE_UNSPECIFIED = "unspecified";

    /** 一次呈现的读数。 */
    public record Shown(String id, String title, String surface, boolean reportable,
                        double score, long atMillis) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("title", title);
            out.put("surface", surface);
            out.put("reportable", reportable);
            out.put("score", score);
            out.put("at", atMillis);
            return out;
        }
    }

    /** id → 最近一次呈现（任何 surface；插入序 = 首次呈现序）。 */
    private final Map<String, Shown> shown = new LinkedHashMap<>();
    /** id → 被呈现次数（跨 surface 累加，长期读数）。 */
    private final Map<String, Integer> presentedCount = new LinkedHashMap<>();
    /**
     * 进过待回报、还没被回报的 id → <b>最近一次「AI 主动要」的那次呈现</b>。
     *
     * <p>★ 为什么不能只留一个 {@code shown} 然后按 id 查（2026-10-03 B13 实机抓到的坑）：
     * {@code shown} 每次呈现都被覆写，<b>包括 L0 目录那种每轮自动重印的非回报性呈现</b>。
     * 于是待回报清单里那一行的 surface 与时间戳会被改成 {@code directory_l0}：
     * <ol>
     *   <li>读的人以为「这是自动印出来的，不必回报」；</li>
     *   <li>更致命：{@code OutcomeCorrelator} 的配对锚点被推到最新一次 L0 时间戳，
     *       于是「主动召回 → 那次任务收尾」这段窗口里的结果<b>永远配不上</b>，
     *       {@code OUTCOME_KNOWN} 实际上不可达。</li>
     * </ol>
     * <p>所以这里存的是<b>另一份</b>记录：只在 {@code reportable} 时写入，被动重印碰不到它。
     * {@code shown} 继续表示「最近一次呈现（任何 surface）」，
     * 供 {@code lastShown} 与「被呈现过几次」使用 —— 两份记录各司其职。
     * <p>用 {@code LinkedHashMap} 而不是 {@code Set + 另开一张表}：键的插入序就是
     * 「首次呈现序」，而值天然带着 surface 与时间戳，两边不可能走散。
     */
    private final Map<String, Shown> pending = new LinkedHashMap<>();
    /** 因为清单满了而**没**被收下的 id —— 如实报出来，不静默丢。 */
    private int pendingRejected;
    /** 因超过 MAX_TRACKED 而停止计数的 id 数。 */
    private int trackingSaturated;
    /** id 空 / 命中不了 entry 的次数（历史脏数据，正常应为 0）。 */
    private int unusableId;

    /**
     * 记一次呈现。
     *
     * @param reportable 是否进待回报清单。只有「AI 主动召回」为 true。
     * @return 实际被记录的条数（被清单上限拒掉的、id 不可用的都不算）
     */
    public synchronized int record(List<ExperienceHit> hits, String surface, boolean reportable) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        int recorded = 0;
        for (ExperienceHit hit : hits) {
            ExperienceEntry entry = hit == null ? null : hit.entry();
            String id = entry == null ? null : entry.id();
            if (id == null || id.isBlank()) {
                unusableId++;
                continue;
            }
            String shownSurface = surface == null || surface.isBlank() ? SURFACE_UNSPECIFIED : surface;
            shown.put(id, new Shown(id, entry.title(), shownSurface, reportable,
                    hit.score(), System.currentTimeMillis()));
            presentedCount.merge(id, 1, Integer::sum);
            if (presentedCount.size() > MAX_TRACKED) {
                trackingSaturated++;
            }
            if (reportable) {
                // 已经在清单里就只刷新时间锚点（AI 又主动要了一次 = 又一个机会）；
                // 不在清单里才需要占名额。被动呈现（reportable=false）永远不碰这一行。
                if (!pending.containsKey(id) && pending.size() >= MAX_PENDING) {
                    // ★ 拒收而不是丢：清单满了要说出来，否则 AI 回报了一条
                    //   「系统从没收到过」的 id，回来只会得到 "no experience with id"。
                    pendingRejected++;
                    continue;
                }
                pending.put(id, shown.get(id));
            }
            recorded++;
        }
        trim();
        return recorded;
    }

    /**
     * 标记一条经验已被回报（{@code recordEvidence} 走到哪一步都算，
     * 包括 id 根本不存在 —— 那也要清掉，否则 AI 会反复看到同一条不存在的 id）。
     */
    public synchronized void report(String id) {
        if (id != null) {
            pending.remove(id);
        }
    }

    /** 待回报清单（首次主动召回序）。空 = 本局还没有 AI 主动召回过任何经验。 */
    public synchronized List<Shown> pendingReports() {
        if (pending.isEmpty()) {
            return List.of();
        }
        // ★ 直接返回 pending 里的值，不再回 shown 查 —— shown 会被 L0 那种
        //   被动重印覆写，回查等于把 surface 与时间锚点一起弄丢（见字段注释）。
        return List.copyOf(pending.values());
    }

    /** 待回报条数。 */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** 这条经验被呈现过几次（任何 surface）。 */
    public synchronized int presentedCount(String id) {
        Integer n = id == null ? null : presentedCount.get(id);
        return n == null ? 0 : n;
    }

    /** 这条经验最近一次呈现的来源；没呈现过返回 null。 */
    public synchronized Shown lastShown(String id) {
        return id == null ? null : shown.get(id);
    }

    /** 出现过几种不同的呈现来源（诊断用：AI 主动 vs 系统自动的比例）。 */
    public synchronized int surfaceCount(String id) {
        Set<String> s = new LinkedHashSet<>();
        for (Map.Entry<String, Shown> e : shown.entrySet()) {
            if (e.getKey().equals(id)) {
                s.add(e.getValue().surface());
            }
        }
        return s.size();
    }

    /**
     * 面板/工具用的读数。
     *
     * <p>刻意<b>不</b>提供「总呈现次数」这种好看的总数：真正要问的是
     * 「有多少被回报过」，那个数 = {@code pendingCount} 与 tracked 之差。</p>
     */
    public synchronized Map<String, Object> readout() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending_reports", pending.size());
        out.put("tracked_ids", presentedCount.size());
        out.put("pending_rejected_over_limit", pendingRejected);
        out.put("tracking_saturated", trackingSaturated);
        out.put("unusable_id_skipped", unusableId);
        out.put("session_only", true);
        return out;
    }

    /** 清空（测试与重启时用；生产不调用）。 */
    public synchronized void reset() {
        shown.clear();
        presentedCount.clear();
        pending.clear();
        pendingRejected = 0;
        trackingSaturated = 0;
        unusableId = 0;
    }

    private void trim() {
        // MAX_TRACKED 只停止「继续加新 id」，不回头删已记录的 ——
        // 删掉会让 AI 手里那条 id 突然从「已呈现」变成「没呈现」，那是倒退。
        while (presentedCount.size() > MAX_TRACKED) {
            presentedCount.remove(presentedCount.keySet().iterator().next());
        }
    }
}