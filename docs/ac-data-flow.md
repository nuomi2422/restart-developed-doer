# AC 系统数据流（自编译系统 2026-08-31 梳理）

> 性质：只读代码梳理，不改码。给后续检查数据流 / 补观测对照。

## 完整数据流

```text
外部 AI / MCP
  │  tools/call ac_execute
  ▼
AcExecuteTool.onServerCall
  ├─ AcAuthoringService.validateJson   ← 校验（工具存在 / 参数schema / 步数≤50 / 每步参数≤32）
  ├─ AcJson.load                        ← 解析 AC JSON（name/version/steps）
  ├─ ExecutionContext = {numen.entityUuid: companion.UUID}
  ├─ CompletableFuture.supplyAsync(executor.execute)  ← 后台线程执行
  ├─ AcSessions.put(executionId → SessionEntry)
  └─ 回执 {status: RUNNING, execution_id}
        │
        ▼
AcExecutor.execute → executeSession
  ├─ 顺序循环每个 step
  │    emit(STEP_STARTED)
  │    → registry.find(tool) → NumenToolBridge.execute(params, context)
  │    → NumenHostAdapter.invoke(callId, argsJson, anchor)
  │    → NumenTool.invoke → ServerToolTransport → 服务端 onServerCall
  │    → BridgeResultMapper.map(结果JSON → StepResult)
  │    ├─ SUCCESS → completed++, state合并, emit(STEP_SUCCEEDED)
  │    ├─ PAUSED  → 存 ResumeContext(断点), emit(STEP_PAUSED), break
  │    └─ FAILED  → emit(STEP_FAILED), break
  ├─ ExecutionRecord(runId/status/completed/state/resume)
  ├─ append(有界历史 maxRecords=1000)
  └─ emit(EXECUTION_SUCCEEDED/PAUSED/FAILED)
        │
        ▼
外部查询
  ├─ ac_status (AcStatusTool): sessions.get → future.isDone? 终态 : RUNNING
  └─ ac_resume (AcResumeTool): 校验 fingerprint/version/断点 → resume 从断点重跑
```

## 观测出口

```text
✅ 内存事件：AcEvent（EXECUTION_STARTED / STEP_STARTED / STEP_SUCCEEDED /
            STEP_PAUSED / STEP_FAILED / EXECUTION_SUCCEEDED / EXECUTION_PAUSED /
            EXECUTION_FAILED）→ 只发内存监听器（AcEventListener）
✅ 内存历史：ExecutionRecord 有界保留（默认 1000 条），按 executionId 查 attempt 链
❌ 未接监测台：无 MonitoringJournal / RddMonitor 引用
   → 监测台 AC 分页看不到 AC 执行事件（只有 RDD 的 rdd.jsonl）
```

## 观测缺口（2026-08-31 已修复）

```text
RDD：RddRuntime.publish → RddMonitor → rdd.jsonl → 监测台 ✅（已接）
AC ：AcExecutor.addEventListener(AcEventListener) → AcMonitor → ac.jsonl → 监测台 ✅（已接）
修复：AcPlugin.setup 给 executor 挂 AcEventListener → AcMonitor.publish(e) 写 ac.jsonl
真机验证：EXECUTION_STARTED / STEP_STARTED·tool / STEP_SUCCEEDED·tool / EXECUTION_SUCCEEDED
  全部写入，监测台 AC 分页显示 kind+tool+step
```

## 关键契约（改码注意）

```text
· PAUSED ≠ 失败：异步工具受理即 PAUSED（可 resume 续跑）
· resume 必须校验 fingerprint：内容/版本变化一律拒绝
· 不把 Numen ToolRegistry 当 AC registry（AC 有独立 registry + schema）
· 惰性桥接：AcPlugin.ensureBridged() 每次 ac_execute 前同步 Numen 工具
```

## 已探明的缺陷（见 ac-multistep-selfstatus-npe.md）

```text
get_self_status / get_owner_status 在多步上下文 NPE
→ 单步正常，多步（含这两工具）NPE
→ 过渡：用 rdd_whereami 等替代工具
```
