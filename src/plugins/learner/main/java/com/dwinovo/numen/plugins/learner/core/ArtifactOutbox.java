package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学习者产物的<b>持久投递箱</b>（2026-10-04 夜间施工 · 第二批 B6/S2）。
 *
 * <p><b>为什么需要它</b>：{@code Verdict} 上的 {@code acScriptDraft} / {@code carrierDraft} /
 * {@code selfCompileRequest} 三个载荷位自 2026-10-03 起就存在，但此前<b>只出现在回执与
 * learner.jsonl 里</b>，没有任何模块消费 —— 「学习者说要写，却写完就没了下文」。
 * 本类给它们一个<b>真的落点</b>：一条产物 = 一个 JSON 文件，可查、可重试、可被下游读。
 *
 * <p><b>为什么不直接调下游插件</b>：{@code numen-plugin.gradle} 刻意封死跨插件 import
 * （{@code NumenApi} 的 javadoc 也写明「规划器和知识提供方是两个互不可见的插件，
 * 联动之间只看这扇门」）。所以跨插件的数据流动只走三条既有通道：共享 {@code configDir()}、
 * 大模型可调的工具、{@code CompanionEvent}。本类走第一条 —— 它是这三条里唯一
 * 「不依赖任何人记得调用」的一条。
 *
 * <p><b>投递 ≠ 生效。</b> 写进投递箱只是「交出去了」。下游是否采纳、是否通过校验，
 * 由 {@link #mark} 回填；本类<b>永不</b>把任何产物置为已上线。
 *
 * <p><b>幂等</b>：{@code artifact_id = kind + 同伴 + memoId + 正文摘要}。同一份草稿
 * 重复提交返回 {@code DUPLICATE} 而不是再写一份 —— 复盘重试、进程重启后重扫，
 * 都不会把同一条产物投成两条。
 *
 * <p><b>纯 JVM</b>：不引用 Minecraft 任何类，因此可被单测直接跑。
 */
public final class ArtifactOutbox {

    private static final Gson GSON = new Gson();

    /** 正文上限：超过就是「这不是草稿」，截断会把错误藏起来，直接拒收并说明原因。 */
    public static final int MAX_BODY_CHARS = 256 * 1024;

    /** 单类产物一次最多读多少条（防止目录长到几千个文件时把主线程卡住）。 */
    public static final int MAX_SCAN = 200;

    /** 产物类别。与 {@code Verdict.Action} 一一对应，不另造第二套分类。 */
    public enum Kind {
        AC_SCRIPT("AC_SCRIPT"),
        CARRIER("CARRIER"),
        SELF_COMPILE_REQUEST("SELF_COMPILE_REQUEST");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Kind fromWire(String s) {
            for (Kind k : values()) {
                if (k.wire.equals(s)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** 投递箱里的状态。PENDING = 已交出去、等下游表态。 */
    public enum Status {
        PENDING,
        ADOPTED,
        REJECTED,
        FAILED
    }

    /**
     * 一次投递的结果。
     *
     * @param artifactId  产物身份（幂等键）
     * @param status      投递结果：LANDED / DUPLICATE / REJECTED / FAILED
     * @param detail      人能读的说明；失败时<b>必须</b>有，不许空
     * @param path        落盘路径（DUPLICATE 时是已存在那条）
     */
    public record Delivery(String artifactId, String status, String detail, String path) {
        public boolean landed() {
            return "LANDED".equals(status);
        }

        public boolean duplicate() {
            return "DUPLICATE".equals(status);
        }
    }

    private final Path root;

    public ArtifactOutbox(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * 提交一条产物。
     *
     * @param kind    哪一类产物
     * @param companionId 同伴；<b>不传 null</b> —— 不按同伴隔离的产物算串数据
     * @param reviewId  哪一轮复审产出的（观测与追责用）
     * @param memoId    哪条备忘录产出的（回溯到现场材料用）
     * @param name      产物名（AC 用脚本名；另两类是标签）
     * @param body      产物正文
     */
    public Delivery submit(Kind kind, UUID companionId, String reviewId, String memoId,
                           String name, String body) {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为 null");
        }
        if (companionId == null) {
            throw new IllegalArgumentException("companionId 不能为 null：产物必须按同伴隔离");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body 为空：没有内容就不该声称「已产出」");
        }
        if (body.length() > MAX_BODY_CHARS) {
            return new Delivery("", "REJECTED",
                    "正文 " + body.length() + " 字符超过上限 " + MAX_BODY_CHARS + "，已拒收（截断会把错误藏起来）",
                    "");
        }
        String hash = hash16(body);
        String safeName = sanitize(name == null || name.isBlank() ? kind.wire() : name);
        String artifactId = kind.wire() + "-" + companionId + "-" + sanitize(memoId) + "-" + hash;

        Path dir = root.resolve(kind.wire()).resolve(companionId.toString());
        Path file = dir.resolve(artifactId + ".json");

        if (Files.exists(file)) {
            // 幂等：同一份草稿再投一次就说清楚是重复，不写第二份。
            // 但要核一下落盘的那条是不是同内容 —— 文件名带摘要，理论上不会撞；
            // 真撞了就如实报出来，不当成成功。
            String existing = readString(file);
            if (existing != null && existing.contains(hash)) {
                return new Delivery(artifactId, "DUPLICATE", "这份草稿已经投过了（幂等，未重复写）",
                        file.toString());
            }
            return new Delivery(artifactId, "FAILED",
                    "同名产物已存在但内容摘要不同，拒绝覆盖（可能内容被手改过，需人工判）",
                    file.toString());
        }

        String now = Instant.now().toString();
        JsonObject o = new JsonObject();
        o.addProperty("artifact_id", artifactId);
        o.addProperty("kind", kind.wire());
        o.addProperty("companion_id", companionId.toString());
        o.addProperty("review_id", reviewId == null ? "" : reviewId);
        o.addProperty("memo_id", memoId == null ? "" : memoId);
        o.addProperty("name", safeName);
        o.addProperty("body", body);
        o.addProperty("content_hash", hash);
        o.addProperty("status", Status.PENDING.name());
        o.addProperty("status_detail", "已交出，等下游表态");
        o.addProperty("consumer_ref", "");
        o.addProperty("submitted_at", now);
        o.addProperty("updated_at", now);
        JsonArrayShim.history(o, now, Status.PENDING.name(), "submitted");

        try {
            writeAtomic(file, GSON.toJson(o));
        } catch (IOException e) {
            // 落盘失败必须看得见：返回 FAILED + 原因，不吞成「已投递」
            return new Delivery(artifactId, "FAILED", "投递箱落盘失败: " + e, "");
        }
        return new Delivery(artifactId, "LANDED", "已写入投递箱（尚未生效）", file.toString());
    }

    /** 下游表态后回填状态；保留 history，不覆盖过程。 */
    public void mark(String artifactId, Status status, String detail, String consumerRef) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId 不能为空");
        }
        if (status == null || status == Status.PENDING) {
            throw new IllegalArgumentException("mark 只能写终态（PENDING 是初始态，不是回填结果）");
        }
        Path file = locate(artifactId);
        if (file == null) {
            throw new IllegalArgumentException("投递箱里没有产物 " + artifactId);
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(readString(file)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("产物 " + artifactId + " 的记录无法解析，拒绝改写: " + e.getMessage(), e);
        }
        String now = Instant.now().toString();
        o.addProperty("status", status.name());
        o.addProperty("status_detail", detail == null ? "" : detail);
        if (consumerRef != null && !consumerRef.isBlank()) {
            o.addProperty("consumer_ref", consumerRef);
        }
        o.addProperty("updated_at", now);
        JsonArrayShim.history(o, now, status.name(), detail == null ? "" : detail);
        try {
            writeAtomic(file, GSON.toJson(o));
        } catch (IOException e) {
            throw new IllegalStateException("回填状态落盘失败: " + e, e);
        }
    }

    /** 按类别读出待下游处理的产物（有序、条数有上限）。 */
    public List<JsonObject> pending(Kind kind) {
        List<JsonObject> out = new ArrayList<>();
        Path dir = root.resolve(kind.wire());
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> companions;
        try (var s = Files.list(dir)) {
            companions = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return out;
        }
        for (Path c : companions) {
            List<Path> files;
            try (var s = Files.list(c)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path f : files) {
                if (out.size() >= MAX_SCAN) {
                    return out;
                }
                JsonObject o = parseQuietly(f);
                if (o == null) {
                    continue;
                }
                if (Status.PENDING.name().equals(str(o, "status"))) {
                    o.addProperty("_path", f.toString());
                    out.add(o);
                }
            }
        }
        return out;
    }

    /**
     * 读出<b>任意状态</b>的产物记录（有序、条数有上限）。
     *
     * <p>为什么需要它（{@link #pending} 不够用）：
     * <ul>
     *   <li><b>人工/外层工程流要能看见被拒的</b> —— {@code pending} 只给 PENDING，
     *       于是「AI 写了但下游拒收」这件事在读侧<b>完全不可见</b>，
     *       那正是「回执说成功、实际没成」的哑故障形态。</li>
     *   <li>携带器与自编译请求<b>还没有下游消费者</b>，它们的记录只会长期停在
     *       {@code LANDED/PENDING} —— 全量列表是它们唯一能被看见的方式。</li>
     * </ul>
     *
     * <p>每条附带 {@code _path}，方便人直接打开。坏文件跳过且<b>不删</b>（同 {@link #pending}）。
     */
    public List<JsonObject> list(Kind kind) {
        List<JsonObject> out = new ArrayList<>();
        Path dir = root.resolve(kind.wire());
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> companions;
        try (var s = Files.list(dir)) {
            companions = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return out;
        }
        for (Path c : companions) {
            List<Path> files;
            try (var s = Files.list(c)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path f : files) {
                if (out.size() >= MAX_SCAN) {
                    return out;
                }
                JsonObject o = parseQuietly(f);
                if (o == null) {
                    continue;
                }
                o.addProperty("_path", f.toString());
                out.add(o);
            }
        }
        return out;
    }

    /** 供 learner_status 挂进 runtime_state / 回执的概览。全部用 long：混着 Integer/Long 会让消费方的断言莫名其妙地红。 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long total = 0;
        for (Kind k : Kind.values()) {
            Path dir = root.resolve(k.wire());
            long n = 0;
            if (Files.isDirectory(dir)) {
                try (var s = Files.walk(dir)) {
                    n = s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
                } catch (IOException ignored) {
                    n = -1;
                }
            }
            m.put(k.wire(), n);
            if (n > 0) {
                total += n;
            }
        }
        m.put("total", total);
        return m;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private Path locate(String artifactId) {
        for (Kind k : Kind.values()) {
            Path dir = root.resolve(k.wire());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var s = Files.walk(dir)) {
                var hit = s.filter(p -> p.getFileName().toString().equals(artifactId + ".json")).findFirst();
                if (hit.isPresent()) {
                    return hit.get();
                }
            } catch (IOException ignored) {
                // 单个目录读不到就跳过下一个类别，不让一处坏目录挡住全部回填
            }
        }
        return null;
    }

    private static JsonObject parseQuietly(Path f) {
        try {
            String s = readString(f);
            if (s == null || s.isBlank()) {
                return null;
            }
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            // 坏文件不是「没有产物」：这里只跳过，不删，原文留着等人看
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    static String readString(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeAtomic(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sanitize(String s) {
        if (s == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 64));
        for (int i = 0; i < s.length() && sb.length() < 64; i++) {
            char c = s.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            sb.append(ok ? c : '_');
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    static String hash16(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("算不出内容摘要（SHA-256 不可用）: " + e, e);
        }
    }

    /** history 数组的写法单独放这儿：submit 与 mark 都要用，形状不能分叉。 */
    static final class JsonArrayShim {
        private JsonArrayShim() {}

        static void history(JsonObject o, String at, String status, String detail) {
            com.google.gson.JsonArray arr = o.has("history") && o.get("history").isJsonArray()
                    ? o.getAsJsonArray("history")
                    : new com.google.gson.JsonArray();
            JsonObject h = new JsonObject();
            h.addProperty("at", at);
            h.addProperty("status", status);
            h.addProperty("detail", detail == null ? "" : detail);
            arr.add(h);
            o.add("history", arr);
        }
    }
}
