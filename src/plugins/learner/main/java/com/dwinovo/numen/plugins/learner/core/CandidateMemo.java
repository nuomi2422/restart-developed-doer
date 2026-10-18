package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;

/**
 * 把一条被判为「该进候选队列」的事件转成一条 {@link Memo}。
 *
 * <p><b>为什么单独一个类</b>：这段映射是纯逻辑（只吃字符串与已压平的观测 map），
 * 原本写在工具里 —— 而工具要 NumenPlayer 才能跑，等于这段逻辑<b>一行都测不到</b>。
 * 「接口在、没人验证过」与「接口在、没人调用」是同一类哑故障，所以把它下沉到 core。
 *
 * <p><b>三条不许编的线</b>（B21：缺失就是缺失，不许用哨兵值伪装成数据）：
 * <ul>
 *   <li>{@code problem} 只复述事件<b>自带的</b>字段（类型 + 归因 + 任务），不加推测；</li>
 *   <li>{@code tried} <b>只在事件真的带了尝试信息时</b>才填（重试次数 / 协商阶段 / 死亡前事件链条数）。
 *       没有就<b>留空</b> —— 空着会让 {@code ExperienceQualityGate} 的 Q1 判 FAIL，
 *       但那是诚实的失败；编一句「它尝试过挖掘」会让那道门变成永远绿的摆设。</li>
 *   <li>{@code snapshot} 是压平后的观测事实，<b>不补一个自己造的 hp=0</b>。
 *       死亡确实意味着血量 0，但那是推断；Q3 需要 hp 才给 PASS，
 *       让它停在 UNDECIDABLE 比替它算出来更诚实。</li>
 * </ul>
 *
 * <p>纯 JVM，无 NUMEN/MC 依赖。
 */
public final class CandidateMemo {

    private CandidateMemo() {
    }

    /** 正文字段的截断上限（{@code MemoQueue} 的硬上限是 4000，这里留出余量不撞墙）。 */
    private static final int CLIP = 1500;

    /**
     * @param ev 源事件（要的是它的 {@code sourceType} 与压平后的 {@code observation}）
     * @param d  已有的入队判定（用它的 {@code primaryTrigger} 标注这条候选是凭什么进来的）
     */
    public static Memo from(FeedbackEvent ev, CandidateGate.Decision d) {
        Map<String, Object> obs = ev.observation();

        StringBuilder problem = new StringBuilder();
        problem.append('[').append(d.primaryTrigger()).append('/').append(ev.sourceType()).append("] ");
        int before = problem.length();
        appendIfPresent(problem, "task", firstPresent(obs, "task"));
        appendIfPresent(problem, "deathCauseId", firstPresent(obs, "deathCauseId"));
        appendIfPresent(problem, "deathAttacker", firstPresent(obs, "deathAttacker"));
        appendIfPresent(problem, "deathKind", firstPresent(obs, "deathKind"));
        appendIfPresent(problem, "reason", firstPresent(obs, "reason"));
        if (problem.length() == before) {
            // 一个归因字段都没有：如实说「没有可读归因」，不拿 eventId 之类的东西冒充内容
            problem.append("事件没有带任何归因字段（type=").append(ev.sourceType()).append("）");
        }

        StringBuilder tried = new StringBuilder();
        appendIfPresent(tried, "重试预算耗尽于第 N 次", firstPresent(obs, "context.attempts"));
        if (Boolean.parseBoolean(String.valueOf(firstPresent(obs, "context.fromNegotiation")))) {
            if (tried.length() > 0) {
                tried.append("；");
            }
            tried.append("来自协商阶段的失败");
        }
        Object traceCount = firstPresent(obs, "recent_trace.count");
        if (traceCount != null) {
            if (tried.length() > 0) {
                tried.append("；");
            }
            tried.append("死亡前事件链 ").append(traceCount).append(" 条（见 snapshot）");
        }

        StringBuilder snap = new StringBuilder();
        for (Map.Entry<String, Object> e : obs.entrySet()) {
            if (snap.length() > 0) {
                snap.append(", ");
            }
            snap.append(e.getKey()).append('=').append(String.valueOf(e.getValue()));
            if (snap.length() > CLIP) {
                snap.append("…[cut]");
                break;
            }
        }

        return new Memo("", problem.toString(), ev.sourceType(),
                tried.toString(), snap.toString(), 0L);
    }

    /**
     * 按<b>末段</b>取观测值（大小写无关、认点路径）。
     *
     * <p>为什么不能直接 {@code obs.get("deathCauseId")}：{@code FeedbackChannel.flatten}
     * 产出的键是<b>源事件自己的 camelCase 点路径</b>（{@code context.deathAttacker}），
     * 而生产方键名还会随源码改动而变。按末段取是唯一不会被这类改名悄悄打断的写法。
     */
    public static Object firstPresent(Map<String, Object> obs, String key) {
        if (obs == null || obs.isEmpty()) {
            return null;
        }
        String lowerKey = key.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, Object> e : obs.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (k.equals(lowerKey) || k.endsWith("." + lowerKey)) {
                Object v = e.getValue();
                if (v != null && !String.valueOf(v).isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static void appendIfPresent(StringBuilder sb, String label, Object raw) {
        if (raw == null) {
            return;
        }
        String v = String.valueOf(raw).trim();
        if (v.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" / ");
        }
        sb.append(label).append('=').append(v);
    }
}