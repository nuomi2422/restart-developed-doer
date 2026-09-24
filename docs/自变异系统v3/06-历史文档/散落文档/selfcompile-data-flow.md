# Self-Compile 系统数据流（自编译系统 2026-08-31 梳理）

> 性质：只读代码梳理，不改码。给后续检查数据流对照。

## 两层结构

```text
层1：游戏内插件（plugins/selfcompile）
  · selfcompile_status / selfcompile_request 工具
  · Mutation*（工作区/预算/状态机/静态检查/编译/候选/产物）
  · SelfCompileMonitor（观测 → ai.jsonl）

层2：外部编排（rdd-selfcompile/scripts）
  · run-mutation.ps1（单轮闭环）
  · run-mutation-loop.ps1（止损循环）
  · gen-code / build-jar / deploy / launch-mc / mcp-drive / verify
```

## 层1：插件数据流（游戏内）

```text
外部 AI / MCP
  → selfcompile_request (SelfCompileRequestTool)
  → SelfCompileService.create(requirement)
  → MutationWorkspace.create
      → 建 mutation-id 工作区（source/classes/artifacts/reports/manifest.json）
  → SelfCompileMonitor.publish("selfcompile_request", {mutation_id, state, requirement})
  → 回执 {id, state=WORKSPACE_CREATED, workspace}
        │
        ▼
MutationPipeline（受控前置）
  → MutationBudget.tryAcquire（次数+时长止损）
  → recordGeneratedSource → MutationSourceStore.write（静态检查+原子落盘）
  → MutationStateMachine.transition → GENERATED
        │
        ▼
MutationCompiler.compile（若调用）
  → 静态检查 → javac → classes/ → compile.log
  → COMPILED
        │
        ▼
MutationArtifactStore.promote
  → 只接受 COMPILED → candidates/<id>/ → CANDIDATE
```

## 状态机（MutationStateMachine）

```text
REQUESTED → WORKSPACE_CREATED → GENERATED → STATICALLY_CHECKED → COMPILED
→ CANDIDATE → VERIFIED → DELIVERED

失败：任意 → FAILED → ARCHIVED → REQUESTED（可重试）
止损：任意 → STOP_LOSS（终态）

禁止：CANDIDATE→DELIVERED（跳验证）、STOP_LOSS→REQUESTED、DELIVERED→*
```

## 层2：外部编排数据流

```text
AI 编程工具（主体）
  → run-mutation.ps1：
      new-mutation → gen-code（生成工具源码+登记）
      → build-jar（编译打包+检测失败）→ deploy（mods）
      → launch-mc（gpt 存档）→ mcp-drive（MCP 调用）→ verify（监测台判定）
  → run-mutation-loop.ps1：
      每轮独立 mutation，失败重试，-MaxAttempts N 轮止损
```

## 观测出口（✅ 已接）

```text
SelfCompileMonitor.publish → config/numen/monitor/ai.jsonl
  类型：selfcompile_request（mutation_id / state / requirement）
→ 监测台 AI 分页已显示（真机验证：selfcompile_request 16:58:59）
```

## 生成工具流（期2 验证）

```text
AI 写 RddWhereamiTool.java → generated/
→ SelfCompileEntry.registerTool(new RddWhereamiTool())
→ build-jar → deploy → 重启游戏
→ MCP tools/list 出现 rdd_whereami → tools/call 读真实坐标
→ 该工具还能被 AC 桥接（3步AC 里 SUCCESS）
```

## 关键契约（改码注意）

```text
· 生成源码必须先过 MutationStaticChecker（包名/危险API/大小/文件名）
· 编译成功 ≠ 行为成功；候选必须先 VERIFIED 才能 DELIVERED
· 止损：MutationBudget（次数+时长）+ run-mutation-loop（MaxAttempts）
· 生成工具注册：SelfCompileEntry.registerTool（硬编码登记，非自动扫描）
· 瘦 API jar 无 MonitoringJournal → SelfCompileMonitor 插件自写文件
```

## 已探明的点

```text
· 单轮闭环 + 止损循环真机验证通过
· rdd_whereami 生成 → 真实读坐标 → 可被 AC 使用
· 自动改码重试未完全自动化（AI 需参与：读失败→改码→重跑）
· 中文参数经 MCP 变问号（已知，编码问题）
```
