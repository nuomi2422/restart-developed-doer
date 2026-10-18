package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * 学习者的<b>回话窄门</b>（2026-10-05）—— 真正发字的那一行。
 *
 * <p><b>为什么要有</b>：用户原话「给白马打一个可回复的地方，不是一直裸着写」。
 * 此前学习者一直在写（备忘录、判定、产物、账本），但<b>从不把结果说出来</b> ——
 * 写完只有日志和监测台里有人看得见，主人只看到同伴「什么都没说」。
 *
 * <p>判定逻辑全在 {@link AnnounceText}（纯 Java、可离线测）；本类只负责
 * 拿到主人并把一句��发出去。刻意<b>不</b>做排队/补发/攒批次 ——
 * 「等一会儿合并成一句」会在出错时把话说错顺序，而这里的话本来就短。
 */
public final class Announce {

    private Announce() {
    }

    public static boolean enabled() {
        return AnnounceText.enabled();
    }

    public static void setEnabled(boolean v) {
        AnnounceText.setEnabled(v);
    }

    public static int minGapSeconds() {
        return AnnounceText.minGapSeconds();
    }

    public static void setMinGapSeconds(int v) {
        AnnounceText.setMinGapSeconds(v);
    }

    /**
     * 说一句。返回是否真说出去（被限频/关闭/主人不在线都会返回 false）。
     *
     * <p>★ 主人不在线时<b>撤回限频占用</b>：这次没发出去就不该占额度，
     * 否则主人一上线就会被一句「额度已被你自己用掉」吞掉的话。
     */
    public static boolean say(NumenPlayer companion, String text) {
        if (companion == null) {
            return false;
        }
        String msg = AnnounceText.clip(text);
        if (msg.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        java.util.UUID id = companion.getUUID();
        if (!AnnounceText.tryClaim(id, now)) {
            return false;
        }
        try {
            var owner = companion.resolveOwnerPlayer();
            if (owner == null) {
                AnnounceText.undo(id, now);
                return false;
            }
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
            return true;
        } catch (RuntimeException e) {
            AnnounceText.undo(id, now);
            return false;
        }
    }

    /** 复审完顺手说一句（内容与措辞在 {@link AnnounceText#reviewLine}）。 */
    public static boolean announceReview(NumenPlayer companion, int verdictCount, int committed,
                                         int restored, int acLanded, int carrierPending, int acRejected,
                                         int acAdopted) {
        return say(companion, AnnounceText.reviewLine(verdictCount, committed, restored,
                acLanded, carrierPending, acRejected, acAdopted));
    }
}