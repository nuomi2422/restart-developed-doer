# 对借用宿主 Numen 的修改说明（Modifications）

> 本项目（restart-developed-doer）在 **[Numen](https://github.com/Dwinovo/minecraft-numen)（NeoForge 1.21.1）** 之上构建了五大模块化系统。
> 本文**逐模块交代我们对 Numen 源码的改动**（与 `patches/heartpact-modifications.patch` 对应，base = Numen 原版接入点 `ea38daa1`）。
> 所有改动都是**叠加扩展**，不修改 Numen 核心语义；模块化 commit 可单独回滚。

---

## 改动概览（39 commit，145 文件）

| 模块 | 新增/改动 | 验证 |
|---|---|---|
| **AC 执行** | ac-api + ac-core + plugins/ac：多步脚本引擎（ac_execute/ac_status/ac_resume/ac_publish） | 单测 + 真机 3 步 AC SUCCESS |
| **RDD 任务链** | rdd-core + plugins/rdd：目标分解 + 卡死监督 + 持久化 + 资产检测 | 单测 + 真机重启恢复 |
| **expmem 经验** | experience-core + plugins/experience：learn/recall/verify | 20 单测 |
| **Self-Compile** | plugins/selfcompile：自变异 + 自动改码 + 证据链 + 生成工具 | 27 单测 |
| **assist 协助模式** | api/common + McpServer：MCP enqueue 注入 + assist（内置 AI 不关） | 真机 |
| **observability** | 模块观测：AI 上下文/工具结果/模块事件写监测台 | 真机 |

---

## 逐模块明细

### 1. AC 执行系统（ac-api / ac-core / plugins/ac）
```text
新增：
  · 多步脚本引擎：ac_execute（提交 AC JSON / 按名复用）/ ac_status / ac_resume / ac_publish
  · 身份契约：name+version+fingerprint，resume 拒绝版本/内容变化
  · FileAcVersionStore：AC 库落盘（config/numen/ac-library.json），重启恢复
  · Numen 工具惰性桥接（ensureBridged）

修复（对 Numen 的缺陷）：
  · Map.copyOf 对 null value 抛 NPE（get_self_status 的 "target":null 触发）→ LinkedHashMap
  · transport 工具在 AC 后台线程异常无护栏 → NumenToolBridge host.invoke 包 try（catch Throwable）
  · AcExecutor step 级异常护栏（异常降级 FAILED 而非炸查询链）
```

### 2. RDD 资产任务链（rdd-core / plugins/rdd）
```text
新增：
  · 目标三层分解（Goal/PrimaryGoal/Subtask）+ 硬编码检测 + Supervisor 语义
  · 卡死监督：资产指纹（背包+位置）连续无变化 → STALLED → nudge 拍醒内置 AI → 行为恢复自动回 RUNNING
  · 任务链持久化：saveRuntimes 落盘（config/numen/rdd-tasks/*.json），游戏重启自动恢复
  · AssetRegistry populate：背包物品写进资产注册表（rdd_status 报真实资产）
  · /goal 接管 + 身体执行桥（TaskDispatch）
```

### 3. expmem 经验记忆（experience-core / plugins/experience）
```text
新增：experience_learn/recall/verify 三工具；JSONL 原子存储；成熟度升级（OBSERVED→VERIFIED→GENERALIZED）
```

### 4. Self-Compile 自变异（plugins/selfcompile）
```text
新增：
  · Mutation 生命周期（REQUESTED→…→DELIVERED + Budget/Workspace/StateMachine/StaticChecker/Compiler/ArtifactStore）
  · 自动改码：MutationErrorParser（javac 输出→结构化 file:line:col）+ MutationPipeline.compile 接通
  · 证据链收紧：MutationVerification 9 阶段全链才 VERIFIED（审查点名缺口）
  · 生成工具：rdd_whereami（读坐标）、rdd_get_inventory（背包清单）——generated/ 目录，自编译可再生
```

### 5. assist 协助模式（api/common / McpServer）
```text
新增：
  · McpMode 分离"驾驶"与"协助"：assist 下内置 AI 保持开轮，MCP 不取代
  · MCP enqueue 工具：任务链喂目标给内置 AI（assist 语义），say 只说话、enqueue 注入任务
  · RddPlugin.bodySubmissionEnabled：assist 下 RDD 暂停自动工具提交（防双驾驶）
```

### 6. observability 观测（api/common / 各插件）
```text
新增：
  · EntityAgentLoop 上下文埋点（user_prompt/assistant/turn）
  · ExecuteToolPayload 服务端 tool_result 埋点（写 tools.jsonl）
  · 模块观测：RddMonitor（rdd.jsonl）/ AcMonitor（ac.jsonl）/ ExperienceMonitor / SelfCompileMonitor
```

---

## 回滚方式

每个模块独立 jar + 独立 commit，可单独回滚：
```text
git revert <模块 commit>  # 或删除对应 jar（mods/ 下）
```
patch 文件可反向 apply：`git apply -R patches/heartpact-modifications.patch`（会回滚全部）。

## 已知边界（不隐瞒）

```text
· 生成工具（generated/）不进 git（可再生成）
· 中文经 MCP enqueue 变问号（编码问题，用英文绕过）
· ApricityUI 每 tick NoSuchMethodException（NbtIo 旧签名，第三方 mod，非本项目改动）
· verdict 已收紧为证据链，但生产双实例隔离未做
```

## 许可证

本项目代码 MIT。**Numen 本体遵循其自身许可证**；本 patch 是叠加扩展，不含 Numen 原版源码。
