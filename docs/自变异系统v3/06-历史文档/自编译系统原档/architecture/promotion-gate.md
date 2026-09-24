# Promotion gate contract

Candidate changes carry `candidate_id`, `parent_commit`, changed files, test instance, evidence paths, and unresolved faults.

Promotion is ordered and monotonic:

`BUILD_PASS -> CONTRACT_PASS -> TEST_RUNTIME_PASS -> MONITOR_HEALTHY -> WORLD_FACT_PASS -> NO_UNRESOLVED_FAULT -> PROMOTABLE -> GPT_BASELINE`

Missing evidence keeps `promotable=false`. Production promotion additionally requires explicit human approval. Gate results are machine events in `runtime/events/promotion.jsonl`.
