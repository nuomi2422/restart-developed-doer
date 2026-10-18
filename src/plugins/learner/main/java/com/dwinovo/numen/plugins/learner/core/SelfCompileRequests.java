package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自编译请求的<b>取件契约</b>（B6/S2 收口，2026-10-05）。
 *
 * <p><b>为什么需要它</b>：自编译请求自落地起就<b>没有任何下游</b>——
 * 外层工程流该读哪、读到之后怎么回执，都没有约定。
 * 于是它只是「躺在投递箱里的一个文件」：既没人认领，也没人知道是否被处理过。
 *
 * <p><b>★ 为什么不写进投递箱本身</b>：投递记录是 append-only 的产物档案
 * （{@code ArtifactOutbox.mark} 靠它留历史）。把「谁领了」写进产物记录，
 * 会让<b>产物状态</b>与<b>流程状态</b>混在一起 ——
 * 一次领件不该改写产物本身。所以取件回执走<b>旁路的 ack 目录</b>。
 *
 * <p><b>★ 明确不越权的地方</b>：本类只管「列出 / 认领 / 标注结果」，
 * <b>不写代码、不编译、不改产物</b>。按架构（B11），写码由外层工程流接手。
 * 这一点写在这里，是为了防止下一个接手的人以为「ack 了就会自动改代码」。
 *
 * <p><b>落盘</b>：{@code <configDir>/selfcompile-requests/<artifactId>.ack.json}
 */
public final class SelfCompileRequests {

    /** 取件状态。 */
    public enum AckState {
        /** 还没有人认领。 */
        UNCLAIMED,
        /** 已认领，正在处理。 */
        TAKEN,
        /** 已处理完（成功/失败/放弃由 note 记录，本类不解释）。 */
        RESOLVED,
        /**
         * ★ 回执文件存在但<b>读不出来</b>。
         *
         * <p>必须是一个真实状态而不是回落成 {@code UNCLAIMED}：
         * 回落会让一条**已经被人领走**的请求重新摆回待办池 ⇒ 两个人同时开工，
         * 而清单看起来一切正常。这正是「读不到 ≠ 没有」那条红线的同款问题。
         */
        UNREADABLE
    }

    /** 一条待办 + 它的取件状态。 */
    public record Request(String artifactId, String name, String companionId, String reviewId,
                          String memoId, String submittedAt, AckState state,
                          String takenBy, String note, String ackedAt) {
    }

    private final ArtifactOutbox outbox;
    private final Path ackDir;

    public SelfCompileRequests(ArtifactOutbox outbox, Path configDir) {
        this.outbox = outbox;
        this.ackDir = configDir.resolve("selfcompile-requests");
    }

    public Path ackDir() {
        return ackDir;
    }

    /**
     * 列出所有自编译请求及其取件状态。
     *
     * <p><b>包含已被认领的</b>：只列「没人要的」会让人以为请求凭空消失。
     * 全部列出 + 状态列，才是可对账的清单。
     */
    public List<Request> list() {
        List<Request> out = new ArrayList<>();
        for (JsonObject o : outbox.list(ArtifactOutbox.Kind.SELF_COMPILE_REQUEST)) {
            String id = str(o, "artifact_id");
            if (id.isBlank()) {
                continue;
            }
            JsonObject ack = readAck(id);
            String state = ack == null ? AckState.UNCLAIMED.name() : str(ack, "state");
            out.add(new Request(id, str(o, "name"), str(o, "companion_id"),
                    str(o, "review_id"), str(o, "memo_id"), str(o, "submitted_at"),
                    parseState(state), ack == null ? "" : str(ack, "by"),
                    ack == null ? "" : str(ack, "note"),
                    ack == null ? "" : str(ack, "at")));
        }
        return out;
    }

    /** 只列还没人认领的。 */
    public List<Request> unclaimed() {
        List<Request> out = new ArrayList<>();
        for (Request r : list()) {
            if (r.state() == AckState.UNCLAIMED) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 认领一条（或更新处理结果）。
     *
     * @param by    认领者；<b>必填且不许空白</b> —— 无主的认领等于没人认领
     * @param state {@code TAKEN} 或 {@code RESOLVED}
     * @param note  备注（{@code RESOLVED} 时建议写清结果，但本类<b>不解释</b>它）
     * @throws IllegalArgumentException 非法状态 / 认领者空白 / 请求不存在
     */
    public Request ack(String artifactId, String by, AckState state, String note) {
        if (by == null || by.isBlank()) {
            // 不静默代填 "unknown"：无主的认领等于这条请求仍然没人负责
            throw new IllegalArgumentException("认领必须给 by：没有责任人的认领等于没人认领");
        }
        if (state != AckState.TAKEN && state != AckState.RESOLVED) {
            throw new IllegalArgumentException("ack 的状态只认 TAKEN / RESOLVED，收到: " + state);
        }
        boolean exists = false;
        for (Request r : list()) {
            if (r.artifactId().equals(artifactId)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            throw new IllegalArgumentException("投递箱里没有这条自编译请求: " + artifactId
                    + "（用 list 看现有的；注意只有 SELF_COMPILE_REQUEST 才归这里管）");
        }
        JsonObject o = new JsonObject();
        o.addProperty("artifact_id", artifactId);
        o.addProperty("state", state.name());
        o.addProperty("by", by.trim());
        o.addProperty("note", note == null ? "" : note.trim());
        o.addProperty("at", java.time.Instant.now().toString());
        writeAck(artifactId, o);
        for (Request r : list()) {
            if (r.artifactId().equals(artifactId)) {
                return r;
            }
        }
        throw new IllegalStateException("写完 ack 却读不回来: " + artifactId);
    }

    private JsonObject readAck(String artifactId) {
        Path f = ackDir.resolve(sanitize(artifactId) + ".ack.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // 坏 ack 不许当成「没认领」—— 那会把一条已被人领走的请求重新摆回待办池
            JsonObject bad = new JsonObject();
            bad.addProperty("artifact_id", artifactId);
            bad.addProperty("state", "UNREADABLE");
            bad.addProperty("by", "");
            bad.addProperty("note", "ack 文件读不出来（" + e + "），需人工看一眼");
            bad.addProperty("at", "");
            return bad;
        }
    }

    private void writeAck(String artifactId, JsonObject o) {
        Path f = ackDir.resolve(sanitize(artifactId) + ".ack.json");
        try {
            Files.createDirectories(ackDir);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写取件回执失败: " + f + " -> " + e, e);
        }
    }

    private static AckState parseState(String s) {
        for (AckState v : AckState.values()) {
            if (v.name().equalsIgnoreCase(s == null ? "" : s.trim())) {
                return v;
            }
        }
        // ★ 未知值**不回落成 UNCLAIMED**：那会把「状态读不出来」说成「没人领」，
        //   已被人领走的请求就会重新流回待办池。宁可显式 UNREADABLE。
        return AckState.UNREADABLE;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : "";
    }

    /** 给工具回执用。 */
    public static Map<String, Object> toMap(List<Request> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rs = new ArrayList<>();
        int unclaimed = 0;
        for (Request r : rows) {
            if (r.state() == AckState.UNCLAIMED) {
                unclaimed++;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("artifact_id", r.artifactId());
            one.put("name", r.name());
            one.put("companion_id", r.companionId());
            one.put("review_id", r.reviewId());
            one.put("memo_id", r.memoId());
            one.put("submitted_at", r.submittedAt());
            one.put("state", r.state().name());
            one.put("taken_by", r.takenBy());
            one.put("note", r.note());
            one.put("acked_at", r.ackedAt());
            rs.add(one);
        }
        m.put("rows", rs);
        m.put("total", rows.size());
        m.put("unclaimed", unclaimed);
        m.put("note", "★ 这里只管列出/认领/标注，**不写代码、不编译、不改产物**；"
                + "写码由外层工程流接手（B11）");
        return m;
    }
}