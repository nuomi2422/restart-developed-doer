package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条「原始备忘录」：干活 AI 遇到问题时的现场记录。
 *
 * <p>刻意保持「原始」：学习者复盘时自己判定，这层不做任何推断、不做归类，
 * 也不写进经验库。它只是一张带环境快照的纸条。
 *
 * <p>纯 JVM，无 NUMEN/MC 依赖，可独立单测。
 */
public record Memo(
        String id,
        String problem,
        String stage,
        String tried,
        String snapshot,
        long createdAt,
        /**
         * ★ 类别（2026-10-03，架构 owner 拍板「同入口但加类别字段」）。
         *
         * <p>{@code learning} = 想学/想记住什么（默认，缺省就是它）；
         * {@code timing} = 时序类（「先挖三格再回头看」）—— 这类最终应该变成 AC 脚本，
         * 而不是变成一条经验。</p>
         *
         * <p>★ 为什么放进 Memo 而不是另开一个队列：owner 原话是「同入口」，
         * 而 {@code MemoQueue} 的容量/原子重写/复盘失败不丢队列这一整套都已经调过，
         * 另开一个队列等于把这些重做一遍，还多一处「两个队列哪个满了」的判断。</p>
         *
         * <p>★ 默认值给 {@code "learning"} 而不是空串：这样
         * 「调用方没传类别」与「调用方显式传了 learning」落在同一个值上，
         * 下游按 {@code learning} 判就够，不必先判空再判值（两段判断必有一段漏）。</p>
         */
        String category
) {
    /** 未声明类别时的缺省值；也是 {@code LearnerNoteTool} 的缺省。 */
    public static final String CATEGORY_LEARNING = "learning";
    /** 时序类：最终应变成 AC 脚本。 */
    public static final String CATEGORY_TIMING = "timing";

    public Memo {
        Objects.requireNonNull(id, "id");
        problem = problem == null ? "" : problem;
        stage = stage == null ? "" : stage;
        tried = tried == null ? "" : tried;
        snapshot = snapshot == null ? "" : snapshot;
        // ⚠️ 不认识的值原样留着，不静默改成 learning ——
        //   「它说了个我没听过的类别」是一条要让人看见的事实，悄悄改掉就查不到了。
        category = category == null || category.isBlank() ? CATEGORY_LEARNING : category.trim();
    }

    /** 老条目 / 程序化构造（投料侧）用：类别缺省 learning。 */
    public Memo(String id, String problem, String stage, String tried, String snapshot, long createdAt) {
        this(id, problem, stage, tried, snapshot, createdAt, CATEGORY_LEARNING);
    }

    /** 供 LLM 复盘用的紧凑文本：只给判断所需，不给无关字段。 */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("[memo ").append(id).append("]\n");
        // ★ 类别给复盘的 LLM 看（2026-10-03）：时序类待办该出 AC 脚本而不是经验，
        //   不告诉它类别，它就只能把「先挖三格再回头」也写成一条经验 —— 那正是 owner
        //   要避免的（「有些时序的问题可以拿这个来写…写 AC」）。
        //   ⚠️ learning 是缺省值，**不印出来**：每轮都印一行「类别: learning」是噪音，
        //   而且会把「显式声明」与「缺省」这两种情形在文本上混成一样。
        if (!CATEGORY_LEARNING.equals(category)) {
            sb.append("类别: ").append(category)
                    .append("（timing = 时序类，考虑写成 AC 脚本而不是经验）\n");
        }
        if (!stage.isBlank()) {
            sb.append("阶段: ").append(stage).append('\n');
        }
        sb.append("问题: ").append(problem).append('\n');
        sb.append("已尝试: ").append(tried.isBlank() ? "(未记录)" : tried).append('\n');
        if (!snapshot.isBlank()) {
            sb.append("环境快照: ").append(snapshot).append('\n');
        }
        return sb.toString();
    }

    /**
     * 承载器三段分级：1 指向谁 → 2 装备 → 3 血量。
     *
     * <p><b>只读快照字符串、只产出携带清单，不落盘、不存状态</b>——严格守住
     * 「只携带不存储」。core:common 的 Menace/Battlefield/Loadout 在插件的
     * compileOnly 类路径外（见 numen-plugin.gradle:41-42），所以这里按快照
     * 的键值对做分级，而不是 import 引擎的威胁评估。
     *
     * <p><b>为什么不用 {@code contains}</b>（2026-09-29 P1 修复，Codex 审出）：
     * {@code snapshot="hp=6/20, armor=none, nearby=zombie"} 里
     * {@code contains("armor")} 为真 → 会把「没有护甲」判成「有护甲」，
     * 携带器给出的清单正好反过来，等于假事实。所以改成解析
     * {@code key=value} 对并识别否定值（none/no/false/0/空）。
     */
    public CarrierAssessment assessCarrier() {
        String raw = snapshot == null ? "" : snapshot;
        if (raw.isBlank()) {
            return new CarrierAssessment("UNKNOWN", "无环境快照，无法分级", List.of(), -1);
        }
        // 2026-10-01（B5 可执行形态 / v3.5 §1）：原来这里是**把三级无条件全算一遍** ——
        // 那不是判断链，是扁平打分。B5 的原意是「第 1 级不成立就不问第 2 级」。
        // → 改成走 CarrierChain，规则**可插拔**、顺序即判断顺序。
        java.util.Map<String, String> kv = parseKeyValues(raw.toLowerCase(java.util.Locale.ROOT));
        CarrierChain.Facts facts = CarrierChain.factsOf(kv, raw.toLowerCase(java.util.Locale.ROOT));
        // B6/S2：用 effective() 而不是写死的 DEFAULT —— 否则经审批的携带器规则在复审里看不到，
        // 「AI 评估说该带 X」与「运行时真的带 X」会长期不一致（而这类不一致极难从外部看出来）。
        CarrierChain.Result r = CarrierChain.evaluate(
                com.dwinovo.numen.api.carrier.CarrierRuleStore.effective(), facts);

        String target;
        if (!facts.has("target") && !facts.hostileNearby() && !facts.passiveNearby()) {
            // 「认不出来」→ UNKNOWN，不编一个看起来对的家族（B21 同族）
            //
            // ★ 2026-10-06 修：这里原来判的是 `r.shortCircuited() && r.carry().isEmpty()` ——
            //   拿「链短路且没带出东西」当「快照读不懂」的代理。这个代理**会被
            //   已批准的携带器规则污染**：``CarrierRuleStore`` 把已批准规则排在默认链
            //   **之前**（`append()` 的有意设计），只要有一条已批准规则不成立且它的
            //   `fix` 为空，链就在第 0 级短路且 carry 为空 ⇒ 一份**完全可读**的快照
            //   （`hostile=false`、`armor=none`…）被判成 UNKNOWN。
            //   实测症状：批准一条规则后，所有同伴的携带器目标全变 UNKNOWN。
            //
            //   改用**可读性**判据（与 `carrierSignal()` 的 `snapshot_parsed` 同一个方法），
            //   于是两个出口口径一致：`snapshot_parsed=false` ⟺ 这里 UNKNOWN。
            //   「链短路」是规则语义，「读不懂」是数据语义 —— 两者不是一回事，不能互相代理。
            target = hasReadableKeys() ? "NONE" : "UNKNOWN";
        } else if (facts.hostileNearby()) {
            target = "HOSTILE_NEARBY";
        } else {
            target = "PASSIVE_ONLY";
        }
        int hp = facts.hasHp() ? facts.hp() : -1;
        // ★ why 保留**旧的三段判据摘要**（指向/护甲/武器/血量）**再追加链路轨迹**。
        //   理由：2026-09-29 那几个测试守的是**真实回归**（armor=none 曾被 contains 判成有护甲），
        //   不能因为换了实现就把它们改掉 —— 那是「为了绿而改测试」。
        //   所以格式是「旧摘要 + 新轨迹」，两套断言同时成立。
        String summary = "指向=" + target
                + " 护甲=" + (facts.hasRealArmor() ? "有" : "无")
                + " 武器=" + (facts.hasRealWeapon() ? "有" : "无")
                + " 血量=" + facts.hpBand()
                + (hp >= 0 ? "(" + hp + ")" : "");
        return new CarrierAssessment(target, summary + "  " + r.why(), r.carry(), hp);
    }

    /**
     * B21「缺失的表达方式」：把分级结论变成**可安全落盘/上报**的信号。
     *
     * <p><b>为什么单独抽出来</b>：这层逻辑原先长在 {@code LearnerNoteTool}（插件层）里，
     * 而插件层依赖 NUMEN/MC 类路径，<b>单测跑不到</b>。core 是纯 JVM，所以下沉到这里才能测。
     *
     * <p><b>禁止的写法</b>（2026-10-01 实机抓到的真缺陷）：
     * <ul>
     *   <li>{@code carrier_hp = -1} —— -1 在本 record 里定义是「没抓到」的哨兵，
     *       原样写进 jsonl 就<b>看起来像数据</b>；</li>
     *   <li>{@code carryList = ["无额外携带需求"]} —— 缺快照时这句话会出现在
     *       {@code assessCarrier} 的早退分支里（虽然当前实现早退给空列表，
     *       但语义上它是个<b>凭空产生的结论</b>，下游分不出它和真判断）。</li>
     * </ul>
     *
     * <p><b>改后的契约</b>：
     * <ul>
     *   <li>永远有 {@code snapshot_present}（true/false）与 {@code snapshot_chars}；</li>
     *   <li>{@code snapshot_present=false} 时：<b>不出现</b> {@code carrier_target} /
     *       {@code carrier_hp} / {@code carry_list} 的任何真值，
     *       {@code carry_list} 为空并附 {@code carry_list_meaning=UNKNOWN_NO_SNAPSHOT}；</li>
     *   <li>{@code snapshot_present=true} 时才有 {@code carrier_target}；
     *       {@code carrier_hp} 仅在解析出非负值时出现（格式认不出就不出现，不写 -1）。</li>
     * </ul>
     */
    public java.util.Map<String, Object> carrierSignal() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        boolean present = snapshot != null && !snapshot.isBlank();
        out.put("snapshot_present", present);
        out.put("snapshot_chars", snapshot == null ? 0 : snapshot.length());
        if (!present) {
            // 刻意不写 carrier_target / carrier_hp —— 缺失用「没有这个键」表达
            out.put("snapshot_parsed", Boolean.FALSE);
            out.put("carry_list", List.of());
            out.put("carry_list_meaning", "UNKNOWN_NO_SNAPSHOT");
            return out;
        }
        // snapshot_parsed：非空 ≠ 解析得出东西。snapshot="garbage" 会让 assessCarrier 判 target=NONE，
        // 而 NONE 是个真值（「附近确实没有敌对实体」）。不额外标记的话，下游会把「没读懂」
        // 当成「看过了，没有」—— 与 B21 要禁的是同一类错误，只是发生在 target 上而不是 hp 上。
        out.put("snapshot_parsed", hasReadableKeys());
        CarrierAssessment a = assessCarrier();
        out.put("carrier_target", a.target());
        if (a.hp() >= 0) {
            out.put("carrier_hp", a.hp());
        }
        out.put("carry_list", a.carryList());
        return out;
    }

    /** 快照里是否至少有一个能认出来的键值对，或一个 {@code N/M} 血量写法。 */
    private boolean hasReadableKeys() {
        String raw = snapshot == null ? "" : snapshot;
        if (raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (!parseKeyValues(lower).isEmpty()) {
            return true;
        }
        return java.util.regex.Pattern.compile("(\\d{1,3})\\s*/\\s*\\d{1,3}").matcher(lower).find();
    }

    /** 有快照时给出可读的判断依据；无快照时返回 {@code null}（缺失不伪装成结论）。 */
    public String carrierPreview() {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        return assessCarrier().why();
    }

    public boolean hasSnapshot() {
        return snapshot != null && !snapshot.isBlank();
    }

    /**
     * 解析快照里的键值对。
     *
     * <p>支持三种写法：{@code hp=6/20, armor=none}、{@code "hp": 6, "armor": "none"}、
     * {@code hp:6 armor:none}。
     *
     * <p>关键：<b>只按 , ; 切分，不按空格切</b>。按空格切会把 {@code "hp": 6} 拆成
     * {@code "hp":} 和 {@code 6} 两个碎片，键值就散了（这是 2026-09-29 单测
     * {@code carrierHandlesJsonStyleSnapshot} 抓到的真 bug）。键值两侧都要清洗，
     * 因为 JSON 写法会带来 {@code {} "} 这些噪声字符。
     */
    private static java.util.Map<String, String> parseKeyValues(String s) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String rawPart : s.split("[,;]")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            int sep = -1;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '=' || c == ':') {
                    sep = i;
                    break;
                }
            }
            if (sep <= 0) {
                continue;
            }
            String k = cleanKey(part.substring(0, sep));
            String v = cleanValue(part.substring(sep + 1));
            if (!k.isBlank()) {
                // 值允许为空：那表示「这个键存在但没值」，等价于未知/无，而不是「键不存在」
                out.putIfAbsent(k, v);
            }
        }
        return out;
    }

    /** 键只保留字母数字下划线，丢掉 JSON 的引号与花括号。 */
    private static String cleanKey(String k) {
        return k.replaceAll("[^A-Za-z0-9_]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 值保留字母数字、下划线、点、斜杠、连字符（20/20、iron_chestplate、6.5 都要活下来）。 */
    private static String cleanValue(String v) {
        return v.replaceAll("[^A-Za-z0-9_./-]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 明确为真：true/yes/1/有/存在，以及非否定词的非空物品名。 */
    private static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (isNegative(t)) {
            return false;
        }
        return !("false".equals(t) || "no".equals(t) || "0".equals(t) || "null".equals(t));
    }

    private static boolean isTrue(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim();
        return "true".equals(t) || "yes".equals(t) || "1".equals(t) || "有".equals(t);
    }

    private static boolean isNegative(String t) {
        return "none".equals(t) || "no".equals(t) || "false".equals(t) || "0".equals(t)
                || "null".equals(t) || "nil".equals(t) || "无".equals(t) || "empty".equals(t);
    }

    /**
     * 「这个键没填值」的标记词。
     *
     * <p><b>与 {@link #isNegative} 的区别是本类 2026-10-01 修的真 bug</b>：
     * {@code isNegative("0") == true}，而 {@code extractHp} 曾用它挡掉「0」。
     * 但 {@code hp=0} 是<b>最关键的血量值</b>（濒死 / 已死亡），
     * 被当成「没数据」= 恰好在最该报警的时刻丢掉数据。
     * 布尔型字段（{@code armor=0}）用 {@code isNegative} 对；<b>数值型字段不能用</b>。
     */
    private static boolean isAbsentToken(String t) {
        return "none".equals(t) || "no".equals(t) || "null".equals(t) || "nil".equals(t)
                || "无".equals(t) || "empty".equals(t) || "unknown".equals(t) || "-".equals(t);
    }

    private static boolean containsAny(String hay, String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 从快照里粗抓血量，支持 {@code hp=12} / {@code "hp":12} / {@code 12/20}；抓不到返回 -1。 */
    private static int extractHp(String lower) {
        java.util.Map<String, String> kv = parseKeyValues(lower);
        String hpRaw = kv.get("hp");
        if (hpRaw == null) {
            hpRaw = kv.get("health");
        }
        if (hpRaw != null && !isAbsentToken(hpRaw.trim())) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < hpRaw.length(); i++) {
                char c = hpRaw.charAt(i);
                if (Character.isDigit(c) || c == '.') {
                    digits.append(c);
                } else {
                    break;
                }
            }
            if (digits.length() > 0) {
                try {
                    return (int) Double.parseDouble(digits.toString());
                } catch (NumberFormatException ignored) {
                    // 落到下面的 N/20 分支
                }
            }
        }
        // 形如 12/20
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{1,3})\\s*/\\s*\\d{1,3}").matcher(lower);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 携带器评估结果。
     *
     * @param target    1 指向谁：HOSTILE_NEARBY / PASSIVE_ONLY / NONE / UNKNOWN
     * @param why       三段分级的依据（透明，便于监测台/人工核对）
     * @param carryList 携带清单（只读输出，不落盘）
     * @param hp        抓到的血量；-1 表示没抓到
     */
    public record CarrierAssessment(String target, String why, List<String> carryList, int hp) {}
}
