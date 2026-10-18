package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.monitor.MonitoringJournal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 同一工具、同一种错误反复失败的看门狗。
 *
 * <h2>为什么要有它</h2>
 * 实测（2026-10-01，进游戏观察者流程）里有两个真实事故，<b>现有的 loop_detected 都抓不到</b>：
 * <ul>
 *   <li>{@code scaffold_materials} 连拒 4 次：每次都是 {@code Expected BEGIN_ARRAY but was
 *       STRING at path $.block_ids}。模型换过两次 payload 形状（JSON 字符串数组、裸标量），
 *       两次都不对，但它自己拿不到任何"你已经试过了"的信号。</li>
 *   <li>{@code goto} 连报 4 次 NO-PATH：目标、起点全一样。根因是那句文案把
 *       "这次没被允许放方块"说成了"你没带垫路料"，模型于是照着错误诊断反复重试。</li>
 * </ul>
 * 两条的共同点：<b>失败发生在同一个工具上、错误原因文本相同，而模型收到的每次回复
 * 都和上一次逐字相同</b>。原有的循环检测盯的是"同一个子任务被反复重启"，
 * 工具协议层这一层是<b>盲区</b>。
 *
 * <h2>这里做两件事
 * <ol>
 *   <li><b>埋点</b>：往 {@code tools.jsonl} 写一条 {@code tool_loop}（不是
 *       {@code tool_rejected} —— 那条是"失败原因"，混进去会污染离线聚类；
 *       {@code tool_loop} 是"重复行为"，语义不同）。</li>
 *   <li><b>给模型一句人话</b>：把"你已经用同一种方式失败 N 次了，别再原样重发，
 *       换一个真正不同的做法"塞进失败回复里。这是唯一能真的打断循环的出口 ——
 *       模型看不见自己的失败计数。</li>
 * </ol>
 *
 * <h2>判定口径
 * <ul>
 *   <li>计数是 <b>per(同伴, 工具)</b> 的，指纹 = 错误原因里的<b>数字全部归一</b>
 *       （坐标、距离、id 都算数字）。所以"about 24 blocks away"和"about 40 blocks away"
 *       算同一次循环，"BEGIN_ARRAY at $.block_ids"和"at $.moves"不会。</li>
 *   <li>该工具一旦<b>成功</b>，计数清零。</li>
 *   <li>超过 {@link #STALE_MS} 没动静的条目视为过期 —— 对话可能早就换题了，
 *       留着会把无关的历史算进循环。</li>
 *   <li>提醒只在第 3 次和之后每 3 次发一次，不每条都发：否则光提醒就把上下文吃掉了。</li>
 * </ul>
 *
 * <h2>已知代价
 * <ul>
 *   <li>静态状态，进程重启即清空 —— 这是有意的：跨局不算循环。</li>
 *   <li>归一化数字会把"距离不同的两次失败"判成同一次。宁可多提醒一句，也不要漏掉循环。</li>
 * </ul>
 */
public final class ToolCallLoopWatch {

    /** 第几次同因失败开始提醒模型。 */
    public static final int WARN_AT = 3;
    /** 提醒之后的重复节奏：第 3、6、9…次。 */
    private static final int WARN_EVERY_AFTER = 3;
    /** 多久没动静就算过期（毫秒）。 */
    private static final long STALE_MS = 5L * 60_000L;
    private static final int MAX_TRACKED = 512;
    private static final int MAX_SAMPLE_CHARS = 400;

    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private record Hit(String fingerprint, String reason, String args, int count, long lastMs) {}

    private static final Map<String, Hit> HITS = new ConcurrentHashMap<>();

    private ToolCallLoopWatch() {}

    /**
     * 记一次工具被拒；返回要追加给模型的那句提醒（不需要提醒时返回 {@code null}）。
     *
     * <p>纯观测 + 一段文本，不改任何执行/重试逻辑。
     */
    public static String onRejected(UUID companionId, String tool, String reason, String argsJson) {
        if (companionId == null || tool == null || tool.isBlank()) return null;
        String key = companionId + "|" + tool;
        long now = System.currentTimeMillis();
        String fp = fingerprint(reason);
        Hit prev = HITS.get(key);
        int count = 1;
        if (prev != null && now - prev.lastMs() <= STALE_MS && prev.fingerprint().equals(fp)) {
            count = prev.count() + 1;
        }
        HITS.put(key, new Hit(fp, clip(reason, MAX_SAMPLE_CHARS), clip(argsJson, MAX_SAMPLE_CHARS), count, now));
        evictIfCrowded();
        if (count < WARN_AT || (count - WARN_AT) % WARN_EVERY_AFTER != 0) return null;
        publishLoop(companionId, tool, fp, reason, argsJson, count);
        return buildAdvice(tool, reason, count);
    }

    /** 该工具成功了 → 计数清零（只清这个工具，不动别的）。 */
    public static void onSucceeded(UUID companionId, String tool) {
        if (companionId == null || tool == null) return;
        HITS.remove(companionId + "|" + tool);
    }

    /** 当前记账条目数（自检/单测用）。 */
    public static int tracked() {
        return HITS.size();
    }

    // ------------------------------------------------------------------ 内部

    /** 数字归一后的指纹：坐标/距离/id 全抹平，只留下"是哪一类失败"。 */
    static String fingerprint(String reason) {
        if (reason == null) return "";
        String s = NUMBER.matcher(reason.toLowerCase(Locale.ROOT)).replaceAll("#");
        s = SPACE.matcher(s).replaceAll(" ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String buildAdvice(String tool, String reason, int count) {
        String shown = clip(reason, 160);
        return " [loop-watch] this exact call has now failed " + count
                + " times in a row (" + tool + ", same reason: " + shown + ")."
                + " Do NOT send it again unchanged. The repeat is not luck: either the tool needs a"
                + " DIFFERENT argument shape, or the premise is wrong. Change one of:"
                + " (a) the argument shape -- check the tool schema and send real JSON, not a string"
                + " containing JSON; (b) the tool -- a read-only perception tool can confirm the premise"
                + " first; (c) the goal -- if the same target keeps failing, say so in plain text"
                + " instead of retrying.";
    }

    private static void publishLoop(UUID companionId, String tool, String fp, String reason,
                                    String argsJson, int count) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companion_id", companionId.toString());
        data.put("tool", tool);
        data.put("repeat_count", count);
        data.put("fingerprint", fp);
        data.put("reason", clip(reason, MAX_SAMPLE_CHARS));
        data.put("args", clip(argsJson, MAX_SAMPLE_CHARS));
        try {
            MonitoringJournal.get().publish("tools", "tool_loop", data);
        } catch (RuntimeException ignored) {
            // 观测崩了绝不影响主循环
        }
    }

    private static void evictIfCrowded() {
        if (HITS.size() <= MAX_TRACKED) return;
        long now = System.currentTimeMillis();
        HITS.entrySet().removeIf(e -> now - e.getValue().lastMs() > STALE_MS);
        if (HITS.size() > MAX_TRACKED) {
            HITS.entrySet().stream()
                    .sorted((a, b) -> Long.compare(a.getValue().lastMs(), b.getValue().lastMs()))
                    .limit(HITS.size() - MAX_TRACKED)
                    .forEach(e -> HITS.remove(e.getKey(), e.getValue()));
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}