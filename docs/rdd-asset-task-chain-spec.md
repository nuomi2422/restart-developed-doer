# RDD 资产任务链模块规范

**状态：已确认架构规范（2026-08-30）**  
**范围：仅资产任务链模块；不包含自编译、AC 执行层、监测台、经验记忆库。**

## 1. 定位与演进策略

RDD 资产任务链是可插拔的 Agent Runtime 增强模块。当前阶段复用 NUMEN 的执行、环境和 AI 能力，但通过 Adapter/Port 接入；RDD 核心不得把 NUMEN 内部类、内部状态或旧任务链格式作为公共契约。

```text
当前：RDD 核心 ← Adapter ← NUMEN 能力
未来：RDD 核心 ← Adapter ← 任意 AI/环境/执行系统
```

> 可以复用 NUMEN 的能力，不可以把 NUMEN 内部实现变成 RDD 依赖。

旧 DD 任务链是前身实验和反面教材，仅借鉴目标分层、懒展开、完成条件、重规划、持久化和失败经验；不继承其 Secretary 一把抓、多事实源、双写入者、静态全局状态、同步等待、双 Scheduler 抢调度或 LLM 猜完成等结构。

## 2. 模块组成

```text
AssetTaskChain
├── TaskChain             总目标、一级/二级目标、推进、重规划、冷却
├── AssetRegistry         当前资产事实、作用域、有效性、来源
├── DetectionScheduler    高频、低频、AI 辅助检测调度
├── Observation Log       实际观察历史，追加式审计记录
└── Adapter Ports         执行、观察、Supervisor 接口
```

状态主权：

| 组件 | 唯一职责 |
|---|---|
| `TaskChain` | 任务节点、当前指针、任务状态、推进与重规划 |
| `AssetRegistry` | 当前资产快照、作用域、有效性与资产变化 |
| `DetectionScheduler` | 调度检测，产生观察和检测事件 |
| `Supervisor` | 语义判断，返回结构化决策 |
| `NUMEN Adapter` | 翻译 RDD 请求，调用 NUMEN 能力并返回结果 |

外部模块不得绕过 `TaskChain` 或 `AssetRegistry` 直接写入状态。

## 3. Observation（检测证据）

Observation/检测证据是检测器从实际环境读取的结构化事实，不是截图，也不是 AI 或工具的自述。

```text
工具结果：工具声称做了什么
Observation：系统实际看到环境变成了什么
```

例如 `craft_item=success` 不能直接完成任务；随后背包检测到 `iron_pickaxe ×1`，才说明铁镐确实存在。工具成功但背包没有铁镐时，不能判定完成。

最小概念结构：

```text
Observation {
    observationId
    type
    source
    environmentId
    observedAt
    value
}
```

Observation 只追加、不覆盖历史。它被 `AssetRegistry` 投影为当前资产事实，再由 `TaskChain` 用于任务推进。Observation、资产状态、任务状态不是同一概念。

## 4. 任务层级与状态

```text
总目标
  └── 一级目标
        └── 二级目标
```

### 4.1 二级目标

二级目标是可执行的小步骤，必须声明 `detectionMode`、执行信息，以及对应的完成条件或 AI 检测配置。

状态：

```text
PENDING → RUNNING → COMPLETED
             ├── FAILED
             ├── STALLED
             ├── COOLDOWN
             └── INVALIDATED
```

- `HARD_CODED` 条件满足：硬编码检测直接标记 `COMPLETED`，不调用 AI。
- `AI_ASSISTED` 返回 `CONFIRMED`：由 `TaskChain` 应用为 `COMPLETED`。
- `NOT_CONFIRMED`、`INSUFFICIENT_EVIDENCE`：保持 `RUNNING`。
- `CANDIDATE_COMPLETED` 不属于二级目标状态；候选只用于提前达成和一级目标语义确认。

### 4.2 一级目标

一级目标拥有阶段性语义、核心资产要求和当前懒加载的二级目标集合。

```text
PENDING → ACTIVE → AWAITING_SUPERVISOR
                         ├── COMPLETED
                         ├── REPLANNING
                         ├── ACTIVE / WAITING
                         └── FAILED
```

全部二级目标完成只代表硬编码条件达到，不等于一级目标最终完成。必须进入 `AWAITING_SUPERVISOR`，由 Supervisor 进行语义确认。

## 5. 二级目标检测模式

### 5.1 HARD_CODED

适用于能可靠表达为确定性条件的目标，例如：

- 背包物品数量达到阈值；
- 指定方块从目标方块变为空气；
- 掉落物消失且背包数量增加；
- 装备栏存在指定装备；
- 指定状态效果存在。

硬编码检测必须读取实际环境，不能把工具返回 `success` 当作检测结果。

### 5.2 AI_ASSISTED

适用于无法完整硬编码表达的语义目标，例如判断地点是否适合建基地、区域是否安全、建筑是否基本完成。

```text
RUNNING
  → 周期性获取环境观察
  → Supervisor 判断当前二级目标
      CONFIRMED              → COMPLETED
      NOT_CONFIRMED          → 保持 RUNNING
      INSUFFICIENT_EVIDENCE  → 保持 RUNNING
```

配置至少包含：

```text
detectionIntervalSeconds
maxAiChecks
recheckOnUnchanged
```

默认环境未变化时不重复调用 AI；等待型目标可设置 `recheckOnUnchanged=true`。AI 只能判断当前二级目标，不得直接完成其他节点、修改资产或重写任务链。

## 6. 双频检测

### 高频快速检测

默认约每 1 秒，服务当前二级目标及刚执行的结果，覆盖当前背包、装备、附近掉落物、当前目标条件和工具声称修改的状态。确认 `HARD_CODED` 条件后直接完成并推进。

### 低频广域检测

默认约每 10 秒，覆盖周围容器、全局装备/状态、登记的遗迹坐标、全局资产位置和其他目标的硬编码条件。发现当前路径之外的条件时产生 `EarlyAchievementEvent`，进入事件队列，不能直接跳过当前目标或改写任务主状态。

通用调度规则：

- 间隔是默认配置，不是不可改变的硬编码；
- 同类检测禁止重入，未结束时合并为 pending；
- 重复事实去重，不重复通知；
- 检测失败/环境不可访问不等同于资产不存在；
- 高频服务当前二级目标，低频负责广域发现。

## 7. 提前达成

```text
低频检测 → EarlyAchievementEvent → 事件队列
→ 当前二级目标处理完成后通知 Supervisor
→ TaskChain/AssetRegistry 应用决策
```

Supervisor 可返回：

```text
CONFIRM_TARGET
REGISTER_ASSET_ONLY
BIND_TO_TASK_NODE
REQUEST_REPLAN
DEFER_TO_MEMO
```

硬编码检测只负责发现事实；AI 决定该事实在当前任务语义中的处理方式。

## 8. 资产生命周期

资产状态：

```text
OBSERVED  最近检测确认存在，可作为当前可用资产
UNKNOWN   暂时无法确认，不等于丢失，也不能作为完成依据
INVALID   已有充分事实证明原记录失效
```

资产变化必须保留前后端点、原因、时间、来源和关联任务节点，不做无痕删除。变化原因可包括 `UPDATED`、`CONSUMED`、`TRANSFORMED`、`RELOCATED`、`UNCONFIRMED`；第一版不要求建立完整转化图。

资产作用域：

```text
TASK_BOUND  由任务产生/发现，保留来源任务节点
GLOBAL      可被多个任务复用，仍保留来源任务节点
```

任务结束不会销毁资产。后续任务复用时增加 `GLOBAL` 作用域或全局引用。只有 `OBSERVED` 资产可直接参与当前任务完成判定；`UNKNOWN` 和 `INVALID` 必须排除。资产重新被检测到时可从 `UNKNOWN` 恢复为 `OBSERVED`。

## 9. 懒加载生成契约

进入新的一级目标前，必须生成至少一个合法、可执行的二级目标。

- 每个节点必须声明 `detectionMode`；
- `HARD_CODED` 必须带确定性检测条件；
- `AI_ASSISTED` 必须带检测周期和最大 AI 检测次数；
- 字段缺失、条件非法或无法执行的节点不得进入任务链；
- 生成失败最多重试 3 次，每次应修正请求上下文；
- 3 次后进入冷却，不得无限调用；
- 最终没有可用二级目标时，触发任务链卡死/异常处理。

## 10. 失败、恢复与熔断

失败类型分开记录：执行失败、卡死、生成失败、生成为空、生成非法、无可执行二级目标、AI 调用异常。

```text
Level 1：当前二级目标局部恢复
  retry / 调整参数 / 重新观察 / 更换工具或路径

Level 2：当前一级目标重规划
  保留有效资产，分析原因，重新生成二级目标

Level 3：能力不足
  发能力不足事件，交给外部自编译模块
```

失败后进入有记录的 `COOLDOWN`，至少记录 `cooldownUntil`、`failureCount`、`lastFailureReason`、`lastAttemptAt`。二级目标级、一级目标级、全局 AI 调用级分别计数。

全局 AI 熔断后停止新的 AI 调用，但保留全部任务和资产状态；任何失败或熔断都不能伪造 `COMPLETED`，也不能删除资产。

## 11. 持久化与重启恢复

必须持久化：总目标、一级/二级目标、当前节点、任务状态、资产快照与作用域、资产来源、失败计数、冷却、熔断和任务链版本。

NUMEN 内部 `Task`、`BrainChain`、`Future`、线程、网络连接和执行句柄内部对象不属于 RDD 持久化契约。

```text
重启前 RUNNING 二级目标
  → RECOVERING
  → Adapter 核对旧执行和实际环境
      仍在执行   → 重新绑定/继续观察
      已结束     → 重新读取结果并重新检测
      不存在     → INTERRUPTED，进入局部恢复
```

历史 Observation 保留；当前资产可用性重新确认；暂时无法确认则为 `UNKNOWN`。持久化必须版本化、原子写入；写入失败不得静默继续。

## 12. Port 与事件

RDD 核心只依赖 Port、Event、Store 三类抽象。

### TaskExecutorPort

```text
startSubtask(request)
stopSubtask(executionId)
getExecutionStatus(executionId)
```

只能返回执行接受、进度、失败、结束或不可用，不返回“RDD 任务已完成”。

### ObservationPort

```text
observeInventory(scope)
observeEquipment(scope)
observeNearbyDrops(scope)
observeWorld(region)
observeContainers(scope)
observeEnvironment(scope)
```

返回 Observation 或 ObservationFailure。

### SupervisorPort

用于一级目标完成确认、提前达成处理和 AI_ASSISTED 二级目标检测。请求用途必须可区分：

```text
SUPERVISE_PRIMARY_GOAL
EARLY_ACHIEVEMENT
CHECK_AI_ASSISTED_SUBTASK
```

所有异步请求携带：

```text
executionId
taskNodeId
environmentId
```

用于防止旧执行结果污染新任务、写错节点或跨环境串台。

## 13. 核心事件流

### HARD_CODED 二级目标

```text
TaskChain
→ TaskExecutorPort
→ NUMEN 执行
→ 高频检测
→ Observation
→ AssetRegistry 更新
→ 条件满足
→ TaskChain 直接完成二级目标
→ 推进下一节点
```

### AI_ASSISTED 二级目标

```text
TaskChain
→ NUMEN 执行
→ 按周期获取 Observation
→ Supervisor 判断
→ TaskChain 应用 CONFIRMED/NOT_CONFIRMED/INSUFFICIENT_EVIDENCE
```

### 一级目标完成

```text
全部二级目标完成
→ AWAITING_SUPERVISOR
→ Supervisor
→ CONFIRM / REJECT / NEED_MORE_EVIDENCE / FAIL
→ TaskChain 应用结果
```

## 14. 第一版范围建议

第一版优先实现：

```text
TaskChain
AssetRegistry
HARD_CODED 高频检测
AI_ASSISTED 检测接口
一级目标 Supervisor 接口
NUMEN Adapter
基础持久化
Level 1/Level 2 基础恢复
```

复杂资产变化推断、完整 Observation 查询系统、高级提前达成策略、细化全局熔断、监测台可视化和经验记忆库联动可后置，不改变本规范的核心边界。
