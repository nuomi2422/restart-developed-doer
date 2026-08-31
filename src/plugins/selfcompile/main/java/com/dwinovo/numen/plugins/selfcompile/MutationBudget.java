package com.dwinovo.numen.plugins.selfcompile;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded retry/cooldown guard shared by generation and verification stages. */
public final class MutationBudget {
    private final int maxAttempts;
    private final Duration maxDuration;
    private final Instant startedAt;
    private final AtomicInteger attempts = new AtomicInteger();

    public MutationBudget(int maxAttempts, Duration maxDuration) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.maxDuration = maxDuration;
        this.startedAt = Instant.now();
    }

    public boolean tryAcquire() {
        if (expired()) return false;
        while (true) {
            int current = attempts.get();
            if (current >= maxAttempts) return false;
            if (attempts.compareAndSet(current, current + 1)) return true;
        }
    }

    public int attempts() { return attempts.get(); }
    public int maxAttempts() { return maxAttempts; }
    public boolean expired() { return Duration.between(startedAt, Instant.now()).compareTo(maxDuration) >= 0; }
    public boolean exhausted() { return attempts.get() >= maxAttempts || expired(); }
}
