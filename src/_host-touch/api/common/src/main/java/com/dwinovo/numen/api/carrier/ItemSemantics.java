package com.dwinovo.numen.api.carrier;

import java.util.List;
import java.util.Locale;

/**
 * 物品**语义**判定（{@code 38} v3.5 §2）。
 *
 * <p><b>为什么不能靠「非空」</b>：这是同一个错误模式的<b>第二次</b>出现。
 * <ul>
 *   <li>2026-09-29：{@code contains("armor")} 把 {@code armor=none} 判成「有护甲」（Codex 审出，已修）</li>
 *   <li>2026-10-01：{@code truthy()} 把 {@code weapon=smart_slab_init}（某模组的<b>台阶方块</b>）
 *       判成「有武器」；{@code armor=dirt} 同理</li>
 * </ul>
 *
 * <p><b>教训</b>：判「某类东西在不在」，<b>「字段非空」几乎总是不够的</b> ——
 * 非空的那一类比你要的那一类<b>大得多</b>。
 * <b>判据必须是「属于哪一类」，不是「有没有值」。</b>
 *
 * <p><b>边界</b>：本类只做<b>字符串后缀/包含</b>判定，
 * <b>不 import {@code core.common} 的 {@code Loadout}</b>
 * （{@code numen-plugin.gradle:37-44} 刻意封死跨插件 import，{@code 38} B4 要求挡住这个 import），
 * 也<b>不 import 任何 MC 类</b>。代价是只能按 id 路径判，不能查物品注册表。
 */
public final class ItemSemantics {

    private ItemSemantics() {
    }

    /**
     * 原版 + 常见模组里能造成伤害的物品 id <b>后缀</b>（<b>统一不带下划线前缀</b>）。
     *
     * <p>⚠️ 这里曾经写成混的（{@code "_sword"} 带前缀、{@code "bow"} 不带），
     * 而检查逻辑给每一项都拼了下划线 → {@code trident} 漏判成「不是武器」。
     * <b>教训：同一张表里的每一项必须同一种写法</b>，否则「看起来对」但边界上会漏。
     */
    public static final List<String> WEAPON_SUFFIXES = List.of(
            "sword", "axe", "bow", "crossbow", "trident", "mace");

    /** 护甲槽位物品的 id 后缀。 */
    public static final List<String> ARMOR_SUFFIXES = List.of(
            "_helmet", "_chestplate", "_leggings", "_boots", "_turban", "_mask");

    /** 敌对实体 id 名片段。 */
    public static final List<String> HOSTILE_NAMES = List.of(
            "zombie", "skeleton", "creeper", "spider", "enderman", "drowned", "husk",
            "stray", "wither", "blaze", "ghast", "witch", "pillager", "vindicator", "ravager");

    /** 被动实体 id 名片段。 */
    public static final List<String> PASSIVE_NAMES = List.of(
            "cow", "pig", "sheep", "chicken", "villager", "horse", "rabbit", "goat", "axolotl");

    /** 明确表示「没有」的否定词。 */
    public static final List<String> NEGATIVE = List.of("none", "no", "false", "null", "nil", "empty", "无");

    public static boolean isNegativeToken(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || NEGATIVE.contains(t);
    }

    /**
     * 这个 id 路径<b>是不是武器</b>。
     *
     * <p><b>认不出来就返回 false</b>（调用方据此判 UNKNOWN），**不猜**。
     */
    public static boolean isWeapon(String idPath) {
        if (isNegativeToken(idPath)) {
            return false;
        }
        String t = idPath.trim().toLowerCase(Locale.ROOT);
        for (String s : WEAPON_SUFFIXES) {
            if (t.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /** 这个 id 路径<b>是不是护甲</b>。认不出来返回 false。 */
    public static boolean isAnyArmor(String idPath) {
        if (isNegativeToken(idPath)) {
            return false;
        }
        String t = idPath.trim().toLowerCase(Locale.ROOT);
        for (String s : ARMOR_SUFFIXES) {
            if (t.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /** 文本里是否出现任一实体名片段。 */
    public static boolean anyContains(String haystackLower, List<String> needles) {
        if (haystackLower == null || haystackLower.isBlank()) {
            return false;
        }
        for (String n : needles) {
            if (haystackLower.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 供 {@link CarrierChain.Facts} 取血量（避免与 {@link Memo} 的 private 方法重复实现）。 */
    static final class MemoFactsBridge {
        private MemoFactsBridge() {
        }

        static int hpOf(String lower) {
            java.util.Map<String, String> kv = new java.util.LinkedHashMap<>();
            for (String part : lower.split("[,;]")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int sep = -1;
                for (int i = 0; i < p.length(); i++) {
                    char c = p.charAt(i);
                    if (c == '=' || c == ':') {
                        sep = i;
                        break;
                    }
                }
                if (sep <= 0) {
                    continue;
                }
                String k = p.substring(0, sep).replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
                String v = p.substring(sep + 1).replaceAll("[^A-Za-z0-9_./-]", "").toLowerCase(Locale.ROOT);
                if (!k.isBlank()) {
                    kv.putIfAbsent(k, v);
                }
            }
            String hpRaw = kv.get("hp");
            if (hpRaw == null) {
                hpRaw = kv.get("health");
            }
            if (hpRaw != null && !ItemSemantics.isNegativeToken(hpRaw)) {
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
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,3})\\s*/\\s*\\d{1,3}").matcher(lower);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            return -1;
        }
    }
}