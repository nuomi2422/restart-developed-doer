package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.AcEvent;
import com.dwinovo.numen.ac.api.AcEventListener;
import com.dwinovo.numen.ac.api.AcTool;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionListener;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.api.ResumeContext;
import com.dwinovo.numen.ac.api.StepResult;
import com.dwinovo.numen.ac.api.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AC 执行器 — 顺序执行步骤，PAUSED 后从真实断点 resume，并对外发布确定顺序的
 * 执行事件（{@link AcEvent}）与有界的历史记录。
 *
 * <h3>身份契约</h3>
 * 一次执行绑定 AC 的 {@code name + version + fingerprint}（见 {@link AcFingerprint}）。
 * resume 只接受同一身份的 PAUSED 记录：版本或内容变化一律明确拒绝（需迁移/重规划），
 * 不静默从头重跑。resume 默认<b>重试暂停的那一步</b>，已完成步骤不重复；原始 input
 * 与已完成输出被保留并沿用。
 *
 * <h3>旁路观察</h3>
 * 事件与终态记录是只读的旁路出口：监听器抛异常会被捕获隔离，不破坏执行。
 * 记录保留最近 {@code maxRecords} 条（默认 1000），按 executionId 可查整条 attempt 链。
 */
public final class AcExecutor {

    /** 默认保留的最大历史记录条数。 */
    public static final int DEFAULT_MAX_RECORDS = 1000;

    private final ToolRegistry registry;
    private final List<ExecutionRecord> records = new CopyOnWriteArrayList<>();
    private final List<AcEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final List<ExecutionListener> recordListeners = new CopyOnWriteArrayList<>();
    private final int maxRecords;

    public AcExecutor(ToolRegistry registry) {
        this(registry, DEFAULT_MAX_RECORDS);
    }

    public AcExecutor(ToolRegistry registry, int maxRecords) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.maxRecords = maxRecords > 0 ? maxRecords : DEFAULT_MAX_RECORDS;
    }

    /** 本次执行器已产生的结果快照（含每次 attempt 的记录）。 */
    public List<ExecutionRecord> records() {
        return List.copyOf(records);
    }

    /** 按稳定 executionId 返回整条 attempt 链（含 PAUSED 各次尝试）。 */
    public List<ExecutionRecord> recordsByExecutionId(String executionId) {
        Objects.requireNonNull(executionId, "executionId");
        List<ExecutionRecord> out = new ArrayList<>();
        for (ExecutionRecord r : records) {
            if (executionId.equals(r.executionId())) out.add(r);
        }
        return List.copyOf(out);
    }

    /** 注册执行生命周期事件监听（旁路观察）。 */
    public void addEventListener(AcEventListener listener) {
        eventListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** 注册终态记录监听（兼容旧接口）。 */
    public void addListener(ExecutionListener listener) {
        recordListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** 从头执行一个 AC。 */
    public ExecutionRecord execute(AcDefinition ac, Map<String, Object> input, ExecutionContext context) {
        Objects.requireNonNull(ac, "ac");
        Objects.requireNonNull(context, "context");
        String executionId = UUID.randomUUID().toString();
        return executeSession(executionId, ac, input == null ? Map.of() : input, context,
                0, Map.of(), 0, null);
    }

    /**
     * 从暂停记录继续执行。校验执行身份（AC name/version/fingerprint）与断点边界；
     * 保留原始 input 与已完成输出，从暂停的那一步继续（重试）。
     *
     * @throws IllegalArgumentException 记录非 PAUSED / 无 resume 上下文 / AC 身份变化 /
     *                                  断点越界时
     */
    public ExecutionRecord resume(AcDefinition ac, ExecutionRecord paused, ExecutionContext context) {
        Objects.requireNonNull(ac, "ac");
        Objects.requireNonNull(context, "context");
        if (paused == null || paused.status() != ExecutionRecord.Status.PAUSED) {
            throw new IllegalArgumentException("cannot resume a non-PAUSED record: "
                    + (paused == null ? "null" : paused.status()));
        }
        ResumeContext rc = paused.resume();
        if (rc == null) throw new IllegalArgumentException("paused record has no resume context");

        String fp = AcFingerprint.of(ac);
        if (!rc.acName().equals(ac.name())) {
            throw new IllegalArgumentException("resume AC name mismatch: " + rc.acName() + " vs " + ac.name());
        }
        if (!rc.acVersion().equals(ac.version())) {
            throw new IllegalArgumentException("resume AC version changed: " + rc.acVersion()
                    + " vs " + ac.version() + " → 需迁移/重规划，不静默续跑");
        }
        if (!rc.acFingerprint().equals(fp)) {
            throw new IllegalArgumentException("resume AC content changed (fingerprint mismatch) → 需迁移/重规划，不静默续跑");
        }
        int start = rc.resumeStepIndex();
        if (start < 0 || start >= ac.steps().size()) {
            throw new IllegalArgumentException("resume step index out of range: " + start
                    + " for " + ac.steps().size() + " steps");
        }
        return executeSession(rc.executionId(), ac, rc.input(), context,
                start, rc.state(), rc.attempt() + 1, paused.runId());
    }

    private ExecutionRecord executeSession(String executionId, AcDefinition ac, Map<String, Object> input,
                                           ExecutionContext context, int start,
                                           Map<String, Object> initialState, int attempt, String parentRunId) {
        long started = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        String fp = AcFingerprint.of(ac);

        emit(AcEvent.Kind.EXECUTION_STARTED, executionId, attempt, runId, fp, ac, null, -1, null, null);

        Map<String, Object> state = new LinkedHashMap<>(initialState);
        int completed = start;
        String current = null;
        String pausedReason = null;
        StepResult result = StepResult.success(Map.of());
        for (int i = start; i < ac.steps().size(); i++) {
            AcDefinition.AcStep step = ac.steps().get(i);
            current = step.id();
            emit(AcEvent.Kind.STEP_STARTED, executionId, attempt, runId, fp, ac, step, i, step.tool(), null);

            AcTool tool = registry.find(step.tool()).orElse(null);
            if (tool == null) {
                result = StepResult.failed("unknown tool: " + step.tool());
                emit(AcEvent.Kind.STEP_FAILED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
                break;
            }
            // 执行前参数校验：AI 生成的参数不经检查不交给宿主工具
            String paramErr = AcParamValidator.validate(registry.schema(step.tool()).orElse(null), step.parameters());
            if (paramErr != null) {
                result = StepResult.failed(step.tool() + " 参数校验失败: " + paramErr);
                emit(AcEvent.Kind.STEP_FAILED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
                break;
            }
            // step 级异常护栏：任何一步抛异常都降级为 FAILED 步骤，绝不把异常冲出去
            // 使整个 execution future 异常完成（否则 ac_status/ac_resume 的 join() 会以
            // CompletionException 上抛，正是多步 AC 查询链 NPE 的根因）。
            // 用 Throwable 而非 RuntimeException：transport 工具在 AC 后台线程可能抛
            // NoClassDefFoundError 等 Error（审计实锤：服务端 sendToServer 类加载问题）。
            try {
                result = tool.execute(step.parameters(), context);
            } catch (Throwable e) {
                result = StepResult.failed(step.tool() + " threw: " + e);
                emit(AcEvent.Kind.STEP_FAILED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
                break;
            }
            if (result == null) {
                result = StepResult.failed(step.tool() + " returned null");
                emit(AcEvent.Kind.STEP_FAILED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
                break;
            }
            if (result.output() != null && !result.output().isEmpty()) {
                state.putAll(result.output());
            }
            if (result.status() == StepResult.Status.SUCCESS) {
                completed = i + 1;
                emit(AcEvent.Kind.STEP_SUCCEEDED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
                continue;
            }
            if (result.status() == StepResult.Status.PAUSED) {
                pausedReason = result.message();
                emit(AcEvent.Kind.STEP_PAUSED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
            } else if (result.status() == StepResult.Status.FAILED) {
                emit(AcEvent.Kind.STEP_FAILED, executionId, attempt, runId, fp, ac, step, i, step.tool(), result.message());
            }
            break;
        }

        ExecutionRecord.Status status = switch (result.status()) {
            case SUCCESS -> ExecutionRecord.Status.SUCCESS;
            case PAUSED -> ExecutionRecord.Status.PAUSED;
            case FAILED -> ExecutionRecord.Status.FAILED;
        };
        // 用 LinkedHashMap 而非 Map.copyOf：工具输出可含 null value（如 get_self_status 的
        // "target": null），Map.copyOf 对 null value 抛 NPE，会异常完成 execution future
        // （join() 时以 CompletionException 上抛 = 多步 AC 查询链 NPE 的真正根因）。
        Map<String, Object> stateCopy = new LinkedHashMap<>(state);
        Map<String, Object> inputCopy = new LinkedHashMap<>(input);
        ResumeContext rc = (status == ExecutionRecord.Status.PAUSED)
                ? new ResumeContext(executionId, attempt, ac.name(), ac.version(),
                        fp, inputCopy, stateCopy, current, completed, pausedReason)
                : null;
        ExecutionRecord record = new ExecutionRecord(runId, executionId, ac.name(), status, completed, current,
                result.message(), stateCopy, started, System.currentTimeMillis(), rc);

        append(record);
        for (ExecutionListener l : recordListeners) invoke(() -> l.recorded(record));

        AcEvent.Kind terminal = switch (status) {
            case SUCCESS -> AcEvent.Kind.EXECUTION_SUCCEEDED;
            case PAUSED -> AcEvent.Kind.EXECUTION_PAUSED;
            case FAILED -> AcEvent.Kind.EXECUTION_FAILED;
        };
        emit(terminal, executionId, attempt, runId, fp, ac, null, -1, null, result.message());
        return record;
    }

    /** 有界追加：超出 maxRecords 时淘汰最旧记录。 */
    private void append(ExecutionRecord record) {
        records.add(record);
        while (records.size() > maxRecords) {
            records.remove(0);
        }
    }

    /** 事件发送：监听器异常捕获隔离，不破坏执行。 */
    private void emit(AcEvent.Kind kind, String executionId, int attempt, String runId, String fp,
                      AcDefinition ac, AcDefinition.AcStep step, int stepIndex,
                      String tool, String message) {
        AcEvent event = new AcEvent(kind, executionId, attempt, runId, ac.name(), ac.version(),
                fp, step == null ? null : step.id(), stepIndex, tool, message,
                System.currentTimeMillis());
        for (AcEventListener l : eventListeners) invoke(() -> l.onEvent(event));
    }

    private void invoke(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.err.println("[ac-executor] listener error isolated: " + e);
        }
    }
}
