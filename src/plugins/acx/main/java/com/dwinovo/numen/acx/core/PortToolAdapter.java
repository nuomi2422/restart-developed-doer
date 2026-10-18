package com.dwinovo.numen.acx.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.api.AcxToolSchema;

/**
 * 把 {@link AcxToolPort} 适配成引擎积木。
 *
 * <p><b>受理账本</b>：ACCEPTED 不是 SUCCESS，而是 PAUSED + 挂账。
 * 挂账键 = {@code 顶层AC名|stepId} —— <b>不能拿 runId 当键</b>：
 * resume 每次 {@code runFrom} 都会生成新 runId，用 runId 会永远对不上，
 * 于是 resume 变成「重发一遍命令」。params 变了就丢旧账重新发起
 * （同一 AC 同一 step 换了目标，旧任务与新目标无关）。</p>
 *
 * <p><b>完成两条路</b>：拉（{@link AcxCompletionProbe} 每次 resume 查一次）/
 * 推（宿主收 task_finished 后调 {@link #markCompleted} / {@link #markFailed}）。</p>
 *
 * <p>引擎不注入 {@code ignore_failure}（它由 runner 消费），但 runner 会把
 * 整份 params 原样传进来，所以适配层先剥掉控制键再校验 / 下发。</p>
 */
public final class PortToolAdapter implements AcxTool {

    public static final String CONTROL_IGNORE_FAILURE = "ignore_failure";

    private final AcxToolPort port;
    private final AcxCompletionProbe probe;
    private final AcxToolSchema toolSchema;
    private final Map<String, Acceptance> pendingByStep = new ConcurrentHashMap<>();
    private final Map<String, AcxToolPort.Result> pushedByTask = new ConcurrentHashMap<>();

    private record Acceptance(String taskId, boolean standing, String paramsSignature,
                              Map<String, Object> data) { }

    private PortToolAdapter(AcxToolPort port, AcxCompletionProbe probe) {
        this.port = port;
        this.probe = probe;
        this.toolSchema = toToolSchema(port.schema());
    }

    public static Builder builder(AcxToolPort port) {
        return new Builder(port);
    }

    public AcxToolPort port() {
        return port;
    }

    @Override
    public String name() {
        return port.name();
    }

    @Override
    public AcxToolSchema schema() {
        return toolSchema;
    }

    @Override
    public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
        Map<String, Object> clean = stripControlKeys(params);

        if (ctx != null && ctx.isCancelRequested()) {
            return AcxStepOutcome.paused(name() + " 看到取消请求，未发起调用",
                    Map.of("_accepted", false, "_pause_reason", "cancel_requested"));
        }

        List<String> errors = port.schema().validate(clean);
        if (!errors.isEmpty()) {
            return AcxStepOutcome.failed(name() + " 参数校验失败: " + String.join("; ", errors));
        }

        String key = (ctx == null ? "" : ctx.acName()) + "|" + (ctx == null ? "" : ctx.stepId());
        String signature = String.valueOf(clean);
        Acceptance acc = pendingByStep.get(key);
        boolean resuming = ctx != null && ctx.isResuming();
        if (acc != null && !acc.paramsSignature().equals(signature) && !resuming) {
            // 同一 AC 同一步骤换了参数 = 换目标：旧任务的账不能算在新目标头上。
            // 但 resume 例外：前序步骤输出没有持久化，$prev 引用解析不出来（保留字面串），
            // 这不代表换了目标 —— 定义真变了的话 resume 的指纹校验已经拒绝过了。
            pendingByStep.remove(key);
            acc = null;
        }
        if (acc != null) {
            return resolvePending(key, acc);
        }

        AcxToolPort.Result r;
        try {
            r = port.invoke(clean);
        } catch (Throwable t) {
            return AcxStepOutcome.failed(name() + " 执行异常: " + t);
        }
        if (r == null) {
            return AcxStepOutcome.failed(name() + " 返回 null（端口契约违规）");
        }
        switch (r.outcome()) {
            case COMPLETED:
                return AcxStepOutcome.success(r.data(), r.message());
            case FAILED:
                return AcxStepOutcome.failed(name() + " 失败: " + r.message());
            case ACCEPTED: {
                Acceptance fresh = new Acceptance(r.taskId(), r.standing(), signature, r.data());
                pendingByStep.put(key, fresh);
                return AcxStepOutcome.paused(acceptMessage(r), acceptedOutput(fresh, r.standing()));
            }
            default:
                throw new IllegalStateException("未覆盖的结果: " + r.outcome());
        }
    }

    private AcxStepOutcome resolvePending(String key, Acceptance acc) {
        if (!acc.taskId().isEmpty()) {
            AcxToolPort.Result pushed = pushedByTask.remove(acc.taskId());
            if (pushed != null) {
                pendingByStep.remove(key);
                return settle(acc, pushed);
            }
        }
        if (acc.standing()) {
            return AcxStepOutcome.paused(name() + " 是常驻任务（standing），没有完成终点；等待宿主 task_stop",
                    acceptedOutput(acc, true));
        }
        if (probe == null) {
            return AcxStepOutcome.paused(name() + " 异步任务仍在进行（无 completion probe，等待宿主完成通知）",
                    acceptedOutput(acc, false));
        }
        AcxCompletionProbe.Snapshot s;
        try {
            s = probe.probe(acc.taskId());
        } catch (Throwable t) {
            return AcxStepOutcome.paused(name() + " 完成探针异常（保留挂账，等待重试）",
                    acceptedOutput(acc, false));
        }
        if (s == null) {
            return AcxStepOutcome.paused(name() + " 完成探针返回 null（保留挂账）",
                    acceptedOutput(acc, false));
        }
        switch (s.state()) {
            case RUNNING:
                return AcxStepOutcome.paused(name() + " 异步任务进行中: " + s.message(),
                        acceptedOutput(acc, false));
            case DONE:
                pendingByStep.remove(key);
                return settle(acc, AcxToolPort.Result.completed(s.data(), s.message()));
            case FAILED:
                pendingByStep.remove(key);
                return AcxStepOutcome.failed(name() + " 异步任务失败: " + s.message());
            default:
                throw new IllegalStateException("未覆盖的探针状态: " + s.state());
        }
    }

    private AcxStepOutcome settle(Acceptance acc, AcxToolPort.Result r) {
        if (r.isFailed()) {
            return AcxStepOutcome.failed(name() + " 失败: " + r.message());
        }
        Map<String, Object> out = new LinkedHashMap<>(acc.data());
        out.putAll(r.data());
        out.put("_accepted", true);
        out.put("_completed", true);
        if (!acc.taskId().isEmpty()) {
            out.put("task_id", acc.taskId());
        }
        return AcxStepOutcome.success(out, r.message());
    }

    private Map<String, Object> acceptedOutput(Acceptance acc, boolean standing) {
        Map<String, Object> out = new LinkedHashMap<>(acc.data());
        out.put("_accepted", true);
        out.put("_completed", false);
        if (!acc.taskId().isEmpty()) {
            out.put("task_id", acc.taskId());
        }
        if (standing) {
            out.put("_standing", true);
            out.put("_pause_reason", "async_standing");
        } else {
            out.put("_pause_reason", "async_accepted");
        }
        return out;
    }

    private String acceptMessage(AcxToolPort.Result r) {
        if (r.standing()) {
            return name() + " 已受理（常驻任务，无完成终点）: taskId=" + r.taskId();
        }
        return name() + " 已受理，后台执行中: taskId=" + r.taskId();
    }

    /** 宿主收 task_finished(done) 后回调：把受理账销掉。 */
    public void markCompleted(String taskId, Map<String, Object> data) {
        if (taskId != null && !taskId.isBlank()) {
            pushedByTask.put(taskId, AcxToolPort.Result.completed(data, "task_finished: done"));
        }
    }

    /** 宿主收 task_finished(failed/timeout/stopped) 后回调。 */
    public void markFailed(String taskId, String message) {
        if (taskId != null && !taskId.isBlank()) {
            pushedByTask.put(taskId, AcxToolPort.Result.failed(message));
        }
    }

    /** 在途受理的账本大小（测试 / 巡检用）。 */
    public int pendingCount() {
        return pendingByStep.size();
    }

    private static Map<String, Object> stripControlKeys(Map<String, Object> params) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (params == null) {
            return clean;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!CONTROL_IGNORE_FAILURE.equals(e.getKey())) {
                clean.put(e.getKey(), e.getValue());
            }
        }
        return clean;
    }

    private static AcxToolSchema toToolSchema(AcxPortSchema s) {
        AcxToolSchema.Builder b = AcxToolSchema.builder();
        for (Map.Entry<String, AcxPortSchema.Param> e : s.params().entrySet()) {
            b.param(e.getKey(), mapType(e.getValue().type()));
        }
        // AcxToolSchema.outputFields() 返回 params.keySet()（现有契约），
        // 所以输出字段要并入 params 才能让 loader 的 $step.field 校验看得见
        for (String f : s.outputFields()) {
            if (!s.params().containsKey(f)) {
                b.param(f, AcxToolSchema.ParamType.ANY);
            }
        }
        for (String r : s.required()) {
            b.required(r);
        }
        return b.build();
    }

    private static AcxToolSchema.ParamType mapType(AcxPortSchema.Type t) {
        return switch (t) {
            case STRING -> AcxToolSchema.ParamType.STRING;
            case INTEGER -> AcxToolSchema.ParamType.INTEGER;
            case NUMBER -> AcxToolSchema.ParamType.NUMBER;
            case BOOLEAN -> AcxToolSchema.ParamType.BOOLEAN;
            default -> AcxToolSchema.ParamType.ANY;
        };
    }

    public static final class Builder {
        private final AcxToolPort port;
        private AcxCompletionProbe probe;

        private Builder(AcxToolPort port) {
            if (port == null) {
                throw new IllegalArgumentException("port 不能为空");
            }
            this.port = port;
        }

        public Builder probe(AcxCompletionProbe v) {
            this.probe = v;
            return this;
        }

        public PortToolAdapter build() {
            return new PortToolAdapter(port, probe);
        }
    }
}
