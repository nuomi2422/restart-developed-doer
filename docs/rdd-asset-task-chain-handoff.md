# RDD 资产任务链 · 权威交接文档（给自编译系统）

> 日期：2026-08-31（最终交接，作者：资产任务链模块的 Claude）
> 状态：**模块验证通过**（单测全绿 + 真机闭环跑通 + 3 缺口已修复）
> 定位：RDD 五模块插件架构中的「资产任务链」模块，本 Claude 的职责到此交接。
> 自编译系统后续若碰本模块，先读本文 + `rdd-module-verification.md` + `rdd-asset-task-chain-verification.md`。

---

## 0. 一句话定位

**RDD 资产任务链 = 把自然语言目标（`/goal`）变成可检测、可执行、状态主权明确的 RDD 任务链；检测说话、世界真身裁决，绝不拿 AI 自述/工具回执当完成。**

```text
/goal 给我木头
 →（客户端）RddDecomposer 调 DeepSeek 分解成多级 HARD_CODED 二级目标（每个带真实资产条件+可选身体指令）
 →（服务端）RddDetector 每 1s：
     自动启动下一二级 → ToolRegistry.resolve(taskType)→onServerCall 提交身体任务（每二级一次）
     → 读真实背包 → HARD_CODED 条件满足 → 推进
     → 身体任务结束未达成 → 重试≤3 → markFailed
 → 全部二级完成 → AWAITING_SUPERVISOR → 简化 Supervisor CONFIRM → 一级 COMPLETED
```

**设计原则（用户拍板）**：
- LLM 只用于**链创建时的分解**（一次）；检测/裁决永远确定性（HARD_CODED 不调 AI）。
- 任务状态**主权归 RDD TaskChain**；NUMEN 只提供执行/观察能力，禁止双写。
- RDD 复用 NUMEN 但可解耦：`rdd-core` 纯 JVM，未来可接任意 AI。

---

## 1. 模块结构（as-built，2026-08-31 修复后）

```text
minecraft-numen/
├── rdd-core/                          ← 纯 JVM 核心（独立模块，2026-08-31 从 ac-core 迁出）
│   └── src/main/java/com/dwinovo/numen/rdd/
│       ├── api/                       ← 数据契约（零第三方依赖）
│       │   ├── Goal / PrimaryGoal / Subtask / DetectionMode / SubtaskStatus / PrimaryGoalStatus
│       │   ├── BodyInstruction(taskType,args) + Subtask.body（第8组件，可空）   ← 本 Claude 加
│       │   ├── SubtaskSpec(description,condition,body)  ← 分解规格，本 Claude 加
│       │   ├── AssetStatus / AssetScope / Observation / ObservationPort
│       │   ├── SupervisorDecision / SupervisorDecisionType / SupervisorPort / TaskExecutorPort
│       │   └── SubtaskExecutionRequest
│       └── core/                      ← 实现
│           ├── TaskChain.java         ← 任务状态唯一写入者（状态机）
│           ├── AssetRegistry.java     ← 资产快照/历史（状态分家）
│           ├── RddRuntime.java        ← 门面（发事件，不持第二份状态）
│           ├── RddChainFactory.java   ← fromObjective(占位) + fromSpec(多级,≤8,asset_key+minimum校验)
│           └── HardCodedEvaluator.java ← asset_key+minimum 纯判定
└── plugins/rdd/                       ← NeoForge 宿主适配（独立部署，内嵌 rdd-core）
    └── src/main/java/com/dwinovo/numen/plugins/rdd/
        ├── RddMod.java                ← @Mod("rdd")，ServerTickEvent.Pre（抽象类不可注册直接监听！）
        ├── RddPlugin.java             ← 注册 rdd_status/rdd_submit + GoalSinks 接管（异步分解）+ contributeState
        ├── RddDecomposer.java         ← 客户端 LLM 分解（合成 decompose_goal 工具→toolCalls JSON→容错parse→fromSpec）
        ├── RddDetector.java           ← 服务端检测 + 身体桥（自动启动二级/提交身体/读背包/重试≤3/markFailed）
        ├── RddMonitor.java            ← rdd.jsonl 观测出口（测试AI补，2026-08-31）
        ├── RddStatusTool.java         ← rdd_status（含 decomposing 态）
        └── RddSubmitTool.java         ← rdd_submit（可选 task_type/args 挂身体指令）
```

## 2. 关键接缝与实现要点

| 接缝 | 位置 | 要点 |
|------|------|------|
| 目标接管 | `GoalSinks.dispatch`（api 桥）+ RddPlugin sink | sink 同步 return true（引擎让位），bind+start 移到分解回调；DECOMPOSING 态可见 |
| LLM 分解 | `RddDecomposer.decompose`（客户端 sink） | `Services.CONFIG`→`LlmEndpoint`→`chatStreaming(合成IToolSpec)`；无 `response_format:json_object` 所以用工具调用拿 JSON；失败/无key/坏JSON 全回落占位链不吞目标 |
| 身体提交 | `RddDetector.submitBody`（服务端 tick） | `ToolRegistry.resolve(taskType)`（大小写容错）→ `tool.onServerCall("rdd-N", argsJson, ap, noopReply)`；每二级只提交一次（BODY map 记录） |
| 检测裁决 | `RddDetector.tickRuntime` + `HardCodedEvaluator` | 每 20 tick 读真实背包 `countInventory`；`asset_key+minimum` 纯函数判定 |
| 完成推进 | `RddRuntime.applyHardCoded` → `TaskChain` | 二级完成→advance→PENDING 下一二级→detector 自动 startCurrent；全完成→AWAITING_SUPERVISOR→简化 CONFIRM |
| 失败止损 | `RddDetector.maybeRetryOrFail` | 身体任务结束（槽空）但条件未满足→重试≤3→`markFailed` |
| 观测 | `RddMonitor.publish` | 写 `config/numen/monitor/rdd.jsonl`，MonitoringJournal 对齐 envelope |

## 3. 已验证（2026-08-31，测试AI实测）

见 `rdd-module-verification.md`（完整报告），摘要：

- **单测**：`:plugins:rdd:test` 4/4（parse 容错）+ `:ac-core:test`（现 rdd-core）14 用例全绿。
- **纯 JVM 边界**：rdd-core grep MC/client/entity/task/agent/monitor/forge/network/mcp/platform/llm → 零命中 ✅。
- **游戏加载**：无 Failed to create mod instance / 无插件登记失败；MCP 工具列表含 rdd_status/rdd_submit ✅。
- **真机闭环（D）**：rdd_submit(asset_key=minecraft:golden_sword, minimum=1) → detector 读背包 → 二级/一级 COMPLETED ✅。
- **自动长链（D+）**：collect_items body → 成功路径 COMPLETED；失败路径（snowy_plains 无橡木）自动重试≤3 → markFailed ✅。
- **限制**：MCP `say` 不触发 `/goal` 指令解析（需游戏内玩家输入或 NumenGateway 主人通道）——外部驾驶模式接口边界，非缺陷。

## 4. 测试AI已修复的 3 个真机缺口（2026-08-31）

| 缺口 | 根因 | 修复（提交） |
|------|------|-------------|
| 惰性桥接 | AcPlugin.setup 早于 NumenCore 全量工具注册，构造期只桥接少数工具 | `ensureBridged()` 幂等同步，ac_execute 前补齐（`2c3f004e`） |
| 裸 JSON 结果 | 非身体工具 complete 自定义 JSON（无 success 字段）被误判 FAILED | `BridgeResultMapper` 无 success → SUCCESS(data)（`2c3f004e`） |
| 重复包冲突 | plugins/ac 与 plugins/rdd 都内嵌 ac-core（含 rdd 包）→ JPMS ResolutionException | rdd 包机械迁出 → 独立 `rdd-core` 模块（`ebf8e8ed`，R100 rename 零逻辑改动）；ac jar 含 rdd 包=0 已验证 |

## 5. 关键不变量（自编译系统必须遵守）

```text
1. rdd-core 纯 JVM：零 net.minecraft / NumenPlayer / com.dwinovo.numen.entity / task / monitor /
   agent.llm / agent.provider 依赖。改核心前 grep 确认。
2. 任务状态唯一写入者 = TaskChain；AssetRegistry 只管资产快照，分家不越界。
3. 不接 BrainChains（本能竞价链）；身体执行走 ToolRegistry→onServerCall→TaskDispatch。
4. LLM 只允许出现在 RddDecomposer（客户端 sink，链创建时一次）；
   RddDetector（服务端 tick）绝不允许 LLM 调用。
5. 检测说话：二级完成必须对应真实世界状态（背包计数≥minimum），不认 AI 自述/工具回执。
6. 不碰 plugins/selfcompile（那是另一条线）；selfcompile 也不该改 rdd-core/plugins/rdd。
7. 模块化 commit：只提交本模块文件，别线未提交改动不碰（git add 精确路径）。
```

## 6. 已知缺口 / 后续可做（本模块未完成）

```text
✅ 已补：rdd.jsonl 监测事件（RddMonitor，测试AI）  ← 曾列为缺口，已解决
❌ 广域检测：现在只做背包物品计数（countInventory），装备/掉落物/容器/方块/遗迹待扩展
❌ AI_ASSISTED 二级：接口/状态契约在（Subtask.aiAssisted），未接真实 AI 手动确认 + 20s 轮询
❌ 真实 AI Supervisor：一级完成现在走简化 CONFIRM，未接 Supervisor LLM
❌ 持久化/重启 RECOVERING、cooldown、全局 circuit-breaker、提前达成通知
❌ RDD 任务链无停止接口：rdd_submit 提交后 task_stop 不适用（RDD 模块边界）
```

## 7. 构建 / 测试 / 部署（自编译系统用）

```powershell
# 环境（wrapper 下载 9.2.0 会超时！用缓存 9.2.1）
cd "E:\restart developing doer\minecraft-numen"
$env:JAVA_HOME = "E:/jdk21/jdk-21.0.11+10"
$gradle = "E:\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat"

# 测试 + 构建
& $gradle :rdd-core:test :plugins:rdd:test :plugins:rdd:build

# 部署 3 路径
$jar = "plugins\rdd\build\libs\numen-plugin-rdd-1.21.1-0.1.3-dev.jar"
Copy-Item $jar "E:\.minecraft\mods\" -Force
Copy-Item $jar "E:\.minecraft\versions\The Best of Twilight Forest\" -Force
Copy-Item $jar "E:\.minecraft\versions\The Best of Twilight Forest\mods\" -Force

# 启动游戏
Start-Process "E:\.minecraft\versions\The Best of Twilight Forest\shaderpacks\启动 The Best of Twilight Forest.bat" -WorkingDirectory "E:\.minecraft\versions\The Best of Twilight Forest\"
```

**进游戏前必先 `git commit`（防回滚）；游戏日志：`E:\.minecraft\versions\The Best of Twilight Forest\logs\latest.log`。**

## 8. 相关提交

```text
469dde59  ac(rdd): RDD 纯JVM核心（契约+状态机+判定+单测）          ← 本 Claude
072406d5  ac(rdd): NUMEN宿主接入（GoalSinks/rdd工具/Detector背包）  ← 本 Claude
8fc2cd57  ac(rdd): 自然语言LLM自动分解 + RDD→NUMEN身体执行桥        ← 本 Claude
40b424d0  fix(rdd): RddMod 监听改 ServerTickEvent.Pre              ← 本 Claude
2c3f004e  ac(修复): 真机验证缺口1+2（惰性桥接/裸JSON）              ← 测试AI
ebf8e8ed  ac(修复缺口3): rdd 核心迁出 ac-core → 独立 rdd-core 模块  ← 测试AI
c0b94e52  docs(ac): 记录真机验证 3 缺口修复                        ← 测试AI
```

## 9. 相关文档

```text
rdd-asset-task-chain-spec.md      ← 架构规格（20 节，最早批准）
rdd-module-verification.md        ← 测试AI 的验证报告（结论：通过）
rdd-asset-task-chain-verification.md ← 我给测试AI 的验证清单（A-E 可执行）
rdd-implementation-progress.md    ← 实现进度（本 Claude 维护）
self-compile-handoff.md           ← 自编译系统自己的交接（别线）
```

## 10. 交接给自编译系统（一句话）

**资产任务链模块已实现并验证通过：核心纯 JVM（rdd-core）、宿主桥接（plugins/rdd）、真机闭环跑通。后续自编译系统若扩展本模块，先读 §1 结构 + §5 不变量 + §6 缺口，改核心前 grep 纯 JVM 边界，提交前 git add 精确路径、不碰别线未提交改动。**
