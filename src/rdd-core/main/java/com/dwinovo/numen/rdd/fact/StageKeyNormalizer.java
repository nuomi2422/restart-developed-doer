package com.dwinovo.numen.rdd.fact;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * 阶段事实键归一器（P0 保守版）。
 *
 * <p>目标只有一个：让"同一个战略阶段"在 LLM 每次换措辞时尽量归一到同一个键，从而让
 * {@link CompletedFactStore} 能认出"这件事过去是否已经被可靠完成过"。P0 <b>刻意不做</b>
 * 同义词/语义模型（那属于后续 P2），只做机械归一：Unicode NFKC + 小写 + 去空白标点 +
 * 去高频动作停用词；归一后为空则回退到原文哈希，保证永不丢键、永不抛异常。
 *
 * <p>纯 JVM，不碰 Minecraft。
 */
public final class StageKeyNormalizer {

    /** P0 高频动作词（子串级剔除）。刻意保守：只去不影响阶段主题识别的动词。 */
    private static final List<String> STOP_WORDS = List.of(
            "获得", "获取", "得到", "进行", "完成", "达到", "准备", "开始");

    private StageKeyNormalizer() {}

    /** 归一到阶段键；输入 null/空白返回 "empty"。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "empty";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                kept.append(c);
            }
            // 空白与标点/符号一律丢弃：只保留字母数字（含 CJK）
        }
        String key = kept.toString();
        for (String stop : STOP_WORDS) {
            key = key.replace(stop, "");
        }
        if (key.isBlank()) {
            // 归一后为空（纯标点/纯停用词）→ 回退原文哈希，保证键稳定且非空
            String basis = raw.strip().isEmpty() ? "empty" : raw.strip();
            return "raw:" + Integer.toHexString(basis.hashCode());
        }
        return key;
    }
}
