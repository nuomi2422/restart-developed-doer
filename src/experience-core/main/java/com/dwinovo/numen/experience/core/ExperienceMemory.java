package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceQuery;
import com.dwinovo.numen.experience.api.ExperienceRetriever;

import java.nio.file.Path;
import java.util.List;

/**
 * 经验记忆的门面：一个主人/同伴一份，负责“学”、“验”、“查”。
 *
 * <p>{@code at(file, retriever)} 构造：文件用于持久化，retriever 是检索接缝
 * （第一版用 {@link LexicalExperienceRetriever}，将来接向量记忆换它）。
 */
public final class ExperienceMemory {

    private final ExperienceStore store;
    private final ExperienceRetriever retriever;

    private ExperienceMemory(Path file, ExperienceRetriever retriever) {
        this.store = ExperienceStore.at(file);
        this.retriever = retriever;
    }

    public static ExperienceMemory at(Path file, ExperienceRetriever retriever) {
        return new ExperienceMemory(file, retriever);
    }

    /** 学一条经验（同 stable key 合并）。 */
    public ExperienceEntry learn(ExperienceEntry entry) {
        return store.learn(entry);
    }

    /**
     * 记录一次真实结果。返回更新后的经验；id 不存在返回 {@code null}。
     *
     * @see ExperienceStore#recordEvidence
     */
    public ExperienceEntry recordEvidence(String id, boolean success, String note) {
        return store.recordEvidence(id, success, note);
    }

    // ---- E8：撤回 / 修订链（薄转发，真逻辑在 ExperienceStore）----

    /** 撤回一条经验（标记不删）。reason 必填且不许空。id 不存在返回 {@code null}。 */
    public ExperienceEntry retract(String id, String reason) {
        return store.retract(id, reason);
    }

    /** 撤销撤回（误撤回救回来）。id 不存在返回 {@code null}。 */
    public ExperienceEntry reinstate(String id) {
        return store.reinstate(id);
    }

    /**
     * 标注 {@code newerId} 取代了 {@code olderId}（E8 修订链，单向）。
     *
     * @return 被更新的旧条目；旧/新任一不存在或新条目已撤回 ⇒ {@code null}
     */
    public ExperienceEntry supersede(String olderId, String newerId) {
        return store.supersede(olderId, newerId);
    }

    /** 当前被取代掉的 id 集合（E8 修订链；转发，供工具侧列出「哪些已过时」）。 */
    public java.util.Set<String> supersededIds() {
        return store.supersededIds();
    }

    /** 查经验：按当前任务/失败/异常的自然语言检索相关经验。 */
    public List<ExperienceHit> recall(String text, int limit, ExperienceMaturity minMaturity, List<String> tags) {
        return recall(text, limit, minMaturity, tags, PresentationReceipt.SURFACE_UNSPECIFIED, false);
    }

    /**
     * 查经验，<b>并记下「这批被呈现过」</b>（E7 前半段）。
     *
     * <p><b>为什么所有召回都必须走这一个方法</b>：三个召回点（召回工具 / L0 目录注入 /
     * 规划知识）此前都不记「谁被用过」，所以「使用结果回流」没有连接键 ——
     * {@code recordEvidence} 等不到人调。现在这一个方法就是汇聚点，
     * 谁走它谁留痕，不必逐个调用点各写一遍。</p>
     *
     * <p><b>⚠️ {@code reportable} 只有「AI 主动召回」才为 true。</b>
     * L0 目录与规划知识是每轮自动印在 prompt 里的，同伴很可能压根没看；
     * 让它们进待回报清单会产出满屏「本任务没有结论」的噪音。</p>
     */
    public List<ExperienceHit> recall(String text, int limit, ExperienceMaturity minMaturity,
                                      List<String> tags, String surface, boolean reportable) {
        // ★ E8：检索**只收 usable()**（排除已撤回 / 已被取代）。
        //   理由：recall 的下游是注入 —— 把一条判错的经验重新喂给同伴，
        //   比「少一条经验」危害大得多。而监测台要看全量视图，它走的是 all()。
        List<ExperienceHit> hits = retriever.retrieve(new ExperienceQuery(text, limit, minMaturity, tags), store.usable());
        store.receipts().record(hits, surface, reportable);
        return hits;
    }

    /** 本同伴的呈现回执表（E7：待回报清单 / 被用过次数）。 */
    public PresentationReceipt receipts() {
        return store.receipts();
    }

    /** E7：还没被回报过的呈现（只读清单，不写盘 —— 见 PresentationReceipt 类注释）。 */
    public List<PresentationReceipt.Shown> pendingReports() {
        return store.receipts().pendingReports();
    }

    /** E7：待回报条数（0 = 本局还没有 AI 主动召回过任何经验）。 */
    public int pendingCount() {
        return store.receipts().pendingCount();
    }

    /**
     * 还能当可信经验用的条目（E8：已撤回 / 已被取代的都不算）。
     *
     * <p>注入侧（{@code ExperienceDirectory}）与检索侧同用这一个读数，
     * 免得出现「检索不到但目录里列着」这种自相矛盾。</p>
     */
    public List<ExperienceEntry> usable() {
        return store.usable();
    }

    /** E8：已撤回的条数（供监测台/目录层如实报告，不让「有 22 条」掩盖「其中 3 条已撤回」）。 */
    public int retractedCount() {
        return store.retractedCount();
    }

    /** E8：被更新的条目取代掉的条数。 */
    public int supersededCount() {
        return store.supersededCount();
    }

    /** 全量经验（调试/交接用）。 */
    public List<ExperienceEntry> all() {
        return store.all();
    }

    public int size() {
        return store.size();
    }

    /**
     * 本次加载的读数（{@code loadedLine/loadedLegacy/loadedFailed/rekeyed/duplicate}）。
     *
     * <p><b>为什么要透传</b>：{@code store} 是 private，外部拿不到加载统计 ⇒
     * 注入侧与监测台此前只能<b>自己按磁盘形状猜</b>「能读出多少条」
     * （监测台 {@code chain-view.mjs} 就是这么干的），那不是代码的读数。
     * E6 的验收判据 A5 要求「注入侧报的条数 = store 条数」，
     * 面板要核这个数就得从代码里拿，而不是猜。</p>
     */
    public ExperienceStore.LoadStats loadStats() {
        return store.loadStats();
    }

    public ExperienceStats stats() {
        int verified = 0;
        int generalized = 0;
        // ★ E8：这里必须数 **usable()** 而不是 all()。
        //   这两个数会被 ExperienceDirectory 当 <verified>/<generalized> 注入给同伴看 ——
        //   把一条已被撤回/已被取代的经验算进「可信」，注入侧就在对同伴**报假事实**。
        //   total 仍用 store.size()（全量），于是 <total> 与 <verified> 的口径差是可查的：
        //   差值 = 已撤回 + 已被取代的条数，监测台能一眼看出来「22 条里只有 19 条还能用」。
        List<ExperienceEntry> usable = store.usable();
        for (ExperienceEntry e : usable) {
            if (e.maturity() == ExperienceMaturity.GENERALIZED) {
                generalized++;
            }
            if (e.maturity() == ExperienceMaturity.VERIFIED) {
                verified++;
            }
        }
        return new ExperienceStats(store.size(), verified, generalized,
                usable.size(), retractedCount(), supersededCount());
    }
}
