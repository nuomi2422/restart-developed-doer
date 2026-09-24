# Monitor watchdog

After an instance starts, the watchdog checks the Monitor and game in that order. Monitor recovery is always first; game recovery follows. Recovery attempts are bounded by `MaxRecoveries`. On repeated failure the watchdog records a HALTED result and stops. It may restore infrastructure, but it never replans a task or approves promotion.
