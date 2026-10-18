package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.monitor.MonitoringJournal;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把「客户端本地执行的工具调用」也写进 {@code monitor/tools.jsonl}。
 *
 * <h2>为什么需要它（2026-10-04 实测）</h2>
 * {@code ExecuteToolPayload}（服务端那条路）会给每次调用发 {@code tool_call} /
 * {@code tool_result}，所以身体工具在日志里一应俱全。
 * <p>
 * 但 {@link NumenTool#invoke} 的<strong>默认实现</strong>是
 * {@code ServerToolTransport.ship(call)} —— 把调用送去服务端，于是被记到了。
 * <strong>覆写了 {@code invoke} 的工具则在客户端本地跑完</strong>，
 * 压根不产生 {@code ExecuteToolPayload} ⇒ 日志里一个字都没有。
 * <p>
 * 实测被漏掉的（live {@code tools.jsonl} 对照内脑自己的 {@code chat.jsonl}）：
 * {@code find_tools}(内脑真调 118 次 / 日志 0 行)、{@code todowrite}(33 / 0)、
 * {@code load_skill}(8 / 0)、{@code selfcompile_request}、{@code selfcompile_status}、
 * {@code drop_items}、{@code sleep}、{@code eat}、{@code use_portal}、
 * {@code locate_structure}、{@code ac_execute}、{@code ac_status} —— 共 12 个。
 * <p>
 * 后果不是「少一行日志」：<strong>监测台无法回答「内脑到底调了什么工具」</strong>，
 * 而我曾因此对同一个问题连判错三次（先看 {@code ai.jsonl} 得假零，
 * 再看 {@code tools.jsonl} 又得一次假零）。
 *
 * <h2>★ 为什么用反射判「本地执行」，而不是给接口加一个标记方法</h2>
 * 加标记（例如 {@code default boolean runsOnClient()}）就多一个坑：
 * 将来谁写了一个本地工具但忘了覆写，它又变隐形，而且**没有任何测试会红**。
 * 反射判「{@code invoke} 的声明类是不是 {@code NumenTool} 自己」没有这个问题 ——
 * 新工具不需要做任何事，判据是它自己的形状。
 *
 * <h2>★ 为什么不会与 {@code ExecuteToolPayload} 重复记</h2>
 * 只有<strong>覆写了 {@code invoke}</strong>（= 本地执行、不会发服务端包）的工具
 * 才在这里记。走默认实现的身体工具不在这里记，仍由服务端那一跳记 ——
 * 而服务端那一跳有它自己独有的好处（校验过的 {@code companion_id}）。
 *
 * <h2>三条不许省的边界</h2>
 * <ol>
 *   <li><strong>这是补日志，不是改行为。</strong>本类只往 {@code MonitoringJournal}
 *       写观测行，不碰任何调度、超时、结果回传。<br>
 *       ⇒ 观测面坏了不许影响同伴干活 —— 所以所有 publish 都包在 try/catch 里。</li>
 *   <li><strong>「本地执行」是推断出来的</strong>（依据：{@code invoke} 被覆写）。
 *       万一某个工具覆写了 {@code invoke} 却仍然发服务端包，那它会被记两次 ——
 *       所以判据与本注释必须一起改。判错的方向是「多记」而不是「漏记」，
 *       这是有意选的：多记会被看见，漏记不会。</li>
 *   <li><strong>不记 {@code tool_result}</strong>：结果由
 *       {@code ToolDispatcher.complete()} / {@code NumenActuator} 那一侧发，
 *       本类只负责「这次调用发生过」。两个地方都发会重复。</li>
 * </ol>
 */
public final class LocalToolCallLog {

    private LocalToolCallLog() {
    }

    /** 反射结果缓存。工具类数量有限，且 invoke 的形状在运行期不变。 */
    private static final Map<Class<?>, Boolean> LOCAL_OVERRIDE = new ConcurrentHashMap<>();

    /**
     * 这个工具是不是「客户端本地执行」—— 判据：它覆写了 {@code invoke}。
     * 反射失败一律按「不是本地」处理（保守：宁可少记，也不要给服务端那一跳重复记）。
     */
    public static boolean runsLocally(NumenTool tool) {
        if (tool == null) {
            return false;
        }
        return LOCAL_OVERRIDE.computeIfAbsent(tool.getClass(), LocalToolCallLog::overridesInvoke);
    }

    private static boolean overridesInvoke(Class<?> cls) {
        try {
            Method m = cls.getMethod("invoke", ToolCall.class);
            return m.getDeclaringClass() != NumenTool.class;
        } catch (NoSuchMethodException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 本地工具的一次调用。**不是本地执行就直接返回**（那条路归
     * {@code ExecuteToolPayload} 记，重复记比漏记更难查）。
     *
     * @param companionId 内脑同伴；null/空就写空串而不是省略键 ——
     *                    键序与「有没有这个键」本身也是读数的一部分。
     */
    public static void publishCall(NumenTool tool, UUID companionId,
                                   String toolCallId, int argsChars) {
        if (!runsLocally(tool)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companion_id", companionId == null ? "" : companionId.toString());
        data.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        data.put("tool", tool.name());
        data.put("args_chars", argsChars);
        // ★ 显式标出这一行的来源：读的人要能分清「客户端本地记的」与「服务端记的」，
        //   否则将来出现重复行时无法判断是重复还是两个不同的调用。
        data.put("journaled_by", "client-local");
        publish("tool_call", data);
    }

    /**
     * 调用被拦下（参数定义没取回之类的）。也值得记 —— 它是「她试过但用不了」的唯一痕迹，
     * 而监测台此前连这个都看不见。
     */
    public static void publishRejected(NumenTool tool, UUID companionId,
                                       String toolCallId, String reason) {
        if (tool == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companion_id", companionId == null ? "" : companionId.toString());
        data.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        data.put("tool", tool.name());
        data.put("reason", reason == null ? "" : reason);
        data.put("journaled_by", "client-local");
        publish("tool_rejected", data);
    }

    /**
     * 本地工具的结果。**只为本地工具记**（判据同 {@link #publishCall}）：服务端那条路
     * 由 {@code ExecuteToolPayload} 记，两个地方都发会重复。
     *
     * <p>★ 2026-10-06 E0（工具耗时可见）：为什么必须补它 —— 本地工具
     * （find_tools / todowrite / load_skill …）此前**只有 tool_call 没有 tool_result**，
     * 监测台看得见「它调了」却看不见「耗了多久、成没成」。补上 duration_ms 与 success 后，
     * 「一直选工具 / 工具执行慢」这类问题才可能被读数定位，而不是靠猜。
     *
     * @param durationMs 从 dispatch 到结果落地的墙钟毫秒（含工具内部等待）
     * @param resultJson 结果原文；只用于取 {@code success} 标记，不整段落盘
     */
    public static void publishResult(NumenTool tool, UUID companionId,
                                     String toolCallId, long durationMs, String resultJson) {
        if (!runsLocally(tool)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companion_id", companionId == null ? "" : companionId.toString());
        data.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        data.put("tool", tool.name());
        data.put("duration_ms", durationMs);
        data.put("success", successOf(resultJson));
        data.put("journaled_by", "client-local");
        publish("tool_result", data);
    }

    /** 从结果 JSON 读 {@code success}；读不到返回 "UNKNOWN"（不把「不知道」写成 false）。 */
    private static Object successOf(String resultJson) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(resultJson);
            if (root.isJsonObject()
                    && root.getAsJsonObject().has("success")
                    && root.getAsJsonObject().get("success").isJsonPrimitive()) {
                return root.getAsJsonObject().get("success").getAsBoolean();
            }
        } catch (RuntimeException ignored) {
            // 结果不是 JSON / 格式意外：按 UNKNOWN 处理
        }
        return "UNKNOWN";
    }

    /**
     * 观测层不许影响同伴干活 —— 日志写失败只 debug 记一行，绝不抛。
     * （这是本类唯一允许吞异常的地方，理由同上：观测面坏了不该让她停手。）
     */
    private static void publish(String type, Map<String, Object> data) {
        try {
            MonitoringJournal.get().publish("tools", type, data);
        } catch (RuntimeException | LinkageError e) {
            com.dwinovo.numen.Constants.LOG.debug(
                    "[numen-tools] journal {} failed for {}: {}", type, data.get("tool"), e.toString());
        }
    }
}