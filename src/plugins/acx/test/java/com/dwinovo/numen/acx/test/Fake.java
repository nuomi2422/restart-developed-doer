package com.dwinovo.numen.acx.test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxEventSink;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTargetBlacklist;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolRegistry;
import com.dwinovo.numen.acx.api.AcxToolSchema;

/**
 * 测试替身。
 *
 * <p><b>计数只在这一处发生</b>：所有积木都走 {@link #record}，返回本次调用序号。
 * 早期版本让 counter 额外调一次 bump，导致计数翻倍、计数型输出也翻倍，
 * 一度把 while 终点测试的结果全带偏 —— 所以这里刻意收敛到单一入口。</p>
 */
public final class Fake {

    private Fake() { }

    /** 调用记录。测试之间用 {@link #resetCalls()} 换新实例。 */
    public static final class Calls {
        public final List<String> order = new ArrayList<>();
        public final Map<String, Integer> count = new LinkedHashMap<>();
        public final List<Map<String, Object>> paramsSeen = new ArrayList<>();

        public int count(String name) {
            return count.getOrDefault(name, 0);
        }

        /** 最近一次调用某积木时看到的参数。 */
        public Map<String, Object> lastParams(String name) {
            // paramsSeen 与 order 同序，用 order 回溯定位最后一次
            int idx = -1;
            for (int i = order.size() - 1; i >= 0; i--) {
                if (name.equals(order.get(i))) {
                    idx = i;
                    break;
                }
            }
            return idx < 0 ? null : paramsSeen.get(idx);
        }

        public List<String> order() {
            return order;
        }
    }

    public static Calls calls = new Calls();

    public static void resetCalls() {
        calls = new Calls();
    }

    /** 唯一的计数入口：登记调用并返回序号（从 1 开始）。 */
    private static int record(String name, Map<String, Object> params) {
        int n = calls.count.merge(name, 1, Integer::sum);
        calls.order.add(name);
        calls.paramsSeen.add(params == null ? Map.of() : new LinkedHashMap<>(params));
        return n;
    }

    // ── 注册表 ──────────────────────────────────────────────────────

    public static final class Registry implements AcxToolRegistry {
        private final Map<String, AcxTool> tools = new LinkedHashMap<>();

        public Registry add(AcxTool t) {
            tools.put(t.name(), t);
            return this;
        }

        @Override
        public Optional<AcxTool> find(String name) {
            return Optional.ofNullable(tools.get(name));
        }

        @Override
        public boolean contains(String name) {
            return tools.containsKey(name);
        }

        @Override
        public Collection<String> names() {
            return tools.keySet();
        }

        @Override
        public void register(AcxTool tool) {
            tools.put(tool.name(), tool);
        }
    }

    // ── 积木基类 ────────────────────────────────────────────────────

    public abstract static class Base implements AcxTool {
        private final String name;
        private final AcxToolSchema schema;

        protected Base(String name, Map<String, Object> outputFields) {
            this.name = name;
            AcxToolSchema.Builder b = AcxToolSchema.builder();
            for (String k : outputFields.keySet()) {
                b.param(k, AcxToolSchema.ParamType.ANY);
            }
            this.schema = b.build();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AcxToolSchema schema() {
            return schema;
        }
    }

    // ── 积木种类 ────────────────────────────────────────────────────

    /** 固定输出。 */
    public static AcxTool fixed(String name, Map<String, Object> output) {
        return new Base(name, output) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                return AcxStepOutcome.success(output, name + " ok");
            }
        };
    }

    /** 恒定失败。 */
    public static AcxTool failing(String name, String msg) {
        return new Base(name, Map.of()) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                return AcxStepOutcome.failed(msg);
            }
        };
    }

    /** 抛异常（含 Error，测 Throwable 兜底而不是只兜 RuntimeException）。 */
    public static AcxTool throwing(String name, Throwable t) {
        return new Base(name, Map.of()) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                if (t instanceof Error e) {
                    throw e;
                }
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new SneakyThrow(t);
            }
        };
    }

    /** 返回 null outcome（测积木契约违规被兜住）。 */
    public static AcxTool nullReturning(String name) {
        return new Base(name, Map.of()) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                return null;
            }
        };
    }

    /** 序号型：输出 {@code {counterField: 第几次调用}}。 */
    public static AcxTool counter(String name, String counterField) {
        return new Base(name, Map.of(counterField, 0)) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                int n = record(name, params);
                return AcxStepOutcome.success(
                        Map.of(counterField, n), name + " run " + n);
            }
        };
    }

    /** 恒 0 产出（永远无实质进展）→ 触发 while 终点2 停滞。 */
    public static AcxTool zero(String name) {
        return new Base(name, Map.of("mined", 0)) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                return AcxStepOutcome.success(Map.of("mined", 0), name + " nothing");
            }
        };
    }

    /** 有产出但可配置：{@code mined=n}。 */
    public static AcxTool mining(String name) {
        return new Base(name, Map.of("mined", 0)) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                int n = record(name, params);
                return AcxStepOutcome.success(Map.of("mined", n), name + " #" + n);
            }
        };
    }

    /** 主动暂停的积木。 */
    public static AcxTool pauser(String name, String reason) {
        return new Base(name, Map.of()) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                return AcxStepOutcome.paused(name + " paused: " + reason,
                        Map.of("_pause_reason", reason));
            }
        };
    }

    /** 「只暂停一次」开关：给 resume 回归用 —— 第一次调用暂停，之后正常成功。 */
    public static final class PauseOnce {
        private boolean paused;

        public boolean tryPause() {
            if (paused) {
                return false;
            }
            paused = true;
            return true;
        }
    }

    public static AcxTool pauserOnce(String name, PauseOnce once, String reason) {
        return new Base(name, Map.of()) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                if (once.tryPause()) {
                    return AcxStepOutcome.paused(name + " paused once: " + reason,
                            Map.of("_pause_reason", reason));
                }
                return AcxStepOutcome.success(Map.of("resumed", true), name + " resumed");
            }
        };
    }

    /** 取消开关：测试里在 runner 建好后把 runner 塞进来。 */
    public static final class Canceller {
        public com.dwinovo.numen.acx.core.AcxRunner runner;
    }

    /**
     * 自己触发取消，然后立刻复查 {@code isCancelRequested()}。
     *
     * <p>这样测「取消对积木可见」是<b>确定性</b>的：不用去和 wall-clock deadline 抢先后，
     * 那种写法在边界上必然 flaky。</p>
     */
    public static AcxTool cancelThenCheck(String name, Canceller c, Map<String, Object> sink) {
        return new Base(name, Map.of("saw_cancel", false)) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                record(name, params);
                c.runner.cancel(ctx.runId());
                sink.put("saw_cancel", ctx.isCancelRequested());
                sink.put("run_id_matches", ctx.runId() != null && !ctx.runId().isBlank());
                sink.put("step_id", ctx.stepId());
                return AcxStepOutcome.success(Map.of("progress", 1), "ok");
            }
        };
    }

    /** 每轮检查取消标志（测 isCancelRequested 能被长耗时积木看见）。 */
    public static AcxTool spin(String name) {
        return new Base(name, Map.of()) {
            @Override
            public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                int n = record(name, params);
                if (ctx.isCancelRequested()) {
                    return AcxStepOutcome.paused(name + " saw cancel at #" + n,
                            Map.of("_pause_reason", "cancel_seen_by_tool"));
                }
                return AcxStepOutcome.success(Map.of("progress", n), name + " #" + n);
            }
        };
    }

    @SuppressWarnings("serial")
    public static final class SneakyThrow extends RuntimeException {
        public SneakyThrow(Throwable t) {
            super(t);
        }
    }

    // ── 事件 / 黑名单 收集 ──────────────────────────────────────────

    public static final class Events implements AcxEventSink {
        public final List<AcxEvent> all = new ArrayList<>();

        @Override
        public void accept(AcxEvent event) {
            all.add(event);
        }

        public int count(AcxEvent.Kind k) {
            int n = 0;
            for (AcxEvent e : all) {
                if (e.kind() == k) {
                    n++;
                }
            }
            return n;
        }

        public boolean has(AcxEvent.Kind k) {
            return count(k) > 0;
        }

        public List<String> kinds() {
            List<String> out = new ArrayList<>();
            for (AcxEvent e : all) {
                out.add(e.kind().name());
            }
            return out;
        }
    }

    public static final class Blacklist implements AcxTargetBlacklist {
        public final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void blacklist(Map<String, Object> failedParams) {
            calls.add(new LinkedHashMap<>(failedParams));
        }
    }

    // ── 小构造器 ───────────────────────────────────────────────────

    public static Map<String, Object> cond(String field, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    public static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
