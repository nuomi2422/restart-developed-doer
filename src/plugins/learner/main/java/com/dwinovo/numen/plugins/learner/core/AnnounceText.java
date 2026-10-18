package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回话窄门的<b>可判定部分</b>（纯 Java，可离线测；发字那一行在 {@link Announce}）。
 *
 * <p><b>为什么要拆开</b>：发字要碰 {@code NumenPlayer}（Minecraft 类），
 * 而限频/措辞/截断这些真正会出错的逻辑不需要它。把它们留在这里，
 * 就能用单测钉住 —— 否则每次验证都得起游戏。
 *
 * <p>三条硬约束（缺一个就变成新噪声）：
 * <ol>
 *   <li><b>只说事实，不下结论</b> —— 不写「我学会了」「以后不会了」这类自评；</li>
 *   <li><b>限频</b> —— 同一同伴默认 {@value #DEFAULT_MIN_GAP_SECONDS} 秒一句，
 *       复审是批量触发的，不限频就刷屏，而刷屏等于没说话；</li>
 *   <li><b>可关</b> —— 出问题时能一键闭嘴。</li>
 * </ol>
 */
public final class AnnounceText {

    /** 默认最小间隔（秒）：同一同伴两次之间至少隔这么久。 */
    public static final int DEFAULT_MIN_GAP_SECONDS = 90;

    /** 单条上限：超了截断加省略号 —— 聊天栏塞不下整段 JSON。 */
    public static final int MAX_CHARS = 220;

    private static final Map<java.util.UUID, Long> LAST_SPOKEN = new ConcurrentHashMap<>();

    private static volatile boolean enabled = true;
    private static volatile int minGapSeconds = DEFAULT_MIN_GAP_SECONDS;

    private AnnounceText() {
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setMinGapSeconds(int v) {
        minGapSeconds = Math.max(0, v);
    }

    public static int minGapSeconds() {
        return minGapSeconds;
    }

    public static long lastSpoken(java.util.UUID id) {
        return LAST_SPOKEN.getOrDefault(id, 0L);
    }

    public static void reset() {
        LAST_SPOKEN.clear();
    }

    /**
     * 能不能现在说一句。
     *
     * <p><b>先占位再返回 true</b>：并发下两条同时检查都会通过，占位让后来的被吞掉。
     * 发送失败时用 {@link #undo} 撤回占位，否则这次会白占额度。
     */
    public static boolean tryClaim(java.util.UUID id, long now) {
        if (!enabled || id == null) {
            return false;
        }
        Long last = LAST_SPOKEN.get(id);
        if (last != null && now - last < minGapSeconds() * 1000L) {
            return false;
        }
        LAST_SPOKEN.put(id, now);
        return true;
    }

    /** 发送失败时撤回限频占用（否则这次白占额度，下次该说的话会被吞）。 */
    public static void undo(java.util.UUID id, long claimedAt) {
        LAST_SPOKEN.remove(id, claimedAt);
    }

    /**
     * 把一条复审结果拼成一句话。
     *
     * <p><b>只报事实</b>：学了几条、产物落了什么、下游拒了什么。
     *
     * @return 可以是 null（没什么可说的就别开口 —— 沉默优于噪声）
     */
    public static String reviewLine(int verdictCount, int committed, int restored,
                                    int acLanded, int carrierPending, int acRejected,
                                    int acAdopted) {
        if (verdictCount <= 0 && committed <= 0 && restored <= 0
                && acLanded <= 0 && carrierPending <= 0 && acRejected <= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[Numen/学习者] 复审了 ").append(verdictCount).append(" 条判定");
        if (committed > 0) {
            sb.append("，入库 ").append(committed).append(" 条");
        }
        if (restored > 0) {
            // 还原是真事实，必须说：主人要知道有东西没被判掉
            sb.append("，退回 ").append(restored).append(" 条(没判出来，不丢)");
        }
        if (acLanded > 0) {
            sb.append("；AC 草稿 ").append(acLanded).append(" 条在等采纳");
        }
        if (acAdopted > 0) {
            // 已采纳但**还没上线** —— 两件事不许混成一句
            sb.append("；已采纳 ").append(acAdopted).append(" 条(在版本库，未上线)");
        }
        if (carrierPending > 0) {
            sb.append("；携带器草稿 ").append(carrierPending).append(" 条待审批");
        }
        if (acRejected > 0) {
            sb.append("；有 ").append(acRejected).append(" 条 AC 草稿被拒收(原因见监测台)");
        }
        return clip(sb.toString());
    }

    /** 截断 + 压空白：聊天栏一行放不下整段。 */
    public static String clip(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.trim().replaceAll("\\s+", " ");
        if (flat.isEmpty()) {
            return "";
        }
        return flat.length() <= MAX_CHARS ? flat : flat.substring(0, MAX_CHARS) + "…";
    }
}