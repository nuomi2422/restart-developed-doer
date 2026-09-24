package com.dwinovo.numen.monitor;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Numen 的低侵入观测出口。单写线程、分区文件、有界队列；监测台不可用时不拖住游戏。
 *
 * <p>这是观察日志，不是任务状态库。消费者可以直接 tail {@code config/numen/monitor/}
 * 下按类别划分的 JSONL 文件，后续再由外部 Monitoring Adapter 转发到 AUI。</p>
 */
public final class MonitoringJournal implements AutoCloseable {
    public static final int DEFAULT_CAPACITY = 2048;
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static final MonitoringJournal INSTANCE = new MonitoringJournal();

    private record Entry(String category, String type, Map<String, Object> data, long time) {}

    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(DEFAULT_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private final Thread writerThread;
    private volatile Path root;
    private volatile long dropped;

    private MonitoringJournal() {
        writerThread = new Thread(this::drain, "numen-monitor-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public static MonitoringJournal get() {
        return INSTANCE;
    }

    /** 设置运行目录；默认使用 Numen 的统一 config/numen 目录。 */
    public synchronized void configure(Path configDir) {
        root = Objects.requireNonNull(configDir, "configDir").resolve("monitor");
    }

    /** 非阻塞投递；队列满时只丢弃观测，不阻塞 Numen 主流程。 */
    public void publish(String category, String type, Map<String, ?> data) {
        if (!running.get() || category == null || type == null) return;
        String safeCategory = safeToken(category);
        String safeType = safeToken(type);
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) copy.putAll(data);
        Entry entry = new Entry(safeCategory, safeType, copy, System.currentTimeMillis());
        if (!queue.offer(entry)) {
            dropped++;
        }
    }

    public long droppedCount() {
        return dropped;
    }

    public int queueSize() {
        return queue.size();
    }

    private void drain() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Entry entry = queue.poll(250, TimeUnit.MILLISECONDS);
                if (entry != null) write(entry);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-monitor] 写入观测失败: {}", ex.toString());
            }
        }
        closeWriters();
    }

    private synchronized void write(Entry entry) {
        Path base = root;
        if (base == null) {
            try {
                base = com.dwinovo.numen.NumenPaths.config().resolve("monitor");
                root = base;
            } catch (RuntimeException ex) {
                return;
            }
        }
        try {
            Files.createDirectories(base);
            BufferedWriter writer = writers.get(entry.category());
            Path file = base.resolve(entry.category() + ".jsonl");
            if (writer == null) {
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writers.put(entry.category(), writer);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", entry.category() + "-" + entry.time() + "-" + queue.size());
            envelope.put("timestamp", Instant.ofEpochMilli(entry.time()).toString());
            envelope.put("source", "numen");
            envelope.put("category", entry.category());
            envelope.put("type", entry.type());
            envelope.put("data", entry.data());
            writer.write(GSON.toJson(envelope));
            writer.newLine();
            writer.flush();
            if (Files.size(file) >= MAX_FILE_BYTES) rotate(writer, file, entry.category());
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-monitor] 观测文件不可写 {}: {}", entry.category(), ex.getMessage());
            closeWriter(entry.category());
        }
    }

    private void rotate(BufferedWriter writer, Path file, String category) throws IOException {
        writer.flush();
        writer.close();
        Path rotated = file.resolveSibling(category + "-" + System.currentTimeMillis() + ".jsonl");
        Files.move(file, rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        writers.remove(category);
    }

    private synchronized void closeWriter(String category) {
        BufferedWriter writer = writers.remove(category);
        if (writer != null) try { writer.close(); } catch (IOException ignored) {}
    }

    private synchronized void closeWriters() {
        for (BufferedWriter writer : writers.values()) {
            try { writer.close(); } catch (IOException ignored) {}
        }
        writers.clear();
    }

    private static String safeToken(String value) {
        String token = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return token.isEmpty() ? "unknown" : token.substring(0, Math.min(token.length(), 48));
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) writerThread.interrupt();
    }
}
