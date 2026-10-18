package com.dwinovo.numen.settlement.core.protect;

/** 保护判定结果。 */
public enum Decision {
    ALLOW, DENY;

    public boolean denied() {
        return this == DENY;
    }
}
