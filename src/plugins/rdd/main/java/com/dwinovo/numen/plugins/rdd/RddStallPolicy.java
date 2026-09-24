package com.dwinovo.numen.plugins.rdd;

/** Pure supervision policy; activity evidence is separate from merely having a task in flight. */
final class RddStallPolicy {
    static final int IDLE_GRACE_CHECKS = 15;
    /** One check per second. A busy slot alone cannot suppress supervision forever. */
    static final int WORK_GRACE_CHECKS = 120;

    record Observation(String assetsAndPosition, String workProgress, boolean waiting, String source) {
        String fingerprint() { return assetsAndPosition + "|work=" + workProgress; }
    }

    record Check(String fingerprint, int unchanged, int limit, boolean changed) {
        boolean stalled() { return unchanged >= limit; }
        int remaining() { return Math.max(0, limit - unchanged); }
    }

    static Check check(String previous, int unchanged, Observation observation) {
        String fingerprint = observation.fingerprint();
        boolean changed = !fingerprint.equals(previous);
        int next = changed ? 0 : unchanged + 1;
        return new Check(fingerprint, next,
                observation.waiting() ? WORK_GRACE_CHECKS : IDLE_GRACE_CHECKS, changed);
    }
}
