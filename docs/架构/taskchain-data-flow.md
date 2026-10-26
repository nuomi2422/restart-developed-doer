# RDD 任务链数据流（2026-09-18 只读代码梳理）

> **性质**：只读代码梳理，不改任何代码。
> **范围**：**只看任务链**。不含自编译、AC 执行层、监测台前端、经验记忆库。
> **来源**：`D:\rdd\workspace\minecraft-numen`，分支 `codex/rdd-monitor-v2`，HEAD `395cd890`。
> **替代**：`rdd-data-flow.md`（2026-08-31 版，早于懒展开多级/条件组/世界事实，已不反映真身）。旧文档保留作历史对照。
>
> 覆盖源文件：`rdd-core/` 7 个 + `plugins/rdd/` 18 个（共 ~4900 行）。

---

## 1. 角色与分层边界

```text
RDD（军师）        = 规划 / 检测 / 判定 / 提醒 / 懒展开
NUMEN 内置 AI（将军）= 自主感知 / 规划 / 执行 / 说话
外部（监测台/MCP）  = 监察 + 显式提交
```

### 两层物理隔离（这是整个设计的地基）

```text
rdd-core/          纯 JVM。不碰 Minecraft、不碰 monitor、不调 LLM、无 IO
  ├── TaskChain               状态唯一写入者（状态机 + 推进 + 重规划）
  ├── AssetRegistry           资产快照（观察历史 + 当前态）
  ├── HardCodedEvaluator      条件判定纯函数
  ├── InventoryGroups         food / wood / blocks 三组白名单 + 组内计件
  ├── WorldFactConditions     世界事实条件 schema 校验
  ├── RddChainFactory         造链（fromStages / fromSpec / fromObjective）
  ├── RddRuntime              宿主门面（chain + assets + snapshot）
  └── api/                    Goal / PrimaryGoal / Subtask / Port 契约

plugins/rdd/       宿主适配层（NeoForge + Minecraft + LLM）
  ├── RddPlugin               接线中枢：注册表、持久化、nudge、开关
  ├── RddDetector             主循环（每秒 tick）
  ├── RddGoalDriver           懒展开"展开权"持有者（唯一调 Stage-B 的地方）
  ├── RddStagePlanner         Stage-A：整条目标 → 一级阶段清单
  ├── RddDecomposer           LLM 传输 + 容错解析（Stage-A/B/fallback 共用）
  ├── RddExpansionPolicy      展开失败阈值（纯函数）
  ├── RddPlanGuard            规划守门（可选升级/已持有 硬规则）
  ├── RddWorldFacts           世界事实读取（服务端线程只读）
  ├── RddWorldAssetObserver   世界资产惰性观察（30s）
  ├── RddStallPolicy          卡死判定（纯函数）
  ├── RddSurplus / RddSurplusPolicy  干草奖励采集
  ├── RddBodyTools            body 语义 → 真实工具参数翻译
  ├── RddTaskHandoff          磁盘交接
  └── RddMonitor              rdd.jsonl 观测出口
```

**铁律**：`rdd-core` 不得把 NUMEN 内部类当契约；适配层不得绕过 `TaskChain`/`AssetRegistry` 直接写状态。

---

## 2. 入口数据流（三条）

### 入口 A：游戏 `/goal`（主路径，懒展开）

```text
玩家 /goal X
  → RddPlugin.setup 注册的 GoalSinks sink(uuid, objective)
      ├─ objective 空 → return false（不认领）
      ├─ server == null → return false
      ├─ CALLBACKS.replace(uuid) → Ticket      ← 换代号：旧回调全部作废
      └─ onServer(server, ticket, beginPlanning)
  → beginPlanning
      DECOMPOSING.add(uuid) / BODY.remove / RddGoalDriver.clear
      RddMonitor.publish("supervisor_input")
      → RddStagePlanner.planStages(uuid, objective, cb)   ← Stage-A 异步 LLM
          ├─ 成功且非空 → RddChainFactory.fromStages(uuid, objective, stages)
          │                → Goal（N 个 unexpanded 一级）
          │                → bindCurrent → RUNTIMES.put(RddRuntime(new TaskChain(goal), assets))
          │                → "goal_staged" → DECOMPOSING.remove
          └─ 空 / 装配抛异常 → decomposeSinglePass（回落）
  → decomposeSinglePass（回落路径，行为对齐旧版）
      RddDecomposer.decompose → Goal（单一级含全部二级）
      → bindCurrent → runtime.startCurrent() → "goal_decomposed"
      → 再失败 → removeCurrent（保留 NUMEN 原生回落）
```

> **首个一级保持 unexpanded**，由 Detector 观察到懒边界后上报 GoalDriver 展开。

### 入口 B：MCP `rdd_submit`（外部显式提交，一次性断言）

```text
外部 → RddSubmitTool.onServerCall
  ├─ 校验 goal / primary_goal / subtask / asset_key 非空 + minimum ≥ 0
  ├─ Subtask.hardCoded(subtaskId, desc, {asset_key, minimum}, body?)
  │     body = task_type 非空才挂；args 是 JSON 字符串，坏 JSON → 忽略（用缺省参数）
  ├─ Goal("goal-"+uuid) ← PrimaryGoal ← List.of(subtask)
  ├─ RddPlugin.bind(uuid, goal)     ← 要求服务端线程，否则 IllegalStateException
  └─ runtime.startCurrent()
  回执 accepted（含 goal/primary_goal/subtask/detection_mode/task_type）
```

### 入口 C：`rdd_skip_optional`（模型发起，唯一允许模型改链的口子）

```text
内置 AI → RddSkipTool.onServerCall(expected_subtask_id, reason)
  → RddPlugin.skipOptionalCurrent(uuid, expected, reason)
     守卫（任一失败即拒，返回原因字符串）：
       ├─ runtime/currentSubtask 为空           → "no active executable RDD subtask"
       ├─ id 不匹配（过期）                      → "refused: stale subtask id"
       ├─ !RddOptionalFood.canSkip               → "refused: only extra food variety..."
       └─ 身体忙（CompanionTickDispatcher）      → "refused: body is busy"
     → chain.skipSubtask(id, reason)            SKIPPED（绝不写 COMPLETED）
     → clearBody → finishResolvedPrimary（防停在 AWAITING_SUPERVISOR）
     → "subtask_skipped"(source=numen_model)
```

### 清除：`/goal clear`

```text
GoalSinks.registerClear(uuid, reason)
  → RddPlugin.remove(uuid) → CALLBACKS.replace → onServer → removeCurrent
      RddTaskHandoff.remove(dir, uuid, onOk)
        onOk → DECOMPOSING/BODY/LAST_CONTEXT/RddGoalDriver 清 + RUNTIMES.remove
      IOException → "task_clear_failed"（**内存任务保留**，绝不假报成功）
```

---

## 3. 主循环：`RddDetector.onServerTick`

注册于 NeoForge 事件总线，**服务端每 tick 跑，节流到 1 秒**（`TICKS_PER_CHECK = 20`）。

```text
onServerTick(server)
  ├─ server == null → return
  ├─ ++tickCounter < 20 → return          ← 节流
  ├─ tickCounter = 0
  ├─ assetTick      = (assetTick + 1) % 5      → 每 5 秒
  ├─ worldAssetTick = (worldAssetTick + 1) % 30 → 每 30 秒
  ├─ RddPlugin.refreshSupervisionFlag()   ← 读 config/numen/rdd-supervision.flag
  ├─ RddPlugin.restoreRuntimes()          ← 磁盘有 / 内存无 → TaskChain.fromJson（幂等）
  ├─ for 每个 ServerPlayer p:
  │     p 非 NumenPlayer → continue
  │     rt = RddPlugin.runtime(p.getUUID())；null → continue
  │     tickRuntime(p, rt)   ← try/catch 包住，检测失败不拖垮 tick
  └─ RddPlugin.saveRuntimes()             ← 全量原子写（tmp + REPLACE_EXISTING）
```

### `tickRuntime` 完整分支顺序（顺序即语义，不可乱）

```text
 1. counts = countInventory(ap)                真实背包 → Map<"minecraft:oak_log", n>
 2. surplus.inspect(ap, chain, counts)         干草奖励收尾（超预算 → stopActive）
 3. RddPlugin.cacheInventory(uuid, counts)     → LAST_INVENTORY（规划注入数据源）
 4. assetTick == 0  → populateAssets(...)      背包 → AssetRegistry（inventory_scan / GLOBAL）
 5. worldAssetTick == 0 → RddWorldAssetObserver.observe + saveAssets + "asset_snapshot"
 —— 以上 3~5 对**所有控制态**都执行（含停车/失败/未展开）——
    控制态早退不得让监测台看起来冻结
 6. primaryStatus ∈ {AWAITING_SUPERVISOR, REPLANNING, COMPLETED, FAILED} → return
 7. primaryStatus ∈ {PENDING, WAITING}（刚推进到的新一级）
      ├─ currentPrimary().unexpanded() → RddGoalDriver.needExpansion(uuid)
      │     + "expansion_needed" + publishTaskSnapshot → return
      │     ★ Detector 绝不自己展开、绝不调 LLM
      └─ rt.activateCurrent(counts)
            waitFor 未满足 → primaryStatus = WAITING + "primary_waiting" → return
            满足          → ACTIVE + "dependency_met" → 继续
 8. currentSubtaskStatus() == PENDING → rt.startCurrent()
 9. status == STALLED → handleStalled(...) → return
10. status == FAILED
      ├─ HARD_CODED 且 counts 此刻已满足 → "early_achievement"
      │     retrySubtask(FAILED→PENDING) → startCurrent(PENDING→RUNNING) → completeSubtask
      │     ★ FAILED 只表"那次尝试失败"，不表"目标未达成"
      └─ 否则 → handleSubtaskFailure(...) → return
11. status != RUNNING → return
12. ★ EarlyAchievement 连跳循环（guard++ < MAX_INSTANT_PASS = 32）
      while primaryStatus == ACTIVE:
        PENDING 且非 HARD_CODED → break
        PENDING → rt.startCurrent()
        非 RUNNING → break
        非 HARD_CODED → break
        !conditionMatches → break
        "early_achievement"（资产已在，跳过 AI 执行）
        completeSubtask(...)
        一级边界（primaryStatus 变 PENDING）：
          下一级 unexpanded → needExpansion + "expansion_needed" → break
          activateCurrent 成功 → "dependency_met"
          失败               → "primary_waiting" → break
        counts = countInventory(ap)     ← 重新扫（跳过的步骤可能改变了背包）
13. AI_ASSISTED 二级 → return          ★ 交还 AI 驱动，不做卡死监督、不派身体
14. supervisionEnabled() && trackStall(...) → 卡死则 return
15. supervisionEnabled() && bodySubmissionEnabled() → maybeSubmitBody(ap, current)
16. conditionMatches(ap, current, counts) → completeSubtask(...) → return
17. maybeRetryOrFail(ap, rt, current)
    finally: assetTick == 0 → publishTaskSnapshot("periodic_observation")
```

---

## 4. 判定数据流：谁判、判什么、拿什么判

```text
conditionMatches(ap, task, counts)
  │
  ├─ condition 含 "type" 且 != "inventory"        ← 世界事实分支
  │    → WorldFactConditions.valid(condition)
  │      && RddWorldFacts.matches(ap, condition)
  │         ├─ advancement    : ap.getAdvancements()…isDone()
  │         ├─ entity_killed   : ap.getStats().ENTITY_KILLED ≥ minimum
  │         │                    ★ 本人终身击杀，不是"实体消失"、不是主人统计
  │         ├─ structure       : 本人所在已加载 chunk 的结构包围盒内
  │         │                    ★ 只解析已加载结构，绝不 forceLoad
  │         └─ base            : 本人 respawn 床 + 附近已放置箱子**且**熔炉
  │                              ★ 背包设施不算建成
  │        未知/畸形/未加载 一律 false（绝不回落成背包判定）
  │
  └─ 否则                                        ← 背包分支
       → HardCodedEvaluator.matches(condition, counts)
            ├─ condition.group   → InventoryGroups.count(group, counts) ≥ minimum
            │      food   : 24 种可直接食用安全食物（排除小麦/干草/生土豆）
            │      wood   : 8 树种 × (_log/_wood/_planks/stripped_*) + 菌柄系 + bamboo_planks
            │      blocks : 石/深板岩/土/下界岩/末地石/安山岩… + 任意 _planks
            │      ★ 组内**按件求和**，不做配方换算
            └─ condition.asset_key → counts.getOrDefault(key, 0) ≥ minimum
                 minimum 缺省 1；key 不在背包时 minimum==0 才算满足
```

**防假成功铁律**：判据永远是**真实背包 / 真实世界**。工具返回 `success`、AI 自述、候选完成度——都不进判定。

### 条件形状校验（进链前把关，宁缺毋滥）

| 校验点 | 位置 | 规则 |
|---|---|---|
| 资产键形状 | `RddKeys.usable` | 必须 `^[a-z0-9_.-]+:[a-z0-9_./-]+$`。裸键/占位 `goal`/大写 → **丢弃** |
| 组条件 | `RddChainFactory.validateCondition` | `group` 已知、**不得同时给 asset_key**、minimum 正整数 |
| 世界事实 | `WorldFactConditions.valid` | 已有 `asset_key`/`group` 时直接 false；type 必须四选一 |
| 数量 | 各处 | `minimum` 必须整数值、非负（组条件必须 > 0） |

> 设计意图：合法 JSON 但结构不可执行的条件（如 `asset_key:"stone_pickaxe"`）必须**进链前丢弃**，
> 而不是留成永久不满足的假卡死。

---

## 5. 懒展开多级数据流（当前架构的主路径）

```text
              ┌─────────────────── Stage-A（一次，整条目标）───────────────────┐
/goal ───────►│ RddStagePlanner.planStages                                      │
              │   prompt = planningPrompt(objective, lastInventory, planningAssets)
              │          + RddPlanningKnowledge.attach(...)    ← 经验知识贴正文
              │          + RddPlanningPolicy.block(objective,"stage_a")  ← 硬规则提示块
              │   → RddDecomposer.llmAsk("stage_a", plan_stages)   ← 单轮 + 单合成工具
              │   → parse(args) → PrimarySpec[](theme, wait_for)   ← 容错解析
              │   → RddPlanGuard.filterStages                     ← 未获要求的可选工程整条丢
              │   → RddChainFactory.fromStages(uuid, objective, stages)
              │        → N 个 PrimaryGoal.unexpanded("primary-<uuid8>-i", theme, waitFor)
              │        → Goal("goal-<uuid8>", objective, primaries)
              └────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
                    bindCurrent → TaskChain(goal) → RUNTIMES
                                        │
                      首个一级 unexpanded，链上没有可运行二级
                                        │
              ┌───────────────────────┴───────────────────────────────────────┐
              │ RddDetector 每秒:  currentPrimary().unexpanded() →            │
              │     RddGoalDriver.needExpansion(uuid)   ★ 幂等，只在边界触发   │
              └───────────────────────┬───────────────────────────────────────┘
                                      ▼
              ┌──────────── Stage-B（每次到达新一级，懒展开其二级）────────────┐
              │ ① 守卫：decomposing? runtime null? 已展开? → 直接忽略          │
              │ ② 换级重置：pid != st.primaryId → attempts/cooldown/inFlight/  │
              │    escalated 全清                                              │
              │ ③ RddExpansionPolicy.shouldFire(inFlight, escalated, attempts, │
              │       cooldownUntil, now)                                      │
              │       MAX_EXPAND_ATTEMPTS = 3   EXPAND_COOLDOWN_MS = 5000      │
              │ ④ 预算耗尽且未 escalate → "expansion_exhausted" 停靠 → return  │
              │ ⑤ inFlight = true；记录 request 令牌（防旧回调污染）           │
              │ ⑥ completedStagesBefore(chain) → 前序一级描述（防倒退重规划） │
              │ ⑦ RddDecomposer.decomposeSpecs(uuid, theme, attempt, completed,│
              │       specs -> onServer(server, ticket, finish(...)))          │
              │      attempt ≥ 1 → 追加"上次为何不可执行"修正提示              │
              │      → llmAsk("stage_b", decompose_goal) → parse              │
              │      → RddPlanGuard.filterSubtasks（可选升级丢 / 已持有复用）  │
              │ ⑧ finish（MC 线程）                                            │
              │      校验：runtime 未换 / State 未换 / primaryId 未变          │
              │      校验：request 令牌匹配（旧回调作废）                      │
              │      id = pid + "-s" + i  ★ 以此为前缀保证跨级全局唯一         │
              │      rt.expandCurrentPrimary(subs)                             │
              │        core 校验：非空 / id 无重复 / 未展开 / 状态合法          │
              │      成功 → primary_expanded，attempts 清零                    │
              │      失败 → attempts++；预算内 cooldown 5s 再试                │
              │                       耗尽 → escalated → expansion_exhausted   │
              └────────────────────────────────────────────────────────────────┘
```

### 展开失败恢复（spec §9）语义

- 失败定义**从严**：LLM 错 / 坏 JSON / 解析空 / 合法但不可执行被 parse 清空 / 注入被 core 拒 —— 全部计一次尝试。
- **绝不塞占位假二级**（`asset_key:"goal"` 那类不可执行节点不得冒充"展开成功"）。
- 预算耗尽 = `escalated` 停靠，等主人换方向（`/goal` 重来或 clear）。**绝不伪造完成**。

### Stage-A 回落

`planStages` 回调空表 → `decomposeSinglePass`（单遍 `decompose`）→ 单一级含全部二级 + 立即 `startCurrent`。
即：**懒展开不可用时目标不被吞**，行为退化但不丢目标。

---

## 6. 状态机（`TaskChain` 是唯一写入者）

### 二级目标 `SubtaskStatus`

```text
                    ┌──────────── skipSubtask ────────────► SKIPPED ─┐
                    │                                                │
PENDING ──start──► RUNNING ──applyHardCoded/AiAssisted(true)──► COMPLETED ──advanceOrAwait──┐
   ▲                  │                                                                      │
   │                  ├── markFailed ──────► FAILED ──retrySubtask──► PENDING                │
   │                  │                        │                                             │
   │                  └── markStalled ─────► STALLED                                          │
   │                             ▲              │                                            │
   │                             └─resumeFromStalled─┘    （行为恢复）                        │
   └──────────────────────────────────────────────────────────────────────────────────────────┘
```

- `skipSubtask` 可从 `RUNNING / FAILED / STALLED` 进入，**必须带非空 reason**，写入 `skipReasons` 表。
- `COMPLETED / SKIPPED` 都触发 `advanceOrAwait()`：还有下一个 → `subtaskIndex++`；否则 → `AWAITING_SUPERVISOR`。

### 一级目标 `PrimaryGoalStatus`

```text
PENDING ──activateCurrent(依赖满足)──► ACTIVE ──全部二级完成──► AWAITING_SUPERVISOR
   │  ▲                                    ▲                          │
   │  └─等待期                               │                          ├─ CONFIRM ─► COMPLETED
   │                                        │                          │     └─ 后面还有一级 → primaryIndex++ / PENDING
WAITING ◄──activateCurrent(依赖未满足)───────┘                          ├─ REJECT / REPLAN ─► REPLANNING
   │                                                                    └─ NEED_MORE_EVIDENCE / DEFER ─► WAITING
   └── expandCurrentPrimary(注入二级) ──────────────────────────────────► PENDING（等激活）
```

### 不变量（core 硬校验，违反即抛）

| 不变量 | 保障点 |
|---|---|
| 未展开一级**不得** `activateCurrent` / `startCurrent` | 抛 `IllegalStateException`（"未展开 ≠ 依赖未满足"，绝不可误判成 WAITING） |
| 未展开一级**必须** `subtaskIndex == 0` | `validateRestoredState` |
| 只有 PENDING/WAITING 一级可 `expandCurrentPrimary` | 抛 IllegalStateException |
| 展开的二级非空、id 不重复、不与已用 id 冲突 | `expandCurrentPrimary` 全查 |
| 所有 mutator 必须 `requireCurrent(id)` | 防**过期 id 污染**（stale subtask） |
| `applySupervisorDecision` 必须 `AWAITING_SUPERVISOR` 且 `targetNodeId` 匹配 | 抛异常 |
| 恢复态必须 status 表与 goal 结构**完全一致**（missing/unknown 都拒） | `validateRestoredState` |
| 所有 mutator `synchronized` | 跨线程（MC 线程 / 服务端线程）安全 |

> 懒展开的 `expandCurrentPrimary` 是**浅改写**：保留一级 id/description/waitFor 与 `primaryStatus`，仅把 `unexpanded` 置 false + 装二级 + `subtaskIndex = 0`。不重写任何其他状态。

---

## 7. 失败与恢复（三级 + 展开失败 + 空转止血）

### Level 1 · 当前二级局部恢复

**卡死监督**（`RddStallPolicy`，纯函数）：

```text
指纹 = (背包计数 + 方块坐标) + "|work=" + 工作进度
工作进度 = 打开的容器槽位内容（跳过燃料槽）
         + furnace 的 getBurnProgress（烹饪进度，不是燃料倒计时）
         + 当前身体任务的 toolName:describe
waiting = furnace 正在生产 || 身体任务非终态

阈值：waiting ? WORK_GRACE_CHECKS(120) : IDLE_GRACE_CHECKS(15)   ← 1 次/秒
```

```text
观测 → 指纹变了 → 计数清零，返回 false（没卡）
     → 连续不变达阈值 → markStalled + "subtask_stalled" + nudge（拍醒内置 AI）
        ↓
     handleStalled：行为恢复 → resumeFromStalled + "subtask_resumed"
                    未恢复   → 给 STALL_RESPONSE_TICKS = 25 秒响应窗（不打扰 AI 思考）
                               窗过仍无变化 → 再 nudge（换措辞）
                               nudges ≥ MAX_NUDGES(2) → markFailed + "subtask_failed"
```

> **为什么是 15 不是 5**：AI 的 LLM 轮次（思考 + 工具链）可能 10~15 秒，太短会把"正在思考"误判成卡死。

**身体重试**：`maybeRetryOrFail`，已派过身体 + 身体任务不在位 + 条件未满足 → 重试 ≤ `MAX_BODY_RETRIES(3)` → 否则 `markFailed`。

### Level 2 · 当前二级重跑（`handleSubtaskFailure`）

```text
retries 绑定 subtaskId（二级变了才重置，防误清）
  ├─ n < MAX_SUBTASK_RETRIES(2) → retrySubtask(FAILED→PENDING) + startCurrent + nudge"换策略"
  └─ n ≥ 2
       ├─ RddOptionalFood.canSkip(可选食物) && 身体空闲
       │    → skipSubtask(SKIPPED) + 清空全部 per-uuid 状态 + finishResolvedPrimary
       └─ 否则 → **停车**（gapParked）
            nudge 一次"已耗尽重试，停车等真实资产" + "subtask_parked"
            之后不再自动重试 / 不再 nudge，只留真实资产检测
```

> **停车设计意图**：耗尽重试**不构成**能力缺口证据。清 retries 会进
> `FAILED→重试→RUNNING→body 重派→失败` 的无限循环，每次 nudge 都烧一次模型调用。
> 停车后 AI 或主人真攒够资产，由 FAILED 分支的 `early_achievement` 自动验收推进——**恢复路径不堵**。

### Level 3 · 能力不足（**刻意保守**）

```text
stallCounts 绑定 subtaskId，累计卡死次数
  total ≥ CAPABILITY_GAP_AFTER_STALLS(3) → nudge"先核对真实工具结果/资源/路径/装备/模型连接"
                                          + "subtask_stall_escalated"(failureClass=UNKNOWN)
                                          + stallCounts.remove
```

> **红线**：nudge 正文明确写"**不要仅凭重复失败推断缺软件工具，只有确认能力缺口后再考虑自编译**"。
> 即：Level 3 **不自动升级为自编译请求**，只上报"需要证据"。

### 展开失败（`RddExpansionPolicy`）

`MAX_EXPAND_ATTEMPTS = 3` → 每次失败 `attempts++` + `EXPAND_COOLDOWN_MS = 5000` → 耗尽 `escalated` + `expansion_exhausted` 停靠。

### 空转止血（`supervisionEnabled = false`）

```text
开关文件：config/numen/rdd-supervision.flag
  内容空 / "run"        → 监督启用
  内容 "pause" / "0" / 任意非空非 run → 监督暂停
  读取失败 → 保持当前状态
```

暂停后 Detector **退化为纯观察**：

| 动作 | 暂停时 |
|---|---|
| 资产检测 + EarlyAchievement 推进 | ✅ 照常 |
| 卡死检测 / nudge 拍醒 | ❌ 跳过 |
| 自动重试 / 失败升级 | ❌ 跳过 |
| 自动重派身体 | ❌ 跳过 |
| STALLED 中的任务 | 保持卡住标记，AI 自己动了才 `resumeFromStalled` |

### assist 模式（`bodySubmissionEnabled = false`）

工具执行交还 NUMEN 内置 AI，RDD 只保留 **资产检测 / 目标完成判定 / 异常提醒**，防双驾驶。

---

## 8. 持久化与重启恢复

### 任务链（`config/numen/rdd-tasks/<uuid>.json`）

```text
写：RddPlugin.saveRuntimes()   每秒一次，每个 runtime 一个文件
    内容 = TaskChain.toJson()
      { goal(完整结构), statuses, skipReasons, primaryIndex, subtaskIndex, primaryStatus }
    原子写：<uuid>.json.tmp → Files.move(REPLACE_EXISTING)

读：RddPlugin.restoreRuntimes()  每次心跳，幂等（RUNTIMES 已有则跳过）
    TaskChain.fromJson：
      ├─ 老链兼容：一级缺 unexpanded 字段 → 补 false（已展开）
      ├─ statuses 表缺失 → 抛（"restored status table missing"）
      └─ validateRestoredState() 拒腐坏态：索引越界 / id 重复 / 表结构不匹配 / 未展开却 index≠0
    解析失败 → LOG.warn，**该文件不加载**（不污染运行时）
```

### 世界资产（`config/numen/rdd-assets/<uuid>.json`）—— **与任务链解耦**

```text
独立存放：清任务不抹基地/已发现结构
只持久化**当前态**（AssetRegistry.toJson），Observation 历史刻意不持久化
```

### 重启后的语义

| 重启前 | 重启后 |
|---|---|
| 未展开的一级 | `currentSubtask() == null`，报"stage_reached_expanding"，GoalDriver 重新展开 |
| RUNNING 二级 | 状态表原样恢复；下一 tick 走正常判定（资产已在 → EarlyAchievement 直接过） |
| FAILED / STALLED | 原样恢复；FAILED 先重验资产，STALLED 进 handleStalled |
| 展开在途 / 冷却 | **不恢复**（`RddGoalDriver.clearAll` on ServerStopped），重新计时 |

> `ServerStoppedEvent` 清空：`furnaces / stalls / retries / stallCounts / gapParked / surplus / 计数器`
> 以及 `RddPlugin.clearWorldState()` 里的 `DECOMPOSING / BODY / RUNTIMES / ASSETS / LAST_*`。
> **磁盘交接存活，在途规划与缓存观测不存活。**

---

## 9. 注入内置 AI 的数据流（RDD → 将军）

### 状态贡献（每秒随模型请求注入）

```text
numen.contributeState(uuid -> context)
  context 变化才发布 "numen_context"（去重）
  渲染：
    <rdd><enabled>true</enabled><active>false</active><decomposing>true</decomposing></rdd>
    <rdd>…<active>true</active><state>stage_reached_expanding</state>
          <current_phase>阶段主题</current_phase></rdd>          ← 未展开时诚实报阶段
    <rdd>…<subtask>id</subtask><current_task>描述</current_task>
          <done_when>条件</done_when><subtask_status>状态</subtask_status></rdd>
    + RddAssetContext.render(assets, 1200)   ← 世界资产块（上限 1200 字符）
```

### 拍醒（nudge）

```text
RddPlugin.nudge(uuid, message)
  守卫：!supervisionEnabled → 只 LOG，绝不注入（兜底闸）
  numenApi.enqueue(uuid, message)          ← 效果等同主人亲手打字
  → "supervisor_output"（含 taskChain 快照 + source=supervisor + target=numen）
```

### `body` 执行桥（仅 `bodySubmissionEnabled` 时）

```text
maybeSubmitBody(ap, current)   ← 每二级只提交一次（BODY 状态记 subtaskId）
  submitBody 三路分派：
    ① RddBodyTools.canonical(raw) 命中词表/别名
         mine|mine_block|mine_ore|gather   → mine
         equip_item|equip                  → equip_item
         craft                             → craft
         collect_items|collect             → collect_items
       → ToolRegistry.resolve(canonical)
       → RddBodyTools.buildArgs(canonical, args, minimum)
            item→block_ids 数组；矿石补 deepslate_ 变体；
            掉落物→矿石映射（diamond→diamond_ore / lapis_lazuli→lapis_ore…）；
            作物→方块映射（carrot→carrots / potato→potatoes…）
       → 未注册工具 / args 翻不动 → "subtask_capability_gap"（响亮，不静默空转）
    ② 词表外但是真实注册工具（外部显式指名）→ 原样透传参数
    ③ 都不是（规划层臆造名）→ "subtask_capability_gap"，只检测不执行
  → dispatchBody → tool.onServerCall(...) → "body_submitted" / "body_submit_failed"
```

> 旧病（已治）：臆造名 `mine_block` 每轮静默重试刷 20 分钟 → 现在响亮报 capability_gap。

---

## 10. 观测出口：`config/numen/monitor/rdd.jsonl`

```text
RddMonitor.publish(type, data)   ← synchronized，追加一行 JSON
envelope = { schema_version:1, event_id:"rdd-<nano>", timestamp,
             source:"numen", category:"rdd", type, data }
不依赖引擎内部类（MonitoringJournal 不在瘦 API jar），行格式与其对齐
```

### 事件全量清单（33 类）

| 类别 | 事件 |
|---|---|
| 目标接入 | `supervisor_input` `goal_staged` `goal_decomposed` `task_bound` |
| 懒展开 | `expansion_needed` `expansion_started` `primary_expanded` `expansion_failed` `expansion_exhausted` |
| 一级推进 | `dependency_met` `primary_waiting` `primary_resolved` `goal_completed` |
| 二级推进 | `subtask_completed` `subtask_skipped` `early_achievement` |
| 卡死监督 | `subtask_stalled` `subtask_work_wait` `subtask_stall_escalated` `subtask_resumed` |
| 失败恢复 | `subtask_failed` `subtask_retry` `subtask_parked` `subtask_capability_gap` |
| 身体执行 | `body_submitted` `body_submit_failed` `surplus_started` |
| 观测快照 | `taskchain_snapshot` `asset_snapshot` `numen_context` `supervisor_context` `planning_policy` `planning_knowledge` |
| 监督输出/开关 | `supervisor_output` `supervision_state` |
| 清除 | `task_clear_failed` |

### 🔴 已确认缺口：两个**死事件**

```text
RddRuntime.publish(type, data)  ← ！！空实现！！
    // The pure JVM core intentionally has no monitor dependency.
    // Host adapters may observe this facade.
```

`TaskChain` 侧的两个事件因此**永远不会进 rdd.jsonl**：

| 死事件 | 声明处 | 语义 | 影响 |
|---|---|---|---|
| `subtask_started` | `RddRuntime.startCurrent()`:27 | 二级开工 | 监测台看不到"这一秒开始做哪一步"，只能靠 `subtask_completed` 倒推 |
| `subtask_detection` | `RddRuntime.applyHardCoded()`:42 | 每次检测**未满足**的结果 | 监测台看不到"检测跑了但没够"，长时间未满足时**与停摆无法区分** |

已全仓库 grep 确认：这两串字面量**只出现在 `RddRuntime.java` 内部**，适配层没有任何地方补发。

> 这是**设计取舍与观测缺口之间的错位**：core 不依赖 monitor 是对的（纯 JVM 可测），
> 但适配层（`RddDetector`/`RddRuntime` 封装）没有把这个洞补上。
> **修法**（不在本次范围，仅记录）：在 `RddDetector` 调 `rt.startCurrent()` 处补 `subtask_started`，
> 在 `conditionMatches == false` 分支节流补 `subtask_detection`。

---

## 11. 关键契约速查

```text
· 状态唯一写入者 = TaskChain；AssetRegistry 只管资产快照；Observation 只追加
· Detector 绝不调 LLM / 绝不改规划结构（只有 GoalDriver 有展开权）
· 判据永远是真实背包 / 真实世界事实 —— 不认工具自述、不认 AI 自报
· 任何失败/熔断都不伪造 COMPLETED，也不删除资产
· 硬编码检测只发现事实；语义判断才交 Supervisor
· 全部二级完成 ≠ 一级完成，必须过 AWAITING_SUPERVISOR
· 三个开关：supervisionEnabled（拍醒）/ bodySubmissionEnabled（自动派身体）/ DECOMPOSING（规划中）
· 原子写 + 版本化；写入失败不静默继续（task_clear_failed 保留内存任务）
```

## 12. 阈值集中表（改参数看这里）

| 常量 | 值 | 位置 | 含义 |
|---|---|---|---|
| `TICKS_PER_CHECK` | 20 | RddDetector | 检测周期 = 1 秒 |
| `assetTick % N` | 5 | RddDetector | 背包 → AssetRegistry = 5 秒 |
| `worldAssetTick % N` | 30 | RddDetector | 世界资产观察 = 30 秒 |
| `IDLE_GRACE_CHECKS` | 15 | RddStallPolicy | 空转判卡死（秒） |
| `WORK_GRACE_CHECKS` | 120 | RddStallPolicy | 有生产/有身体任务判卡死（秒） |
| `STALL_RESPONSE_TICKS` | 25 | RddDetector | 拍醒后响应窗（秒） |
| `MAX_NUDGES` | 2 | RddDetector | 拍醒上限 → 判失败 |
| `MAX_BODY_RETRIES` | 3 | RddDetector | 身体任务重派上限 |
| `MAX_SUBTASK_RETRIES` | 2 | RddDetector | Level 2 重跑上限 → 停车 |
| `CAPABILITY_GAP_AFTER_STALLS` | 3 | RddDetector | Level 3 上报门槛 |
| `MAX_INSTANT_PASS` | 32 | RddDetector | 单 tick 连跳已满足二级上限 |
| `MAX_EXPAND_ATTEMPTS` | 3 | RddExpansionPolicy | 懒展开重试上限 |
| `EXPAND_COOLDOWN_MS` | 5000 | RddExpansionPolicy | 展开重试最小间隔 |
| `MAX_STAGES` | 12 | RddChainFactory | 一级阶段上限 |
| `MAX_SUBTASKS` | 8 | RddChainFactory | 单次分解二级上限 |
| `MAX_OBJECTIVE_CHARS` | 4000 | RddChainFactory | 目标正文截断 |
| `MAX_HISTORY` | 512 | AssetRegistry | Observation 历史上限 |
| `DEFAULT_MINIMUM` | 1 | HardCodedEvaluator | 条件缺省阈值 |
| `RddOptionalFood` 门槛 | ≥16 | RddOptionalFood | 可跳过的替代食物储备 |
| `RddSurplus` 预算 | 8 件 / 30 秒 / 8 格 / HP>12 | RddSurplusPolicy | 干草顺手多收 |

---

## 附：一图流（端到端）

```text
 玩家 /goal ─┐
             ├─► GoalSinks ─► RddPlugin.beginPlanning ─► Stage-A(LLM) ─► fromStages
 MCP rdd_submit ─► RddSubmitTool ─► RddPlugin.bind ──────────────────────┐
                                                                          ▼
                                                          RUNTIMES[uuid] = RddRuntime
                                                              (TaskChain + AssetRegistry)
                                                                          │
                 ┌────────────────────────────────────────────────────────┘
                 ▼
     ┌──── RddDetector.onServerTick（1 秒）────┐
     │  countInventory ─► counts              │◄──── 真实背包（唯一事实源）
     │  populateAssets ─► AssetRegistry       │
     │  ┌─ 未展开？ ─► RddGoalDriver ─► Stage-B(LLM) ─► expandCurrentPrimary
     │  ├─ 依赖门 waitFor ─► activateCurrent  │
     │  ├─ PENDING ─► startCurrent            │
     │  ├─ STALLED ─► handleStalled ─► nudge ─┼──► numenApi.enqueue ──► 内置 AI（将军）
     │  ├─ FAILED  ─► 重验资产 / Level2 / 停车│
     │  ├─ EarlyAchievement 连跳              │
     │  ├─ trackStall / maybeSubmitBody ──────┼──► ToolRegistry ─► 女仆身体
     │  └─ conditionMatches ─► completeSubtask│
     └────────────────┬───────────────────────┘
                      │
        ┌─────────────┼───────────────┬────────────────────┐
        ▼             ▼               ▼                    ▼
   TaskChain     AssetRegistry   rdd.jsonl        config/numen/rdd-tasks
   (状态主权)     (资产快照)      (监测台)          (每秒原子写)
                      │
                      └──► rdd-assets/<uuid>.json（清任务不抹基地）
```
