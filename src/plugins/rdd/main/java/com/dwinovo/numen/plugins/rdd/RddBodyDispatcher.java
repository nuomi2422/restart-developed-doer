package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.BodyInstruction;
import com.dwinovo.numen.rdd.api.Subtask;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 身体执行的派发桥：把一个带 {@code body} 的二级翻译成真实工具调用，直接交给同伴身体执行。
 *
 * <p>只在服务端 tick 线程调用（与工具实现同线程，安全）；工具内部走 {@code TaskDispatch.setTask}。
 *
 * <p><b>入口分三路</b>：
 * <ol>
 *   <li>{@code task_type} 在 RDD 词表/别名内（mine/craft/equip_item/collect_items，含旧臆造名
 *       mine_block/equip）→ {@link RddBodyTools} 翻译成真实工具参数（mine 要 block_ids 数组 + deepslate 变体）；</li>
 *   <li>词表外但是真实注册工具（外部 rdd_submit/监测台显式指名驱动）→ 原样派发（操作者负责参数契约）；</li>
 *   <li>都不是（规划层臆造名）→ 响亮 {@code subtask_capability_gap}，绝不再静默"只检测不执行"空转。</li>
 * </ol>
 *
 * <p>无状态：全部依赖 {@link ToolRegistry} 与 {@link RddPlugin} 的静态门面。
 */
final class RddBodyDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RddBodyDispatcher.class);
    private static final Gson GSON = new Gson();

    /** 当前二级带 body 且还没提交过 → 提交一次。 */
    static void maybeSubmit(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        if (body == null) {
            return;
        }
        var state = RddPlugin.bodyState(ap.getUUID());
        if (state != null && state.subtaskId().equals(current.id())) {
            return;
        }
        submit(ap, current);
        RddPlugin.rememberBody(ap.getUUID(), current.id(), 1);
    }

    /** 重试路径：绕过"已提交过"判断，强制重派一次（submitCount 由调用方维护）。 */
    static void resubmit(NumenPlayer ap, Subtask current) {
        submit(ap, current);
    }

    /** 把 body 翻译成真实工具调用；词表内走 RddBodyTools，词表外原样透传，都不匹配则报能力缺口。 */
    private static void submit(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        String raw = body.taskType();
        String canonical = RddBodyTools.canonical(raw);
        if (canonical != null) {
            NumenTool tool = ToolRegistry.resolve(canonical);
            if (tool == null) {
                LOG.error("[rdd] 规范身体工具 {} 未注册（插件与注册表脱节）", canonical);
                RddMonitor.publish("subtask_capability_gap", Map.of(
                        "subtask", current.id(), "reason", "canonical body tool unregistered: " + canonical));
                return;
            }
            Object condMin = current.condition().get("minimum");
            Integer min = condMin instanceof Number num ? num.intValue() : null;
            JsonObject realArgs = RddBodyTools.buildArgs(canonical, body.args(), min);
            if (realArgs == null) {
                LOG.error("[rdd] body {} 的 args 无法翻译成 {} 参数: {}", current.id(), canonical, body.args());
                RddMonitor.publish("subtask_capability_gap", Map.of(
                        "subtask", current.id(), "reason", "untranslatable args for " + canonical));
                return;
            }
            dispatch(ap, current, canonical, tool, realArgs, body);
            return;
        }
        NumenTool explicit = ToolRegistry.resolve(raw);
        if (explicit == null) {
            LOG.error("[rdd] body 工具名 {} 不在 RDD 词表 {} 也非真实注册工具 —— 规划层臆造，"
                    + "该二级只做资产检测不身体执行", raw, RddBodyTools.SUPPORTED);
            RddMonitor.publish("subtask_capability_gap", Map.of(
                    "subtask", current.id(), "reason", "unsupported body tool name: " + raw));
            return;
        }
        // 外部显式指名驱动任意真实工具：原样透传参数。
        JsonObject passthrough = new JsonObject();
        if (body.args() != null) {
            body.args().forEach((k, v) -> passthrough.add(k, GSON.toJsonTree(v)));
        }
        dispatch(ap, current, raw, explicit, passthrough, body);
    }

    /** 真正调用工具的服务端实现；失败只记日志 + 发事件，绝不拖垮 tick。 */
    private static void dispatch(NumenPlayer ap, Subtask current, String toolName, NumenTool tool,
                                 JsonObject callArgs, BodyInstruction body) {
        try {
            tool.onServerCall(RddPlugin.nextBodyCallId(), callArgs, ap, reply -> { });
            LOG.info("[rdd] 已提交身体任务 {} -> {} {}", current.id(), toolName, callArgs);
            RddMonitor.publish("body_submitted", Map.of(
                    "subtask", current.id(), "task_type", toolName, "args", body.args()));
        } catch (RuntimeException e) {
            LOG.warn("[rdd] 提交身体任务失败 {}: {}", toolName, e.toString());
            RddMonitor.publish("body_submit_failed", Map.of(
                    "subtask", current.id(), "task_type", toolName, "error", String.valueOf(e)));
        }
    }
}
