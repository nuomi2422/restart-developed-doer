package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.ExecutionRecord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 门面层在执行的 AC 会话表：executionId → 会话条目。后台线程跑 AC，门面工具
 * 轮询 future 拿终态记录（RUNNING 期间可查暂停点）。
 */
public final class AcSessions {

    /** 会话条目。{@code future} 完成即整次尝试的终态（含 PAUSED）。 */
    public record SessionEntry(String executionId, String acName, AcDefinition ac,
                               CompletableFuture<ExecutionRecord> future) {
        public SessionEntry {
            if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId required");
            if (ac == null) throw new IllegalArgumentException("ac required");
        }
    }

    private final Map<String, SessionEntry> byId = new ConcurrentHashMap<>();

    public void put(SessionEntry entry) {
        byId.put(entry.executionId(), entry);
    }

    public Optional<SessionEntry> get(String executionId) {
        return Optional.ofNullable(byId.get(executionId));
    }

    public void remove(String executionId) {
        byId.remove(executionId);
    }
}
