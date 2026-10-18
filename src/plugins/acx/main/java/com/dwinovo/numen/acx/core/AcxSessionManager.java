package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * 会话层：按 {@code run_id} 管理异步执行，是宿主门面（{@link AcxFacade}）的底座。
 *
 * <p>它把旧 AC 门面的三个洞补在本层（不推给宿主插件）：</p>
 * <ol>
 *   <li><b>executionId 双份</b>：旧 AC 的 {@code AcExecuteTool} 自生成一个 executionId、
 *       执行器内部又生成一个，两边对不上。ACX 由本层<b>先定 runId</b>，再把它交给
 *       {@link AcxRunner#run(AcxDefinition, Map, String)} —— 全链只有一个 id。</li>
 *   <li><b>对已取消的 future 调 join() 会崩</b>：本层从不 join；完成/异常统一经回调
 *       落进 {@link Session#record()} / {@link Session#error()}，状态查询只读会话字段。</li>
 *   <li><b>会话不清理内存涨</b>：已完成会话按 {@code maxFinished} 自动淘汰（默认
 *       {@value #DEFAULT_MAX_FINISHED}），另有 {@link #purgeFinished()}。</li>
 * </ol>
 *
 * <p><b>执行器必须由宿主注入</b>（没有默认公共池）：旧 AC 用公共 ForkJoin 池跑任务，
 * 和游戏服的调度无关；ACX 要求宿主给出自己的执行器（如单线程守护线程或服务器调度器），
 * 这样取消/重载/停机时的收手是可控的。</p>
 *
 * <p>并发安全：会话表的读写都在 {@code sessions} 锁内；执行在宿主给的 executor 上跑，
 * 完成回调只改会话自身的 volatile 字段 + 受锁保护的淘汰。</p>
 */
public final class AcxSessionManager {

    private static final Logger LOG = Logger.getLogger("numen.acx.session");

    /** 默认保留的已完成会话数（超出从最老的开始淘汰）。 */
    public static final int DEFAULT_MAX_FINISHED = 32;

    /** 一条执行会话。除定义/输入外全部可读；写只发生在会话层内部。 */
    public static final class Session {
        private final String runId;
        private final AcxDefinition definition;
        private final Map<String, Object> input;
        private final long startedAtMillis;

        private volatile AcxRunRecord record;
        private volatile Throwable error;
        private volatile boolean inFlight;
        private volatile boolean cancelRequested;

        private Session(String runId, AcxDefinition definition, Map<String, Object> input) {
            this.runId = runId;
            this.definition = definition;
            this.input = input;
            this.startedAtMillis = System.currentTimeMillis();
        }

        public String runId() {
            return runId;
        }

        public AcxDefinition definition() {
            return definition;
        }

        public Map<String, Object> input() {
            return input;
        }

        public long startedAtMillis() {
            return startedAtMillis;
        }

        /** 最近一次完成的记录；还在跑或从未完成时为 null（resume 期间保留上一条记录）。 */
        public AcxRunRecord record() {
            return record;
        }

        /** 会话内部错误（执行器自身异常、executor 拒收等）；正常终态走 record()。 */
        public Throwable error() {
            return error;
        }

        public boolean isRunning() {
            return inFlight;
        }

        public boolean isCancelRequested() {
            return cancelRequested;
        }
    }

    private final AcxRunner runner;
    private final Executor executor;
    private final int maxFinished;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public AcxSessionManager(AcxRunner runner, Executor executor) {
        this(runner, executor, DEFAULT_MAX_FINISHED);
    }

    public AcxSessionManager(AcxRunner runner, Executor executor, int maxFinished) {
        this.runner = Objects.requireNonNull(runner, "runner 不能为 null");
        this.executor = Objects.requireNonNull(executor, "executor 不能为 null");
        if (maxFinished < 1) {
            throw new IllegalArgumentException("maxFinished 至少为 1: " + maxFinished);
        }
        this.maxFinished = maxFinished;
    }

    /**
     * 启动一条 AC，立刻返回 run_id。
     *
     * <p>runId 由本层预生成并交给执行器 —— 这是「executionId 只有一份」的落点。</p>
     */
    public String start(AcxDefinition def, Map<String, Object> input) {
        Objects.requireNonNull(def, "AC 定义不能为 null");
        Map<String, Object> in = input == null ? Map.of() : input;
        String runId = AcxRunRecord.shortUuid();
        Session s = new Session(runId, def, in);
        synchronized (sessions) {
            sessions.put(runId, s);
            s.inFlight = true;
            evictFinishedLocked();
        }
        schedule(s, () -> runner.run(def, in, runId));
        return runId;
    }

    public Session session(String runId) {
        synchronized (sessions) {
            return sessions.get(runId);
        }
    }

    public boolean isRunning(String runId) {
        Session s = session(runId);
        return s != null && s.isRunning();
    }

    /**
     * 续跑一条已 PAUSED 的会话。
     *
     * <p>校验链（名/版本/指纹/断点）由 {@link AcxRunner#checkResumable} 在<b>调用线程上同步执行</b>，
     * 不通过直接抛 {@link IllegalArgumentException} —— 门面层据此回「明确拒绝」而不是异步失败。</p>
     *
     * @return false = 会话不存在 / 还在跑 / 不是 PAUSED
     */
    public boolean resume(String runId, Map<String, Object> newInput) {
        return resume(runId, newInput, null);
    }

    /**
     * 同 {@link #resume(String, Map)}，但允许调用方传入<b>重新解析过的当前定义</b>。
     *
     * <p>★ TODO AC-B22 修复（2026-10-06）：会话手里的 {@code s.definition} 是 start() 时的旧对象，
     * 拿它做校验等于同一份内容自己比自己 —— 名/版本/指纹三道内容门<b>恒真</b>，
     * 改过脚本（没升版本）的续跑会静默按旧定义跑完。门面现在按名字重新查库并把
     * 当前定义传进来；传 null 时保持旧行为（内联 ac_json 执行的会话没有库条目）。</p>
     */
    public boolean resume(String runId, Map<String, Object> newInput, AcxDefinition currentDefinition) {
        Session s;
        AcxRunRecord prior;
        AcxDefinition use;
        synchronized (sessions) {
            s = sessions.get(runId);
            if (s == null || s.inFlight) {
                return false;
            }
            prior = s.record;
            if (prior == null || prior.status() != AcxStatus.PAUSED) {
                return false;
            }
            use = currentDefinition == null ? s.definition : currentDefinition;
            runner.checkResumable(use, prior);
            s.inFlight = true;
        }
        Map<String, Object> in = newInput == null ? prior.input() : newInput;
        final AcxDefinition def = use;
        schedule(s, () -> runner.resume(def, prior, in));
        return true;
    }

    /** 协作式取消：只置标志，积木轮询 {@link com.dwinovo.numen.acx.api.AcxTool.AcxCallContext#isCancelRequested()} 后收手。 */
    public boolean cancel(String runId) {
        Session s = session(runId);
        if (s == null || !s.isRunning()) {
            return false;
        }
        boolean sent = runner.cancel(runId);
        if (sent) {
            s.cancelRequested = true;
        }
        return sent;
    }

    public int size() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    public int runningCount() {
        synchronized (sessions) {
            int n = 0;
            for (Session s : sessions.values()) {
                if (s.inFlight) {
                    n++;
                }
            }
            return n;
        }
    }

    public int finishedCount() {
        synchronized (sessions) {
            int n = 0;
            for (Session s : sessions.values()) {
                if (!s.inFlight) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 清掉所有已结束会话，返回清除数（在跑的保留）。 */
    public int purgeFinished() {
        synchronized (sessions) {
            int removed = 0;
            Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                if (!it.next().getValue().inFlight) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private void schedule(Session s, Callable<AcxRunRecord> call) {
        try {
            executor.execute(() -> {
                AcxRunRecord r;
                try {
                    r = call.call();
                } catch (Throwable t) {
                    finishWithError(s, t);
                    return;
                }
                finishWithRecord(s, r);
            });
        } catch (Throwable t) {
            // executor 拒收（关池/满队列）也要让会话能查得到原因，不能静默卡在 RUNNING
            finishWithError(s, t);
        }
    }

    private void finishWithRecord(Session s, AcxRunRecord r) {
        synchronized (sessions) {
            s.record = r;
            s.inFlight = false;
            s.cancelRequested = false;
            evictFinishedLocked();
        }
    }

    private void finishWithError(Session s, Throwable t) {
        synchronized (sessions) {
            s.error = t;
            s.inFlight = false;
            LOG.warning("acx 会话 " + s.runId + " 异常: " + t);
        }
    }

    /** 保留的已完成会话超过上限时，从最老的开始淘汰。调用方须持有锁。 */
    private void evictFinishedLocked() {
        int finished = 0;
        for (Session s : sessions.values()) {
            if (!s.inFlight) {
                finished++;
            }
        }
        while (finished > maxFinished) {
            Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                if (!it.next().getValue().inFlight) {
                    it.remove();
                    finished--;
                    break;
                }
            }
        }
    }
}
