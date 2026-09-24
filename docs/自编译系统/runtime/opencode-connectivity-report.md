# OpenCode 连通性探针

- 时间：2026-09-13
- CLI：OpenCode 1.18.30
- 模型：`opencode/big-pickle`
- 模式：`readonly`
- 目标仓库：`E:\restart developing doer\minecraft-numen`
- 退出码：`0`
- 耗时：约 72 秒

探针读取了 `TaskChain`、`RddPlugin`、`RddMonitor`、`RddRuntime` 和 `MonitoringJournal`，返回了任务链、RDD 事件和监测台入口的结构化报告。工作树在探针结束后保持干净，没有提交或推送。

调用方式（Windows 执行策略受限时使用 Bypass）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "E:\restart developing doer\rdd-selfcompile\scripts\opencode-harness.ps1" `
  -Mode readonly -Task "只读检查指定批次并返回证据" `
  -Output "E:\新建文件夹\rdd架构\自编译系统\runtime\opencode-probe.json"
```

OpenCode 只作为受控下位 Harness；`implement` 批次仍需 Codex 指定文件范围和验证命令，不能自行 commit、push、部署或批准生产。
