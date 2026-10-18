package com.dwinovo.numen.experience.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询分词：拉丁词照切，<b>中文按 2-gram 切</b>。
 *
 * <p><b>为什么必须有这一层</b>：{@link LexicalExperienceRetriever} 原来按
 * {@code [^\p{L}\p{N}]+} 切，{@code "挖钻石之前先铺水"} 会变成<b>一个</b> token，
 * 再拿它去做 {@code field.contains(token)} 子串匹配 —— 除非条目里一字不差出现这整句，
 * 否则<b>一条都命中不了</b>。实测后果：中文任务注入经验目录时目录恒为空。
 * （英文路径一直是对的，所以这个洞很隐蔽：单测用英文查询就发现不了。）</p>
 *
 * <p><b>为什么是 2-gram 而不是词典分词</b>：词典要维护、还会有同义问题；
 * 2-gram 无词典、无歧义、召回稳定，是中文检索的常用起点。
 * 代价是「之前」「需要」这类通用片段会命中一堆条目 ⇒ 用 {@link #STOP_BIGRAMS} 挡掉。</p>
 *
 * <p><b>刻意的取舍</b>：单个汉字的连续段（长度 1）<b>不</b>产出 token ——
 * 一个「水」「火」能命中无数条目，会把所有条目的分数一起抬高、反而淹没有用的。
 * 宁可漏召回（目录少一条），不要假命中（目录全是废话、且看起来「在检索」）。</p>
 */
final class LexicalQueryTokenizer {

    /** 功能词 2-gram：命中它们说明不了任何东西，只会把所有条目都抬分。 */
    static final Set<String> STOP_BIGRAMS = Set.of(
            "之前", "之后", "以后", "以前", "现在", "刚才", "这个", "那个", "这些", "那些",
            "我们", "你们", "他们", "它们", "需要", "应该", "可以", "能够", "进行", "通过",
            "一个", "一些", "什么", "怎么", "怎样", "时候", "如果", "但是", "因为", "所以",
            "已经", "还是", "不要", "不能", "以及", "或者", "于是", "然后", "随后", "接着",
            "这里", "那里", "东西", "事情", "而且", "并且", "虽然", "不过", "只是", "正在");

    private LexicalQueryTokenizer() {}

    /**
     * 分词。
     *
     * <p>返回的是<b>有序去重</b>的 token 列表：既包含原来那套「整段保留」的切法
     * （英文行为完全不变），也包含中文 2-gram。</p>
     */
    static List<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String part : lower.split("[^\\p{L}\\p{N}]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!isAllCjk(part)) {
                // 拉丁/数字段：整段保留（原行为，英文命中靠它）
                out.add(part);
            }
            // ★ 纯 CJK 段**不**保留整段：一个「挖钻石之前先铺水」当单 token 时
            //   contains 几乎必然全不命中，留着只是白占一次匹配。
            //   需要整句命中���那条路径由 retriever 的反向匹配（trigger 整串出现）负责。
            addCjkGrams(part, out);
        }
        return new ArrayList<>(out);
    }

    /** 整段是否全是 CJK（长度 > 0）。 */
    private static boolean isAllCjk(String part) {
        for (int i = 0; i < part.length(); i++) {
            if (!isCjk(part.charAt(i))) {
                return false;
            }
        }
        return !part.isEmpty();
    }

    /** 把一段里的每个 CJK 连续段切 2-gram 塞进 {@code out}（停用词与重复去掉）。 */
    private static void addCjkGrams(String part, Set<String> out) {
        int i = 0;
        int n = part.length();
        while (i < n) {
            if (!isCjk(part.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isCjk(part.charAt(i))) {
                i++;
            }
            int end = i;
            // 长度 1 的 CJK 段不产出 token：见类注释的取舍说明
            for (int k = start; k + 1 < end; k++) {
                String g = part.substring(k, k + 2);
                if (!STOP_BIGRAMS.contains(g)) {
                    out.add(g);
                }
            }
        }
    }

    /**
     * 是否 CJK 汉字。
     *
     * <p><b>范围用数值而不是字面量</b>：U+3400（㐀）、U+FAFF（﫿）这类生僻码位
     * 写成字符字面量会在编辑/传输链路上被改写（本机实测过一次：写进去的字面量
     * 与预期的码位对不上，{@code isCjk} 把假名判成了汉字）。数值转义没有这个问题。</p>
     *
     * <p>覆盖 CJK 统一汉字 U+4E00–U+9FFF、扩展 A U+3400–U+4DBF、
     * 兼容汉字 U+F900–U+FAFF。</p>
     *
     * <p>⚠️ <b>不把假名/谚文算进来</b>：那些文字的 2-gram 语义密度和中文不同，
     * 而且本工程的经验条目基本是中文/英文混排。判错会静默产出无意义的 token。</p>
     */
    static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }
}