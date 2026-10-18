package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 备忘录队列：干活 AI 往里写，学习者从里取。
 *
 * <p><b>关键不变量</b>（对应规格 §3「复盘失败不丢队列」）：
 * {@link #drain} 采用「先取快照 → 外部处理 → 成功才删除」的协议。
 * 真正删除只发生在 {@link #commitDrain}；失败时 {@link #restore} 把条目原样写回。
 * 也就是说「学习者清空队列」在语义上是「取走并复盘」，不是「丢掉」。
 *
 * <p>落盘 {@code config/numen/learner-memos-&lt;uuid&gt;.json}，内容是一个 JSON 数组
 * （<b>不是逐行 JSONL</b> —— 早期命名带 .jsonl 是误导，2026-09-29 已改正，
 * 因为全量重写无法保证「一行一条」的原子性）。
 * 写入用 temp + ATOMIC_MOVE 全量重写（抄 ExperienceStore 的做法）。
 *
 * <p>纯 JVM，可独立单测。
 */
public final class MemoQueue {

    /** 队列上限：超出拒收并回报，不静默丢。 */
    public static final int MAX_QUEUE = 64;

    /** 单条正文字段上限（problem/tried/snapshot 各算一份），防一条爆量把全量重写拖垮。 */
    public static final int MAX_FIELD_CHARS = 4000;

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Memo>>() {}.getType();

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();

    public MemoQueue(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** 当前队列深度。 */
    public int size() {
        lock.lock();
        try {
            return readAll().size();
        } finally {
            lock.unlock();
        }
    }

    /** 写一条备忘录。队列满、或字段超长时返回 false（调用方须回报，不要假装成功）。 */
    public boolean append(Memo memo) {
        if (oversized(memo)) {
            return false;
        }
        lock.lock();
        try {
            List<Memo> all = readAll();
            if (all.size() >= MAX_QUEUE) {
                return false;
            }
            all.add(memo);
            writeAll(all);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static boolean oversized(Memo m) {
        if (m == null) {
            return true;
        }
        return (m.problem() != null && m.problem().length() > MAX_FIELD_CHARS)
                || (m.tried() != null && m.tried().length() > MAX_FIELD_CHARS)
                || (m.snapshot() != null && m.snapshot().length() > MAX_FIELD_CHARS)
                || (m.stage() != null && m.stage().length() > 500);
    }

    /**
     * 取走最多 limit 条（不移除！）。
     *
     * <p>调用方复盘成功后必须 {@link #commitDrain}，失败必须 {@link #restore}。
     * 这样「清空队列」不等于「数据丢失」。
     */
    public List<Memo> drain(int limit) {
        lock.lock();
        try {
            List<Memo> all = readAll();
            if (limit <= 0 || all.isEmpty()) {
                return List.of();
            }
            return List.copyOf(all.subList(0, Math.min(limit, all.size())));
        } finally {
            lock.unlock();
        }
    }

    /** 复盘成功：把这批 memoId 真正从队列移除。 */
    public int commitDrain(List<Memo> taken) {
        lock.lock();
        try {
            List<Memo> all = readAll();
            List<Memo> kept = new ArrayList<>();
            for (Memo m : all) {
                boolean wasTaken = false;
                for (Memo t : taken) {
                    if (t.id() != null && t.id().equals(m.id())) {
                        wasTaken = true;
                        break;
                    }
                }
                if (!wasTaken) {
                    kept.add(m);
                }
            }
            writeAll(kept);
            return all.size() - kept.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 复盘失败：把没处理掉的条目原样写回队列尾部。
     *
     * <p>遵守 {@link #MAX_QUEUE}：restore 只补到上限，绝不为了「不丢数据」
     * 把队列撑爆（Codex 2026-09-29 审出：原实现无上限，可能突破 64）。
     * 超限的条目数会被返回，调用方据此报「仍有 N 条待处理」。
     */
    public int restore(List<Memo> taken) {
        lock.lock();
        try {
            List<Memo> all = readAll();
            int dropped = 0;
            for (Memo m : taken) {
                if (m == null || m.id() == null || oversized(m)) {
                    continue;
                }
                boolean present = false;
                for (Memo e : all) {
                    if (m.id().equals(e.id())) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    continue;
                }
                if (all.size() >= MAX_QUEUE) {
                    dropped++;
                    continue;
                }
                all.add(m);
            }
            writeAll(all);
            return all.size();
        } finally {
            lock.unlock();
        }
    }

    public List<Memo> all() {
        lock.lock();
        try {
            return List.copyOf(readAll());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读队列。
     *
     * <p><b>读取失败绝不按「空队列」继续</b>（2026-09-29 P1 修复，Codex 审出）：
     * 原实现在 IOException/解析异常时返回空 list，紧接着 {@code append} 的
     * 全量重写会把原文件覆盖掉 —— 一条读失败 = 全部备忘录静默丢失。
     * 现在读失败直接抛，调用方（工具层）会如实回报失败，坏文件原地保留待查。
     */
    private List<Memo> readAll() {
        try {
            if (!Files.exists(file)) {
                return new ArrayList<>();
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new ArrayList<>();
            }
            List<Memo> parsed = GSON.fromJson(raw, LIST_TYPE);
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "cannot read memo queue (file left untouched, nothing overwritten): " + file, e);
        }
    }

    private void writeAll(List<Memo> all) {
        // 临时名带 PID+纳秒：同一路径的多个进程/实例不会互相覆盖 tmp
        Path tmp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid()
                + "-" + System.nanoTime() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, GSON.toJson(all, LIST_TYPE), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件删不掉不该盖掉真正的失败原因
            }
            throw new IllegalStateException("cannot persist memo queue: " + file, e);
        }
    }
}
