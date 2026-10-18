package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.InstrumentationEvents;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * RDD 埋点统一事件通道（插件侧落地 · 埋点工单 2026-09-25）。
 *
 * <p>职责边界（先说死）：
 * <ul>
 *   <li><b>只做观测，不做决策。</b>所有产出是落盘事件 + 数据元字段，不改任何 Agent 行为逻辑；
 *       埋点层一旦影响行为就没法归因。全部写操作 try-catch fail-silent，<b>观测崩了 Agent 照常玩</b>。</li>
 *   <li>单一 JSONL（{@code config/numen/monitor/instrumentation.jsonl}），append-only，每行一个事件，
 *       带 {@code schema_version} 与{@code game_time}（游戏 tick）。信封拼装/解析在纯 JVM 的
 *       {@link InstrumentationEvents}，本类只管「安全落盘 + 限流 + 游戏时间 + 容错计数」。</li>
 *   <li><b>磁盘要算账</b>：高频事件（asset_mismatch / repeat_gather / resource_waste / loop_detected）
 *       按类型限流；单文件满 {@link #MAX_FILE_BYTES} 轮转并清理旧归档，保观测目录总量有界。</li>
 *   <li><b>不许夹带私货</b>：阈值只用于"该不该记录"，绝不回注 Agent 决策。</li>
 * </ul>
 *
 * <p>事件最小字段（在 data 内统一带）：{@code companionId}（关联同伴）、{@code task}（关联任务）、
 * {@code reason}、{@code context}（上下文快照）。六个事件 + 死亡 + instrumentation_change 的类型常量
 * 与 {@link InstrumentationEvents} 对齐。
 *
 * <p><b>修埋点本身也记一条 {@code instrumentation_change}</b>（丢数据类→随时修；格式/语义类→段间修，
 * 不中途动）。用法：改完埋点后调 {@link #change(String)}，分析端据此知道数据从哪个时刻换了尺子。
 */
public final class RddInstrumentation {

    public static final String DEATH = InstrumentationEvents.DEATH;
    public static final String STARVATION_DEATH = InstrumentationEvents.STARVATION_DEATH;
    public static final String LOOP_DETECTED = InstrumentationEvents.LOOP_DETECTED;
    public static final String REPEAT_GATHER = InstrumentationEvents.REPEAT_GATHER;
    public static final String RESOURCE_WASTE = InstrumentationEvents.RESOURCE_WASTE;
    public static final String ASSET_MISMATCH = InstrumentationEvents.ASSET_MISMATCH;
    public static final String RECOVERY_FAILED = InstrumentationEvents.RECOVERY_FAILED;
    public static final String INSTRUMENTATION_CHANGE = InstrumentationEvents.INSTRUMENTATION_CHANGE;

    private static final String FILE_NAME = "instrumentation.jsonl";
    private static final String ROTATED_PREFIX = "instrumentation-";
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ROTATED_FILES = 3;

    /** 噪音型事件按类型的限流冷却（毫秒）；缺省的型不额外限流。 */
    private static final Map<String, Long> COOLDOWN_MS = Map.of(
            ASSET_MISMATCH, 60_000L,
            REPEAT_GATHER, 30_000L,
            RESOURCE_WASTE, 30_000L,
            LOOP_DETECTED, 30_000L,
            RECOVERY_FAILED, 60_000L);

    /** 死亡→恢复失败的判定窗：死亡后多少 tick 内的重规划受阻算"恢复失败"。 */
    private static final long RECENT_DEATH_WINDOW_TICKS = 6_000L;   // 5 分钟 @ 20tps
    private static final Map<UUID, Long> RECENT_DEATH_TICKS = new ConcurrentHashMap<>();

    /**
     * ★ B6：<b>每同伴一个</b>事件链环形缓冲（死亡那一刻冻结快照用）。
     *
     * <p>⚠️ 为什么按同伴分组而不是全局一个：全局窗口会把不同同伴的事件混在一起，
     * 冻结出来的「怎么走到死的」就<b>掺了别人的因果</b>—— 那比没有更糟。</p>
     */
    private static final Map<String, TraceRing> TRACE_RINGS = new ConcurrentHashMap<>();

    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong SUPPRESSED = new AtomicLong();
    private static final AtomicLong WRITTEN = new AtomicLong();
    private static final Map<String, Long> LAST_EMIT_MS = new ConcurrentHashMap<>();
    /** 测试用：指定观测目录覆盖 FMLPaths（null = 用游戏目录）。 */
    private static volatile Path dirOverride;
    private static final AtomicLong EVENT_SEQ = new AtomicLong();
    /** 游戏时钟源（可注入；生产=服务端 tick，测试=桩）。 */
    private static volatile LongSupplier gameTimeSource = RddInstrumentation::serverGameTime;

    private RddInstrumentation() {}

    // ------------------------------------------------------------------ 埋点入口

    /** 记录一条事件；game_time 由服务端当前 tick 解析（服务端不存在→-1）。 */
    public static void publish(String type, Map<String, ?> data) {
        publishAt(type, data, currentGameTimeTicks());
    }

    /** 记录一条事件（显式游戏 tick；死亡/测试路径用）。 */
    public static void publishAt(String type, Map<String, ?> data, long gameTimeTicks) {
        if (!throttleAllows(type)) return;
        try {
            Path dir = targetDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_NAME);
            if (Files.exists(file) && Files.size(file) >= MAX_FILE_BYTES) {
                rotate(dir, file);
            }
            long seq = EVENT_SEQ.incrementAndGet();
            // ★ B6：死亡类事件先把「这个同伴最近发生了什么」冻结进同一行（快照本身不入窗口）。
            //   快照在推进窗口**之前**取，所以里面只有「死亡之前」的事 —— 这才是「怎么走到死的」。
            String companion = companionOf(data);
            TraceRing ring = ringFor(companion);
            Map<String, Object> dataWithDropped = enrich(data);
            if (isDeathType(type) && ring != null) {
                dataWithDropped.putAll(ring.snapshot().toMap());
            }
            String line = InstrumentationEvents.line("instr-" + seq, type, gameTimeTicks, dataWithDropped);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            WRITTEN.incrementAndGet();
            // ⚠️ 写成功才推进：缓冲回答的是「**已记录**的链」。
            //   写失败也推进会让快照里出现 jsonl 里查不到的事件（与落盘不一致，比少一条更糟）。
            if (ring != null) {
                ring.push(seq, type, gameTimeTicks, data);
            }
        } catch (Exception ignored) {
            DROPPED.incrementAndGet();   // fail-silent：观测崩了绝不影响主循环
        }
    }

    // ------------------------------------------------------------------ B6：事件链环形缓冲

    /** 哪些事件类型要冻结快照（死亡的那一刻）。 */
    private static boolean isDeathType(String type) {
        return DEATH.equals(type) || STARVATION_DEATH.equals(type);
    }

    /**
     * 从 event data 里取同伴标识；<b>取不到就返回 null</b>。
     *
     * <p>⚠️ 不做任何猜测/兜底（比如「拿 task 当同伴」）—— 猜出来的分组会让「这条链属于谁」
     * 变成假事实，而那正是这条链路要表达的东西。</p>
     */
    private static String companionOf(Map<String, ?> data) {
        if (data == null) return null;
        Object v = data.get("companionId");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static TraceRing ringFor(String companion) {
        if (companion == null) return null;
        return TRACE_RINGS.computeIfAbsent(companion, k -> new TraceRing());
    }

    /** 该同伴当前窗口里的条目数（诊断/测试读数；无窗口 = 0）。 */
    public static int traceRingSize(String companion) {
        TraceRing r = TRACE_RINGS.get(companion);
        return r == null ? 0 : r.size();
    }

    /** 取该同伴窗口的冻结快照（诊断/测试用；不消耗窗口）。 */
    public static TraceRing.Snapshot traceSnapshot(String companion) {
        TraceRing r = TRACE_RINGS.get(companion);
        return r == null ? TraceRing.Snapshot.empty() : r.snapshot();
    }

    /** 清掉某个同伴（或全部）的窗口 —— 换存档 / 测试隔离用。 */
    public static void clearTraceRings(String companion) {
        if (companion == null) {
            TRACE_RINGS.clear();
        } else {
            TRACE_RINGS.remove(companion);
        }
    }

    /** 修埋点本身也记事件（丢数据类可随时修；格式/语义类段间修）。 */
    public static void change(String what) {
        publishAt(INSTRUMENTATION_CHANGE,
                Map.of("what", what == null ? "no description" : what), currentGameTimeTicks());
    }

    // ------------------------------------------------------------------ 死亡追踪（供 recovery_failed 判定）

    static void recordDeathTick(UUID companionId, long gameTimeTicks) {
        if (companionId != null && gameTimeTicks >= 0) {
            RECENT_DEATH_TICKS.put(companionId, gameTimeTicks);
        }
    }

    static boolean recentDeath(UUID companionId, long nowGameTimeTicks) {
        if (companionId == null || nowGameTimeTicks < 0) return false;
        Long at = RECENT_DEATH_TICKS.get(companionId);
        return at != null && nowGameTimeTicks - at >= 0 && nowGameTimeTicks - at <= RECENT_DEATH_WINDOW_TICKS;
    }

    // ------------------------------------------------------------------ 内部

    private static boolean throttleAllows(String type) {
        Long cooldown = COOLDOWN_MS.get(type);
        if (cooldown == null || cooldown <= 0) return true;
        long now = System.currentTimeMillis();
        Long last = LAST_EMIT_MS.putIfAbsent(type, now);
        if (last != null) {
            if (now - last < cooldown) {
                SUPPRESSED.incrementAndGet();
                return false;
            }
            LAST_EMIT_MS.put(type, now);
        }
        return true;
    }

    private static Map<String, Object> enrich(Map<String, ?> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (data != null) out.putAll(data);
        long dropped = DROPPED.getAndSet(0);   // 上一次写失败的包袱，挂在本次成功事件上，自报告不自弃
        if (dropped > 0) out.put("dropped_events_since_previous", dropped);
        return out;
    }

    private static Path targetDir() {
        if (dirOverride != null) return dirOverride;
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
    }

    /** 服务端当前游戏 tick；无服务端/异常 → -1（测试与手测环境）。 */
    static long currentGameTimeTicks() {
        try {
            return gameTimeSource.getAsLong();
        } catch (Throwable t) {
            // 时钟源不可用（含 neoforge 类不在场）→ 回落 -1，绝不让埋点炸掉主循环
            return -1L;
        }
    }

    private static long serverGameTime() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.overworld().getGameTime();
        return -1L;
    }

    private static void rotate(Path dir, Path file) throws IOException {
        Files.move(file, dir.resolve(ROTATED_PREFIX + System.currentTimeMillis() + ".jsonl"),
                StandardCopyOption.REPLACE_EXISTING);
        prune(dir);
    }

    private static void prune(Path dir) throws IOException {
        List<Path> stale;
        try (var stream = Files.list(dir)) {
            stale = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(ROTATED_PREFIX) && name.endsWith(".jsonl");
                    })
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .skip(MAX_ROTATED_FILES)
                    .toList();
        }
        for (Path p : stale) Files.deleteIfExists(p);
    }

    // ------------------------------------------------------------------ 测试观察口

    static long droppedEvents() { return DROPPED.get(); }
    static long suppressedEvents() { return SUPPRESSED.get(); }
    static long writtenEvents() { return WRITTEN.get(); }
    static void setDirOverrideForTest(Path dir) { dirOverride = dir; }
    static void setGameTimeSourceForTest(LongSupplier source) { gameTimeSource = source; }
    static void resetForTest() {
        DROPPED.set(0);
        SUPPRESSED.set(0);
        WRITTEN.set(0);
        EVENT_SEQ.set(0);
        LAST_EMIT_MS.clear();
        RECENT_DEATH_TICKS.clear();
        TRACE_RINGS.clear();   // ★ B6：漏掉这条会让「上一个测试的链」漏进下一个测试的快照
        dirOverride = null;
        gameTimeSource = RddInstrumentation::serverGameTime;
    }
}