package com.dwinovo.numen.plugins.learner.core;

import java.util.List;
import java.util.Map;

/**
 * 一条「执行结果」的只读反馈（{@code 38} v3 §2.4 / v3.2 B22）。
 *
 * <p><b>方向是单向的</b>：干活侧写任务链事件 → 学习者<b>只读</b>。
 * 本 record 及其构造通道<b>没有任何回写方法</b>（编译期保证，不是约定）。
 *
 * <p><b>B21（缺失的表达方式）在本 record 上的具体含义</b>：
 * <ul>
 *   <li>{@code observation} 里<b>不出现</b>就表示「这条事件没有这个观测项」，
 *       <b>不许</b>用 {@code -1} / {@code ""} / {@code ["无"]} 冒充；</li>
 *   <li>{@code observationSummary} 允许为 {@code null} ——
 *       第 1 批<b>刻意留空</b>，第 2 批的「沉淀」才填。
 *       「留空」是诚实的表达，编一句「看起来像结论」的话不是（GPT-6：叫 verdict 会诱导夹带评价）。</li>
 * </ul>
 *
* @param eventId            幂等标识；去重靠它
 * @param generation         世界代际。<b>注意</b>：只读通道拿不到宿主，
 *                           这里用「存档名 + session.lock mtime」作为<b>存档级代际</b>，
 *                           <b>不是</b> {@code ServerLifecycleHooks} 的真代际（v3.2 §2 已如实标注）
 * @param ts                 事件时间（源文件里的原值，不做时区换算，避免换算错）
 * @param sourceType         <b>源事件的原始 {@code type} 字符串</b>。2026-10-03（E1 批次）新增：
 *                           只有 {@link #kind} 家族时「饿死」与「被打死」长得一样，
 *                           而 {@code RddPlugin.java:684-696} 特意把两者拆成两个 type
 *                           就是因为「归因方向相反」—— 压成同一个 kind 等于把那个信息扔掉。
 *                           kind 回答「属于哪一族」，sourceType 回答「到底发生了什么」。
 * @param kind               归类：任务链 / 战斗 / 死亡 / 资产 / 规划
 * @param subjectRef         <b>批次身份 + 数量</b>（{@code RL-15} 的纪律）。键缺失就是缺失，不填默认值
 * @param observation        世界可查事实（源事件 {@code data} 原样）
 * @param observationSummary 自由文本补充；<b>第 1 批恒为 {@code null}</b>
 */
public record FeedbackEvent(
        String eventId,
        String generation,
        String ts,
        String sourceType,
        String kind,
        Map<String, Object> subjectRef,
        Map<String, Object> observation,
        String observationSummary
) {

    public FeedbackEvent {
        eventId = eventId == null ? "" : eventId;
        generation = generation == null ? "" : generation;
        ts = ts == null ? "" : ts;
        sourceType = sourceType == null ? "" : sourceType;
        kind = kind == null ? "UNKNOWN" : kind;
        subjectRef = subjectRef == null ? Map.of() : Map.copyOf(subjectRef);
        observation = observation == null ? Map.of() : Map.copyOf(observation);
    }

    /** 只读渲染，供工具返回值与观测用。**不新增任何推断字段。** */
    public Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("event_id", eventId);
        m.put("generation", generation);
        m.put("ts", ts);
        // sourceType 恒在（缺就是空串）：「是哪一类事件」和「属于哪一族」是两件事，
        // 只给 kind 会让 starvation_death 与 death 在下游长成同一条。
        m.put("source_type", sourceType);
        m.put("kind", kind);
        m.put("subject_ref", subjectRef);
        m.put("observation", observation);
        // observationSummary 为 null 时**不放这个键**，而不是放 "" 或 "无"（B21）
        if (observationSummary != null && !observationSummary.isBlank()) {
            m.put("observation_summary", observationSummary);
        }
        return m;
    }

    /** 判据用：这条事件有没有值得看的观测（防止把空事件当成「有反馈」）。 */
    public boolean hasSubstance() {
        return !observation.isEmpty() || !subjectRef.isEmpty();
    }

    /** 便捷：kind 是否属于给定集合。 */
    public boolean isKind(String... kinds) {
        for (String k : kinds) {
            if (k.equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /** 便捷：列出 kind 家族，供工具按需过滤。 */
    public static List<String> kindFamilies() {
        return List.of("task", "combat", "death", "asset", "planning");
    }
}