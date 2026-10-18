package com.dwinovo.numen.acx.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.dwinovo.numen.acx.api.AcxCondition;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxEventSink;
import com.dwinovo.numen.acx.api.AcxExperience;
import com.dwinovo.numen.acx.api.AcxExperienceSink;
import com.dwinovo.numen.acx.api.AcxPrecondition;
import com.dwinovo.numen.acx.api.AcxRecordStore;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStep;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTargetBlacklist;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolRegistry;
import com.dwinovo.numen.acx.api.AcxVerifier;
import com.dwinovo.numen.acx.api.Operator;

/**
 * ACX 执行器 —— 控制块 / 熔断 / 停滞 / 断点 / 容错 的唯一实现。
 *
 * <p>结构照 DD 版 {@code AcRunner}（{@code brain/ac/AcRunner.java}，654 行），但把五处
 * DD 的坑换掉了。每一处都在 {@code docs/03-与DD的差异和修复清单.md} 里有对照。</p>
 *
 * <h3>与 DD 的五处差异（都记在 03 文档里）</h3>
 * <ol>
 *   <li><b>停滞/超限的 while 立即停帧</b>。DD 是「跳过 markStepCompleted 然后 continue 到下一步」，
 *       于是后面每步都推进断点，最终 {@code completedStepIndex == steps.size()}，
 *       resume 变成空跑（{@code AcRunner.java:193-200} + {@code :223}）。
 *       ACX 停下并把断点留在 while 本身（{@code docs/02} §4.4）。</li>
 *   <li><b>TIMEOUT 用枚举</b>。DD 靠「消息里含"熔斷"」区分（{@code :113}）；
 *       ACX 直接返回 {@link AcxStatus#TIMEOUT}。</li>
 *   <li><b>子 AC 的 PAUSED 往上传</b>。DD 把「非 success」一律当失败（{@code :297}），
 *       于是子 AC 优雅停下会被报成 FAIL。ACX 区分 PAUSED / FAIL。</li>
 *   <li><b>可被宿主取消</b>。DD 没有取消通道。ACX 每步前查 cancel 标志，
 *       取消 → PAUSED（复用续跑契约，不新增第 5 个终态）。</li>
 *   <li><b>没有 LAST_FAILURE ThreadLocal</b>。DD 用「返回 null + ThreadLocal 存失败」
 *       表示失败（{@code :59}），null 有多种含义容易漏判。ACX 全程返回
 *       {@link AcxStepOutcome}，失败信息跟着返回值走。</li>
 * </ol>
 *
 * <h3>线程</h3>
 * 无状态：所有可变状态都在 {@link Ctx}（每次执行一个），因此同一 {@code AcxRunner}
 * 可以被多线程共享并发跑多条 AC。取消标志按 {@code runId} 存在 ConcurrentHashMap 里。
 */
public final class AcxRunner {

    private static final Logger LOG = Logger.getLogger(AcxRunner.class.getName());

    private final AcxToolRegistry tools;
    private final AcxCatalog catalog;
    private final AcxVerifier verifier;
    private final AcxTargetBlacklist blacklist;
    private final AcxEventSink events;
    private final AcxRecordStore store;
    private final AcxExperienceSink experienceSink;
    private final AcxRuntimeLimits limits;

    private final ConcurrentHashMap<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();

    private AcxRunner(Builder b) {
        this.tools = b.tools;
        this.catalog = b.catalog == null ? AcxCatalog.empty() : b.catalog;
        this.verifier = b.verifier;
        this.blacklist = b.blacklist == null ? AcxTargetBlacklist.noop() : b.blacklist;
        this.events = b.events == null ? AcxEventSink.noop() : b.events;
        this.store = b.store;
        this.experienceSink = b.experienceSink;
        this.limits = b.limits == null ? AcxRuntimeLimits.defaults() : b.limits;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 对外入口
    // ═══════════════════════════════════════════════════════════════════

    /** 从第 0 步开始执行一条 AC。 */
    public AcxRunRecord run(AcxDefinition def, Map<String, Object> input) {
        return runFrom(def, input, 0, limits);
    }

    /**
     * 指定 runId 从第 0 步开始执行。
     *
     * <p>会话层（AcxSessionManager）必须先拿到 runId 才能注册会话并立即回执，
     * 所以允许外部预生成；传空则退回 {@link AcxRunRecord#shortUuid()}。</p>
     */
    public AcxRunRecord run(AcxDefinition def, Map<String, Object> input, String runId) {
        return runFrom(def, input, 0, limits, runId, null);
    }

    /**
     * 从 {@code startStepIndex} 开始执行（resume 断点续跑的底层入口）。
     *
     * <p>断点粒度是<b>顶层步骤</b>。控制块内部（if/while 的 children）不单独记断点 ——
     * 暂停的 while 本身就是断点，resume 重跑整个 while。</p>
     */
    public AcxRunRecord runFrom(AcxDefinition def, Map<String, Object> input,
                                int startStepIndex, AcxRuntimeLimits lim) {
        return runFrom(def, input, startStepIndex, lim, null, null);
    }

    /**
     * 同 {@link #runFrom(AcxDefinition, Map, int, AcxRuntimeLimits)}，
     * 但允许外部指定 runId —— 会话层用稳定 run_id 串起 start/status/resume/cancel。
     */
    public AcxRunRecord runFrom(AcxDefinition def, Map<String, Object> input,
                                int startStepIndex, AcxRuntimeLimits lim, String runIdOverride,
                                Map<String, Object> seedVars) {
        long start = System.currentTimeMillis();
        AcxRuntimeLimits effective = AcxRuntimeLimits.merged(lim == null ? limits : lim, def.limits());
        long deadline = start + effective.maxTimeoutMs();
        String runId = (runIdOverride == null || runIdOverride.isBlank())
                ? AcxRunRecord.shortUuid() : runIdOverride;
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancels.put(runId, cancelFlag);

        Ctx c = new Ctx(runId, start, deadline, cancelFlag, effective, events, def.name(), startStepIndex);
        if (seedVars != null) {
            c.vars.putAll(seedVars);
        }
        c.rec = AcxRunRecord.builder()
                .runId(runId)
                .acName(def.name())
                .acVersion(def.version())
                .fingerprint(AcxFingerprint.of(def))
                .input(input == null ? Map.of() : input)
                .timestamp(start);

        emit(c, AcxEvent.Kind.RUN_STARTED, null, null, null);

        AcxStepOutcome outcome;
        try {
            outcome = runFrame(def, input, c, startStepIndex, true);
        } catch (Throwable t) {
            // 执行器自身崩了也要留记录，否则现场丢失
            outcome = AcxStepOutcome.failed("执行器内部异常: " + t);
            LOG.warning("acx run " + runId + " 内部异常: " + t);
        }

        c.rec.status(outcome.status())
                .completedStepIndex(c.completedStepIndex)
                .currentStepId(c.currentStepId)
                .loopCount(c.loopCount)
                .stagnantStep(c.stagnantStep)
                .failedStep(c.failedStep)
                .errorMessage(outcome.message())
                .pausedReason(c.pausedReason)
                .progress(AcxProgress.extractProgress(c.progress))
                .elapsedMs(System.currentTimeMillis() - start)
                .vars(new LinkedHashMap<>(c.vars));

        AcxRunRecord record = c.rec.build();
        if (store != null) {
            try {
                // 全量终态都通知 store（含 SUCCESS）——是否写盘由 store 实现决定；
                // 质量台账（AcxQualityLedger）要靠这里数成功次数
                store.append(record);
            } catch (Throwable t) {
                // 落盘失败不能反过来把执行结果搞没
                LOG.warning("acx 记录落盘失败: " + t);
            }
        }
        if (record.status() == AcxStatus.SUCCESS && experienceSink != null) {
            try {
                experienceSink.onSuccess(AcxExperience.from(record));
            } catch (Throwable t) {
                // 记经验是观测行为，不能反过来把一次成功改成失败
                LOG.warning("acx 成功经验回流失败: " + t);
            }
        }
        emit(c, AcxEvent.Kind.RUN_FINISHED, null, outcome.status(),
                Map.of("completed_step_index", c.completedStepIndex,
                       "elapsed_ms", record.elapsedMs()));

        cancels.remove(runId);
        return record;
    }

    /**
     * 断点续跑。
     *
     * <p><b>校验链</b>：AC 名 / 版本 / 指纹三者任一不符就拒绝。
     * 这是我们现有 AC 已有的安全契约（{@code AcExecutor.resume}），DD 那边根本没有 resume，
     * 所以继续沿用 —— 定义变了还静默从旧断点续跑会产出无法解释的结果。</p>
     */
    public AcxRunRecord resume(AcxDefinition def, AcxRunRecord prior) {
        return resume(def, prior, prior == null ? null : prior.input());
    }

    /**
     * 断点续跑，并刷新环境输入。
     *
     * <p>前置条件读的正是 input（环境事实由监督/宿主在 resume 时重新采），
     * 所以「条件不满足暂停 → 环境补齐 → 带新 input 续跑」是一条闭环。</p>
     */
    public AcxRunRecord resume(AcxDefinition def, AcxRunRecord prior, Map<String, Object> newInput) {
        try {
            int at = checkResumable(def, prior);
            emitDetached(AcxEvent.Kind.RESUME_STARTED, def.name(), at);
            // 复用原 runId：会话层的 status/cancel 靠一个稳定 id 串起整条续跑链
            // ★ AC-B9：变量从记录恢复 → 跨断点仍能读 $var.*（真机限制 L-06 就靠这条解掉）
        return runFrom(def, newInput, at, limits, prior.runId(), prior.vars());
        } catch (RuntimeException e) {
            emitDetached(AcxEvent.Kind.RESUME_REJECTED, def.name(), -1,
                    Map.of("reason", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    /**
     * resume 前的校验链（AC 名 / 版本 / 指纹 / 断点范围），返回本次续跑起点。
     *
     * <p>校验不通过抛 {@link IllegalArgumentException} —— 会话层靠它把拒绝留在同步路径上，
     * 不把「指纹不符」推迟到异步任务里变成会话错误。</p>
     */
    public int checkResumable(AcxDefinition def, AcxRunRecord prior) {
        if (prior == null) {
            throw new IllegalArgumentException("没有可续传的执行记录");
        }
        if (!def.name().equals(prior.acName())) {
            throw new IllegalArgumentException("AC 名不匹配: 记录 " + prior.acName() + " vs 当前 " + def.name());
        }
        if (!def.version().equals(prior.acVersion())) {
            throw new IllegalArgumentException(
                    "AC 版本已变（" + prior.acVersion() + " → " + def.version() + "），需迁移/重规划，不静默续跑");
        }
        String fp = AcxFingerprint.of(def);
        // ★ TODO AC-B21 修复（2026-10-06）：验不了 ≠ 验过了。
        //   旧写 `prior.fingerprint() != null && !fp.equals(...)` ⇒ 记录没有指纹时
        //   **整道门被跳过**（名/版本仍通过），拿着内容可能早已改过的旧断点静默续跑。
        //   这道门的全部意义就是「无法证明内容未变就不许续跑」，所以 null 是拒绝，不是放行。
        if (prior.fingerprint() == null || prior.fingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "记录里没有指纹，无法证明 AC 内容未变（需迁移旧记录或重跑，不静默续跑）");
        }
        if (!fp.equals(prior.fingerprint())) {
            throw new IllegalArgumentException("AC 内容已变（指纹不一致），需迁移/重规划，不静默续跑");
        }
        int at = prior.completedStepIndex();
        if (at < 0 || at >= def.steps().size()) {
            throw new IllegalArgumentException(
                    "断点越界: completedStepIndex=" + at + "，AC 共 " + def.steps().size() + " 步");
        }
        return at;
    }

    /** 请求取消一次执行。长耗时积木应轮询 {@link AcxTool.AcxCallContext#isCancelRequested()} 提前收手。 */
    public boolean cancel(String runId) {
        AtomicBoolean flag = cancels.get(runId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    public boolean isRunning(String runId) {
        return cancels.containsKey(runId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 帧执行（每层子 AC 一帧）
    // ═══════════════════════════════════════════════════════════════════

    private AcxStepOutcome runFrame(AcxDefinition def, Map<String, Object> input,
                                   Ctx c, int startIndex, boolean topLevel) {
        if (c.callStack.contains(def.name())) {
            String chain = String.join(" → ", c.callStack) + " → " + def.name();
            emit(c, AcxEvent.Kind.CIRCUIT_RECURSION, null, null, Map.of("chain", chain));
            return AcxStepOutcome.failed("循环引用: " + chain);
        }
        if (c.callStack.size() >= c.limits.maxDepth()) {
            String chain = String.join(" → ", c.callStack) + " → " + def.name();
            return AcxStepOutcome.failed("子 AC 递归层数超限 " + c.limits.maxDepth() + ": " + chain);
        }
        c.callStack.addLast(def.name());

        Map<String, Object> in = input == null ? Map.of() : input;
        Map<String, Object> lastOutput = new LinkedHashMap<>();
        Map<String, Map<String, Object>> allOutputs = new LinkedHashMap<>();
        if (startIndex == 0 && !def.preconditions().isEmpty()) {
            AcxStepOutcome blocked = checkPreconditions(def, in, c);
            if (blocked != null) {
                return blocked;
            }
        }
        try {
            for (int i = startIndex; i < def.steps().size(); i++) {
                AcxStepOutcome breaker = checkCircuit(c, def, i);
                if (breaker != null) {
                    return breaker;
                }
                AcxStep step = def.steps().get(i);
                if (topLevel) {
                    // ★ 只有顶层步骤才写 currentStepId —— 子 AC 帧若也写，
                    //   记录里就会变成子 AC 内部某步的名字，resume 时定位不到父层调用点
                    c.currentStepId = step.id();
                }

                if (step.isControl()) {
                    AcxStepOutcome r = execControl(step, def, in, lastOutput, allOutputs, c);
                    lastOutput = r.output();
                    if (r.status() != AcxStatus.SUCCESS) {
                        return r;
                    }
                    // ★ TODO AC-B19 修复（2026-10-06）：控制块跑完时若宿主已取消，
                    //   迟到的成功不许把整次运行翻回 SUCCESS（见线性分支同注释）。
                    if (c.cancelFlag.get()) {
                        return cancelled(c);
                    }
                    allOutputs.put(step.id(), r.output());
                    if (isPausedControl(r.output())) {
                        // ★ 断点停在控制块自身：不 markStepCompleted，resume 重跑这个 while
                        c.stagnantStep = Boolean.TRUE.equals(r.output().get("_stagnated")) ? step.id() : null;
                        c.pausedReason = String.valueOf(r.output().get("_pause_reason"));
                        return AcxStepOutcome.paused(pauseMessage(def, step, r.output()), r.output());
                    }
                    markStepCompleted(c, i);
                    continue;
                }

                c.stepCounter[0]++;
                AcxStepOutcome r = execLinear(step, def, in, lastOutput, allOutputs, c);
                if (r.isSuccess()) {
                    // ★ TODO AC-B19 修复（2026-10-06）：取消标志只在 checkCircuit（每步**之前**）看，
                    //   于是「宿主在最后一步在飞时取消、该步随后成功」的迟到回执会把终态翻回 SUCCESS。
                    //   实机危害：取消的运行会被 experienceSink.onSuccess 记成一条成功样本。
                    //   这里在收下成功之前复查一次；断点**不推进**（取消=停下等人工决定，不做隐含进度）。
                    if (c.cancelFlag.get()) {
                        return cancelled(c);
                    }
                    allOutputs.put(step.id(), r.output());
                    lastOutput = r.output();
                    mergeProgress(c, r.output());
                    markStepCompleted(c, i);
                    continue;
                }
                if (r.status() == AcxStatus.PAUSED) {
                    lastOutput = r.output();
                    c.pausedReason = String.valueOf(r.output().get("_pause_reason"));
                    return r;
                }
                // FAIL
                if (ignoreFailure(step)) {
                    emit(c, AcxEvent.Kind.STEP_IGNORED_FAILURE, step.id(), r.status(),
                            Map.of("reason", String.valueOf(r.message())));
                    lastOutput = withFailureDiag(lastOutput, step, r.message());
                    allOutputs.put(step.id(), lastOutput);
                    continue;
                }
                c.failedStep = step.id();
                return r;
            }
            return AcxStepOutcome.success(lastOutput,
                    def.name() + " 完成 (" + def.steps().size() + " 步)");
        } finally {
            // 无论正常 / 熔断 / 失败 / 暂停都把调用栈弹干净，否则递归判定会误报循环引用
            c.callStack.removeLast();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 起跑前置条件 / 步骤级守卫 / 参数绑定
    // ═══════════════════════════════════════════════════════════════════

    /**
     * AC 级前置条件。全部满足返回 null；任一不满足 → PAUSED（可续，断点留在第 0 步）。
     *
     * <p>环境没就绪不是「做不到」而是「现在不该开跑」，所以这里刻意不用 FAIL：
     * FAIL 会把整条 AC 判死并走重试，而 precondition 需要的语义是
     * 「停住、给出提示、环境补齐后带新 input resume 原地重评」。</p>
     */
    private AcxStepOutcome checkPreconditions(AcxDefinition def, Map<String, Object> in, Ctx c) {
        for (AcxPrecondition p : def.preconditions()) {
            AcxCondition cond = p.condition();
            boolean ok = AcxConditionEvaluator.evaluate(cond, Map.of(), in, Map.of());
            if (ok) {
                continue;
            }
            String desc = AcxConditionEvaluator.describe("precondition", cond, Map.of(), in, Map.of());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_preconditions_passed", false);
            out.put("_precondition_failed", desc);
            out.put("_pause_reason", "precondition_failed: " + desc);
            if (!p.hint().isEmpty()) {
                out.put("_precondition_hint", p.hint());
            }
            c.pausedReason = String.valueOf(out.get("_pause_reason"));
            String msg = "【暂停可续】" + def.name() + " 前置条件未满足: " + desc
                    + (p.message().isEmpty() ? "" : "；说明: " + p.message())
                    + (p.hint().isEmpty() ? "" : "；提示: " + p.hint());
            emit(c, AcxEvent.Kind.PRECONDITION_FAILED, null, AcxStatus.PAUSED,
                    Map.of("condition", cond.toString(), "hint", p.hint(), "message", p.message()));
            return AcxStepOutcome.paused(msg, out);
        }
        return null;
    }

    /**
     * 步骤级守卫：不调积木，只对 conditions 求值（condition 单条 / conditions 列表）。
     *
     * <p>满足 → SUCCESS 并输出 {@code {_guard_passed:true}}（后续步骤可用
     * {@code $<guardId>._guard_passed} 读）；不满足 → 默认 PAUSED（等环境，resume 重跑本步），
     * {@code on_fail:"fail"} 时改成 FAIL。</p>
     */
    private AcxStepOutcome execGuard(AcxStep step, Map<String, Object> params, Ctx c) {
        List<AcxCondition> checks = new ArrayList<>();
        if (params.get("condition") instanceof Map<?, ?> m) {
            AcxCondition cond = parseCondition(castMap(m));
            if (cond != null) {
                checks.add(cond);
            }
        }
        if (params.get("conditions") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    AcxCondition cond = parseCondition(castMap(m));
                    if (cond != null) {
                        checks.add(cond);
                    }
                }
            }
        }

        for (AcxCondition cond : checks) {
            if (AcxConditionEvaluator.evaluate(cond, Map.of(), Map.of(), Map.of(), c.vars)) {
                continue;
            }
            String desc = AcxConditionEvaluator.describe("guard", cond, Map.of(), Map.of(), Map.of());
            String reason = "guard[" + step.id() + "] 未满足: " + desc;
            String hint = params.get("hint") == null ? "" : String.valueOf(params.get("hint"));
            String message = params.get("message") == null ? "" : String.valueOf(params.get("message"));
            boolean hardFail = "fail".equalsIgnoreCase(
                    String.valueOf(params.getOrDefault("on_fail", "pause")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_guard_passed", false);
            out.put("_guard_failed", true);
            if (!hint.isEmpty()) {
                out.put("_guard_hint", hint);
            }
            out.put("_pause_reason", reason);
            emit(c, AcxEvent.Kind.GUARD_FAILED, step.id(),
                    hardFail ? AcxStatus.FAIL : AcxStatus.PAUSED,
                    Map.of("condition", cond.toString(), "hint", hint, "on_fail",
                            hardFail ? "fail" : "pause"));
            if (hardFail) {
                c.failedStep = step.id();
                return AcxStepOutcome.failed(reason
                        + (message.isEmpty() ? "" : "；说明: " + message)
                        + (hint.isEmpty() ? "" : "；提示: " + hint));
            }
            c.pausedReason = reason;
            return AcxStepOutcome.paused("【暂停可续】" + reason
                    + (message.isEmpty() ? "" : "；说明: " + message)
                    + (hint.isEmpty() ? "" : "；提示: " + hint), out);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_guard_passed", true);
        emit(c, AcxEvent.Kind.STEP_SUCCEEDED, step.id(), AcxStatus.SUCCESS, Map.of("guard", true));
        return AcxStepOutcome.success(out, step.id() + " guard 通过");
    }

    /** 参数解析统一入口：绑定描述符 + 引用解析，并把解析不到的引用转成事件提示。 */
    private Map<String, Object> resolveParams(AcxStep step, Map<String, Object> input,
                                              Map<String, Object> lastOutput,
                                              Map<String, Map<String, Object>> allOutputs, Ctx c) {
        List<AcxParamBinder.Warning> warnings = new ArrayList<>();
        Map<String, Object> resolved = AcxParamBinder.bind(
                step.params(), lastOutput, input, allOutputs, c.vars, warnings);
        for (AcxParamBinder.Warning w : warnings) {
            emit(c, AcxEvent.Kind.REF_UNRESOLVED, step.id(), null, Map.of(
                    "param", w.param(),
                    "ref", String.valueOf(w.ref()),
                    "reason", w.reason(),
                    "hint", w.hint()));
        }
        return resolved;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 单步执行：子 AC → 原子积木
    // ═══════════════════════════════════════════════════════════════════

    private AcxStepOutcome execLinear(AcxStep step, AcxDefinition def, Map<String, Object> input,
                                      Map<String, Object> lastOutput,
                                      Map<String, Map<String, Object>> allOutputs, Ctx c) {
        c.detailScratch = new LinkedHashMap<>();
        c.lastStepChangedVar = false;
        // ★ 嵌套控制块派发：children 里可以再套 if / while（真实 .ac 就有两层嵌套）。
        //   runFrame 只在顶层派发，execLinear 不派发的话嵌套控制块会被当成
        //   「引用了不存在的积木 if」而 FAIL。
        if (step.isControl()) {
            return execControl(step, def, input, lastOutput, allOutputs, c);
        }
        Map<String, Object> params;
        try {
            params = resolveParams(step, input, lastOutput, allOutputs, c);
        } catch (RuntimeException e) {
            // 参数解析里的响亮失败（AC-B9：$var 拼错、未知名）要变成一条 FAIL 记录，
            // 不能把异常甩到宿主线程 —— 甩出去的话 run() 直接崩，门面那边什么都看不到。
            AcxStepOutcome r = AcxStepOutcome.failed(
                    step.id() + " 参数解析失败: " + e.getMessage());
            emit(c, AcxEvent.Kind.STEP_FAILED, step.id(), AcxStatus.FAIL,
                    Map.of("reason", String.valueOf(r.message())));
            return r;
        }
        // 仅为 ignore_failure 黑名单回调复用已解析参数，避免二次解析；每次入口覆盖
        c.lastResolvedParams = params;

        emit(c, AcxEvent.Kind.STEP_STARTED, step.id(), null, Map.of("block", step.block()));

        // ★ 步骤级守卫：内置块，不查子 AC / 积木注册表
        if (step.isGuard()) {
            return execGuard(step, params, c);
        }

        // ★ AC-B9 变量写回：内置块，不查子 AC / 积木表。params 已解析完（含 $var 读取）。
        if (step.isSet()) {
            // 只有「值真的变了」才算进展：否则 while 里反复 set 同一个值会被停滞判定打断，
            // 而那正是「变量在往目标收敛」的正常写法。
            boolean changed = false;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object old = c.vars.get(e.getKey());
                Object nv = e.getValue();
                if (nv == null ? old != null : !String.valueOf(nv).equals(String.valueOf(old))) {
                    changed = true;
                    break;
                }
            }
            c.lastStepChangedVar = changed;
            c.vars.putAll(params);
            Map<String, Object> written = new LinkedHashMap<>(params);
            return AcxStepOutcome.success(Map.of("_set", written));
        }

        // 顺序照 DD：先查子 AC，再查原子积木。控制块不查表（见 execControl）
        Optional<AcxDefinition> sub = catalog.find(step.block());
        if (sub.isPresent()) {
            return runFrame(sub.get(), params, c, 0, false);
        }

        AcxTool tool = tools.find(step.block()).orElse(null);
        if (tool == null) {
            AcxStepOutcome r = AcxStepOutcome.failed(
                    step.id() + " 引用了不存在的积木或 AC: " + step.block());
            emit(c, AcxEvent.Kind.STEP_FAILED, step.id(), AcxStatus.FAIL,
                    Map.of("reason", String.valueOf(r.message())));
            return r;
        }

        long stepStart = System.currentTimeMillis();
        AcxStepOutcome out;
        try {
            out = tool.execute(params, new CallCtx(c, step));
        } catch (Throwable e) {
            // 用 Throwable 而非 RuntimeException：桥接 NumenTool 时 transport 层可能抛
            // NoClassDefFoundError（现有 AC 的 AcExecutor 也是这么防的）
            AcxStepOutcome r = AcxStepOutcome.failed(step.id() + " 执行异常: " + e);
            emit(c, AcxEvent.Kind.STEP_FAILED, step.id(), AcxStatus.FAIL,
                    Map.of("reason", String.valueOf(r.message())));
            return r;
        }
        if (out == null) {
            AcxStepOutcome r = AcxStepOutcome.failed(step.id() + " 返回 null outcome（积木契约违规）");
            emit(c, AcxEvent.Kind.STEP_FAILED, step.id(), AcxStatus.FAIL,
                    Map.of("reason", String.valueOf(r.message())));
            return r;
        }
        if (!out.isSuccess()) {
            // ★ TODO AC-B20 修复（2026-10-06）：脚本在等 ≠ 身体动作已停（69 号第 1 组红线）。
            //   旧实现把 PAUSED 也记成 STEP_FAILED，且 detail 只有 reason+elapsed_ms ——
            //   实机读数（2593 行 acx.jsonl）：STEP_FAILED|PAUSED=53（在飞动作）vs
            //   STEP_FAILED|FAIL=29（真失败），按 kind 统计失败比真失败多 1.8 倍；
            //   在飞任务的 task_id 只以文本混在 reason 里，机器读不出来。
            //   现在：PAUSED 单发 STEP_PAUSED，并把适配层给的机器可读字段原样带上。
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", String.valueOf(out.message()));
            detail.put("elapsed_ms", System.currentTimeMillis() - stepStart);
            if (out.status() == AcxStatus.PAUSED) {
                carryAcceptedFields(out.output(), detail);
                emit(c, AcxEvent.Kind.STEP_PAUSED, step.id(), AcxStatus.PAUSED, detail);
            } else {
                emit(c, AcxEvent.Kind.STEP_FAILED, step.id(), out.status(), detail);
            }
            return out;
        }

        AcxStepOutcome rejected = verify(step, params, out, c);
        if (rejected != null) {
            return rejected;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("block", step.block());
        detail.put("elapsed_ms", System.currentTimeMillis() - stepStart);
        detail.putAll(c.detailScratch);
        emit(c, AcxEvent.Kind.STEP_SUCCEEDED, step.id(), AcxStatus.SUCCESS, detail);
        return out;
    }

    /**
     * 实证验证。每步成功后拿真实世界读数对撞（DD {@code VerificationHelper} 的位置）。
     *
     * @return {@code null} 表示通过；非 {@code null} 表示应当把这一步判 FAIL
     */
    private AcxStepOutcome verify(AcxStep step, Map<String, Object> params,
                                  AcxStepOutcome out, Ctx c) {
        if (verifier == null) {
            if (!c.verifierAbsentEmitted) {
                c.verifierAbsentEmitted = true;
                emit(c, AcxEvent.Kind.VERIFIER_ABSENT, step.id(), null,
                        Map.of("note", "本次运行没有配置 AcxVerifier，成功判定仅来自积木自述"));
            }
            return null;
        }
        AcxVerifier.VerifyResult vr;
        try {
            vr = verifier.verify(step.block(), params, out.output());
        } catch (Throwable e) {
            vr = AcxVerifier.VerifyResult.reject("verifier 抛异常: " + e);
        }
        if (vr != null && vr.verified()) {
            return null;
        }
        String reason = vr == null ? "verifier 返回 null" : String.valueOf(vr.reason());
        emit(c, AcxEvent.Kind.VERIFY_FAILED, step.id(), AcxStatus.FAIL, Map.of("reason", reason));
        return AcxStepOutcome.failed(step.id() + " 实证验证不通过: " + reason);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 控制块：if 一次 / while 循环（循环三终点）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 每轮重算条件：把原始 condition 里的 {@code $} 引用按当轮的 lastOutput / vars 解析。
     * 原文不是 Map（例如 conditions 列表）时退回已解析的那份。
     */
    private AcxCondition condEachRound(Object condRaw, AcxCondition fallback,
                                       Map<String, Object> cur, Map<String, Object> input,
                                       Map<String, Map<String, Object>> allOutputs, Ctx c) {
        if (!(condRaw instanceof Map<?, ?> raw)) {
            return fallback;
        }
        AcxValueResolver r = new AcxValueResolver(cur, input, allOutputs, c.vars);
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (("field".equals(k) || "value".equals(k)) && v instanceof String sv && sv.startsWith("$")) {
                m.put(k, r.resolve(sv));
            } else if ("value".equals(k) && v instanceof List<?> lst) {
                List<Object> out = new ArrayList<>(lst.size());
                for (Object o : lst) {
                    out.add(o instanceof String os && os.startsWith("$") ? r.resolve(os) : o);
                }
                m.put(k, out);
            } else {
                m.put(k, v);
            }
        }
        return parseCondition(m);
    }

    private AcxStepOutcome execControl(AcxStep step, AcxDefinition def, Map<String, Object> input,
                                       Map<String, Object> lastOutput,
                                       Map<String, Map<String, Object>> allOutputs, Ctx c) {
        List<AcxStep> children = step.children();
        if (children == null || children.isEmpty()) {
            return AcxStepOutcome.failed(
                    def.name() + " step " + step.id() + " (" + step.block() + ") 缺少 children");
        }
        boolean isWhile = step.isLoop();
        Map<String, Object> resolved = resolveParams(step, input, lastOutput, allOutputs, c);
        Map<String, Object> condSpec = resolved.get("condition") instanceof Map
                ? castMap(resolved.get("condition")) : null;
        AcxCondition cond = parseCondition(condSpec);
        // AC-B10：condition 里若含 $var / $loop / $prev 引用，必须每轮重新求值 ——
        // bind 在进循环前只做一次，那会把「变量在收敛」的循环钉死成常量条件。
        Object condRaw = step.params() == null ? null : step.params().get("condition");
        if (!(condRaw instanceof Map)) {
            condRaw = step.params() == null ? null : step.params().get("conditions");
        }

        // AC-B11：for 的迭代上限来自列表长度（也可显式 max_iters 截断）
        boolean isFor = step.isFor();
        List<Object> forItems = null;
        String forVar = "item";
        if (isFor) {
            Object raw = resolved.get("list");
            if (!(raw instanceof List<?> l)) {
                return AcxStepOutcome.failed(step.id()
                        + " (for) 的 list 必须指向一个列表，实际是: " + raw);
            }
            forItems = new ArrayList<>(l);
            Object asRaw = resolved.get("as");
            if (asRaw != null && !String.valueOf(asRaw).isBlank()) {
                forVar = String.valueOf(asRaw);
            }
        }
        int maxIters = isWhile
                ? AcxValueResolver.toInt(resolved.get("max_iters"), AcxLimits.DEFAULT_MAX_ITERS)
                : (isFor ? Math.max(1, Math.min(forItems.size(),
                        (resolved.get("max_iters") == null ? Integer.MAX_VALUE
                                : AcxValueResolver.toInt(resolved.get("max_iters"), Integer.MAX_VALUE))))
                : 1);
        int stagnantLimit = isWhile
                ? AcxValueResolver.toInt(resolved.get("stagnant_limit"), AcxLimits.DEFAULT_STAGNANT_LIMIT)
                : AcxLimits.DEFAULT_STAGNANT_LIMIT;
        // ★ do_while：先跑一轮再判条件（默认 false = DD 的轮首求值）
        boolean doWhile = isWhile && boolFlag(resolved.get("do_while"));

        int iters = 0;
        int stagnantRounds = 0;
        String ifUnmatched = null;
        boolean stoppedByStagnation = false;
        boolean stoppedByMaxIters = false;
        Map<String, Object> cur = lastOutput;

        while (true) {
            AcxStepOutcome breaker = checkCircuit(c, def, 0);
            if (breaker != null) {
                return breaker;
            }
            // do_while：第 1 轮无条件执行（条件源由本轮 body 刷新，第 2 轮起才读得到）
            boolean condTrue = isFor ? iters < forItems.size()
                    : (doWhile && iters == 0)
                    || cond == null
                    || AcxConditionEvaluator.evaluate(condEachRound(condRaw, cond, cur, input, allOutputs, c), cur, input, allOutputs, c.vars);
            if (!condTrue) {
                if (!isWhile && condSpec != null) {
                    ifUnmatched = AcxConditionEvaluator.describeUnmatched(condEachRound(condRaw, cond, cur, input, allOutputs, c), cur, input, allOutputs);
                    emit(c, AcxEvent.Kind.IF_UNMATCHED, step.id(), null, Map.of("note", ifUnmatched));
                }
                break;
            }
            if (isFor && iters >= maxIters) {
                // 撞上显式 max_iters 截断 → 与 while 同语义：停下等续跑，不算成功
                stoppedByMaxIters = true;
                break;
            }
            if (isWhile && iters >= maxIters) {
                stoppedByMaxIters = true;
                break;
            }
            iters++;
            // AC-B10：循环状态写进变量 → children 里能用 $loop.iter / $loop.count
            c.vars.put("loop_iter", (long) iters);
            c.vars.put("loop_count", (long) iters);
            if (isFor) {
                // 元素写进变量 → children 里用 $var.<as> 取；$loop.total 给总数
                c.vars.put(forVar, forItems.get(iters - 1));
                c.vars.put("loop_total", (long) forItems.size());
            }

            boolean roundHasProgress = false;
            for (AcxStep child : children) {
                c.stepCounter[0]++;
                c.detailScratch = new LinkedHashMap<>();
                AcxStepOutcome r = execLinear(child, def, input, cur, allOutputs, c);
                c.detailScratch = new LinkedHashMap<>();
                if (r.isSuccess()) {
                    allOutputs.put(child.id(), r.output());
                    cur = r.output();
                    if (AcxProgress.hasRealProgress(r.output()) || c.lastStepChangedVar) {
                        roundHasProgress = true;
                    }
                    // 嵌套控制块自己停摆（停滞/超限）→ 整条 AC 就此暂停，
                    // 断点留在包含它的顶层那一步，不让它继续往下跑
                    if (isPausedControl(r.output())) {
                        Map<String, Object> merged = new LinkedHashMap<>(r.output());
                        merged.put("_paused_control", child.id());
                        return AcxStepOutcome.paused(
                                pauseMessage(def, child, r.output()), r.output());
                    }
                    continue;
                }
                if (r.status() == AcxStatus.PAUSED) {
                    // 积木/子 AC 主动让位 → 整个控制块暂停，断点留在 while 本身
                    Map<String, Object> merged = new LinkedHashMap<>(cur);
                    merged.putAll(r.output());
                    merged.put("_pause_reason", r.output().get("_pause_reason") == null
                            ? "child[" + child.id() + "] paused" : r.output().get("_pause_reason"));
                    merged.put("_paused_child", child.id());
                    return AcxStepOutcome.paused(r.message(), merged);
                }
                // FAIL
                if (ignoreFailure(child)) {
                    Map<String, Object> failedParams = c.lastResolvedParams;
                    if (isWhile) {
                        // while 里的「一次到位」策略：拉黑这个目标 → 跳出本轮 → 回条件换目标
                        blacklist.blacklist(failedParams);
                        emit(c, AcxEvent.Kind.STEP_IGNORED_FAILURE, child.id(), AcxStatus.FAIL,
                                Map.of("reason", String.valueOf(r.message()),
                                       "while", step.id(),
                                       "action", "blacklist_and_retry"));
                    } else {
                        emit(c, AcxEvent.Kind.STEP_IGNORED_FAILURE, child.id(), AcxStatus.FAIL,
                                Map.of("reason", String.valueOf(r.message()),
                                       "block", step.id(),
                                       "action", "stop_block_body"));
                    }
                    cur = withFailureDiag(cur, child, r.message());
                    allOutputs.put(child.id(), cur);
                    break;
                }
                c.failedStep = child.id();
                return r;
            }
            if (!isWhile) {
                break;
            }
            // ── 终点2：连续 stagnantLimit 轮无实质进展 → 判定此路不通 ──
            if (!roundHasProgress && isWhile) {
                stagnantRounds++;
                if (stagnantRounds >= stagnantLimit) {
                    stoppedByStagnation = true;
                    break;
                }
            } else {
                stagnantRounds = 0;
            }
        }

        c.loopCount += iters;

        Map<String, Object> out = new LinkedHashMap<>(cur == null ? Map.of() : cur);
        if (stoppedByStagnation) {
            out.put("_stagnated", true);
            out.put("_pause_reason", "while[" + step.id() + "] 连续 " + stagnantLimit + " 轮无实质进展");
            out.put("_loop_count", iters);
            emit(c, AcxEvent.Kind.CONTROL_STAGNATED, step.id(), AcxStatus.PAUSED,
                    Map.of("iters", iters, "stagnant_rounds", stagnantRounds));
        }
        if (stoppedByMaxIters) {
            out.put("_reached_max_iters", true);
            out.put("_pause_reason", "while[" + step.id() + "] 达到 max_iters=" + maxIters);
            out.put("_loop_count", iters);
            emit(c, AcxEvent.Kind.CONTROL_MAX_ITERS, step.id(), AcxStatus.PAUSED,
                    Map.of("iters", iters, "max_iters", maxIters));
        }
        if (!stoppedByStagnation && !stoppedByMaxIters) {
            out.put("_loop_count", iters);
        }
        if (ifUnmatched != null) {
            out.put("_if_unmatched", ifUnmatched);
        }
        return AcxStepOutcome.success(out, step.id() + " (" + (isWhile ? "while×" + iters : "if") + ")");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 熔断 / 工具方法
    // ═══════════════════════════════════════════════════════════════════

    /** 每步执行前的三道熔断检查。返回非 null 表示该结束整次执行了。 */
    private AcxStepOutcome checkCircuit(Ctx c, AcxDefinition def, int i) {
        if (c.cancelFlag.get()) {
            return cancelled(c);
        }
        long now = System.currentTimeMillis();
        if (now > c.deadline) {
            String where = i > 0
                    ? def.name() + " step " + (i + 1) + "/" + def.steps().size()
                    : def.name() + " step " + c.currentStepId;
            emit(c, AcxEvent.Kind.CIRCUIT_TIMEOUT, c.currentStepId, AcxStatus.TIMEOUT,
                    Map.of("elapsed_ms", now - c.start, "at", where));
            return AcxStepOutcome.timedOut(
                    "AC 熔断: 执行超时（" + (now - c.start) + "ms），当前: " + where);
        }
        if (c.stepCounter[0] >= c.limits.maxSteps()) {
            emit(c, AcxEvent.Kind.CIRCUIT_STEPS, c.currentStepId, AcxStatus.FAIL,
                    Map.of("max_steps", c.limits.maxSteps()));
            return AcxStepOutcome.failed(
                    "AC 熔断: 已达最大总步数 " + c.limits.maxSteps() + "（当前 AC: " + def.name() + "）");
        }
        return null;
    }

    /**
     * 取消的统一出口（单源）：checkCircuit 与「成功回执前的复查」（AC-B19）共用。
     *
     * <p>断点保留在当前位置，pausedReason = {@code host_cancelled}；resume 可原地再来
     * （取消是宿主决定，不代表已完成的步骤不可重看）。</p>
     */
    private AcxStepOutcome cancelled(Ctx c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_cancelled", true);
        out.put("_pause_reason", "host_cancelled");
        c.pausedReason = "host_cancelled";
        emit(c, AcxEvent.Kind.STEP_PAUSED, c.currentStepId, AcxStatus.PAUSED,
                Map.of("cancelled", true));
        return AcxStepOutcome.paused("已被宿主取消（断点保留，可 resume）", out);
    }

    private void markStepCompleted(Ctx c, int topIndex) {
        c.completedStepIndex = topIndex + 1;
    }

    /** 宽松布尔：真布尔直接取，字符串走 parseBoolean（.ac 里 "true"/"false" 两种写法都存在）。 */
    private static boolean boolFlag(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static boolean ignoreFailure(AcxStep step) {
        Object v = step.params().get("ignore_failure");
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static boolean isPausedControl(Map<String, Object> out) {
        return Boolean.TRUE.equals(out.get("_stagnated"))
                || Boolean.TRUE.equals(out.get("_reached_max_iters"));
    }

    /** 在飞任务的机器可读字段（适配层 {@code acceptedOutput} 的约定）：原样带进事件 detail。 */
    private static void carryAcceptedFields(Map<String, Object> output, Map<String, Object> detail) {
        if (output == null) {
            return;
        }
        for (String key : new String[]{"task_id", "_accepted", "_completed", "_standing"}) {
            if (output.containsKey(key)) {
                detail.put(key, output.get(key));
            }
        }
    }

    /**
     * 失败诊断四个字段，供后续 {@code if($prev.failure_reason)} 走降级分支。
     *
     * <p><b>关键：合并而不是替换。</b>替换会让上一步的进度字段从 {@code $prev} 里消失，
     * 于是 while 的退出条件再也读不到它 —— 循环只能靠停滞/硬上限收场，
     * 「换目标继续直到扫不到」这条正常路径走不通。DD 就是替换
     * （{@code AcRunner.java:213-217} 与 {@code :442-446}）。</p>
     */
    private static Map<String, Object> withFailureDiag(Map<String, Object> base,
                                                       AcxStep step, String message) {
        Map<String, Object> m = new LinkedHashMap<>(base == null ? Map.of() : base);
        m.put("failure_reason", message == null ? "未知失败" : message);
        m.put("failed_step", step.id());
        m.put("failed_block", step.block());
        m.put("_failed", true);
        return m;
    }

    private static void mergeProgress(Ctx c, Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        for (String f : AcxProgress.FIELDS) {
            Object v = output.get(f);
            if (v instanceof Number n) {
                double cur = AcxValueResolver.toDouble(c.progress.get(f));
                if (n.doubleValue() > cur) {
                    c.progress.put(f, n);
                }
            } else if (v instanceof Boolean b && b) {
                c.progress.put(f, b);
            }
        }
    }

    private String pauseMessage(AcxDefinition def, AcxStep step, Map<String, Object> out) {
        String kind = Boolean.TRUE.equals(out.get("_stagnated")) ? "停滞" : "超限(max_iters)";
        Object reason = out.get("_pause_reason");
        String progress = AcxProgress.renderSummary(AcxProgress.extractProgress(out));
        return "【暂停可续】" + def.name() + " 停在这: step[" + step.id() + "] " + kind
                + ": " + reason + "，进度: " + progress
                + "。AI诊断：真废→换工具 / 补齐环境材料→resume 续传";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static AcxCondition parseCondition(Map<String, Object> spec) {
        if (spec == null) {
            return null;
        }
        Object f = spec.get("field");
        if (f == null) {
            return null;
        }
        Operator op = Operator.fromSymbol(String.valueOf(spec.getOrDefault("op", "==")));
        if (op == null) {
            // 未知算子不能当成 EQ（那是把写错的脚本悄悄改成另一个语义）
            LOG.warning("条件算子无法识别，恒为 false: " + spec.get("op"));
            op = Operator.UNKNOWN;
        }
        return new AcxCondition(String.valueOf(f), op, spec.get("value"));
    }

    private void emit(Ctx c, AcxEvent.Kind kind, String stepId, AcxStatus status, Map<String, Object> detail) {
        emit(c.events, kind, c.runId, c.acNameForEvents, stepId, status, detail);
    }

    private void emitDetached(AcxEvent.Kind kind, String acName, int at) {
        emit(events, kind, "resume", acName, null, null, Map.of("start_index", at));
    }

    private void emitDetached(AcxEvent.Kind kind, String acName, int at, Map<String, Object> detail) {
        emit(events, kind, "resume", acName, null, null, detail);
    }

    private void emit(AcxEventSink sink, AcxEvent.Kind kind, String runId, String acName,
                      String stepId, AcxStatus status, Map<String, Object> detail) {
        try {
            sink.accept(AcxEvent.of(kind, runId, acName)
                    .stepId(stepId)
                    .status(status)
                    .loopCount(kind == AcxEvent.Kind.CONTROL_STAGNATED
                            || kind == AcxEvent.Kind.CONTROL_MAX_ITERS ? 1 : 0)
                    .detail(detail)
                    .build());
        } catch (Throwable t) {
            // 事件出口挂了不能影响执行 —— 观测不该成为执行依赖
            LOG.warning("acx 事件出口抛异常（已忽略）: " + t);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 内部状态
    // ═══════════════════════════════════════════════════════════════════

    private static final class Ctx {
        final String runId;
        final long start;
        final long deadline;
        final AtomicBoolean cancelFlag;
        final AcxRuntimeLimits limits;
        final AcxEventSink events;
        final Deque<String> callStack = new ArrayDeque<>();
        final int[] stepCounter = { 0 };
        final Map<String, Object> progress = new LinkedHashMap<>();
        /** AC-B9：set 步写入的运行期变量。进 AcxRunRecord → resume 后仍在（解 L-06 跨断点引用）。 */
        final Map<String, Object> vars = new LinkedHashMap<>();
        /** 本步是否真的改变了某个变量（AC-B10：变量在收敛时不该被判「停滞」）。 */
        boolean lastStepChangedVar;

        int completedStepIndex;
        int loopCount;
        String currentStepId;
        String stagnantStep;
        String failedStep;
        String pausedReason;
        boolean verifierAbsentEmitted;
        Map<String, Object> lastResolvedParams = Map.of();
        Map<String, Object> detailScratch = new LinkedHashMap<>();
        String acNameForEvents;
        final boolean resumeMode;
        AcxRunRecord.Builder rec;

        Ctx(String runId, long start, long deadline, AtomicBoolean cancelFlag,
            AcxRuntimeLimits limits, AcxEventSink events, String acName, int startIndex) {
            this.runId = runId;
            this.start = start;
            this.deadline = deadline;
            this.cancelFlag = cancelFlag;
            this.limits = limits;
            this.events = events;
            this.acNameForEvents = acName;
            this.completedStepIndex = startIndex;
            this.resumeMode = startIndex > 0;
        }
    }

    private final class CallCtx implements AcxTool.AcxCallContext {
        private final Ctx c;
        private final AcxStep step;

        CallCtx(Ctx c, AcxStep step) {
            this.c = c;
            this.step = step;
        }

        @Override
        public String runId() {
            return c.runId;
        }

        @Override
        public String stepId() {
            return step.id();
        }

        @Override
        public String acName() {
            // 顶层 AC 名（子 AC 帧不覆盖）—— 适配层受理挂账的键，resume 换 runId 也不能丢
            return c.acNameForEvents;
        }

        @Override
        public boolean isCancelRequested() {
            return c.cancelFlag.get() || System.currentTimeMillis() > c.deadline;
        }

        @Override
        public boolean isResuming() {
            return c.resumeMode;
        }

        @Override
        public void emitDetail(String key, Object value) {
            c.detailScratch.put(key, value);
        }
    }

    // ═══════════════════════════════════════════════════════════════════

    public static final class Builder {
        private AcxToolRegistry tools;
        private AcxCatalog catalog;
        private AcxVerifier verifier;
        private AcxTargetBlacklist blacklist;
        private AcxEventSink events;
        private AcxRecordStore store;
        private AcxExperienceSink experienceSink;
        private AcxRuntimeLimits limits;

        public Builder tools(AcxToolRegistry v) { this.tools = v; return this; }
        public Builder catalog(AcxCatalog v) { this.catalog = v; return this; }
        public Builder verifier(AcxVerifier v) { this.verifier = v; return this; }
        public Builder blacklist(AcxTargetBlacklist v) { this.blacklist = v; return this; }
        public Builder events(AcxEventSink v) { this.events = v; return this; }
        public Builder store(AcxRecordStore v) { this.store = v; return this; }
        public Builder experienceSink(AcxExperienceSink v) { this.experienceSink = v; return this; }
        public Builder limits(AcxRuntimeLimits v) { this.limits = v; return this; }

        public AcxRunner build() {
            if (tools == null) {
                throw new IllegalStateException("AcxRunner 必须有积木注册表");
            }
            return new AcxRunner(this);
        }
    }
}
