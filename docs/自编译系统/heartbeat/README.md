# Heartbeat contract

The existing `scripts/heartbeat.ps1` remains the single scheduler entry point. It wakes approximately every ten minutes, checks the durable task queue, and stops itself when no active task exists.

When `runtime/task-queue/active-task.md` exists, the heartbeat injects a reminder to read the task chain, latest handoff, and monitor logs. Recovery is idempotent and does not create `run-state.json`. `-WhatIf` is available for contract tests without typing into a live terminal.
