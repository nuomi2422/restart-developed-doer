package com.dwinovo.numen.experience.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条经验的完整定义。它是<b>可独立检索、可验证、可复用</b>的单元，不是一句日志。
 *
 * <p>{@code id} 即稳定去重键：由 {@link #stableKey} 派生（type + 规范化 title），
 * 同名经验只会合并不会无限追加。
 *
 * <p>核心字段语义：
 * <ul>
 *   <li>{@code description} — 发生了什么（现象）。</li>
 *   <li>{@code rationale} — 为什么这条值得保留（AI 自反思溯源）。</li>
 *   <li>{@code rootCause} — 根因是什么。</li>
 *   <li>{@code recommendedResponse} — 以后遇到应该怎么做。</li>
 *   <li>{@code triggerStrings/toolNames/tags} — 什么情况下应该再次想起。</li>
 *   <li>{@code maturity/verifiedCount/counterexamples} — 可信度与验证证据。</li>
 * </ul>
 */
public record ExperienceEntry(
        String id,
        ExperienceType type,
        String title,
        String description,
        String rationale,
        String rootCause,
        String recommendedResponse,
        List<String> triggerStrings,
        List<String> toolNames,
        List<String> tags,
        ExperienceMaturity maturity,
        int verifiedCount,
        int priority,
        List<String> counterexamples,
        long createdAt,
        long verifiedAt,
        long lastAccessedAt
) {

    /** 经验条目 JSONL 的文件格式版本；记录模型变了就 +1。 */
    public static final int FORMAT_VERSION = 1;

    /** 生成稳定去重键：type + 规范化 title。 */
    public static String stableKey(ExperienceType type, String title) {
        String t = title == null ? "" : title.trim().toLowerCase();
        return (type == null ? "UNKNOWN" : type.name()).toLowerCase() + "|" + t;
    }

    /** 复制一条并替换证据字段（evidence 更新专用，保留其它内容）。 */
    public ExperienceEntry withEvidence(ExperienceMaturity newMaturity, int newVerifiedCount,
                                        long newVerifiedAt, List<String> newCounterexamples) {
        return builder().from(this)
                .maturity(newMaturity)
                .verifiedCount(newVerifiedCount)
                .verifiedAt(newVerifiedAt)
                .counterexamples(newCounterexamples)
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", FORMAT_VERSION);
        o.addProperty("id", id);
        o.addProperty("type", type == null ? null : type.name());
        o.addProperty("title", title);
        o.addProperty("description", description);
        o.addProperty("rationale", rationale);
        o.addProperty("root_cause", rootCause);
        o.addProperty("recommended_response", recommendedResponse);
        o.add("trigger_strings", strArray(triggerStrings));
        o.add("tool_names", strArray(toolNames));
        o.add("tags", strArray(tags));
        o.addProperty("maturity", maturity == null ? null : maturity.name());
        o.addProperty("verified_count", verifiedCount);
        o.addProperty("priority", priority);
        o.add("counterexamples", strArray(counterexamples));
        o.addProperty("created_at", createdAt);
        o.addProperty("verified_at", verifiedAt);
        o.addProperty("last_accessed_at", lastAccessedAt);
        return o;
    }

    /** 容错解析：缺字段/坏字段一律落到默认值，不抛异常（坏行在 store 层直接跳过）。 */
    public static ExperienceEntry fromJson(JsonObject o) {
        return builder()
                .id(str(o, "id"))
                .type(parseEnum(ExperienceType.class, str(o, "type")))
                .title(str(o, "title"))
                .description(str(o, "description"))
                .rationale(str(o, "rationale"))
                .rootCause(str(o, "root_cause"))
                .recommendedResponse(str(o, "recommended_response"))
                .triggerStrings(strList(o, "trigger_strings"))
                .toolNames(strList(o, "tool_names"))
                .tags(strList(o, "tags"))
                .maturity(parseEnum(ExperienceMaturity.class, str(o, "maturity")))
                .verifiedCount(intVal(o, "verified_count"))
                .priority(intVal(o, "priority"))
                .counterexamples(strList(o, "counterexamples"))
                .createdAt(longVal(o, "created_at"))
                .verifiedAt(longVal(o, "verified_at"))
                .lastAccessedAt(longVal(o, "last_accessed_at"))
                .build();
    }

    // ---- codec helpers ----

    private static JsonArray strArray(List<String> values) {
        JsonArray a = new JsonArray();
        if (values != null) {
            for (String s : values) {
                if (s != null) {
                    a.add(s);
                }
            }
        }
        return a;
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement el = o.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    private static int intVal(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? 0 : el.getAsInt();
    }

    private static long longVal(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? 0L : el.getAsLong();
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (T c : type.getEnumConstants()) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    // ---- builder ----

    public static final class Builder {
        private String id;
        private ExperienceType type;
        private String title = "";
        private String description = "";
        private String rationale = "";
        private String rootCause = "";
        private String recommendedResponse = "";
        private List<String> triggerStrings = List.of();
        private List<String> toolNames = List.of();
        private List<String> tags = List.of();
        private ExperienceMaturity maturity = ExperienceMaturity.OBSERVED;
        private int verifiedCount;
        private int priority = 50;
        private List<String> counterexamples = List.of();
        private long createdAt;
        private long verifiedAt;
        private long lastAccessedAt;

        public Builder from(ExperienceEntry e) {
            if (e == null) {
                return this;
            }
            return id(e.id())
                    .type(e.type())
                    .title(e.title())
                    .description(e.description())
                    .rationale(e.rationale())
                    .rootCause(e.rootCause())
                    .recommendedResponse(e.recommendedResponse())
                    .triggerStrings(e.triggerStrings())
                    .toolNames(e.toolNames())
                    .tags(e.tags())
                    .maturity(e.maturity())
                    .verifiedCount(e.verifiedCount())
                    .priority(e.priority())
                    .counterexamples(e.counterexamples())
                    .createdAt(e.createdAt())
                    .verifiedAt(e.verifiedAt())
                    .lastAccessedAt(e.lastAccessedAt());
        }

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder type(ExperienceType v) {
            this.type = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder rationale(String v) {
            this.rationale = v;
            return this;
        }

        public Builder rootCause(String v) {
            this.rootCause = v;
            return this;
        }

        public Builder recommendedResponse(String v) {
            this.recommendedResponse = v;
            return this;
        }

        public Builder triggerStrings(List<String> v) {
            this.triggerStrings = v;
            return this;
        }

        public Builder toolNames(List<String> v) {
            this.toolNames = v;
            return this;
        }

        public Builder tags(List<String> v) {
            this.tags = v;
            return this;
        }

        public Builder maturity(ExperienceMaturity v) {
            this.maturity = v;
            return this;
        }

        public Builder verifiedCount(int v) {
            this.verifiedCount = v;
            return this;
        }

        public Builder priority(int v) {
            this.priority = v;
            return this;
        }

        public Builder counterexamples(List<String> v) {
            this.counterexamples = v;
            return this;
        }

        public Builder createdAt(long v) {
            this.createdAt = v;
            return this;
        }

        public Builder verifiedAt(long v) {
            this.verifiedAt = v;
            return this;
        }

        public Builder lastAccessedAt(long v) {
            this.lastAccessedAt = v;
            return this;
        }

        public ExperienceEntry build() {
            long now = System.currentTimeMillis();
            long created = createdAt > 0 ? createdAt : now;
            String t = title == null ? "" : title.trim();
            String d = description == null ? "" : description.trim();
            if (id == null || id.isBlank()) {
                id = stableKey(type, t);
            }
            return new ExperienceEntry(
                    id,
                    type,
                    t,
                    d,
                    nz(rationale),
                    nz(rootCause),
                    nz(recommendedResponse),
                    copy(triggerStrings),
                    copy(toolNames),
                    copy(tags),
                    maturity == null ? ExperienceMaturity.OBSERVED : maturity,
                    verifiedCount,
                    priority <= 0 ? 50 : priority,
                    copy(counterexamples),
                    created,
                    verifiedAt,
                    lastAccessedAt > 0 ? lastAccessedAt : created
            );
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }

        private static List<String> copy(List<String> src) {
            if (src == null || src.isEmpty()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String s : src) {
                if (s != null && !s.isBlank()) {
                    seen.add(s.trim());
                }
            }
            return List.copyOf(seen);
        }
    }
}
