package com.dwinovo.numen.worldstate.core.provider;

import com.dwinovo.numen.worldstate.core.model.FailReason;

/**
 * 采集器明确失败。带上 {@link FailReason}，让上层把「读不到」和「没有」分开，
 * 也<b>绝不静默</b>——失败时保留旧观测并如实报因。
 */
public final class ProviderException extends RuntimeException {

    private final FailReason reason;

    public ProviderException(FailReason reason, String message) {
        super(message);
        this.reason = reason == null ? FailReason.PROVIDER_ERROR : reason;
    }

    public FailReason reason() {
        return reason;
    }
}
