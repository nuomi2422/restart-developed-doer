package com.dwinovo.numen.plugins.rdd;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Serializes goal replacement with callback application, including callbacks already queued. */
final class RddCallbackGuard {
    record Ticket(UUID companionId, Object generation) {}
    private final Map<UUID, Ticket> current = new HashMap<>();

    synchronized Ticket replace(UUID companionId) {
        Ticket ticket = new Ticket(companionId, new Object());
        current.put(companionId, ticket);
        return ticket;
    }

    synchronized Ticket current(UUID companionId) {
        return current.computeIfAbsent(companionId, id -> new Ticket(id, new Object()));
    }

    synchronized boolean isCurrent(Ticket ticket) {
        return current.get(ticket.companionId()) == ticket;
    }

    synchronized void clear() {
        clear(() -> {});
    }

    /** Invalidate queued work and discard its temporary owner state as one lifecycle boundary. */
    synchronized void clear(Runnable cleanup) {
        current.clear();
        cleanup.run();
    }

    /** Check inside the destination executor, never merely before enqueueing. */
    void dispatch(Ticket ticket, Executor executor, Runnable callback) {
        executor.execute(() -> {
            synchronized (this) {
                if (isCurrent(ticket)) callback.run();
            }
        });
    }
}
