package com.dwinovo.numen.rdd.fact;

import com.dwinovo.numen.rdd.core.AssetRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 共同事实的<b>不可变快照</b>（第三批 N1 地基，2026-10-05）。
 *
 * <p><b>为什么先做这个</b>：N2（重要度/损失累计）与 N3（机会匹配）都要读事实，
 * 而它们需要的正是三样东西 ——
 * <ol>
 *   <li><b>一个时刻的稳定视图</b>：事实库在读的过程中会被别的线程改，
 *       逐个 getter 读会拿到「半新半旧」的结果；</li>
 *   <li><b>三态而不是布尔</b>：<b>未知 ≠ 0 ≠ false</b>（DL-4），过期也不能当当前；</li>
 *   <li><b>投影而不是三份存储</b>：简要/任务相关/详细应该是<b>同一份快照的投影</b>，
 *       复制三份就必然漂移。</li>
 * </ol>
 *
 * <p><b>★ 同伴/维度隔离</b>：快照在构造时就绑定了 {@code companionId} 与
 * {@code dimensionId}，两者都是不可变字段 ⇒ 拿 A 同伴/主维度的快照去回答
 * B 同伴/下界的问题，<b>结构上做不到</b>（那需要另 capture 一次）。
 *
 * <p><b>★ 时钟可注入</b>（计划要求）：所有时间判断走构造时传入的 {@code now}，
 * 本类<b>不读系统时钟</b>。否则「过期」这条无法用单测钉住 —— 单测不能真的等 5 分钟。
 */
public final class FactSnapshot {

    /**
     * 事实的确定度 —— <b>三态，不是布尔</b>。
     *
     * <p>★ 这是 DL-4 在事实层的落点：用 boolean 表达「有/没有」会把
     * 「我们不知道」压成「没有」，然后下游据此得出「缺口 = 0」这种反向结论。
     */
    public enum Certainty {
        /** 事实已记录且未过期。 */
        KNOWN,
        /** <b>我们没有这条事实</b>（不是「事实是假的」，也不是「值为 0」）。 */
        UNKNOWN,
        /** 有记录，但已超出有效期 ⇒ <b>不能当当前使用</b>。 */
        STALE
    }

    /** 一条快照内的事实。 */
    public record Entry(String stageKey, String rawStage, long observedAt,
                        Certainty certainty, String evidence) {
    }

    private final UUID companionId;
    private final String dimensionId;
    private final long capturedAt;
    private final long ttlMillis;
    private final Map<String, Entry> entries;
    private final Map<String, Integer> quantities;
    private final Map<String, Integer> observedUsage;
    private final List<String> notes;

    private FactSnapshot(UUID companionId, String dimensionId, long capturedAt, long ttlMillis,
                         Map<String, Entry> entries, Map<String, Integer> quantities,
                         Map<String, Integer> observedUsage,
                         List<String> notes) {
        this.companionId = companionId;
        this.dimensionId = dimensionId;
        this.capturedAt = capturedAt;
        this.ttlMillis = ttlMillis;
        this.entries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.quantities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
        this.observedUsage = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observedUsage));
        this.notes = List.copyOf(notes);
    }

    /**
     * 采集一份快照。
     *
     * @param companionId    归属同伴；<b>必填</b>，不传就没有同伴隔离可言
     * @param dimensionId    归属维度；<b>必填且不许空串</b>（空串等于没隔离）
     * @param facts          共同事实（可为 null ⇒ 全部为 {@link Certainty#UNKNOWN}）
     * @param registry       资产登记（可为 null ⇒ 数量表为空，<b>不是 0</b>）
     * @param observedUsage  已观测的用量表（可为 null ⇒ 用量表为空，<b>不是 0</b>）
     * @param now            <b>当前时间由调用方注入</b>（单测据此钉住过期行为）
     * @param ttlMillis      事实有效期；超过则 {@link Certainty#STALE}
     */
    public static FactSnapshot capture(UUID companionId, String dimensionId,
                                       CompletedFactStore facts, AssetRegistry registry,
                                       Map<String, Integer> observedUsage,
                                       long now, long ttlMillis) {
        if (companionId == null) {
            throw new IllegalArgumentException("companionId 不能为空：没有同伴就没有隔离");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            // 空串会「看起来有维度」但实际不隔离 ⇒ 比 null 更危险，明确拒
            throw new IllegalArgumentException("dimensionId 不能为空串：空串等于没有维度隔离");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttl 必须为正：非正 TTL 会把所有事实判成过期");
        }
        List<String> notes = new ArrayList<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (facts == null) {
            notes.add("FACTS_UNAVAILABLE：没有共同事实来源 ⇒ 全部记为 UNKNOWN（不是「没有事实」）");
        } else {
            for (StageFact f : facts.stageFacts()) {
                if (f.stageKey() == null || f.stageKey().isBlank()) {
                    continue;
                }
                long age = Math.max(0L, now - f.completedAtMillis());
                Certainty c = age > ttlMillis ? Certainty.STALE : Certainty.KNOWN;
                entries.put(f.stageKey(), new Entry(f.stageKey(), f.rawStage(),
                        f.completedAtMillis(), c, f.evidence()));
            }
            if (entries.isEmpty()) {
                notes.add("NO_FACTS：事实库为空 ⇒ 查询结果一律 UNKNOWN，别读成「都没做」");
            }
        }

        Map<String, Integer> quantities = new LinkedHashMap<>();
        if (registry != null) {
            // ★ 只收 usableCounts()，不猜数量：UNKNOWN（没扫背包）时这张表是空的，
            //   而空表<b>不是</b>「持有量为 0」—— 见 quantitiesCertainty()。
            quantities.putAll(registry.usableCounts());
        } else {
            notes.add("NO_REGISTRY：没有资产登记 ⇒ 数量表为空（= 不知道，不是 0）");
        }

        Map<String, Integer> usageMap = new LinkedHashMap<>();
        if (observedUsage != null) {
            usageMap.putAll(observedUsage);
        } else {
            notes.add("NO_USAGE_SOURCE：没有用量来源 ⇒ 用量表为空（= 不知道，不是 0）");
        }

        return new FactSnapshot(companionId, dimensionId, now, ttlMillis,
                entries, quantities, usageMap, notes);
    }

    public UUID companionId() {
        return companionId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public long capturedAt() {
        return capturedAt;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    /** 全部条目（有序，不可修改）。 */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public List<String> notes() {
        return notes;
    }

    /** 数量表（可能为空 —— 空表示<b>不知道</b>，不是 0）。 */
    public Map<String, Integer> quantities() {
        return quantities;
    }

    /**
     * 观测到的用量表（可能为空 —— 空表示<b>不知道</b>，不是 0）。
     *
     * <p>★ DL-4 在用量维度的落点：没有记录 ≠ 用量为 0。调用方必须先问
     * {@link #usageKnown()} 再决定能不能把缺口算成 0。
     */
    public Map<String, Integer> observedUsage() {
        return observedUsage;
    }

    /** 用量维度是否<b>已知</b>（空表 ⇒ false）。 */
    public boolean usageKnown() {
        return !observedUsage.isEmpty();
    }

    /**
     * 查某键的观测用量。
     *
     * @return <b>不知道时返回 {@link OptionalInt#empty()}</b>（不是 0）。
     */
    public OptionalInt observedUsageOf(String key) {
        if (key == null) {
            return OptionalInt.empty();
        }
        Integer v = observedUsage.get(key);
        return v == null ? OptionalInt.empty() : OptionalInt.of(v);
    }

    /**
     * ★ 数量维度是否<b>已知</b>。
     *
     * <p>空表 ⇒ {@code false}。这是「空资产表不能直接推导缺口」那条红线
     * （DL-6）在快照层的落点：调用方必须先问这句，再决定能不能算缺口。
     */
    public boolean quantitiesKnown() {
        return !quantities.isEmpty();
    }

    /**
     * 查一条事实。
     *
     * @return <b>没有记录时返回 {@link Certainty#UNKNOWN} 的条目，而不是 {@code null}</b>
     *         —— 让「未知」在类型上就可见，调用方不容易漏判。
     */
    public Entry lookup(String stageKey) {
        Entry e = stageKey == null ? null : entries.get(stageKey);
        if (e != null) {
            return e;
        }
        return new Entry(stageKey == null ? "" : stageKey, null, 0L, Certainty.UNKNOWN, "");
    }

    /** 这条事实是否可当作「当前」用（KNOWN 且未过期）。 */
    public boolean isCurrent(String stageKey, long now) {
        Entry e = lookup(stageKey);
        if (e.certainty() != Certainty.KNOWN) {
            return false;
        }
        return now - e.observedAt() <= ttlMillis;
    }

    /** 按最近发生排序的可读摘要（投影的原料，不是给人看的成品）。 */
    public List<Entry> byRecency() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort((a, b) -> Long.compare(b.observedAt(), a.observedAt()));
        return List.copyOf(out);
    }

    /** 供投影与诊断：把快照摘要成一张小 map（不改快照本身）。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("companion_id", companionId.toString());
        m.put("dimension_id", dimensionId);
        m.put("captured_at", capturedAt);
        m.put("ttl_millis", ttlMillis);
        m.put("entry_count", entries.size());
        m.put("quantities_known", quantitiesKnown());
        m.put("usage_known", usageKnown());
        m.put("notes", notes);
        return m;
    }

    /** 便捷查询：某条事实的确定度。 */
    public Certainty certaintyOf(String stageKey) {
        return lookup(stageKey).certainty();
    }

    /** 便捷查询：某资产的数量；<b>不知道时返回 {@link Optional#empty()}</b>（不是 0）。 */
    public Optional<Integer> quantityOf(String assetKey) {
        return assetKey == null ? Optional.empty() : Optional.ofNullable(quantities.get(assetKey));
    }
}
