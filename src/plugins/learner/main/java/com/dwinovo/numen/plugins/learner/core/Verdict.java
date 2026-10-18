package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 学习者对一条备忘录的判定（v1 = plan-only，不执行）。
 *
 * <p>对应规格 §3 的六个取值。动作是<b>多选</b>：一条备忘录可以同时建议
 * 写经验和调 AC。
 *
 * <p>解析策略：LLM 返回的文本必须能被解析成合法 JSON 才产出判定；
 * <b>解析不出来就返回 null</b>，由调用方明确回报 LLM_UNAVAILABLE/UNPARSEABLE，
 * 绝不伪造一个「看起来合理」的判定（对齐经验库 §11 的验收要求）。
 */
public record Verdict(
        String memoId,
        List<Action> actions,
        double confidence,
        String reasoning,
        Experience experience,
        String acScriptDraft,
        // ---- 2026-10-03（架构 owner 拍板「多产物要改载荷形状」后补的两个载荷位）----
        //
        // ★ 更正：我曾说过「Verdict 只解析 experience 一个结构化对象、载荷形状要改」—— **那是错的**。
        //   `actions` 本来就是 `List<Action>`（可多选），`acScriptDraft` 本来就有。
        //   真正缺的只有下面两个：**声明了 USE_CARRIER / SELF_COMPILE，却没有地方放草稿**。
        //   声明与载荷对不上 = 学习者说「我要写携带器」但写不出内容，
        //   而回执里也不会有任何线索说明它本该写什么 —— 那正是「僵尸字段」的形状。
        //
        // ⚠️ 为什么不复用 acScriptDraft 装三种东西：载荷混在一个字段里，
        //   消费侧就得分字符串猜哪段是 AC、哪段是携带器 —— 而 62 号 §2 已经明写
        //   「`why` 保持原文不要改写，保持可检索」，同一原则：各载荷位分开、可分别检索。
        //
        // ⚠️ SELF_COMPILE 这一位**不直接喂 `selfcompile_request` 工具**：按 B11，
        //   「学习者请求 → 落成结构化待办 → 写码由外层 agent 接手」，下游**不自动化**。
        //   它只是草稿的落点，落地是外层的事。
        String carrierDraft,
        String selfCompileRequest,
        /**
         * ★ B1（2026-10-05）：这条判定里各产物的<b>使用结果</b>。
         *
         * <p><b>为什么加这个字段</b>：使用账本按 {@code artifact_id} 记事实，
         * 而产物 id 里带 {@code memoId}（{@code ArtifactOutbox} 的
         * {@code artifact_id = kind + 同伴 + memoId + 摘要}）。
         * ⇒ 只要把这个字段从账本里<b>按 memoId 反查</b>填进来，
         * 「这条判定 → 它产出的东西 → 那东西跑成没成」就串成了一条链，
         * 两边终于有共同键（memoId），对账才有意义。
         *
         * <p><b>形状</b>：{@code [{"kind":"AC_SCRIPT","name":"...","phase":"RESULT",
         * "outcome":"SUCCESS","artifact_id":"...","run_count":1}]}。
         * <b>只读事实</b>：{@code outcome} 来自执行记录，
         * <b>不含任何「所以有效」的判断</b> —— 有效性仍然只能由人显式声明。
         *
         * <p><b>缺省空列表</b>：没有产物、或产物还没被执行过，都是空。
         * 空列表与「查不到」要分开：查不到时由调用方写 {@code usage_lookup} 说明。
         */
        List<Map<String, Object>> usageOutcome,
        List<String> rewrittenQuery
) {

    /** 判定动作。v1 全部是「建议」，由 AI 决定是否执行。 */
    public enum Action {
        WRITE_EXPERIENCE,
        USE_AC,
        USE_CARRIER,
        SELF_COMPILE,
        NO_ACTION,
        NEEDS_HUMAN;

        public static Action parse(String raw) {
            if (raw == null) {
                return null;
            }
            String k = raw.trim().toUpperCase(Locale.ROOT);
            for (Action a : values()) {
                if (a.name().equals(k)) {
                    return a;
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new Gson();

    /**
     * 从 LLM 原始回复里解析出判定；解析失败返回 {@code null}（调用方须如实回报）。
     *
     * <p>容忍模型把 JSON 包在 ```json 代码块里 —— 这在实际调用里非常常见。
     */
    public static Verdict parse(String fallbackMemoId, String rawLlmText) {
        if (rawLlmText == null || rawLlmText.isBlank()) {
            return null;
        }
        String cleaned = stripFence(rawLlmText.trim());
        try {
            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();

            // P1 修复（2026-09-29）：memo_id 必须从 JSON 里读。
            // 原实现用调用方传的占位符（批量时是 "?"），导致下游无法把判定
            // 对回具体备忘录，复盘工具只能整批 commit —— 会删掉没被复盘到的条目。
            // 缺失 memo_id 时返回 null（让调用方把这批当未判定处理），不许猜。
            String jsonMemoId = optString(obj, "memo_id");
            if (jsonMemoId.isBlank()) {
                jsonMemoId = fallbackMemoId == null ? "" : fallbackMemoId.trim();
            }
            if (jsonMemoId.isBlank()) {
                return null;
            }
            final String memoId = jsonMemoId;

            List<Action> actions = new ArrayList<>();
            if (obj.has("actions") && obj.get("actions").isJsonArray()) {
                for (JsonElement e : obj.getAsJsonArray("actions")) {
                    if (!e.isJsonPrimitive()) {
                        continue;
                    }
                    Action a = Action.parse(e.getAsString());
                    if (a != null && !actions.contains(a)) {
                        actions.add(a);
                    }
                }
            }
            if (actions.isEmpty()) {
                return null;
            }

            double confidence = 0.0;
            if (obj.has("confidence") && obj.get("confidence").isJsonPrimitive()) {
                try {
                    confidence = obj.get("confidence").getAsDouble();
                } catch (RuntimeException ignored) {
                    confidence = 0.0;
                }
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            String reasoning = optString(obj, "reasoning");
            // 2026-10-01：experienceDraft(String) → experience(Experience)。
            // 实机证据：老格式给的是一段散文，七字段一个都没结构化。
            // ⚠️ **刻意不把老的 experience_draft 字符串塞进 mechanism 蒙过去** ——
            // 那样会让「格式已落地」看起来成立，而实际仍然是一段散文。
            Experience exp = Experience.parse(obj);
            String acDraft = optString(obj, "ac_script_draft");
            // 2026-10-03：携带器实现与自编译请求的草稿位（声明了 actions 却没有载荷位 = 僵尸声明）
            String carrierDraft = optString(obj, "carrier_draft");
            String selfCompile = optString(obj, "self_compile_request");

            List<String> queries = new ArrayList<>();
            if (obj.has("rewritten_query")) {
                JsonElement q = obj.get("rewritten_query");
                if (q.isJsonArray()) {
                    JsonArray arr = q.getAsJsonArray();
                    for (JsonElement e : arr) {
                        if (e.isJsonPrimitive()) {
                            String v = e.getAsString().trim();
                            if (!v.isBlank()) {
                                queries.add(v);
                            }
                        }
                    }
                } else if (q.isJsonPrimitive()) {
                    String v = q.getAsString().trim();
                    if (!v.isBlank()) {
                        queries.add(v);
                    }
                }
            }

            // usageOutcome **不由 LLM 给**：LLM 手里没有执行事实，
            // 让它填就是让它编。解析时一律空，由 LearnerReviewTool 从账本按 memoId 反查填入。
            return new Verdict(memoId, List.copyOf(actions), confidence, reasoning,
                    exp, acDraft, carrierDraft, selfCompile, List.of(), List.copyOf(queries));
        } catch (RuntimeException e) {
            // 不是合法 JSON：如实返回 null，让调用方报 UNPARSEABLE
            return null;
        }
    }

    private static String stripFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNl = s.indexOf('\n');
        if (firstNl < 0) {
            return s;
        }
        int lastFence = s.lastIndexOf("```");
        if (lastFence <= firstNl) {
            return s.substring(firstNl + 1);
        }
        return s.substring(firstNl + 1, lastFence).trim();
    }

    private static String optString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()) {
            return "";
        }
        String v = obj.get(field).getAsString().trim();
        return v == null ? "" : v;
    }

    /** 供日志/监测台使用的紧凑 JSON。 */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("memo_id", memoId);
        JsonArray acts = new JsonArray();
        for (Action a : actions) {
            acts.add(a.name());
        }
        o.add("actions", acts);
        o.addProperty("confidence", confidence);
        o.addProperty("reasoning", reasoning);
        // B21：experience 为 null 时**不放这个键**，而不是放 "" 或 null —— 缺失就缺失
        if (experience != null) {
            JsonObject ex = new JsonObject();
            for (String f : Experience.FIELDS) {
                String v = experience.field(f);
                if (!v.isBlank()) {
                    ex.addProperty(f, v);
                }
            }
            o.add("experience", ex);
            o.addProperty("experience_fields_filled", experience.filledCount());
            // 关键字段不齐要**说出来**：调用方才知道这条经验不能直接入库
            o.addProperty("experience_acceptable", experience.acceptable());
            if (!experience.acceptable()) {
                o.addProperty("experience_unacceptable_reason", experience.unacceptableReason());
            }
        }
        o.addProperty("ac_script_draft", acScriptDraft);
        // 2026-10-03：两个新载荷位也进 toJson —— 回执里看不到载荷，
        // 学习者就以为「声明了但没写出来」，而调用方也无从知道该不该再问一次。
        o.addProperty("carrier_draft", carrierDraft);
        o.addProperty("self_compile_request", selfCompileRequest);
        JsonArray qs = new JsonArray();
        for (String q : rewrittenQuery) {
            qs.add(q);
        }
        o.add("rewritten_query", qs);
        return GSON.toJson(o);
    }

    /** 展示用摘要。 */
    public String summary() {
        return memoId + " -> " + String.join("+", actions.stream().map(Enum::name).toList())
                + " (confidence=" + String.format(Locale.ROOT, "%.2f", confidence) + ")";
    }
}
