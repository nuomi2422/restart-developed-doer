# RDD 资产任务链 — 验证清单（给持续测试 AI）

> 目标：系统验证 RDD 资产任务链模块的实现正确性 + 架构不变量 + 游戏闭环。
> 本文档自包含，测试 AI 无需读取实现会话上下文。实现仓库：`E:\restart developing doer\minecraft-numen`。

## 0. 环境（已知坑，先对齐）

- Gradle wrapper 下载 9.2.0 会超时。**直接用缓存的 9.2.1**：
  ```
  cd "E:\restart developing doer\minecraft-numen"
  set JAVA_HOME=E:/jdk21/jdk-21.0.11+10
  "E:\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat" <task>
  ```
- 游戏日志：`E:\.minecraft\versions\The Best of Twilight Forest\logs\latest.log`（启动 bat 在 `...\shaderpacks\启动 The Best of Twilight Forest.bat`）。
- 相关提交：`469dde59`（纯 JVM 核心）、`072406d5`（宿主接入）、`8fc2cd57`（LLM 分解+身体桥）、`40b424d0`（RddMod 修 ServerTickEvent.Pre）。
- **模块化 commit 纪律**：本仓库有多条工作线（selfcompile/AC 执行层/经验库/监测台/我的 RDD）。你只验证，不提交；若发现别线文件被改动，别管也别提交。

## A. 构建与单测（先跑这个）

| # | 命令 | 通过标准 |
|---|------|---------|
| A1 | `:ac-core:test` | 全绿。含 RDD 核心：TaskChain 状态机、AssetRegistry、HardCodedEvaluator、RddChainFactory（fromObjective + fromSpec 多级/非法拒绝）、TaskChain 多二级推进（完成第一→current 变第二、primaryStatus 仍 ACTIVE） |
| A2 | `:plugins:rdd:test` | 全绿。含 RddDecomposer.parse 4 用例：合法多二级（含 body 透传）、坏/空 JSON→空、缺 asset_key 丢弃、>8 截断 |
| A3 | `:plugins:rdd:build` | 通过；jar（`plugins/rdd/build/libs/numen-plugin-rdd-1.21.1-0.1.3-dev.jar`）内嵌 ac-api+ac-core 类（`unzip -l ... | grep rdd` 应有 30+ 个类） |
| A4 | `:core:neoforge:jar` | 通过（回归，确保 RDD 改动没破坏 core 构建） |

## B. 架构不变量（代码级，只读检查）

| # | 检查项 | 验证方法 | 通过标准 |
|---|--------|---------|---------|
| B1 | **纯 JVM 边界** | `grep -r "net.minecraft\|NumenPlayer\|com.dwinovo.numen.entity\|com.dwinovo.numen.task\|monitor\|MonitoringJournal\|agent.llm\|agent.provider" ac-api/src/main/java/com/dwinovo/numen/rdd/ ac-core/src/main/java/com/dwinovo/numen/rdd/` | **零命中**（纯 JVM 核心不碰 MC/身体/LLM/监测台） |
| B2 | **任务状态主权** | 读 `TaskChain.java` | 状态变更只集中在 TaskChain（startCurrent/applyHardCoded/applySupervisor/markFailed）；RddRuntime 只是门面 + 发事件，不持有第二份状态 |
| B3 | **资产状态分家** | 读 `AssetRegistry.java` | 资产快照/历史归 AssetRegistry，任务状态归 TaskChain，互不越界 |
| B4 | **不接 BrainChains** | `grep -rn "BrainChains" plugins/rdd/ ac-core/ ac-api/` | 零命中。身体执行走 `ToolRegistry.resolve → NumenTool.onServerCall → TaskDispatch.setTask`，不经本能竞价链 |
| B5 | **不碰 selfcompile** | `git log --oneline -- plugins/selfcompile/` | 最近提交非 RDD 线新增（RDD 提交应不触及 selfcompile 目录） |
| B6 | **LLM 只用于链创建** | 读 `RddDecomposer.java` + `RddDetector.java` | LLM 调用只在 RddDecomposer.decompose（sink 触发，客户端）；RddDetector（服务端 tick）**没有** LLM 调用，只有确定性 `HardCodedEvaluator.matches` |

## C. 游戏加载（重启游戏后）

| # | 检查 | 通过标准 |
|---|------|---------|
| C1 | mod 加载 | 日志 `Failed to create mod instance` 计数 = 0；mod 列表含 `RDD Asset Task Chain 0.1.3-dev (rdd)` |
| C2 | 插件 setup | 日志无「插件登记失败」；rdd_status/rdd_submit 工具已注册（`registered N tool(s)` 应 > core 自身 46） |
| C3 | 检测循环 | 世界加载后无 `[rdd] 检测 tick 异常` 刷屏；无 FATAL/OOM |

## D. 功能闭环（游戏内实测，核心验证）

**准备**：游戏内需有一只在场的女仆（NumenPlayer），对它输 `/goal`。

| # | 步骤 | 期望（日志/rdd_status） |
|---|------|------------------------|
| D1 | 输 `/goal 给我木头` | 客户端触发分解；`rdd_status` 短暂返回 `decomposing:true`（或状态注入 `<decomposing>true</decomposing>`） |
| D2 | 等 2~5 秒（LLM 返回） | `rdd_status` → `active:true, primary_status:ACTIVE`，`subtask` 变为具体收集任务（如 `minecraft:oak_log`），不再是占位 `goal` |
| D3 | 观察自动身体执行 | 日志出现 `[rdd] 已提交身体任务 ... -> collect_items`（分解出的 body 被 RDD 自动调 NUMEN 身体工具） |
| D4 | 女仆收集，背包达标 | 日志出现 `[rdd] 二级目标完成: subtask-...`；自动推进下一个二级 |
| D5 | 全部二级完成 | 日志 `[rdd] 一级目标完成: ...`；`rdd_status` → `primary_status:COMPLETED` |
| D6 | **防假成功** | 二级完成必须对应真实背包计数 ≥ minimum（不认 AI 自述/工具回执当完成）；全程跟踪 `rdd_status` + 真实资产状态 + 完成事件 |

## E. 容错路径（逐个构造）

| # | 场景 | 构造方法 | 期望 |
|---|------|---------|------|
| E1 | 分解失败回落 | 断网/改错 key 后输 `/goal` | 回落占位链：`rdd_status` active:true 但 `subtask` 是占位（asset_key=goal），**不吞目标、不卡死** |
| E2 | 坏 JSON/空分解 | （可在单测层验证） | RddDecomposer.parse 返回空 → 回落占位链 |
| E3 | 身体工具不存在 | 分解/rdd_submit 给出不存在的 task_type | 日志 warn「身体工具不存在，该二级只检测不执行」，不崩，检测-only 仍可推进 |
| E4 | 身体任务结束未达成 | 让 collect_items 失败/被取消后条件未满足 | 重试 ≤3 次（日志「重试 N 次」）→ 超限 `markFailed`（`rdd_status` subtask_status=FAILED） |
| E5 | 二级超 8 个 | 构造 20 个二级的 LLM 返回 | 截断到 8（parse 层单测已覆盖） |

## F. 回归底线

1. 改码/测完不提交（本仓多线共享，提交归各线）。
2. 失败项如实记录（含 `expected/actual`），不以编译通过代替行为验证。
3. 游戏测试后若需重启，先 `git log` 确认 RDD 四个提交在，不误删别线工作。

## 完成标准（一段话）

`/goal` 输入后：分解生效（decomposing→ACTIVE 具体二级）→ RDD 自动提交 collect_items 身体任务 → 真实背包达标 → 二级逐级完成 → 一级 COMPLETED；断网回落占位不吞目标；坏输入不崩；纯 JVM 核心零 MC/LLM 依赖；任务状态只由 TaskChain 写。全部满足即验收通过。
