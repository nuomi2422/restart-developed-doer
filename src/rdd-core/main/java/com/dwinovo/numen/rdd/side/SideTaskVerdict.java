package com.dwinovo.numen.rdd.side;

import java.util.Map;

/**
 * 一次支线评估的结论。支线类型只描述"现在怎样"，通用引擎负责改状态、发事件、恢复主线。
 */
public record SideTaskVerdict(Kind kind, String evidence, String nextPhase, Map<String, String> data) {

    public enum Kind {
        /** 继续；{@code nextPhase}/{@code data} 描述新阶段。 */
        CONTINUE,
        COMPLETE,
        /** 超时跳过（不是失败）。 */
        SKIP,
        CANCEL
    }

    public SideTaskVerdict {
        evidence = evidence == null ? "" : evidence;
        nextPhase = nextPhase == null ? "" : nextPhase;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static SideTaskVerdict keep(String nextPhase, Map<String, String> data) {
        return new SideTaskVerdict(Kind.CONTINUE, "", nextPhase, data);
    }

    public static SideTaskVerdict complete(String evidence) {
        return new SideTaskVerdict(Kind.COMPLETE, evidence, "", Map.of());
    }

    public static SideTaskVerdict skip(String reason) {
        return new SideTaskVerdict(Kind.SKIP, reason, "", Map.of());
    }

    public static SideTaskVerdict cancel(String reason) {
        return new SideTaskVerdict(Kind.CANCEL, reason, "", Map.of());
    }
}
