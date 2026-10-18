package com.dwinovo.numen.acx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.acx.api.AcxDefinition;

/**
 * 学习者 AC 草稿的<b>下游采纳口</b>（2026-10-04 夜间施工 · 第二批 B6/S2 的另一半）。
 *
 * <p><b>对应的上游</b>：{@code plugins/learner} 的 {@code ArtifactOutbox}。两个插件之间
 * <b>不能 import</b>（{@code numen-plugin.gradle} 刻意封死），所以契约是
 * {@code config/numen/artifact-outbox/AC_SCRIPT/<companion>/<artifact_id>.json} 这个
 * <b>文件形状</b>。改形状要同时改两边，本文件与上游各有契约测试。
 *
 * <p><b>采纳 = publish 成 GENERATED，永远不是上线。</b> 这是本类最重要的一条语义：
 * {@link FileAcxLibrary#approve} 是生产上线的唯一入口，它需要人调用。学习者产的草稿
 * 进库后状态是 GENERATED，要人工 {@code acx_approve} 才切 active ——
 * 与 {@code 63 §8}「游戏内 AI 写 AC 可以，但发布要过闸」一致。
 * <b>本类不调用 approve，也不该由任何自动路径调用它。</b>
 *
 * <p><b>失败必须落在产物记录里</b>：解析不了、静态校验不过、落库失败，都写回
 * {@code status=REJECTED} + 人能读的 reason，原文保留在 {@code body} 里等人或 AI 改。
 * 静默跳过 = 「学习者交了、看起来投了、其实没了」。
 */
public final class AcxArtifactAdopter {

    /** 一次最多采纳多少条（防目录很大时把启动卡住）。 */
    public static final int MAX_ADOPT = 50;

    /** 文件契约：与 learner 侧 {@code ArtifactOutbox} 一一对应。 */
    private static final String K_DIR = "AC_SCRIPT";
    private static final String K_STATUS = "status";
    private static final String K_BODY = "body";
    private static final String K_ID = "artifact_id";
    private static final String K_COMPANION = "companion_id";
    private static final String K_REVIEW = "review_id";
    private static final String K_MEMO = "memo_id";
    private static final String K_PENDING = "PENDING";

    /** 单条采纳结果。 */
    public record Adopted(String artifactId, String name, String version, String status, String detail) {}

    /** 一轮采纳的汇总。 */
    public record Report(int scanned, int adopted, int rejected, int skipped, List<Adopted> rows) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scanned", scanned);
            m.put("adopted", adopted);
            m.put("rejected", rejected);
            m.put("skipped", skipped);
            List<Map<String, Object>> r = new ArrayList<>();
            for (Adopted a : rows) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("artifact_id", a.artifactId());
                one.put("name", a.name());
                one.put("version", a.version());
                one.put("status", a.status());
                one.put("detail", a.detail());
                r.add(one);
            }
            m.put("rows", r);
            return m;
        }
    }

    private final Path outboxRoot;
    private final FileAcxLibrary library;
    private final Set<String> knownAcNames;

    public AcxArtifactAdopter(Path configDir, FileAcxLibrary library, Set<String> knownAcNames) {
        this.outboxRoot = configDir.resolve("artifact-outbox").resolve(K_DIR);
        this.library = library;
        this.knownAcNames = knownAcNames == null ? Set.of() : new HashSet<>(knownAcNames);
    }

    /** 待处理条数（给工具回执/运行时状态用，不触发采纳）。 */
    public int pendingCount() {
        List<Map<String, Object>> all = readAll();
        return all.size();
    }

    /**
     * 扫一遍投递箱并采纳。
     *
     * <p>幂等：只处理 {@code status=PENDING} 的条目，采纳成功后回填 {@code ADOPTED}，
     * 下次启动不会再取。同名同版本重复到达时按「已存在同内容」放过，不同内容则拒绝并说明。
     */
    public Report adoptAll() {
        List<Map<String, Object>> items = readAll();
        int adopted = 0;
        int rejected = 0;
        int skipped = 0;
        List<Adopted> rows = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (rows.size() >= MAX_ADOPT) {
                skipped++;
                continue;
            }
            String artifactId = str(it, K_ID);
            String body = str(it, K_BODY);
            if (body.isBlank()) {
                rejected++;
                rows.add(new Adopted(artifactId, "", "", "REJECTED", "投递记录里没有 body 字段"));
                writeBack(it, "REJECTED", "投递记录里没有 body 字段（学习者侧形状变了？）", "");
                continue;
            }
            AcxDefinition def;
            try {
                def = AcxLoader.parseJson(body, null);
            } catch (RuntimeException e) {
                rejected++;
                String why = "草稿不是可解析的 ACX 定义 JSON: " + e.getMessage();
                rows.add(new Adopted(artifactId, "", "", "REJECTED", why));
                writeBack(it, "REJECTED", why, "");
                continue;
            }
            // 名字/版本从定义里取，不信文件名，也不信上游给的标签
            String name = def.name();
            String version = def.version();
            if (name.isBlank() || version.isBlank()) {
                rejected++;
                String why = "定义里 name/version 为空（name=" + name + ", version=" + version + "）";
                rows.add(new Adopted(artifactId, name, version, "REJECTED", why));
                writeBack(it, "REJECTED", why, "");
                continue;
            }
            // 静态校验 problems 原样带出来：门禁拦下时人要能知道**为什么**
            List<String> problems = library.validate(def, knownAcNames);
            if (!problems.isEmpty()) {
                rejected++;
                // ★★ 2026-10-05 实机：AI 连三轮都在编 block 名（move / 空想动作），
                //   光说「找不到可用的注册块」它无从改 —— 因为**合法名单只有 ACX 自己知道**。
                //   把名单塞进拒收原因，下一轮经 RejectionFeedback 回喂时它就能照着改。
                //   这是把「盲猜」变成「按名单填」的最省力一刀。
                String why = "静态校验未通过: " + String.join("; ", problems)
                        + blockVocabularyHint();
                rows.add(new Adopted(artifactId, name, version, "REJECTED", why));
                writeBack(it, "REJECTED", why, "");
                continue;
            }
            try {
                String published = library.publish(def, noteFor(it), knownAcNames);
                adopted++;
                String detail = "已进版本库，状态 GENERATED；上线仍需人工 acx_approve";
                rows.add(new Adopted(artifactId, name, published, "ADOPTED", detail));
                writeBack(it, "ADOPTED", detail, name + "@" + published);
            } catch (RuntimeException e) {
                rejected++;
                String why = "入库失败: " + e.getMessage();
                rows.add(new Adopted(artifactId, name, version, "REJECTED", why));
                writeBack(it, "REJECTED", why, "");
            }
        }
        return new Report(items.size(), adopted, rejected, skipped, rows);
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private String noteFor(Map<String, Object> it) {
        return "学习者复审产物（review=" + str(it, K_REVIEW)
                + " memo=" + str(it, K_MEMO)
                + " companion=" + str(it, K_COMPANION)
                + "）；GENERATED 待人工批准";
    }

    /**
     * 合法 block 名单（挂在拒收原因后面，回喂给写稿子的模型）。
 *
 * <p>拿不到就返回空串 —— 不猜、不写死一份名单（写死的名单必然与运行时漂移，
 * 那比不报更坏：模型会照着一份过期的名单改）。
 */
    private String blockVocabularyHint() {
        List<String> names;
        try {
            var b = library.blocks();
            names = b == null ? List.of() : List.copyOf(b.names());
        } catch (RuntimeException e) {
            return "";
        }
        if (names.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(names);
        java.util.Collections.sort(sorted);
        // 名单可能很长，截断时明说截断了 —— 半个名单比「完整名单」更危险
        int cap = 60;
        String head = sorted.size() <= cap ? String.join(", ", sorted)
                : String.join(", ", sorted.subList(0, cap)) + " …(共 " + sorted.size() + " 个)";
        return " | ★可用 block 只能从这些名字里选（不要自创）: " + head;
    }

    private List<Map<String, Object>> readAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(outboxRoot)) {
            return out;
        }
        List<Path> companionDirs;
        try (var s = Files.list(outboxRoot)) {
            companionDirs = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return out;
        }
        for (Path c : companionDirs) {
            List<Path> files;
            try (var s = Files.list(c)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path f : files) {
                Map<String, Object> m = parseQuietly(f);
                if (m == null) {
                    continue;
                }
                m.put("_path", f.toString());
                if (K_PENDING.equals(str(m, K_STATUS))) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> parseQuietly(Path f) {
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            if (s == null || s.isBlank()) {
                return null;
            }
            return JsonlRecordStore.toMap(s);
        } catch (IOException | RuntimeException e) {
            // 坏文件不删不覆盖：它还在那儿，等人看。只跳过这一条。
            return null;
        }
    }

    /** 回填状态，保留 history 轨迹。写失败只记日志，不让一条坏回填中止整轮采纳。 */
    private void writeBack(Map<String, Object> item, String status, String detail, String consumerRef) {
        Path f = item.get("_path") == null ? null : Path.of(String.valueOf(item.get("_path")));
        if (f == null) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>(item);
        out.remove("_path");
        String now = Instant.now().toString();
        out.put(K_STATUS, status);
        out.put("status_detail", detail);
        if (consumerRef != null && !consumerRef.isBlank()) {
            out.put("consumer_ref", consumerRef);
        }
        out.put("updated_at", now);
        Object histObj = out.get("history");
        List<Object> hist = new ArrayList<>();
        if (histObj instanceof List<?> l) {
            hist.addAll(l);
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("at", now);
        h.put("status", status);
        h.put("detail", detail);
        hist.add(h);
        out.put("history", hist);
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, JsonlRecordStore.toJson(out), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.getLogger(AcxArtifactAdopter.class.getName()).log(System.Logger.Level.WARNING,
                    "[acx] 学习者草稿状态回填失败（产物已入库，但记录没更新）: " + e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
