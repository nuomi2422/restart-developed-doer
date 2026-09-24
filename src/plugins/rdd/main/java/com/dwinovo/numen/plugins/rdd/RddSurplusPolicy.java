package com.dwinovo.numen.plugins.rdd;

/** Pure budget rules, independent of Minecraft classes for regression testing. */
final class RddSurplusPolicy {
    static boolean withinBudget(long elapsedNanos, double distanceSquared, float health, int gained) {
        return elapsedNanos >= 0 && elapsedNanos < 30_000_000_000L && distanceSquared <= 64
                && distanceSquared >= 0 && health > 12 && gained < 8;
    }
}
