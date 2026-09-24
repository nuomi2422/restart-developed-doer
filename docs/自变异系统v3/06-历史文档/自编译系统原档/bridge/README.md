# Claude-Codex Bridge

The current orchestration is Codex-led: Codex is the commander and technical reviewer; Claude CLI is an auxiliary execution Harness. The official `claude-codex-bridge` package is registered as two stdio MCP servers.

- Claude server: `npx.cmd claude-codex-bridge serve codex`
- Codex server: `npx.cmd claude-codex-bridge serve claude`
- Recursion guard: keep the package default `BRIDGE_DEPTH` limit.
- No API keys are stored in this document or generated config.
- Reviewer output is advice only; deployment and production promotion remain gated by tests and explicit human approval.

Verify with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\verify-claude-codex-bridge.ps1
```
