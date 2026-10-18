package com.dwinovo.numen.acx.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolPort;

/** 宿主端口测试替身。调用一次记一次，脚本化返回受理 / 完成 / 失败。 */
public final class FakePorts {

    private FakePorts() { }

    public static AcxPortSchema anySchema() {
        return AcxPortSchema.builder().build();
    }

    public static AcxTool.AcxCallContext ctx(String acName, String stepId) {
        return new AcxTool.AcxCallContext() {
            @Override
            public String runId() {
                return "run-1";
            }

            @Override
            public String stepId() {
                return stepId;
            }

            @Override
            public String acName() {
                return acName;
            }

            @Override
            public boolean isCancelRequested() {
                return false;
            }

            @Override
            public void emitDetail(String key, Object value) {
            }
        };
    }

    public static final class Scripted implements AcxToolPort {
        private final String name;
        private final AcxPortSchema schema;
        private final Deque<AcxToolPort.Result> script = new ArrayDeque<>();
        private AcxToolPort.Result fallback;
        public final List<Map<String, Object>> calls = new ArrayList<>();

        public Scripted(String name, AcxPortSchema schema) {
            this.name = name;
            this.schema = schema == null ? anySchema() : schema;
        }

        public static Scripted of(String name) {
            return new Scripted(name, anySchema());
        }

        public Scripted then(AcxToolPort.Result r) {
            script.addLast(r);
            return this;
        }

        public Scripted thenCompleted(Map<String, Object> data) {
            return then(AcxToolPort.Result.completed(data));
        }

        public Scripted thenAccepted(String taskId, Map<String, Object> acceptance) {
            return then(AcxToolPort.Result.accepted(taskId, acceptance, "accepted"));
        }

        public Scripted thenAcceptedStanding(String taskId, Map<String, Object> acceptance) {
            return then(AcxToolPort.Result.acceptedStanding(taskId, acceptance, "standing accepted"));
        }

        public Scripted thenFailed(String message) {
            return then(AcxToolPort.Result.failed(message));
        }

        public Scripted fallbackCompleted(Map<String, Object> data) {
            this.fallback = AcxToolPort.Result.completed(data);
            return this;
        }

        public int invokeCount() {
            return calls.size();
        }

        public Map<String, Object> lastCall() {
            return calls.isEmpty() ? null : calls.get(calls.size() - 1);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake port " + name;
        }

        @Override
        public AcxPortSchema schema() {
            return schema;
        }

        @Override
        public AcxToolPort.Result invoke(Map<String, Object> params) {
            calls.add(new LinkedHashMap<>(params == null ? Map.of() : params));
            AcxToolPort.Result r = script.pollFirst();
            if (r != null) {
                return r;
            }
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalStateException("fake port " + name + " 脚本耗尽");
        }
    }

    public static final class Probe implements AcxCompletionProbe {
        private final Deque<AcxCompletionProbe.Snapshot> script = new ArrayDeque<>();
        public final List<String> asked = new ArrayList<>();

        public Probe then(AcxCompletionProbe.Snapshot s) {
            script.addLast(s);
            return this;
        }

        public Probe thenRunning(String message) {
            return then(AcxCompletionProbe.Snapshot.running(message));
        }

        public Probe thenDone(Map<String, Object> data) {
            return then(AcxCompletionProbe.Snapshot.done(data, "done"));
        }

        public Probe thenFailed(String message) {
            return then(AcxCompletionProbe.Snapshot.failed(message));
        }

        @Override
        public AcxCompletionProbe.Snapshot probe(String taskId) {
            asked.add(taskId);
            AcxCompletionProbe.Snapshot s = script.pollFirst();
            return s != null ? s : AcxCompletionProbe.Snapshot.running("fake running");
        }
    }
}
