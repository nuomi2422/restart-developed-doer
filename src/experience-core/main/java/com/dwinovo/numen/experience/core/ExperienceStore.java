package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 经验的 JSONL 持久化（一行一条 {@link ExperienceEntry}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>按稳定键去重</b>：同 {@code id} 的经验只合并，不无限追加——防止旧 DD 18K 条
 *       “同失败刷屏”问题复发。</li>
 *   <li><b>原子落盘</b>：每次变更写临时文件 + {@code ATOMIC_MOVE} 替换，不半写坏文件。</li>
 *   <li><b>best-effort</b>：IO 失败只记日志、保留内存态，绝不让经验写入拖垮宿主。</li>
 *   <li><b>线程安全</b>：本类所有变更方法 {@code synchronized}；内存镜像 + 全量重写，
 *       对经验这种小数据量是正确的换复杂度。</li>
 * </ul>
 *
 * <p>经验何时升级成熟度（详见 {@link #recordEvidence}）：真实成功累计证据；
 * 失败只加反例、最多把 OBSERVED 推到 ATTEMPTED，不降级已确认经验。
 */
public final class ExperienceStore {

    private static final Logger LOG = LoggerFactory.getLogger(ExperienceStore.class);

    /** 多少次真实成功从 VERIFIED 升级到 GENERALIZED。 */
    public static final int GENERALIZED_THRESHOLD = 3;
    /** 反例最多保留几条，防列表无限膨胀。 */
    public static final int MAX_COUNTEREXAMPLES = 5;

    private final Path file;
    private final List<ExperienceEntry> mirror = new ArrayList<>();
    private boolean loaded;

    private ExperienceStore(Path file) {
        this.file = file;
    }

    public static ExperienceStore at(Path file) {
        return new ExperienceStore(file);
    }

    public Path file() {
        return file;
    }

    /** 全部经验（插入序快照）。 */
    public synchronized List<ExperienceEntry> all() {
        ensureLoaded();
        return List.copyOf(mirror);
    }

    public synchronized int size() {
        ensureLoaded();
        return mirror.size();
    }

    /**
     * 写入或合并一条经验。同 {@code id} 已存在时合并：描述类字段取新值、触发词/工具/标签
     * 并集、成熟度与验证次数取更高者——合并绝不清掉已积累的证据。
     *
     * @return 实际落库的那条（可能是合并结果）
     */
    public synchronized ExperienceEntry learn(ExperienceEntry entry) {
        if (entry == null || entry.type() == null || entry.title() == null || entry.title().isBlank()
                || entry.description() == null || entry.description().isBlank()) {
            throw new IllegalArgumentException("experience requires type, title and description");
        }
        ensureLoaded();
        ExperienceEntry normalized = entry.id() == null || entry.id().isBlank()
                ? ExperienceEntry.builder().from(entry)
                .id(ExperienceEntry.stableKey(entry.type(), entry.title())).build()
                : entry;

        int index = indexOf(normalized.id());
        ExperienceEntry stored;
        if (index < 0) {
            stored = normalized;
            mirror.add(stored);
            LOG.info("[experience] learned '{}' ({})", stored.title(), stored.type());
        } else {
            stored = merge(mirror.get(index), normalized);
            mirror.set(index, stored);
            LOG.info("[experience] merged '{}' (now {})", stored.title(), stored.maturity());
        }
        persist();
        return stored;
    }

    /**
     * 记录一次真实世界的验证结果。
     *
     * <pre>{@code
     * success=true ：verifiedCount++；成熟度按证据累计升级
     *               (OBSERVED/ATTEMPTED→VERIFIED，verifiedCount≥3→GENERALIZED)
     * success=false：追加反例（封顶 MAX_COUNTEREXAMPLES）；OBSERVED→ATTEMPTED；不降级
     * }</pre>
     *
     * @return 更新后的经验；id 不存在返回 {@code null}
     */
    public synchronized ExperienceEntry recordEvidence(String id, boolean success, String note) {
        ensureLoaded();
        int index = indexOf(id);
        if (index < 0) {
            return null;
        }
        ExperienceEntry old = mirror.get(index);
        List<String> counterexamples = old.counterexamples();
        ExperienceMaturity maturity = old.maturity();
        int verifiedCount = old.verifiedCount();
        long verifiedAt = old.verifiedAt();

        if (success) {
            verifiedCount++;
            verifiedAt = System.currentTimeMillis();
            maturity = maturityFor(verifiedCount);
            // 永不降级：新证据只升不降。
            if (maturity.level() < old.maturity().level()) {
                maturity = old.maturity();
            }
        } else {
            String reason = (note == null || note.isBlank()) ? "unconfirmed" : note;
            Set<String> seen = new LinkedHashSet<>(counterexamples);
            seen.add(reason);
            if (seen.size() > MAX_COUNTEREXAMPLES) {
                seen.remove(seen.iterator().next());
            }
            counterexamples = List.copyOf(seen);
            if (old.maturity().level() < ExperienceMaturity.ATTEMPTED.level()) {
                maturity = ExperienceMaturity.ATTEMPTED;
            }
        }

        ExperienceEntry updated = old.withEvidence(maturity, verifiedCount, verifiedAt, counterexamples);
        mirror.set(index, updated);
        persist();
        return updated;
    }

    // ---- internals ----

    /** 按验证次数推出成熟度（成功视角）。 */
    private static ExperienceMaturity maturityFor(int verifiedCount) {
        if (verifiedCount >= GENERALIZED_THRESHOLD) {
            return ExperienceMaturity.GENERALIZED;
        }
        if (verifiedCount >= 1) {
            return ExperienceMaturity.VERIFIED;
        }
        return ExperienceMaturity.ATTEMPTED;
    }

    private int indexOf(String id) {
        for (int i = 0; i < mirror.size(); i++) {
            if (mirror.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private ExperienceEntry merge(ExperienceEntry old, ExperienceEntry fresh) {
        return ExperienceEntry.builder()
                .id(old.id())
                .type(fresh.type() == null ? old.type() : fresh.type())
                .title(fresh.title())
                .description(fresh.description())
                .rationale(blank(fresh.rationale()) ? old.rationale() : fresh.rationale())
                .rootCause(blank(fresh.rootCause()) ? old.rootCause() : fresh.rootCause())
                .recommendedResponse(blank(fresh.recommendedResponse()) ? old.recommendedResponse() : fresh.recommendedResponse())
                .triggerStrings(union(old.triggerStrings(), fresh.triggerStrings()))
                .toolNames(union(old.toolNames(), fresh.toolNames()))
                .tags(union(old.tags(), fresh.tags()))
                .maturity(ExperienceMaturity.max(old.maturity(), fresh.maturity()))
                .verifiedCount(Math.max(old.verifiedCount(), fresh.verifiedCount()))
                .priority(Math.max(old.priority(), fresh.priority()))
                .counterexamples(fresh.counterexamples().isEmpty() ? old.counterexamples() : fresh.counterexamples())
                .createdAt(old.createdAt())
                .verifiedAt(fresh.verifiedAt() > 0 ? fresh.verifiedAt() : old.verifiedAt())
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> seen = new LinkedHashSet<>(a);
        seen.addAll(b);
        return List.copyOf(seen);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                        ExperienceEntry e = ExperienceEntry.fromJson(o);
                        if (e.id() == null || e.id().isBlank() || e.title() == null || e.title().isBlank()) {
                            continue;
                        }
                        if (indexOf(e.id()) < 0) {
                            mirror.add(e);
                        }
                    } catch (RuntimeException ex) {
                        LOG.warn("[experience] skipping unparsable line in {}: {}", file.getFileName(), ex.toString());
                    }
                }
            } catch (IOException ex) {
                LOG.warn("[experience] failed to read {}: {}", file, ex.toString());
            }
        }
        loaded = true;
    }

    /** 全量原子重写：temp 文件 → ATOMIC_MOVE 替换。 */
    private void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder(mirror.size() * 128);
            for (ExperienceEntry e : mirror) {
                sb.append(e.toJson()).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            LOG.warn("[experience] failed to persist {}: {}", file, ex.toString());
        }
    }
}
