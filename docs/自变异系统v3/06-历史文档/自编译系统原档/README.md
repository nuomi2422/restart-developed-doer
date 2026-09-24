# RDD 自编译与多 Harness 协作系统

状态：已获用户授权，部分模块已实装，按批次验证；不能把设计文档当作运行证明。  
最新施工与并发边界：[监测台 / 任务链交接（2026-09-13）](监测台-任务链-并发交接-2026-09-13.md)。  
权威资料根目录：`E:\新建文件夹\rdd架构\`  
当前 Numen 源码：`E:\restart developing doer\minecraft-numen\`（旧 DD 基线仅供历史参考）。  
外部脚本：`E:\TouhouLittleMaid-HeartPact\scripts\`

## 需求理解

本系统把现有 RDD 自编译/自变异能力、Claude↔Codex Harness 协作、网页模型评审、10 分钟心跳、Monitor 看门狗、`gpt`/`test` 双 MC 实例和软件工程规范整理到同一个可读的资料中枢。现有 RDD 文档保留原位置；本目录提供统一入口、索引、模板和后续实施边界。

VR 不是需求；“secuous MCP”也不是指定组件。MCP 在本设计中仅指实际需要的桥接或游戏测试通道，不引入未知产品。

## 目标与非目标

目标：

1. 让一个 Harness 能受控调用/通信另一个 Harness，当前采用 Codex 主指挥官、Claude/OpenCode 下位 Harness。
2. 让自编译系统能在隔离实验环境生成、编译、验证、回滚候选能力，并在事实证据充分时交付。
3. 让通宵任务、监测任务和长线测试具备断线恢复能力。
4. 让每个 AI 知道任务档位、权限、交接格式、测试门禁和资料存放位置。

非目标：

- 第一阶段不重写 DD Java 核心，不把文档目录当运行时目录。
- 不允许任一模型单独批准生产部署。
- 不要求模型输出隐藏思维链；只记录“已开启思考/推理模式”后的意见、结论和证据。

## 三档任务模型

### 一档：工具级

范围：单个工具、简单测试工具、局部脚本或文档修正。AI 可自行完成，Codex review 为可选；不因等待其他模型或用户而停工。仍需最小测试、日志和结果记录。

### 二档：模块级

范围：心跳、Monitor 看门狗、双实例脚本、Bridge 接入、Harness 小模块、自编译子模块等。Codex 负责规划、边界和验收，Claude/OpenCode 在批次内执行并回报；存在真实架构取舍时再调用网页模型。只有跨模块、高危冲突、不可替代信息缺失时才暂停并升级。

### 三档：架构级

范围：改变 RDD 核心架构、跨模块契约、生产/安全模型或自变异控制边界。必须走完整九步编程流：分类、探索、逐问澄清、方案、外脑意见、设计、用户批准、实现计划、闭合验证。需要用户决定的事项才询问用户；“还有文档没写完”本身不是暂停理由。

发现隐藏复杂度时只能从一档升二档、从二档升三档，不能降档。

## 总体架构

```text
用户任务
   │
   ├─ Codex CLI（GPT-6-Astra）：主指挥官/最终技术决策
   │       ├─ claude-codex-bridge ── MCP ── Claude CLI：辅助 Harness
   │       └─ OpenCode：免费执行 Harness
   │
   ├─ 网页模型池：DeepSeek、千问、ChatGPT、Gemini（仅在触发条件满足时）
   │       └─ 开启思考/推理模式，只返回意见，不索要隐藏思维链
   │
   ├─ Git / 契约测试 / 回归测试 / 日志证据
   │
   └─ 自编译外部编排
          ├─ test：候选变异实验
          └─ gpt：通关 MC 与长线基准验证
```

边界：

- 本目录：设计、规范、索引、模板、交接。
- `E:\TouhouLittleMaid-HeartPact\scripts`：心跳、看门狗、启动/恢复等外部编排。
- `E:\MaidSoulCore1-wt-baseline`：Harness、游戏内自编译子环节和 Java 实现。
- `E:\.minecraft\versions\The Best of Twilight Forest`：MC 实例、存档和日志。
- `claude-codex-bridge`：Harness 通信，不拥有部署批准权。

## Bridge 与多模型协作契约

### 已验证的 OpenCode Harness

本机 OpenCode CLI `1.18.30` 已用只读探针实际连接 Numen 仓库并返回结构化报告，证据见 [`runtime/opencode-connectivity-report.md`](runtime/opencode-connectivity-report.md) 与同目录 JSON 原始记录。Windows 执行策略阻止直接运行 `.ps1` 时，使用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` 调用脚本。探针结束后工作树保持干净。

### Numen API 与 Core 的部署边界

`numen_api-neoforge-1.21.1-0.1.3-dev.jar`（API 引擎）和 `numen-neoforge-1.21.1-0.1.3-dev.jar`（Core 工具包）必须作为两个文件同时放入实例 `mods/`。API 文件不能改名覆盖 Core 文件；缺少 Core 时，RDD 会报告 `mine` 未注册。部署后检查 `numen-core registered 51 tool(s)`，再用真实世界资产和 Monitor 事件验收。

下位 Harness 也可接入 OpenCode：使用 `E:\restart developing doer\rdd-selfcompile\scripts\opencode-harness.ps1` 发送受控任务，默认 `readonly`，返回 JSON 证据；只有 Codex 明确划定实现批次时才使用 `implement`。OpenCode 不拥有 commit、push、部署或生产批准权。

官方 `claude-codex-bridge` 提供两类 stdio MCP 服务：Claude 调 `codex_*`，Codex 调 `claude_*`；保留官方递归深度护栏（`BRIDGE_DEPTH >= 2`）。第一版默认单向工作流为：

```text
Codex 制定批次与边界
→ 调用 Claude/OpenCode 执行或提供第二意见
→ Codex 审查文件、测试、风险和真实证据
→ 下位 Harness 修复并回报
→ 再次 review
→ 测试和真实结果通过后交付
```

多模型评审触发条件：架构/自变异设计、连续两次失败仍找不到根因、生产交付前、用户明确要求。网页模型必须开启思考/推理模式；记录摘要意见和来源，不记录或索要隐藏思维过程。最终决策由主协调 AI 基于源码、测试、日志和 MC 世界事实作出。

## 心跳与任务注入

复用并升级现有 `scripts\heartbeat.ps1`，不新建第二套心跳机制。

```text
每 10 分钟唤醒
→ 读取现有任务链、最近交接文档、监测日志和会话上下文
→ 有长线任务：启动/恢复 AI，并注入继续任务提醒
→ 无任务：不启动；已有守护对象可自停
→ AI 每次收到任务注入时都看到“通宵测试/通关/自编译闭环”提醒
→ 记录唤醒、恢复、停止和止损结果
```

不新增独立 `run-state.json`；恢复事实来自现有任务链、交接文档和日志，最终是否继续由 AI 根据上下文判断。心跳不得绕过用户停止意图、任务止损或生产门禁。

## Monitor 看门狗与双实例

每次启动 `gpt` 或 `test` 后等待约 2 分钟，检查游戏进程、`latest.log`、Monitor `server.py` 和 `ws://127.0.0.1:8765`：

1. Monitor 失联：先重启 Monitor，保留游戏。
2. 游戏崩溃或进程消失：记录 crash，再重启对应实例并重连 Monitor。
3. 连续恢复失败：停止本轮，写交接/止损记录，不无限重启。
4. 同一时间只允许一个实例占用 8765，切换前确认旧实例已退出。

实例职责固定：

- `test`：自编译/自变异候选能力实验，允许失败和回滚；候选 JAR 不得直接污染 `gpt`。
- `gpt`：通关 MC、长线行为验证和生产基准；只接受已通过实验验证的版本。

现有 GPT 启动脚本复制为 test 入口时，只修改存档名、实例标签、日志/验证目标；不得复制敏感凭据或让两个实例共用状态目录。

## 自编译/自变异闭环

```text
能力缺口或失败
→ 按三档分级
→ 创建隔离 mutation 工作区
→ AI 生成/修改候选工具
→ 静态检查
→ 编译与打包候选 JAR
→ test 实例启动
→ MCP 实际游玩 + Monitor 观测 + 世界快照对撞
→ 通过才允许合并/交付到 gpt
→ 失败记录、回滚或止损
```

### Promotion Gate（晋级门）

候选能力必须按顺序通过以下机器可判定门禁，缺少任一证据不得进入 `gpt`：

```text
CANDIDATE
  → BUILD_PASS
  → CONTRACT_PASS
  → TEST_RUNTIME_PASS
  → MONITOR_HEALTHY
  → WORLD_FACT_PASS
  → NO_UNRESOLVED_FAULT
  → PROMOTABLE
  → GPT_BASELINE
```

每次晋级记录至少包含：`candidate_id`、`parent_commit`、变更文件/行、测试实例、各 gate 状态、证据路径、未解决故障列表和 `promotable` 布尔值。只有 `promotable=true` 才能进入 GPT 基准；进入生产仍需人工批准。

### 自治状态机与权限

```text
INIT → RUNNING → RETRYING → REPLANNING → SELF_COMPILING
                                   ↓             ↓
                               DEGRADED       TESTING
                                                 ↓
                                           PROMOTABLE
                                                 ↓
                                           BASELINE
                                                 ↓
                                           PRODUCTION
                                                 ↓
                                           ROLLBACK
任意状态 → HALTED（Safety Gate / Kill Switch）
```

状态转移权限固定：Executor 负责 `RUNNING→RETRYING`；Supervisor 负责 `RETRYING→REPLANNING`；策略门负责进入 `SELF_COMPILING`；Self-Compile 负责 `SELF_COMPILING→TESTING`；Test Gate 负责 `TESTING→PROMOTABLE`；Promotion Gate 负责 `PROMOTABLE→BASELINE`；生产晋级必须人工批准；Watchdog 只能触发基础设施恢复或 `ROLLBACK/HALTED`，不能重规划任务。

### 实例、锁与事件事实层

`test`、`gpt` 和未来的 benchmark/recovery 实例通过 InstanceRegistry 分配独立 `gameDir/world/logs/monitor/websocket`；启动前持有 `instance.lock` 与 `monitor.lock`，端口由注册表动态分配（当前默认 test=8765、gpt=8766，可调整）。结构化 `events/*.jsonl` 是机器控制面，Markdown 是人类/AI 解释层；二者通过 `candidate_id`、`task_id` 和 `execution_id` 关联。

### 故障分类与 Kill Switch

故障统一分类为 `BUILD`、`CONTRACT`、`TOOL`、`RUNTIME`、`TEST`、`ARCHITECTURE`、`UNKNOWN`。编译失败优先本地/Codex 调试，工具失败优先重试或换工具，运行时失败交给 Monitor/Supervisor，架构失败才触发网页外脑评审。

Kill Switch 在检测到多实例串台、证据链缺失、Watchdog 连续恢复失败、生产门禁绕过或不可解释状态转移时立即进入 `HALTED`；停止自动改码/部署，保留现场并通知人工。

现有 `self-compile-module.md`、`self-compile-loop.md`、`self-compile-rollback.md`、`self-compile-workspace.md` 和 `self-compile-handoff.md` 继续作为已实现能力的事实来源；本目录只做统一索引和边界，不重复声称尚未验证的功能。

## 软件工程规范（九步流内置）

- 开工先分类；一次只问一个澄清问题；未经对应档位批准不越过门禁。
- 改接口前列影响面和消费者；接口变更必须契约回归。
- 每个模块首测后立即做边界、异常、极端和多数据测试。
- 写码同步在关键路径埋 debug；验证必须能回答入口、输入、分支、输出。
- 编译成功不等于行为成功；MCP 返回成功不等于世界真实改变。
- 每次变更按模块 commit 标注，保留单模块回滚点；通过后 push。
- 偏离方向连续失败两次且找不到根因时止损汇报，不无限硬磕。
- 固化闭环必须包含测试、日志/Monitor 证据和真实结果；没有证据不宣称完成。

## 记录格式

补充资料：[`MC通关指南.md`](MC通关指南.md) 是 RDD 规划知识库；[`Codex指挥官运行规范.md`](Codex指挥官运行规范.md) 是 V2.1 主指挥与下位 Harness 的批次协议。

每个任务至少记录：档位、目标、设计、参与模型及其意见摘要、改动文件、测试命令和结果、Monitor/MC 事实证据、未完成项、止损或下一步。交接文档必须能让下一个 AI 在不依赖口头上下文的情况下恢复。

## 外脑状态说明

项目固化条款要求用 Playwright 询问网页外脑并审阅 spec。外脑接口已接入（本机 `@playwright/mcp@0.0.79` 全局安装 + `npx playwright` 1.63.0 + Edge CDP 驱动通道），DeepSeek 专家模式 / ChatGPT 网页窗口为固定外脑，必须按九步流外脑环节真连窗口拿意见并记录摘要；不再出现「未开启、未暴露、接口不可用」类落伍声明（旧稿曾误写为未接入，已修正）。外脑回答当参考不当真理，涉及代码必须对着真实源码核。

## 当前实施边界

2026-09-17 最新增量状态见 [判定组、监测台与 Codex 续做验收](2026-09-17-判定组与续做验收.md)。本轮503恢复指当前Codex窗口；App心跳与Minecraft两小时关闭分别负责，不混用API配置。下方是早期设计阶段说明，不能据此否认已记录的后续实现。

本文件是设计稿，不代表 Bridge、心跳升级、Monitor 看门狗或双实例脚本已经实现。用户审阅并批准本 spec 后，才进入 writing-plans；实现时按模块拆分、逐项测试、逐项交付。
