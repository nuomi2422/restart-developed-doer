package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * ★ B4：<b>检索侧</b>的任务指纹 —— 让「同类任务再次发生 → 命中同一条经验」这件事<b>可判</b>。
 *
 * <p><b>⚠️ 口径澄清（这条断点最容易被做歪，所以先钉死）</b>：
 * 这里算的是<b>拿任务去匹配经验</b>的指纹，<b>不是</b>经验条目自身的去重键
 * （那个是 {@code ExperienceEntry.stableKey(type,title)}，B7 已经修过）。
 * 两者用途不同：<code>stableKey</code> 判「这两条是不是同一条经验的两次落笔」，
 * 本指纹判「这个任务和那条经验讲的是不是同一类场景」。</p>
 *
 * <p><b>为什么需要它（60 号 A6 判据）</b>：A6 要判「再发生一次同类事件时，这条经验被再次命中」，
 * 而修之前<b>没有任何东西能回答这个问题</b> ——
 * {@code ExperienceKnowledgeSource.buildQuery} 只是把
 * {@code objective + stage + knownFailures} 整句拼起来去字面匹配，
 * 没有稳定标识，于是「命中了」和「碰巧命中」分不开，也没法说「这是同一类任务」。</p>
 *
 * <p><b>指纹怎么算（以及为什么用「单字集合」而不是词序）</b>：
 * 把任务文本过一遍 {@link LexicalQueryTokenizer}，对中文取<b>单字集合</b>、
 * 对拉丁文取<b>整词集合</b>，合并后算 SHA-256 前 16 位十六进制。
 * <ul>
 *   <li><b>按集合算，不按顺序</b>：「先挖钻石再回家」与「先回家再挖钻石」指纹相同
 *       —— 它们是同一类任务。</li>
 *   <li>⚠️ <b>中文必须用单字而不是 2-gram</b>：这是被测试逼出来的。
 *       2-gram（E6a 给检索分词用的那套）<b>本身就把语序编码进去了</b> ——
 *       「先<b>石再</b>回…」与「先回<b>家再</b>挖…」的 2-gram 集合不同，
 *       所以「按集合去重排序」**并不能**消掉语序差异。
 *       我第一版注释写「按集合算所以语序无关」，那句话是<b>错的</b>，单字集合才对。
 *       代价：区分度下降（「采集甘蔗」与「甘蔗采集」同指纹）——
 *       但那是可接受的，因为它本来就该同。</li>
 *   <li><b>不截断</b>：曾想过「只取前 N 个 token」让长句与短句能撞上，
 *       但字典序取前 N 个既难解释也难校准（实测「采集甘蔗」与
 *       「采集甘蔗并运回基地的箱子旁边」取前 2 个就不同）。
 *       改成<b>不截断</b>：指纹只判「<b>完全</b>同一段话」，措辞变了就是「近似」，
 *       <b>近似交给检索层的字面打分</b>判，不靠指纹硬凑。
 *       这是更诚实的分工：指纹不假装能做语义相似度。</li>
 *   <li><b>不用 embedding、不用 LLM</b>：本项目现在<b>没有任何向量检索设施</b>，
 *       加一个模型依赖会让「离线可测」这条性质断掉。可被单测钉死比「更聪明」重要。</li>
 * </ul></p>
 *
 * <p><b>⚠️ 指纹相同 <b>不等于</b> 经验适用</b>：它只是「大概同一类场景」。
 * 所以用它做的是<b>命中线索与观测</b>（A6 能判了），<b>不</b>直接决定注入。</p>
 */
public final class TaskFingerprint {

    /**
     * 指纹最多取几个 token（<b>0 = 不截断</b>，这是默认值与推荐值）。
     *
     * <p>⚠️ 保留这个参数只是为了让「将来要收窄」有个入口，<b>现在刻意不截断</b>：
     * 截断的收益（让长句与短句在「讲同一件事」时撞上）要用一个拍脑袋的 N 去买，
     * 而实测那个 N 很难解释（「取字典序前 2 个」听着就怪）。
     * 不截断的代价是「措辞变了就不同指纹」—— 那个交给检索层，不靠指纹。</p>
     */
    public static final int MAX_FINGERPRINT_TOKENS = 0;

    /** 任务指纹为空时的占位（不返回 null，让「没算出来」也有一个可比的值）。 */
    public static final String UNKNOWN = "unknown";

    private TaskFingerprint() {}

    /**
     * 任务文本 → 指纹。
     *
     * @param texts 任务的各个片段（objective / stage / knownFailures…），可为空数组
     * @return 16 位十六进制；<b>没抽出任何 token 时返回 {@link #UNKNOWN}</b>
     *         （不返回 null —— 免得调用方各处判 null 而漏掉一处）
     */
    public static String of(List<String> texts) {
        return of(texts, MAX_FINGERPRINT_TOKENS);
    }

    /** 同上，但显式指定取前几个 token（单测要用，不要在生产里乱传）。 */
    /** 同上，但显式指定取前几个 token（单测要用；{@code <=0} = 不截断）。 */
    public static String of(List<String> texts, int maxTokens) {
        if (texts == null || texts.isEmpty()) {
            return UNKNOWN;
        }
        Set<String> tokens = fingerprintTokens(texts);
        if (tokens.isEmpty()) {
            return UNKNOWN;
        }
        List<String> picked = new ArrayList<>(tokens);
        if (maxTokens > 0 && picked.size() > maxTokens) {
            picked = picked.subList(0, maxTokens);
        }
        return sha256_16(String.join("", picked));
    }

    /**
     * 任务文本 → 指纹用的 token 集合：<b>中文单字 + 拉丁整词</b>，去重 + 字典序。
     *
     * <p>⚠️ <b>这里刻意不用 {@link LexicalQueryTokenizer} 的 2-gram</b>：
     * 2-gram 是给<b>检索分词</b>用的（要「能匹配到」），而指纹要的是「<b>语序无关</b>」——
     * 2-gram 把语序编码进去了（「先<b>石再</b>回」与「先回<b>家再</b>挖」的 2-gram 集合不同），
     * 用它算指纹就<b>不可能</b>语序无关。两个目的不同 ⇒ 两套取法，
     * 这是有意的，不是重复代码。</p>
     */
    private static Set<String> fingerprintTokens(List<String> texts) {
        // TreeSet = 去重 + 字典序，保证「同一组词不同顺序」得到同一个集合
        Set<String> out = new TreeSet<>();
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            for (String part : lower.split("[^\\p{L}\\p{N}]+")) {
                if (part.isBlank()) {
                    continue;
                }
                StringBuilder latin = new StringBuilder();
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (isCjk(c)) {
                        out.add(String.valueOf(c));     // ★ 中文按单字 ⇒ 语序无关
                    } else {
                        latin.append(c);               // 拉丁/数字按整词
                    }
                }
                if (latin.length() > 0) {
                    out.add(latin.toString());
                }
            }
        }
        return out;
    }

    private static boolean isCjk(char c) {
        return (c >= '\u4e00' && c <= '\u9fff')          // 基本汉字
                || (c >= '\u3400' && c <= '\u4dbf')      // 扩展 A
                || (c >= '\u3040' && c <= '\u30ff')      // 日文假名
                || (c >= '\uac00' && c <= '\ud7af');     // 韩文音节
    }

    /**
     * 一组任务的「场景指纹」：多个指纹去重排序。
     *
     * <p>用途：一次规划里往往同时涉及好几件事（挖矿 / 搭桥 / 搬箱子），
     * 它们的指纹不同 ⇒ 合并后能看出「这次任务跨了几类场景」，
     * 而不是糊成一个说不清的串。空集合返回空列表（不返回 null）。</p>
     */
    public static List<String> sceneFingerprints(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                continue;
            }
            out.add(of(List.of(t)));
        }
        out.remove(UNKNOWN);
        return List.copyOf(out);
    }

    /**
     * 经验条目的「适用场景指纹」：拿它的标题 + 检索线索算。
     *
     * <p>⚠️ <b>这是「它声称适用于哪类场景」，不是「它在哪个场景被验证过」</b> ——
     * 后者需要真实命中记录（E8 的 supersede 链也还没到这个精度）。
     * 两者混为一谈就会把「自称适用」读成「实测适用」，那是假事实。</p>
     */
    public static List<String> entrySceneFingerprints(ExperienceEntry e) {
        if (e == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        if (e.title() != null) {
            parts.add(e.title());
        }
        if (e.triggerStrings() != null) {
            parts.addAll(e.triggerStrings());
        }
        return sceneFingerprints(parts);
    }

    /** 两个指纹是否「同一类场景」。{@link #UNKNOWN} 永远不算命中（没算出来就别装作是）。 */
    public static boolean sameScene(String a, String b) {
        return a != null && b != null && !UNKNOWN.equals(a) && a.equals(b);
    }

    private static String sha256_16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，真拿不到就退化成「不用指纹」而不是崩掉整条检索
            return UNKNOWN;
        }
    }

    /** 只保留排序后的 token（给观测/单测看「这个指纹由哪些词构成」）。 */
    /** 只保留排序后的 token（给观测/单测看「这个指纹由哪些词构成」）。 */
    public static List<String> explainTokens(List<String> texts, int maxTokens) {
        if (texts == null) {
            return List.of();
        }
        Set<String> tokens = fingerprintTokens(texts);
        List<String> picked = new ArrayList<>(tokens);
        if (maxTokens > 0 && picked.size() > maxTokens) {
            picked = picked.subList(0, maxTokens);
        }
        return List.copyOf(picked);
    }
}