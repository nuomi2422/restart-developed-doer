# Machine state contract

The runtime uses explicit states: INIT, RUNNING, RETRYING, DEGRADED, REPLANNING, SELF_COMPILING, TESTING, PROMOTABLE, BASELINE, PRODUCTION, ROLLBACK, HALTED.

Only the state-machine script may append state transition events. A transition is valid only when both the edge and authority role match policy. SafetyGate, Watchdog, or Human may enter HALTED; Watchdog restores infrastructure only and never replans work.

Machine facts are appended to `runtime/events/state.jsonl`. Markdown remains the explanation layer.
