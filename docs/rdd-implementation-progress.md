# RDD 资产任务链实现进度

日期：2026-08-30

## 本轮完成

- 在 NUMEN `ac-api` 增加纯 JVM RDD 数据契约：Goal、PrimaryGoal、Subtask、检测模式、资产状态/作用域、Observation、Executor/Supervisor/Observation Port。
- 在 NUMEN `ac-core` 增加 TaskChain、AssetRegistry、RddRuntime 最小实现。
- HARD_CODED 二级目标满足后直接推进；一级目标完成后进入 AWAITING_SUPERVISOR。
- 增加 `plugins:rdd` 独立插件，注册 `rdd_status` 查询工具，并通过 NUMEN 公开插件入口接入。
- 监测台接入采用现有 `MonitoringJournal` 的旁路观察原则；纯 JVM AC 核心不依赖监测台/MC。
- 不接 BrainChains；不修改自变异插件逻辑。

## 验证

- `:ac-core:test`：通过。
- `:plugins:rdd:compileJava`：通过。
- `:core:neoforge:compileJava`：通过。
- `:core:neoforge:build :plugins:rdd:build`：通过（Javadoc 既有遗留警告不阻断构建）。
- 已部署到三处游戏目录：`mods/`、版本根目录、版本 `mods/`。

## 当前限制

- RDD 插件当前提供状态查询和核心运行时挂载；尚未把自然语言目标自动生成成 TaskChain，也未把每种 NUMEN 身体工具自动映射成 RDD 二级目标。
- 下一批应实现真正的 `TaskRecord → TaskFactory → TaskDispatch.setTask` 执行桥，以及实际背包/世界 Observation 适配。

## 崩溃修复
- 2026-08-30 22:51 启动崩溃根因不是 RDD 逻辑，而是插件 JAR 的 neoforge.mods.toml 未展开变量：Illegal version number specified version。plugins:rdd/build.gradle 已补 processResources expand。
- 同时清理了游戏目录中旧的无效 RDD JAR，重新构建并部署 metadata 已展开的版本。
- 崩溃报告另有 Flashback 缺少 inter-medium.ttf，属于独立问题；本次 RDD 崩溃点已修复。

## 第二轮：接入真正的任务提交入口（08-31）

- 新增 `plugins/rdd` 的 `RddSubmitTool`（`rdd_submit`）：接收 goal/primary_goal/subtask/asset_key/minimum，创建 RDD Goal → PrimaryGoal → Subtask(HARD_CODED) → bind → startCurrent。
- 插件 JAR 现在捆绑 ac-api + ac-core 纯 JVM 核心，独立自包含部署。
- 修复回归：core jar 恢复 ysm/tlm 内嵌平铺（Builtin 联动），selfcompile/rdd 保持独立插件。
- 游戏实测（00:00 会话）：rdd 0.1.3-dev 加载成功、selfcompile 加载成功、`registered 42 tool(s)`（比上轮多 rdd_submit）、rdd 同伴登录、YSM 联动恢复。

### 当前验证入口
- `rdd_status`：查询 RDD 任务链状态（无任务返回 active=false）。
- `rdd_submit`：提交一个最小 HARD_CODED 任务链并启动。

### 待做
- 把 rdd_submit 的 HARD_CODED 检测接上真实 Observation（背包/世界）。
- 一级目标 Supervisor 确认与任务推进闭环。
- 把 RDD 二级目标接到 NUMEN TaskRecord/TaskDispatch 身体执行。

## 当前资产任务链完成度（截至 2026-08-31）

### 已完成（代码/测试/宿主加载）

1. **纯 JVM 数据契约**：Goal、PrimaryGoal、Subtask、DetectionMode、任务/资产状态、Observation、Executor/Observation/Supervisor Port。
2. **TaskChain 核心状态机**：二级目标 `PENDING → RUNNING → COMPLETED`；硬编码满足后直接推进；一级目标进入 `AWAITING_SUPERVISOR`；Supervisor 决策入口；失败入口和 stale node 防护。
3. **AssetRegistry 最小实现**：资产快照、Observation 历史、`OBSERVED/UNKNOWN/INVALID`、`TASK_BOUND/GLOBAL`、可用资产投影。
4. **HARD_CODED 判定器**：按 `asset_key + minimum` 对宿主提供的实际计数做纯函数判定。
5. **NUMEN 插件入口**：独立 `plugins:rdd`，注册 `rdd_status`、`rdd_submit`，RDD 核心捆绑进插件 JAR，避免运行时缺类。
6. **Goal 接管兜底接缝**：`GoalSinks` 已加入 NUMEN API；RDD 可接管长期目标；RDD sink 异常/未注册时可回落 NUMEN 原生 Goal 循环。原生目标不被永久删除，只是 RDD 接管期间让位。
7. **服务端检测循环**：`RddDetector` 每 20 tick（约 1 秒）读取 NumenPlayer 实际背包，满足 HARD_CODED 条件后推进二级目标，并进入简化 Supervisor 确认。
8. **监测台可见性**：RDD 状态通过 NUMEN `contributeState` 持续注入；纯 JVM 核心不反向依赖监测台。宿主监测日志遵循旁路观察原则。
9. **真实游戏加载**：已验证 RDD mod、自变异 mod、NUMEN 同时加载；RDD 工具注册数已从 41 增至 42；后续 detector 版本已重新构建部署。

### 已通过的验证

- `:ac-core:test`：通过（含 RDD 核心与 HARD_CODED 判定测试）。
- `:plugins:rdd:build`：通过。
- `:core:neoforge:compileJava`：通过。
- `:core:neoforge:jar`：通过。
- 游戏启动：RDD mod 元数据正确、无 `Illegal version number`；游戏可进世界。
- `rdd_status`：已被 NUMEN AI 实际发现并调用，返回 `active:false`，证明查询链通。

### 当前仍未完成

1. `rdd_submit` 已能建链，但还没有把二级目标自动映射到 NUMEN `TaskRecord → TaskFactory → TaskDispatch.setTask`，因此目前是**任务链状态/检测闭环**，不是完整身体执行闭环。
2. `RddChainFactory` 目前把自然语言目标包装成单个占位 HARD_CODED 二级目标（`asset_key=goal`），尚未真正让 AI 自动拆分出多个带真实资产条件的二级目标。
3. `RddDetector` 目前只实现背包物品计数，不含装备、掉落物、容器、方块、遗迹等广域检测。
4. AI_ASSISTED 只完成接口/状态契约，尚未接真实 Supervisor LLM。
5. 资产持久化、重启 `RECOVERING`、冷却/全局熔断、提前达成事件尚未完整接入宿主。
6. RDD 运行事件仍未全部接入 `MonitoringJournal` 的 `rdd.jsonl`，当前主要依赖 runtime state 和 NUMEN 原生日志观察。

### 准确结论

当前不是“RDD 全部完成”，而是：

```text
RDD 插件加载与公开入口       ✅
RDD 核心任务/资产状态机       ✅
HARD_CODED 背包检测推进       ✅（代码和单测，游戏新版本待实测）
/goal 接管与 NUMEN 兜底       ✅（已编译部署，待新会话行为确认）
自然语言自动分解             ❌
RDD → NUMEN 身体任务桥        ❌
完整双频/AI 检测体系          ❌
完整持久化与恢复              ❌
```

当前最小闭环的严谨验证标准是：

```text
提交 rdd_submit
→ rdd_status = ACTIVE/RUNNING
→ 真实背包达到 minimum
→ 日志出现二级目标完成
→ 一级目标进入/通过 Supervisor
→ rdd_status = COMPLETED
```

只看到“能生成、能激活、能执行”还不够；必须同时跟踪 `rdd_status`、真实资产状态和完成事件，避免把工具回执或 AI 自述当作任务完成。

---

## 第二批：自然语言 LLM 自动分解 + RDD→NUMEN 身体执行桥（08-31）

### 本轮完成（全部提交 + 构建通过 + 部署）

1. **ac-api 契约扩展**：新增 `BodyInstruction(taskType, args)`（纯 JVM 身体指令）、`SubtaskSpec(description/condition/body)`（分解规格）；`Subtask` 加第 8 组件 `body`（可空）+ 7 参便捷构造兼容。
2. **ac-core 多级链工厂**：`RddChainFactory.fromSpec(uuid, objective, specs)`——objective 非空、specs 1..8、每份 condition 必须含非空 `asset_key`（minimum 可选非负）；`fromObjective` 改为 fromSpec 薄封装（行为不变，回落路径零改动）。
3. **客户端 LLM 分解器** `RddDecomposer`：
   - 纯静态容错 `parse(json)`：Gson 解析 LLM 的 `decompose_goal` 工具 arguments → `List<SubtaskSpec>`；坏 JSON/空/缺 asset_key/超上限一律丢弃，彻底失败返回空（回落占位链），不对称错误代价。
   - `decompose(uuid, objective, done)`：`Services.CONFIG` 组装 `LlmEndpoint` → `NumenLlmClient.forEndpoint(ep).chatStreaming(...)`（合成 `IToolSpec`，因为引擎无 `response_format:json_object`）→ `toolCalls()[0].arguments()` → parse → fromSpec；无 key/失败/无 toolCalls/规格非法全部回落占位链；结果经 `Minecraft.getInstance().execute` 弹回主线程。
   - LLM 只在链创建时用一次（用户拍板）；检测/裁决仍确定性。
4. **GoalSinks 异步化** `RddPlugin`：sink 仍同步 `return true`（让引擎让位），但 `bind+startCurrent` 移到分解回调；新增 `DECOMPOSING` 集合（`rdd_status`/`contributeState` 显示 `decomposing:true`）；新增 `BODY` 提交计数 map。
5. **服务端身体桥** `RddDetector`：
   - 自动启动后续二级（PENDING→startCurrent）。
   - 二级带 body 且未提交 → `ToolRegistry.resolve(taskType)`（大小写容错）→ 直接 `onServerCall("rdd-N", argsJson, ap, ...)` 提交（每二级一次）。
   - HARD_CODED 条件满足（真实背包）→ applyHardCoded → 推进 → 全完成 → 简化 Supervisor CONFIRM。
   - 身体任务结束（槽空）但条件未满足 → 重试 ≤3 → `markFailed`。
6. **rdd_submit 身体支持**：可选 `task_type`/`args`（JSON 字符串）→ 构造 `BodyInstruction`。

### 验证

- `:ac-core:test` 全绿（新增 fromSpec 多级/非法拒绝、TaskChain 中间态 ACTIVE 测试）。
- `:plugins:rdd:test` 全绿（新增 RddDecomposer.parse 4 个容错测试）。
- `:plugins:rdd:build` 通过；已部署 3 路径；`40b424d0`+`8fc2cd57` 提交。
- 游戏实测修复：RddMod 服务端 tick 监听改 `ServerTickEvent.Pre`（抽象类不可注册直接监听，NeoForge EventBus 拒绝 → mod broken state → 已修）。

### 测试AI 后续修复（2026-08-31，详见 `rdd-module-verification.md` + `rdd-asset-task-chain-handoff.md`）

- 真机验证结论：**RDD 模块通过**。背包检测闭环真机跑通（golden_sword→COMPLETED）、身体桥成功/失败/重试路径实测。
- 修复 3 个真机缺口：①惰性桥接（ac_execute 前 ensureBridged）②裸 JSON 结果映射（无 success 字段→SUCCESS(data)）③rdd 包迁出 ac-core → 独立 `rdd-core` 模块（消除与 plugins/ac 的 JPMS 重复包冲突）。
- 新增 `RddMonitor`：写 `config/numen/monitor/rdd.jsonl`，监测事件缺口已补。

### 当前仍未完成

1. 广域检测（装备/掉落物/容器/方块），现在只做背包物品计数。
2. AI_ASSISTED 二级（AI 手动确认）+ 真实 AI Supervisor LLM。
3. 持久化/重启 RECOVERING、冷却/全局熔断、提前达成通知。
4. RDD 任务链无停止接口（rdd_submit 后 task_stop 不适用）。
5. `/goal` LLM 分解路径已真机验证（代码+单测），但端到端经 rdd_submit 验证；MCP 外部驾驶下 `say` 不触发 `/goal` 指令解析（接口边界，非缺陷）。
