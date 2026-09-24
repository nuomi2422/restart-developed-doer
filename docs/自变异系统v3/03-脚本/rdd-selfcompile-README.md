# rdd-selfcompile — 自变异系统单轮闭环（外部编排）

> 方向：主体是**外挂式 AI 编程工具**，通过启动/关闭游戏 + MCP 实际游玩验证 + 部署 jar 实现能力自我扩展。本目录是外部循环编排层，不进入 Numen 仓库。

## 目录

```text
E:\restart developing doer\rdd-selfcompile\
├── README.md
├── scripts\
│   ├── config.ps1         公共配置（路径/端口/token）
│   ├── run-mutation.ps1   单轮闭环编排器（入口）
│   ├── new-mutation.ps1   创建 mutation 工作区
│   ├── gen-code.ps1       生成工具源码落盘 + 登记（期2）
│   ├── build-jar.ps1      安全编译 + 打包 jar
│   ├── deploy.ps1         部署到 mods（实验）
│   ├── launch-mc.ps1      启动/关闭 gpt 存档
│   ├── mcp-drive.ps1      通过 Numen MCP 调用工具/读事件
│   └── verify.ps1         读监测台 + 判定 verdict
├── mutations\             每次变异的工作区（自动生成）
└── report\                报告归档
```

## 用法

### 完整单轮（真实闭环）

```powershell
.\scripts\run-mutation.ps1 -Requirement "你的需求描述"
```

流程：

```text
创建 mutation-id
→ build-jar 编译打包
→ deploy 部署 mods
→ launch-mc 启动 gpt 存档
→ mcp-drive MCP 调用工具
→ verify 读监测台判定
→ 产出 verdict.json
```

### 不打游戏（编译+部署+验证）

```powershell
.\scripts\run-mutation.ps1 -Requirement "xxx" -SkipLaunch -SkipMcp
```

### 手动等 MCP

游戏启动后，等待 Numen MCP（8765）就绪（1~3 分钟），然后单独跑：

```powershell
.\scripts\mcp-drive.ps1 -MutationId <id> -ToolName selfcompile_status
.\scripts\verify.ps1 -MutationId <id>
```

## mutation 工作区结构

```text
mutations/<mutation-id>/
├── requirement.txt
├── mutation.json
├── phase-build.log / phase-build.json
├── phase-deploy.json
├── phase-launch.json
├── phase-mcp.json
├── monitor-snapshot/snapshot.json
└── verdict.json
```

## 关键配置

```text
MCP:   config/numen/mcp_server.json  → enabled=true, 127.0.0.1:8765
监测台: http://127.0.0.1:8776/         → 读 config/numen/monitor/
存档:   gpt
构建:   JDK 21 + Gradle 9.2 + 代理 7897
```

## 硬门禁

- 编译成功 ≠ 行为成功；
- MCP 返回成功 ≠ 世界真变（必须监测台 + 世界快照对撞）；
- 未 VERIFIED 不部署生产；
- 部署生产前先 commit。

## 期1状态（已完成）

```text
编译/部署/验证 dry-run     ✅
MCP 端口探测               ✅（未监听时正确失败）
真实 MCP 调用 selfcompile_status ✅
```

## 期2状态（已完成）

```text
AI 生成工具源码            ✅（我直接生成 RddWhereamiTool.java）
gen-code.ps1 落盘+登记      ✅
编译打包 jar               ✅
部署 + 重启游戏            ✅
MCP 真实调用 rdd_whereami   ✅ → 返回真实坐标
```

真实验证结果（mutation-20260831-002937）：

```text
rdd_whereami → {"dimension":"minecraft:overworld","x":165,"y":64,"z":-128,"on_ground":true}
```

## 后续（期3）

- [中转 API 稳定性备选（用户提供，待核实；通关后按需评估）](report/relay-stability-candidates-20260917.md)

- 失败自动改码重试 + 止损循环；
- 更多工具生成模板（读背包/读任务/执行动作等）。
