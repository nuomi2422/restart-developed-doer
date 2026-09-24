# RDD 自编译与多 Harness 协作系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 RDD 自编译/自变异、Claude↔Codex Harness、三档协作、10 分钟心跳、Monitor 看门狗和 `gpt`/`test` 双实例整理成可审计、可恢复、可分模块交付的系统。

**Architecture:** 文档中枢位于 `E:\新建文件夹\rdd架构\自编译系统\`；外部编排脚本位于 `E:\TouhouLittleMaid-HeartPact\scripts\`；Java 自编译子环节继续位于 `E:\MaidSoulCore1-wt-baseline\`。实现按 Bridge、心跳、看门狗、双实例、规则同步、闭合验证拆分，每个模块通过文件/日志契约连接，候选变异只在 `test` 验证后才可交付到 `gpt`。

**Tech Stack:** PowerShell 5.1、Windows Task Scheduler、Node.js 18+ / `npx claude-codex-bridge`、Claude Code CLI、Codex CLI、Python `debug-ui/server.py`、WebSocket `127.0.0.1:8765`、NeoForge 1.21.1、Git、Gradle/JDK 21。

**Spec:** `E:\新建文件夹\rdd架构\自编译系统\README.md`

## Global Constraints

- 旧 RDD 文档全部保留；新资料优先放 `E:\新建文件夹\rdd架构\自编译系统\`。
- 三档任务：工具级自主推进；模块级 Claude/Codex 协作并自主决策；架构级完整九步流并在核心取舍处请求用户批准。
- 网页模型只在架构/自变异设计、连续两次失败、生产交付前或用户要求时调用；必须开启思考/推理模式，只记录意见摘要，不索要隐藏思维链。
- Codex（GPT-6-Astra）负责主指挥、全局协调和最终技术审查；Claude CLI、OpenCode 等作为受控下位 Harness；任何单模型不能批准生产部署。
- 心跳每 10 分钟唤醒，复用任务链/交接文档/监测日志恢复上下文，不新增 `run-state.json`。
- 游戏启动后约 2 分钟检查 Monitor；先恢复 Monitor，再恢复游戏；连续失败必须止损，不无限重启。
- `test` 仅做自编译/自变异实验；`gpt` 做通关 MC/长线基准验证；两者不得共享运行状态或同时占用 8765。
- InstanceRegistry 动态分配端口；默认 `test=8765`、`gpt=8766`，由实例锁和监测锁保证互斥。
- 机器控制事实写入结构化 `events/*.jsonl`；Markdown 只承担人类/AI 解释层。
- 候选必须持有 `candidate_id`、`parent_commit` 和 Promotion Gate 证据；`promotable=true` 才能进入 GPT，生产仍需人工批准。
- Watchdog 只能恢复基础设施；Kill Switch 触发后进入 `HALTED`，禁止自动改码、重规划和部署。
- 代码改动前退出游戏；改动后按项目 SOP 编译、部署、验证、commit、push、自动启动。
- 每个模块 commit message 使用 `[模块名]` 前缀，并留下可独立回滚点。
- 本计划不把“编译成功”“MCP 返回 OK”当作行为成功；必须有日志/Monitor/世界事实证据。

## File Map

| 文件 | 责任 |
|---|---|
| `E:\新建文件夹\rdd架构\自编译系统\README.md` | 设计与统一入口（已创建） |
| `E:\新建文件夹\rdd架构\自编译系统\implementation-plan.md` | 本实现计划 |
| `E:\TouhouLittleMaid-HeartPact\dd-memory\dd-workflow-style.md` | AI 身份、三档协作和记录规则 |
| `E:\TouhouLittleMaid-HeartPact\AGENTS.md` | 当前项目的默认资料位置和开工规则 |
| `E:\TouhouLittleMaid-HeartPact\CLAUDE.md` | Claude 会话入口规则 |
| `E:\MaidSoulCore1-wt-baseline\CLAUDE.md` | 源码仓入口与 RDD 资料指针 |
| `E:\新建文件夹\rdd架构\自编译系统\维护者手册.md` | 日常巡检、故障处理、回滚和交接 |
| `E:\新建文件夹\rdd架构\自编译系统\开发者手册.md` | 代码落点、状态权限、测试和提交规范 |
| `E:\TouhouLittleMaid-HeartPact\scripts\rdd_state_machine.ps1` | 状态转移与权限校验 |
| `E:\TouhouLittleMaid-HeartPact\scripts\promotion_gate.ps1` | 候选晋级门禁与 promotable 判定 |
| `E:\TouhouLittleMaid-HeartPact\scripts\instance_registry.ps1` | 实例锁、动态端口和运行目录注册 |
| `E:\TouhouLittleMaid-HeartPact\scripts\heartbeat.ps1` | 10 分钟心跳与 AI 恢复 |
| `E:\TouhouLittleMaid-HeartPact\scripts\monitor_watchdog.ps1` | 游戏/Monitor 2 分钟检查与有限恢复 |
| `E:\TouhouLittleMaid-HeartPact\scripts\launch-instance.ps1` | `gpt`/`test` 启动前置、标签和互斥 |
| `E:\.minecraft\versions\The Best of Twilight Forest\shaderpacks\启动 The Best of Twilight Forest-test.bat` | `test` 存档启动入口 |
| `C:\Users\lenovo\.claude.json` | Claude Code MCP 注册（保留其他服务器） |
| `C:\Users\lenovo\.codex\config.toml` | Codex MCP 注册（保留现有配置） |

### Task 0: 机器契约（状态机、Promotion Gate、事件与 Kill Switch）

**Files:**
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\rdd_state_machine.ps1`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\promotion_gate.ps1`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\instance_registry.ps1`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\machine-contract-test.ps1`
- Create: `E:\新建文件夹\rdd架构\自编译系统\architecture\state-machine.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\architecture\promotion-gate.md`

**Interfaces:**
- Produces: 状态枚举、合法转移及权限；Promotion Gate JSON 结果；candidate/任务/执行事件；实例锁与动态端口登记。
- Consumes: `candidate_id`、`parent_commit`、实例名、证据路径和故障分类。

- [ ] **Step 1: 写契约测试**

覆盖合法/非法状态转移、缺证据不得晋级、`promotable=false` 阻断 GPT、生产转移需要人工标记、重复实例锁和端口冲突、Kill Switch 后拒绝任何执行动作。

- [ ] **Step 2: 运行测试确认实现缺失**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\machine-contract-test.ps1 -BeforeChange`

Expected: FAIL，列出缺少的状态、门禁、锁和事件断言。

- [ ] **Step 3: 实现三个机器契约脚本**

`rdd_state_machine.ps1` 只允许授权角色执行合法转移；`promotion_gate.ps1` 输出结构化 gate 结果；`instance_registry.ps1` 分配端口并持有锁。所有事件写入按实例隔离的 `events/*.jsonl`。

- [ ] **Step 4: 运行契约矩阵**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\machine-contract-test.ps1`

Expected: PASS；任何非法转移、缺证据晋级、端口/锁冲突和 Kill Switch 后动作均被拒绝。

- [ ] **Step 5: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/rdd_state_machine.ps1 scripts/promotion_gate.ps1 scripts/instance_registry.ps1 scripts/machine-contract-test.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[machine-contracts] lock state promotion and instance authority"
```

### Task 1: 资料入口与工程规则同步

**Files:**
- Modify: `E:\TouhouLittleMaid-HeartPact\dd-memory\dd-workflow-style.md`
- Modify: `E:\TouhouLittleMaid-HeartPact\AGENTS.md`
- Modify: `E:\TouhouLittleMaid-HeartPact\CLAUDE.md`
- Modify: `E:\MaidSoulCore1-wt-baseline\CLAUDE.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\engineering-standards\README.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\ai-roles\README.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\维护者手册.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\开发者手册.md`

**Interfaces:**
- Produces: 所有 AI 可读取的权威路径、三档判定表、Claude/Codex/网页模型角色、心跳/看门狗/双实例规则、维护和开发手册。
- Consumes: 已批准 spec `README.md`；现有 `dd-workflow-style.md` 的角色与交接格式。

- [ ] **Step 1: 写规则同步检查脚本**

创建 PowerShell 检查器，逐文件确认四份入口都包含权威目录、三档关键词、`gpt`/`test` 和“思考模式只记录意见”规则；缺项返回非零码。

- [ ] **Step 2: 运行检查器确认当前状态失败**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\check-rdd-rules.ps1`

Expected: FAIL，并列出尚未同步的文件/关键词。

- [ ] **Step 3: 按 spec 更新四份入口和两份角色规范**

只追加指针和规则，不重写已有历史；在 `dd-workflow-style.md` 中保留贵/便宜模型职责，同时增加“工具级/模块级/架构级”判定。

- [ ] **Step 4: 运行检查器确认通过**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\check-rdd-rules.ps1`

Expected: PASS，输出四份入口均命中必需规则。

- [ ] **Step 5: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add AGENTS.md CLAUDE.md dd-memory/dd-workflow-style.md scripts/check-rdd-rules.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[rules] point all AIs to RDD self-compile system"
```

### Task 2: Claude↔Codex Bridge 配置与最小闭环

**Files:**
- Modify: `C:\Users\lenovo\.claude.json`（只增 `codex` MCP，保留现有服务器）
- Modify: `C:\Users\lenovo\.codex\config.toml`（只增 `[mcp_servers.claude]`）
- Create: `E:\新建文件夹\rdd架构\自编译系统\bridge\README.md`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\verify-claude-codex-bridge.ps1`

**Interfaces:**
- Produces: Claude 侧 `codex_query`/`codex_review_code` 等工具，Codex 侧 `claude_query`/`claude_review_code` 等工具。
- Consumes: 本机 `claude`、`codex`、`node/npx`；官方包 `claude-codex-bridge`。

- [ ] **Step 1: 写配置备份和 JSON/TOML 结构测试**

验证写入前会创建带时间戳备份，JSON 可解析，TOML 新增段不重复，命令固定为 `npx claude-codex-bridge serve codex` 与 `npx claude-codex-bridge serve claude`。

- [ ] **Step 2: 运行结构测试确认未配置时失败**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\verify-claude-codex-bridge.ps1 -CheckOnly`

Expected: FAIL 或明确报告“bridge 未登记”，不修改配置。

- [ ] **Step 3: 写入 Claude/Codex MCP 配置**

Claude 配置使用 stdio server：`command=npx`、`args=["claude-codex-bridge","serve","codex"]`；Codex 配置增加 `tool_timeout_sec = 600`，不写入任何 API token。

- [ ] **Step 4: 运行双向最小查询**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\verify-claude-codex-bridge.ps1`

Expected: 两侧 server 可启动；至少完成一次只读 query；递归深度测试返回官方阻断信息而不是无限调用。

- [ ] **Step 5: Commit 配置说明和验证脚本**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/verify-claude-codex-bridge.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[bridge] add Claude Codex MCP verification"
```

用户配置文件本身不提交；只在报告中记录配置已生效和备份路径。

### Task 3: 10 分钟心跳与长线任务注入

**Files:**
- Modify: `E:\TouhouLittleMaid-HeartPact\scripts\heartbeat.ps1`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\heartbeat-contract-test.ps1`
- Create: `E:\新建文件夹\rdd架构\自编译系统\heartbeat\README.md`

**Interfaces:**
- Consumes: 现有任务链文件、最近交接文档、Monitor 日志、Claude transcript。
- Produces: 每 10 分钟唤醒/恢复记录；任务注入文本包含长线测试提醒；无任务时自停。

- [ ] **Step 1: 为心跳脚本建立模拟输入测试**

测试覆盖：有任务且会话断开、有任务且会话活跃、无任务、交接文档只有旧记录、日志不存在、重复唤醒。测试通过临时目录和 `-WhatIf`，不得触碰真实游戏。

- [ ] **Step 2: 运行测试确认现有脚本缺少新契约**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\heartbeat-contract-test.ps1 -BeforeChange`

Expected: FAIL，列出缺少“任务注入提醒/无任务自停/恢复记录”的断言。

- [ ] **Step 3: 升级 heartbeat.ps1**

保留 transcript idle/auto-compact 逻辑；新增任务链/交接/日志摘要读取、长线任务注入模板、恢复动作和可自停分支。所有外部进程启动使用显式路径和实例标签；不创建 `run-state.json`。

- [ ] **Step 4: 运行边界与异常测试**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\heartbeat-contract-test.ps1`

Expected: PASS；无任务不启动 AI，任务存在时注入提醒，文件缺失时降级为可读记录而非崩溃。

- [ ] **Step 5: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/heartbeat.ps1 scripts/heartbeat-contract-test.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[heartbeat] add long-run task wake and self-stop contract"
```

### Task 4: Monitor 看门狗与有限恢复

**Files:**
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\monitor_watchdog.ps1`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\monitor-watchdog-contract-test.ps1`
- Create: `E:\新建文件夹\rdd架构\自编译系统\monitor-watchdog\README.md`

**Interfaces:**
- Consumes: InstanceRegistry 的实例标签/动态端口/锁、游戏进程、`latest.log`、`debug-ui/server.py`、对应 WS 可达性。
- Produces: 2 分钟检查结果、Monitor 优先恢复、游戏崩溃恢复、连续失败止损记录。

- [ ] **Step 1: 写纯模拟看门狗测试**

用 `-GamePid`、`-MonitorPid`、`-LatestLog`、`-MaxRecoveries` 参数注入假状态；断言顺序为 monitor-first、game-second、stop-after-limit。

- [ ] **Step 2: 运行测试确认无实现时失败**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\monitor-watchdog-contract-test.ps1 -BeforeChange`

Expected: FAIL。

- [ ] **Step 3: 实现 monitor_watchdog.ps1**

启动后等待 120 秒；先检查 InstanceRegistry 登记的 Monitor/WS，再检查游戏进程和 `latest.log`；恢复动作写入实例标签、动态端口、时间戳、原因和计数；达到阈值后写止损记录并退出非零码。

- [ ] **Step 4: 运行异常矩阵测试**

覆盖：Monitor 死而游戏活、游戏死而 Monitor 活、两者都死、日志停更、端口被其他进程占用、连续恢复失败。

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\monitor-watchdog-contract-test.ps1`

Expected: PASS；每种状态都有唯一恢复顺序和可读记录。

- [ ] **Step 5: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/monitor_watchdog.ps1 scripts/monitor-watchdog-contract-test.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[monitor-watchdog] recover monitor before game and stop safely"
```

### Task 5: `gpt`/`test` 双实例启动入口

**Files:**
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\launch-instance.ps1`
- Create: `E:\.minecraft\versions\The Best of Twilight Forest\shaderpacks\启动 The Best of Twilight Forest-test.bat`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\instance-contract-test.ps1`
- Create: `E:\新建文件夹\rdd架构\自编译系统\game-instances\README.md`

**Interfaces:**
- Consumes: 现有 GPT 启动 bat、存档 `gpt`/`test`、JDK21、统一版本目录、InstanceRegistry 动态端口和锁。
- Produces: 带实例标签的启动命令；互斥检查；`test` 候选实验与 `gpt` 基准验证分离。

- [ ] **Step 1: 读取并固定现有启动参数**

提取现有 bat 的 Java、classpath、NeoForge 参数和 `--quickPlaySingleplayer "gpt"`；不得复制 access token 到文档或新脚本注释。

- [ ] **Step 2: 写启动契约测试并确认初始失败**

断言 `-Instance gpt` 使用 `gpt`，`-Instance test` 使用 `test`，未知实例拒绝，注册表端口已占用或锁冲突时拒绝并返回实例信息。

- [ ] **Step 3: 实现 launch-instance.ps1 与 test bat**

复制现有启动流程，只替换存档名、实例标签和验证目标；启动前通过 InstanceRegistry 获取端口并持锁，检查旧游戏/Monitor 是否退出；启动后交给 watchdog；不允许两个实例共用运行状态。

- [ ] **Step 4: 运行双实例静态与隔离测试**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\instance-contract-test.ps1`

Expected: PASS；命令中无交叉存档名，无共享状态路径，无敏感凭据输出。

- [ ] **Step 5: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/launch-instance.ps1 scripts/instance-contract-test.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[game-instances] isolate gpt baseline and test mutation worlds"
```

### Task 6: 自编译/自变异与 Bridge/Harness 交接模板

**Files:**
- Create: `E:\新建文件夹\rdd架构\自编译系统\templates\task-record.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\templates\review-record.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\templates\handoff-record.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\self-compile\README.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\mutation\README.md`
- Create: `E:\新建文件夹\rdd架构\自编译系统\testing\README.md`
- Modify: `E:\新建文件夹\rdd架构\自编译系统\README.md`

**Interfaces:**
- Consumes: `E:\新建文件夹\rdd架构\self-compile-*.md` 现有事实文档、baseline `selfcompile` Java 代码、Task 1-5 的规则。
- Produces: 可由下一个 AI 恢复的任务记录、review 记录、handoff 记录和“test→gpt”交付门禁。

- [ ] **Step 1: 为模板写 schema 检查**

检查每份记录都包含档位、目标、设计、参与模型意见摘要、改动文件、测试命令/结果、Monitor/MC 证据、未完成项和止损原因。

- [ ] **Step 2: 运行 schema 检查确认空模板失败**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\validate-rdd-records.ps1`

Expected: FAIL，并指出缺失字段。

- [ ] **Step 3: 创建模板和自编译事实索引**

模板只描述可验证字段；`self-compile/README.md` 链接现有 `self-compile-module.md`、`self-compile-loop.md`、`self-compile-rollback.md`、`self-compile-workspace.md`、`self-compile-handoff.md`，不复制实现声称。

- [ ] **Step 4: 运行模板和链接检查**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\validate-rdd-records.ps1 -CheckLinks`

Expected: PASS；旧文档全部存在，模板字段齐全。

- [ ] **Step 5: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/validate-rdd-records.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[selfcompile-docs] add mutation review and handoff contracts"
```

### Task 7: 集成闭合与真实验收

**Files:**
- Create: `E:\新建文件夹\rdd架构\自编译系统\checklists\closure-checklist.md`
- Create: `E:\TouhouLittleMaid-HeartPact\scripts\run-rdd-selfcompile-closure.ps1`
- Modify: `E:\新建文件夹\rdd架构\自编译系统\README.md`（仅更新已验证状态）

**Interfaces:**
- Consumes: Task 0-6 的脚本、Bridge 配置、`test`/`gpt` 入口、baseline Gradle/JAR 流程、Promotion Gate 和 Monitor 证据。
- Produces: 一份闭合报告，证明规则→Bridge→心跳→看门狗→test→gpt 的链路没有断点。

- [ ] **Step 1: 写闭合检查器**

检查顺序固定为：机器契约/状态机→规则入口→Bridge 双向可达→心跳模拟→看门狗模拟→实例隔离/动态端口→baseline 契约回归→test 冒烟→Promotion Gate→Monitor 事件→gpt 只读基准。

- [ ] **Step 2: 运行纯模拟闭合测试**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File E:\TouhouLittleMaid-HeartPact\scripts\run-rdd-selfcompile-closure.ps1 -SimulationOnly`

Expected: PASS；输出每个模块的证据文件路径。

- [ ] **Step 3: 运行 baseline 回归**

Run: `Push-Location E:\MaidSoulCore1-wt-baseline; .\gradlew.bat check --no-build-cache; Pop-Location`

Expected: 现有契约/回归测试全绿。

- [ ] **Step 4: 实验实例闭环**

先确认生产游戏已退出并完成 Git 保护，再启动 `test`；等待 watchdog 2 分钟检查；执行一个最小自编译工具验证，读取 Monitor 和世界事实；候选通过后才允许进入 `gpt` 基准验证。任何失败写入 handoff 并按预算止损。

- [ ] **Step 5: 更新状态并形成闭合报告**

只有在 Bridge、心跳、看门狗、双实例、测试和真实证据都通过后，才把 README 状态从“设计稿”改为对应模块“已验证”；未验证项必须保留为“待实现/待验证”。

- [ ] **Step 6: Commit**

```powershell
git -C E:\TouhouLittleMaid-HeartPact add scripts/run-rdd-selfcompile-closure.ps1
git -C E:\TouhouLittleMaid-HeartPact commit -m "[closure] verify RDD self-compile collaboration chain"
```

## Plan Self-Review

- Spec coverage：三档任务、Bridge、多模型思考模式、心跳、Monitor 2 分钟恢复、`gpt/test`、自编译闭环、软件工程规范均有对应任务。
- Placeholder scan：未使用空白占位语句或“适当处理”式空步骤；每个任务包含真实路径和命令。
- Type/contract consistency：Task 3 输出心跳记录供 Task 7；Task 4 输出看门狗证据供 Task 7；Task 5 固定实例名供 Task 4/7；Task 6 的记录字段供 Task 7 闭合检查。
- Scope：每个任务有独立测试和 `[模块名]` commit，可在模块级失败时单独回滚；跨模块行为只在 Task 7 验收。
- External brain：本会话已接入 Playwright 网页外脑（@playwright/mcp 全局可用 + Edge CDP），按九步流规则真连 DeepSeek 专家模式 / ChatGPT 免登录窗口征求意见并追加记录；不伪造网页模型意见。
