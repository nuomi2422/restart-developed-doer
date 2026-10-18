package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「上轮被下游拒收的产物 + 拒收原因」摆进复审 prompt（B6/S2，2026-10-05）。
 *
 * <p><b>为什么必须有这个</b>：实机三轮的教训 ——
 * AI 连着交了三稿 AC 草稿：散文 → {@code step 缺 id} → 编了个代码库里根本不存在的键
 * {@code non_empty}。<b>三轮的错误它自己一点都不知道</b>，因为
 * {@link PriorRound} 只读上一轮的 {@code verdicts}，<b>不读投递箱里的拒收原因</b>。
 * ⇒ 模型在「盲猜」，而盲猜是不会收敛的。
 *
 * <p><b>最小改动、最大收益</b>：完整 AC schema 塞不进提示词（它随版本变、很长），
 * 但「你上次这么写，被拒了，原因是这个」<b>一句话就能带</b>。
 * 把盲猜变成「按报错改」，采纳率才可能从 0 变成正数。
 *
 * <p><b>失败模式不设计成静默</b>：投递箱读不出来时 {@link #promptBlock()} 返回
 * 「读不到拒收记录」，而不是悄悄给一段空白 —— 那会让「为什么模型老是重犯」变成无头案。
 */
public final class RejectionFeedback {

    /** 每类最多带几条拒收（再多就是噪声，模型读不完也改不动）。 */
    public static final int MAX_PER_KIND = 3;

    /** 单条原因裁剪长度：够看出错在哪，又不至于把 prompt 撑爆。 */
    public static final int MAX_DETAIL_CHARS = 220;

    private final Map<ArtifactOutbox.Kind, List<Item>> byKind = new LinkedHashMap<>();

    /** 一条拒收记录。 */
    public record Item(String name, String detail, String when) {
    }

    /**
     * 从投递箱里挑出<b>本同伴</b>最近的拒收记录。
     *
     * <p>★ <b>同伴隔离是硬要求，不是优化</b>（2026-10-05 加固）：
     * 投递箱在 {@code config/numen/artifact-outbox/} 下<b>混着所有同伴</b>的记录。
     * 若不过滤，A 的失败原因会作为「上轮情况」喂给 B，而每类只留 N 条 ——
     * <b>A 的拒收记录会挤掉 B 自己的</b>。
     *
     * <p>过滤按记录里的 {@code companion_id} 字段（{@link ArtifactOutbox} 写入），
     * <b>不靠路径/文件名猜</b>。
     *
     * @param readable 投递箱是否读得到；读不到时 {@link #promptBlock()} 会明说，
     *                 <b>不假装「没有拒收」</b>
     * @param companionId 只看这个同伴的；{@code null} ⇒ <b>什么都看不到</b>
     *                     （不退化成「把全部都喂出去」，那等于没隔离）
     */
    public static RejectionFeedback scan(ArtifactOutbox outbox, boolean readable,
                                         java.util.UUID companionId) {
        RejectionFeedback fb = new RejectionFeedback();
        if (!readable || outbox == null || companionId == null) {
            return fb;
        }
        String want = companionId.toString();
        for (ArtifactOutbox.Kind kind : ArtifactOutbox.Kind.values()) {
            List<Item> items = new ArrayList<>();
            List<JsonObject> all;
            try {
                all = outbox.list(kind);
            } catch (RuntimeException e) {
                continue;
            }
            for (JsonObject o : all) {
                if (!ArtifactOutbox.Status.REJECTED.name().equals(str(o, "status"))) {
                    continue;
                }
                if (!want.equals(str(o, "companion_id"))) {
                    // ★ 别的同伴的拒收不进这一份回喂
                    continue;
                }
                items.add(new Item(str(o, "name"), str(o, "status_detail"), str(o, "updated_at")));
            }
            if (items.isEmpty()) {
                continue;
            }
            // 按<b>更新时间</b>排序后取尾部 —— 旧实现是「文件名有序取尾部」，
            // 而文件名含 memoId/摘要，<b>顺序与时间无关</b>，取出来的「最近 N 条」是随机的。
            items.sort(java.util.Comparator.comparing(Item::when, java.util.Comparator.nullsFirst(
                    java.util.Comparator.naturalOrder())));
            int from = Math.max(0, items.size() - MAX_PER_KIND);
            fb.byKind.put(kind, List.copyOf(items.subList(from, items.size())));
        }
        return fb;
    }

    public boolean isEmpty() {
        return byKind.isEmpty();
    }

    public int count() {
        int n = 0;
        for (List<Item> l : byKind.values()) {
            n += l.size();
        }
        return n;
    }

    /**
     * 摆进 prompt 的一段文本。
     *
     * <p><b>读不到投递箱时也要说话</b>：返回「读不到拒收记录」而不是空串 ——
     * 空串会被当成「没有拒收」，那就等于把「读不到」伪装成「没问题」。
     */
    public String promptBlock() {
        if (byKind.isEmpty()) {
            return "（投递箱里没有拒收记录：产物还没被下游拒过，或投递箱读不到。）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("★★ 你上轮交下去的产物被下游拒收了。下面是拒收原因，**这次别再犯同样的错**：\n");
        for (Map.Entry<ArtifactOutbox.Kind, List<Item>> e : byKind.entrySet()) {
            sb.append("【").append(e.getKey().wire()).append("】\n");
            for (Item i : e.getValue()) {
                sb.append("  - 名字：").append(i.name().isBlank() ? "(未命名)" : i.name()).append('\n');
                sb.append("    拒收原因：").append(clip(i.detail())).append('\n');
            }
        }
        sb.append("★ 注意：AC 草稿的形状以真实的 .ac 脚本为准（每个 step 有 id/block/params，"
                + "没有 trigger 字段，条件写成 block:\"guard\" 的 step）；"
                + "block 名**不许自创**，只能从 system 里的【可用积木】表挑；"
                + "凑不出**可执行的动作序列**时不要声明 USE_AC —— "
                + "交只读空壳比不交更坏：它会真的被执行、真的报 SUCCESS，而世界一点没变。\n");
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) {
            return "(没写原因)";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return "(没写原因)";
        }
        return t.length() <= MAX_DETAIL_CHARS ? t : t.substring(0, MAX_DETAIL_CHARS) + "…";
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive()
                ? o.get(k).getAsString() : "";
    }
}