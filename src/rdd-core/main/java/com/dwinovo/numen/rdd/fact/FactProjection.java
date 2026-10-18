package com.dwinovo.numen.rdd.fact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快照的<b>投影</b>（第三批 N1，2026-10-05）。
 *
 * <p>计划要求：「简要/任务相关/详细视图为<b>可配置投影</b>，而非复制三份存储」。
 * 本类就是那个投影：<b>三种视图都是同一份 {@link FactSnapshot} 的派生计算</b>，
 * 快照本身不可变（{@link FactSnapshot} 里已 unmodifiable），所以
 * <b>投影在结构上不可能改变事实</b>。
 *
 * <p><b>★ 投影不吞三态</b>：简要视图里一条 {@code UNKNOWN} 仍然是 {@code UNKNOWN}，
 * 不会因为「摘要只留重要的」就变成「没有」或「0」。
 */
public final class FactProjection {

    /** 投影参数（可配置的部分都在这里，不散落在各视图里）。 */
    public record Options(int maxEntries, boolean includeStale, boolean includeEvidence) {
        public static Options brief() {
            return new Options(8, false, false);
        }

        public static Options task() {
            return new Options(16, true, true);
        }

        public static Options full() {
            return new Options(Integer.MAX_VALUE, true, true);
        }
    }

    /** 一条投影输出。 */
    public record Row(String stageKey, String rawStage, long observedAt,
                      FactSnapshot.Certainty certainty, String evidence, boolean truncated) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage_key", stageKey);
            m.put("raw_stage", rawStage);
            m.put("observed_at", observedAt);
            m.put("certainty", certainty.name());
            if (evidence != null && !evidence.isBlank()) {
                m.put("evidence", evidence);
            }
            m.put("truncated", truncated);
            return m;
        }
    }

    /** 用量投影输出（第三批 N1：把观测用量也投影出去，N2 直接消费）。 */
    public record UsageRow(String key, int value, boolean known) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", value);
            m.put("known", known);
            return m;
        }
    }

    /** 投影结果：行 + 「被截断了多少」。 */
    public record Result(List<Row> rows, int totalConsidered, int omitted, List<String> notes) {

        /** 投影是否因为预算而<b>丢过东西</b> —— 丢了就必须说出来，否则会被读成「就这些」。 */
        public boolean truncated() {
            return omitted > 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", rows.stream().map(Row::toMap).toList());
            m.put("total_considered", totalConsidered);
            m.put("omitted", omitted);
            m.put("truncated", truncated());
            m.put("notes", notes);
            return m;
        }
    }

    private FactProjection() {
    }

    /**
     * 简要投影：按最近发生取前 N 条。
     *
     * <p><b>被预算截断时必须报 {@code omitted}</b>：静默截断会把
     * 「我只看了 8 条」说成「一共就这么些」。
     */
    public static Result brief(FactSnapshot snapshot, Options options) {
        requireSnapshot(snapshot);
        Options opt = options == null ? Options.brief() : options;
        List<FactSnapshot.Entry> all = filterStale(snapshot.byRecency(), opt);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (i >= opt.maxEntries()) {
                break;
            }
            rows.add(toRow(all.get(i), opt, false));
        }
        List<String> notes = new ArrayList<>(snapshot.notes());
        if (all.size() > rows.size()) {
            notes.add("按预算截断：" + (all.size() - rows.size())
                    + " 条未进简要视图（**不是**「不存在」，要看全量请用 full 投影）");
        }
        return new Result(List.copyOf(rows), all.size(), Math.max(0, all.size() - rows.size()),
                List.copyOf(notes));
    }

    /**
     * 任务相关投影：只保留<b>明确点名</b>的 stageKey。
     *
     * <p>★ <b>点名了但事实里没有 ⇒ 输出 {@code UNKNOWN} 行</b>，不静默省略：
     * 「我要求看它，但不知道」与「我没要求看」是两件事。
     */
    public static Result forTask(FactSnapshot snapshot, List<String> wantedKeys, Options options) {
        requireSnapshot(snapshot);
        Options opt = options == null ? Options.task() : options;
        List<Row> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>(snapshot.notes());
        if (wantedKeys == null || wantedKeys.isEmpty()) {
            notes.add("没有点名任何 stageKey ⇒ 无内容可投影（不是「没有事实」）");
            return new Result(List.of(), 0, 0, List.copyOf(notes));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String key : wantedKeys) {
            if (key == null || key.isBlank() || !seen.add(key)) {
                continue;
            }
            FactSnapshot.Entry e = snapshot.lookup(key);
            if (e.certainty() == FactSnapshot.Certainty.STALE && !opt.includeStale()) {
                rows.add(toRow(e, opt, false));
                continue;
            }
            rows.add(toRow(e, opt, false));
        }
        int unknown = 0;
        for (Row r : rows) {
            if (r.certainty() == FactSnapshot.Certainty.UNKNOWN) {
                unknown++;
            }
        }
        if (unknown > 0) {
            notes.add("点名的 " + unknown + " 条在事实里没有记录 ⇒ 标为 UNKNOWN（**不是**「没做」）");
        }
        return new Result(List.copyOf(rows), rows.size(), 0, List.copyOf(notes));
    }

    /** 详细投影：全部条目，带证据。 */
    public static Result full(FactSnapshot snapshot) {
        requireSnapshot(snapshot);
        List<FactSnapshot.Entry> all = filterStale(snapshot.byRecency(), Options.full());
        List<Row> rows = new ArrayList<>();
        for (FactSnapshot.Entry e : all) {
            rows.add(toRow(e, Options.full(), false));
        }
        return new Result(List.copyOf(rows), all.size(), 0, List.copyOf(snapshot.notes()));
    }

    /**
     * 用量投影：把快照里的 observedUsage 全部投影出去。
     *
     * <p>这是给 N2/N3 消费的窄接口 —— 它们只需要拿用量表，不该自己去翻
     * {@link FactSnapshot#observedUsage()}。
     *
     * @return 每个键的用量 + 是否已知；快照没有用量来源时返回空列表（不是「用量全为 0」）
     */
    public static List<UsageRow> usage(FactSnapshot snapshot) {
        requireSnapshot(snapshot);
        if (!snapshot.usageKnown()) {
            return List.of();
        }
        List<UsageRow> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : snapshot.observedUsage().entrySet()) {
            rows.add(new UsageRow(e.getKey(), e.getValue(), true));
        }
        return List.copyOf(rows);
    }

    /**
     * 单消费者最小契约：查「某阶段是否已完成」，<b>三态返回</b>。
     *
     * <p>这是给 N2/N3 接上来用的窄接口 —— 它们只需要问一句事实，
     * 不该自己去翻 {@link FactSnapshot#entries()}。
     *
     * @return {@code KNOWN}/{@code STALE}/{@code UNKNOWN}，调用方<b>必须</b>区分
     */
    public static FactSnapshot.Certainty stageState(FactSnapshot snapshot, String stageKey, long now) {
        requireSnapshot(snapshot);
        FactSnapshot.Entry e = snapshot.lookup(stageKey);
        if (e.certainty() == FactSnapshot.Certainty.KNOWN
                && now - e.observedAt() > snapshot.ttlMillis()) {
            // 快照采集之后又过期的 —— 采集时判 KNOWN，读取时可能已 STALE
            return FactSnapshot.Certainty.STALE;
        }
        return e.certainty();
    }

    private static List<FactSnapshot.Entry> filterStale(List<FactSnapshot.Entry> all, Options opt) {
        if (opt.includeStale()) {
            return all;
        }
        List<FactSnapshot.Entry> out = new ArrayList<>();
        for (FactSnapshot.Entry e : all) {
            if (e.certainty() != FactSnapshot.Certainty.STALE) {
                out.add(e);
            }
        }
        return out;
    }

    private static Row toRow(FactSnapshot.Entry e, Options opt, boolean truncated) {
        return new Row(e.stageKey(), e.rawStage(), e.observedAt(), e.certainty(),
                opt.includeEvidence() ? e.evidence() : null, truncated);
    }

    private static void requireSnapshot(FactSnapshot s) {
        if (s == null) {
            // 不接受 null 快照：那会让「没有快照」与「快照里啥都没有」混起来
            throw new IllegalArgumentException("snapshot 不能为 null："
                    + "「没有快照」与「快照是空的」是两件事");
        }
    }
}