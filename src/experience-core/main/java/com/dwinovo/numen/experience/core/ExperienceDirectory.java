package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceHit;

import java.util.ArrayList;
import java.util.List;

/**
 * 分级注入的 <b>L0 目录层</b>渲染（{@code 59} 号 §8.1）。
 *
 * <p><b>为什么要有这一层，而不只是三个计数</b>：断点 B3 的实测是
 * {@code <experience>} 标签里<b>只有 total/verified/generalized 三个数，没有任何正文</b>，
 * 经验正文只能靠 AI 主动调 {@code experience_recall} —— 而 AI 不知道该查什么，
 * 于是这层等于不存在。目录层的意义就是<b>让 AI 先看见「我有什么经验」</b>，
 * 看见之后才可能决定要不要展开（L1/L2）或主动查。</p>
 *
 * <p><b>三条硬约束</b>（与 {@code PlanningKnowledge} 同源）：</p>
 * <ol>
 *   <li><b>不伪造</b>：库里没有就返回空串，不编一条。</li>
 *   <li><b>不假装是任务相关内容</b>：{@code contributeState} 拿不到当前任务
 *       （只看得到同伴 UUID），所以这块目录<b>不是</b>按当前任务筛的。
 *       块里必须<b>明说</b>这一点，否则 AI 会把「排在前面」读成「跟我现在的任务有关」。</li>
 *   <li><b>不超预算</b>：条数与字符双预算，超了截断并<b>如实标 truncated</b>。</li>
 * </ol>
 *
 * <p><b>纯 JVM、零 MC/NUMEN 依赖</b>：渲染是纯函数，可以直接单测字节级的输出。</p>
 */
public final class ExperienceDirectory {

    /** 目录最多列几条。 */
    public static final int DEFAULT_MAX_ROWS = 6;
    /** 目录正文字符预算。 */
    public static final int DEFAULT_MAX_CHARS = 700;
    /** 单行上限，防止一条超长标题吃光预算。 */
    public static final int MAX_ROW_CHARS = 90;
    /** 行内「适用线索」的条数与单条长度。 */
    private static final int MAX_TRIGGERS = 2;
    private static final int MAX_TRIGGER_CHARS = 14;

    private ExperienceDirectory() {}

    /**
     * 一行目录。
     *
     * @param id        经验 id —— <b>必须给</b>：AI 看到目录后要能拿 id 去
     *                  {@code experience_recall} / {@code experience_verify}
     * @param maturity  成熟度名（OBSERVED/ATTEMPTED/VERIFIED/GENERALIZED）
     * @param title     标题
     * @param triggers  适用线索（取自 triggerStrings，截断）
     * @param priority  优先级（用于「值得先看哪条」的排序直觉）
     */
    public record Row(String id, String maturity, String title, String triggers, int priority) {

        public Row {
            id = id == null ? "" : id;
            maturity = maturity == null ? "" : maturity;
            title = title == null ? "" : title;
            triggers = triggers == null ? "" : triggers;
        }
    }

    /**
     * 渲染结果。
     *
     * @param text      整块 XML 文本（无经验时为空串）
     * @param rows      实际列出的行（顺序即展示顺序）
     * @param truncated 是否因预算被截断
     */
    public record Block(String text, List<Row> rows, boolean truncated) {

        public Block {
            text = text == null ? "" : text;
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        public boolean empty() {
            return rows.isEmpty();
        }
    }

    /**
     * 渲染 L0 目录。
     *
     * @param hits       候选（<b>调用方负责排序</b>：通常是
     *                   {@code recall("", n, …)} 的可信度兜底排序）
     * @param total      库里总条数
     * @param verified   已验证条数
     * @param generalized 泛化条数
     * @param stats      加载读数（可为 {@code null}；给了就把可读/归一/重复/降级报出来）
     * @param maxRows    条数预算（{@code <=0} 用默认值）
     * @param maxChars   字符预算（{@code <=0} 用默认值）
     */
    public static Block render(List<ExperienceHit> hits, int total, int verified, int generalized,
                               ExperienceStore.LoadStats stats, int maxRows, int maxChars) {
        return render(hits, total, verified, generalized, stats, null, maxRows, maxChars);
    }

    /**
     * ★ E8 增强签名：多带一个 {@link ExperienceStats}（撤回/取代的读数）。
     *
     * <p>保留上面那个 7 参重载是有意的 —— 它让「不知道 E8 状态」的调用方
     * <b>照旧能编译</b>，代价是那三个键不出现（如实表现为「没测到」而不是「0」）。
     * 消掉重载、逼所有调用方都补 E8 读数，也可以，但那是把一个<b>可选项</b>
     * 变成<b>必填项</b>：现存调用方里有一半拿不到 E8 状态，会逼它们编 0。</p>
     */
    public static Block render(List<ExperienceHit> hits, int total, int verified, int generalized,
                               ExperienceStore.LoadStats stats, ExperienceStats estats,
                               int maxRows, int maxChars) {
        if (hits == null || hits.isEmpty() || total <= 0) {
            return new Block("", List.of(), false);
        }
        int rows = maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        int chars = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;

        StringBuilder sb = new StringBuilder();
        sb.append("<experience>\n");
        // 三个原有计数原样保留（面板与已有解析都按它们读）
        sb.append("<total>").append(total).append("</total>");
        sb.append("<verified>").append(verified).append("</verified>");
        sb.append("<generalized>").append(generalized).append("</generalized>");
        if (stats != null) {
            sb.append("<readable>").append(stats.total()).append("</readable>");
            sb.append("<rekeyed>").append(stats.rekeyed()).append("</rekeyed>");
            sb.append("<duplicate>").append(stats.duplicate()).append("</duplicate>");
            sb.append("<degraded>").append(stats.degraded()).append("</degraded>");
        }
        sb.append("\n");

        // ★ E8：把「有多少条已经不能用了」一起报出来。
        //   不报的话 <total>22</total> 会让同伴以为 22 条都能用，
        //   而其中可能有几条已被撤回/已被取代 —— 那是假事实。
        //   ⚠️ 取不到 estats（null）时这几个键**不出现**，而不是报 0 冒充「一条都没撤回」——
        //   「没测到」和「确实是 0」必须能分开（B21 同一条纪律）。
        if (estats != null) {
            sb.append("<usable>").append(estats.usable()).append("</usable>");
            sb.append("<retracted>").append(estats.retracted()).append("</retracted>");
            sb.append("<superseded>").append(estats.superseded()).append("</superseded>");
            sb.append("\n");
        }

        List<Row> out = new ArrayList<>();
        boolean truncated = false;
        for (ExperienceHit hit : hits) {
            if (out.size() >= rows) {
                truncated = true;
                break;
            }
            ExperienceEntry e = hit.entry();
            Row row = new Row(e.id(), e.maturity().name(), clip(e.title(), MAX_ROW_CHARS),
                    clip(triggerHint(e), MAX_TRIGGER_CHARS * MAX_TRIGGERS), e.priority());
            String line = "<n m=\"" + esc(row.maturity()) + "\" p=\"" + row.priority()
                    + "\" id=\"" + esc(row.id()) + "\">" + esc(row.title());
            if (!row.triggers().isBlank()) {
                line += " ｜ " + esc(row.triggers());
            }
            line += "</n>\n";

            if (sb.length() + line.length() + closeTag().length() > chars) {
                truncated = true;
                break;
            }
            sb.append(line);
            out.add(row);
        }

        // ★ 实测抓到的洞：调用方（contributeState）传进来的 hits 本身就是 recall(..., maxRows) 的
        // 结果，被预算砍掉的那部分**根本不在 hits 里** ⇒ 循环里永远看不到「还有更多」，
        // 于是 22 条只列 6 条时 truncated 竟然报 false（AI 会以为这就是全部）。
        // 判据改成「列出来的条数 < 库里的总条数 ⇒ 一定截断过」，不依赖调用方给不给得全。
        truncated = truncated || out.size() < total;

        sb.append("<listed>").append(out.size()).append("</listed>");
        sb.append("<truncated>").append(truncated).append("</truncated>");
        // ★ 明说「这不是按当前任务筛的」——否则 AI 会把顺序读成相关性
        sb.append("<note>").append(ESCAPED_NOTE).append("</note>");
        sb.append("\n").append(closeTag()).append('\n');
        return new Block(sb.toString(), out, truncated);
    }

    private static String closeTag() {
        return "</experience>";
    }

    /** 块内固定说明（已转义版本，避免每次拼接再转义）。 */
    private static final String ESCAPED_NOTE =
            "这是本同伴经验库的目录，<b>未按当前任务筛选</b>；"
                    + "与你要做的事相关就用 id 调 experience_recall 展开，"
                    + "不相关就当没看见。maturity 越靠后越可信，OBSERVED 只是被观察过一次。";

    /** 取触发词做「适用线索」，最多 {@link #MAX_TRIGGERS} 条、每条 {@link #MAX_TRIGGER_CHARS} 字。 */
    private static String triggerHint(ExperienceEntry e) {
        if (e.triggerStrings().isEmpty()) {
            return "";
        }
        List<String> picked = new ArrayList<>();
        for (String t : e.triggerStrings()) {
            if (t != null && !t.isBlank()) {
                picked.add(clip(t, MAX_TRIGGER_CHARS));
            }
            if (picked.size() >= MAX_TRIGGERS) {
                break;
            }
        }
        return String.join("/", picked);
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * XML 最小转义：{@code & < >} 与引号。
     *
     * <p>⚠️ <b>必须转义</b>：标题里出现 {@code <} 会把整块标签结构打乱，
     * 而注入侧解析是按字符串找标签的 —— 一个未转义的 {@code <} 能让后面整块内容
     * 「消失」（找不���闭合标签），且<b>不会报错</b>。</p>
     */
    static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}