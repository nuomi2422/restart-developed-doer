package com.dwinovo.numen.plugins.rdd;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ B6：<b>事件链环形缓冲</b> —— 让「死亡那一刻整条链被冻结」这件事有东西可冻。
 *
 * <p><b>为什么需要它（60 号 {@code gap-no-death-snapshot}）</b>：
 * 用户要「<b>整个事情的链路都要冻结</b>」，而现状是每条 death 事件只带<b>最后一刻</b>的状态
 * （hp / pos / 装备），前面「怎么走到死的」没有任何留痕。
 * 死亡链上其实已经有两个半成品（{@code recordDeathLostHistory} 的 LOST 资产台账、
 * 「传整张台账而不是单个坐标」的掉落点传递），但它们都<b>只覆盖死亡前后那一小段</b>。</p>
 *
 * <p><b>为什么是「内存环形缓冲」而不是另建落盘文件</b>：
 * {@code instrumentation.jsonl} <b>本身就是持久历史</b>（16MB × 4 份轮转），它能回答
 * 「死之前发生了什么」—— 只要有人去按 seq 查。缺的是<b>死亡那一刻就把它一起带走</b>，
 * 免得事后翻日志时窗口已经滚掉、或要跨文件拼接。
 * 另建文件会引入新的<b>形状/损坏风险</b>（B1 的教训：文件形状不对 ⇒ 整个库被读成 0 条），
 * 所以这里<b>只在内存里留一个窗口</b>，随 death 事件一起序列化。</p>
 *
 * <p><b>⚠️ 容量 {@link #DEFAULT_CAPACITY} 未经实测。</b>
 * 取 32 是权衡值：够看清「一次任务失败前的动作序列」，又不至于把 death 那一行撑到读不动。
 * 与 {@code DEMOTE_AFTER_CONSECUTIVE_FAILURES=3}、{@code MAX_FINGERPRINT_TOKENS=0}
 * 同一纪律：<b>值本身要能被校准，所以它可配，且 {@link #capacity()} 是公开读数。</b></p>
 *
 * <p><b>⚠️ 快照<b>不是全量</b>，而且如实说</b>：
 * ①每个窗口条目只带 {@link #TRACE_FIELDS} 里那几个字段（<b>我自己挑的</b>，不是全量 data）——
 * 全量会把 death 行撑爆，要全量请查 jsonl；
 * ②窗口满了会挤掉最早的 ⇒ 快照带 {@code trace_truncated}，
 * <b>不让人把「窗口里有 32 条」误读成「只发生过 32 条」</b>（E8b {@code listed < total}
 * 那条诚实纪律的同款做法）。</p>
 */
public final class TraceRing {

    /** 默认窗口容量（<b>⚠️ 未经实测的权衡值</b>，见类注释）。 */
    public static final int DEFAULT_CAPACITY = 32;

    /**
     * 快照里每个窗口条目带哪些字段（<b>我自己挑的精简集</b>，不是全量 data）。
     *
     * <p>挑的是「能看出怎么走到死」的：是谁（companion）、做的是哪个子任务、
     * 关键结论（reason）、以及游戏 tick。<b>没有它就查不出因果</b>。</p>
     */
    public static final List<String> TRACE_FIELDS = List.of(
            "companionId", "primary", "task", "subtask", "reason", "goal");

    /** 一个窗口条目：<b>不可变快照</b>（推进时拷贝，不留引用）。 */
    public record Entry(long seq, String type, long gameTimeTicks, Map<String, Object> fields) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("type", type);
            m.put("game_time", gameTimeTicks);
            if (fields != null) {
                for (String k : TRACE_FIELDS) {
                    Object v = fields.get(k);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        m.put(k, v);
                    }
                }
            }
            return m;
        }
    }

    /**
     * 冻结出来的快照。
     *
     * @param entries     窗口里的条目（<b>旧的在前</b>：因果是从后往前读更顺，所以反过来排）
     * @param capacity    窗口容量（<b>必须一起报</b>，否则读的人不知道这是被截过的）
     * @param everTruncated 该窗口<b>是否曾经被挤满过</b>（true ⇒ 更早的历史没进窗口）
     */
    public record Snapshot(List<Entry> entries, int capacity, boolean everTruncated) {
        public int size() {
            return entries == null ? 0 : entries.size();
        }

        /** 序列化成可直接挂进事件 data 的 map。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (entries != null) {
                for (Entry e : entries) {
                    rows.add(e.toMap());
                }
            }
            m.put("recent_trace", rows);
            m.put("trace_len", rows.size());
            m.put("trace_capacity", capacity);
            // ★ 诚实口径：只要「曾经满过」，就明说「更早的历史不在这里」。
            //   不说的话，读的人会把 trace_len 当成「总共就发生了这么多事」。
            if (everTruncated || (entries != null && entries.size() >= capacity)) {
                m.put("trace_truncated", true);
                m.put("trace_note", "窗口只留最近 " + capacity
                        + " 条，更早的历史在 instrumentation.jsonl 里（按 seq 查）");
            }
            return m;
        }

        /** 空快照：没有任何可冻的内容时用它，**不返回 null**（免得调用方各处判 null 漏一处）。 */
        public static Snapshot empty() {
            return new Snapshot(List.of(), DEFAULT_CAPACITY, false);
        }
    }

    private final int capacity;
    private final Deque<Entry> ring;
    private boolean everTruncated;

    public TraceRing() {
        this(DEFAULT_CAPACITY);
    }

    public TraceRing(int capacity) {
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
        this.ring = new ArrayDeque<>(this.capacity);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return ring.size();
    }

    /** 窗口是否曾经被挤满过（= 更早的历史丢过）。 */
    public boolean everTruncated() {
        return everTruncated;
    }

    /**
     * 推进一条事件（<b>调用方必须在事件<b>真的写进 jsonl 之后</b>才调</b>）。
     *
     * <p>⚠️ 为什么要求「写成功才推进」：缓冲要回答的是「<b>已记录</b>的链」，
     * 如果写盘失败也推进，窗口里就会有「jsonl 里查不到的事件」⇒ 冻结出来的快照
     * 与落盘记录<b>不一致</b>，那比少一条更糟（失败事件本身有
     * {@code dropped_events_since_previous} 在自报告）。</p>
     */
    public synchronized void push(long seq, String type, long gameTimeTicks, Map<String, ?> data) {
        if (ring.size() >= capacity) {
            ring.pollFirst();
            everTruncated = true;
        }
        ring.addLast(new Entry(seq, type, gameTimeTicks, data == null ? Map.of() : new LinkedHashMap<>(data)));
    }

    /**
     * 冻结一份快照（<b>不消耗窗口</b>，可以反复取）。
     *
     * <p>返回<b>旧的在前</b>（从最早一条到最新一条）—— 读一条链的自然方向是
     * 「先发生什么、后发生什么」。</p>
     */
    public synchronized Snapshot snapshot() {
        // ⚠️ push 用的是 addLast ⇒ deque 的迭代顺序**本来就是「最老在前」**，这里绝不能再 reverse。
        //   反了两次的后果很隐蔽：容量、条数、trace_truncated 全对，只有因果顺序整个倒过来 ——
        //   读的人会以为「先 loop_detected 再 loop_detected 再 resource_waste」，
        //   于是照着一条倒着的链去复盘「怎么走到死的」，比没有快照更糟。
        return new Snapshot(List.copyOf(new ArrayList<>(ring)), capacity, everTruncated);
    }

    /** 只给测试/诊断看：当前窗口的内容（新的在前）。 */
    public synchronized List<Entry> entriesNewestFirst() {
        List<Entry> rows = new ArrayList<>(ring);
        java.util.Collections.reverse(rows);
        return List.copyOf(rows);
    }

    /** 清空窗口（换存档 / 测试隔离用）。 */
    public synchronized void clear() {
        ring.clear();
        everTruncated = false;
    }
}