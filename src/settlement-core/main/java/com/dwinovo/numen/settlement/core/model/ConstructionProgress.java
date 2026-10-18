package com.dwinovo.numen.settlement.core.model;

import java.util.Map;

/**
 * 施工进度对账（第一层判据）——计数 + 生命周期账本。
 *
 * <p><b>这不是验收</b>：施工工具执行结束 ≠ 设施已建好 ≠ 已投入生产。这里只记"图纸要求多少、
 * 完成多少、掉了几格、主动跳了几格"，给补料和续建提供依据。是否"结构可用/能生产"另由
 * {@link com.dwinovo.numen.settlement.core.accept.AcceptanceEvaluator} 判。
 *
 * <p>2026-10-09 扩账（基建工具 V1）：加了 {@link #status} / {@link #template} /
 * {@link #anchor} / {@link #taskId} / {@link #missing} / {@link #message}。
 * 原因是用户点名的两条硬要求——「开工前就登记为施工中」（否则重启后半成品被当地图上的空地，
 * AI 会在同一位置创建第二座）和「任何中断后都知道这块地正在建什么、还差什么」。
 * 只有计数答不了这两个问题。
 *
 * <p><b>向后兼容</b>：旧 JSON 没有新字段 → Gson 传 null/0 → 紧凑构造器归一为
 * {@link ConstructionStatus#UNTRACKED} 与空账，老数据照常读得进来。
 */
public record ConstructionProgress(
        int completed,
        int skipped,
        int droppedAtLoad,
        int total,
        long checkedAtEpochMs,
        ConstructionStatus status,
        String template,
        DimAnchor anchor,
        String taskId,
        Map<String, Integer> missing,
        String message) {

    public ConstructionProgress {
        if (completed < 0 || skipped < 0 || droppedAtLoad < 0 || total < 0) {
            throw new IllegalArgumentException("construction counters must be non-negative");
        }
        status = status == null ? ConstructionStatus.UNTRACKED : status;
        missing = missing == null ? Map.of() : Map.copyOf(missing);
    }

    /**
     * 旧 5 参形态（计数-only，无施工账）。保留是因为它被单测与手工登记路径使用；
     * 语义 = {@link ConstructionStatus#UNTRACKED}，即"没走放置流程"。
     */
    public ConstructionProgress(int completed, int skipped, int droppedAtLoad, int total,
                                long checkedAtEpochMs) {
        this(completed, skipped, droppedAtLoad, total, checkedAtEpochMs,
                ConstructionStatus.UNTRACKED, null, null, null, Map.of(), null);
    }

    public static ConstructionProgress unknown() {
        return new ConstructionProgress(0, 0, 0, 0, 0L);
    }

    public static ConstructionProgress of(int completed, int skipped, int droppedAtLoad, int total, long at) {
        return new ConstructionProgress(completed, skipped, droppedAtLoad, total, at);
    }

    /** 新开一笔施工账（放置请求受理时立刻落，这就是"开工前先登记施工中"）。 */
    public static ConstructionProgress opened(String template, DimAnchor anchor, String taskId,
                                              int total, ConstructionStatus status, long at) {
        return new ConstructionProgress(0, 0, 0, Math.max(0, total), at, status, template, anchor,
                taskId, Map.of(), null);
    }

    /** 图纸要求的格子是否都已交代（建成或被明确跳过）。 */
    public boolean reconciled() {
        return total > 0 && completed + skipped >= total;
    }

    /** 还差多少格没交代（未建也未跳）。 */
    public int outstanding() {
        return Math.max(0, total - completed - skipped);
    }

    public boolean hasGaps() {
        return skipped > 0 || droppedAtLoad > 0;
    }

    /** 施工是否还开着（未收口）——放置请求的去重与"这块地正在建什么"都读它。 */
    public boolean isOpen() {
        return status.isOpen();
    }

    public ConstructionProgress withStatus(ConstructionStatus next, long at) {
        return new ConstructionProgress(completed, skipped, droppedAtLoad, total, at, next,
                template, anchor, taskId, missing, message);
    }

    public ConstructionProgress withCounters(int completed, int skipped, int droppedAtLoad,
                                             int total, long at) {
        return new ConstructionProgress(completed, skipped, droppedAtLoad, total, at, status,
                template, anchor, taskId, missing, message);
    }

    public ConstructionProgress withTask(String nextTaskId, long at) {
        return new ConstructionProgress(completed, skipped, droppedAtLoad, total, at, status,
                template, anchor, nextTaskId, missing, message);
    }

    /** 缺料账（续建要它；空 map 表示不缺料）。 */
    public ConstructionProgress withMissing(Map<String, Integer> nextMissing, String nextMessage, long at) {
        return new ConstructionProgress(completed, skipped, droppedAtLoad, total, at,
                nextMissing == null || nextMissing.isEmpty() ? status : ConstructionStatus.BLOCKED,
                template, anchor, taskId, nextMissing, nextMessage);
    }

    public ConstructionProgress withMessage(String nextMessage, long at) {
        return new ConstructionProgress(completed, skipped, droppedAtLoad, total, at, status,
                template, anchor, taskId, missing, nextMessage);
    }
}
