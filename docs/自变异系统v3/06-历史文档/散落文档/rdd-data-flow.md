# RDD 系统数据流（assist 架构修正后 · 2026-08-31 更新）

> 性质：只读代码梳理。assist 模式下 RDD 职责边界变化：暂停自动工具提交，只做检测/判定/提醒。

## 角色（架构修正后）

```text
RDD = 军师（规划/检测/提醒）
NUMEN 内置 AI = 将军（执行/说话）
外部 = 上帝视角（监察）
```

## 完整数据流

### 入口A：MCP rdd_submit（显式提交）

```text
外部 → RddSubmitTool.onServerCall
  → 校验 goal/primary_goal/subtask/asset_key/minimum（+可选 task_type/args）
  → Subtask.hardCoded(...) + parseBody(task_type/args)
  → RddPlugin.bind(companionUUID, goal)    ← 建 RddRuntime(TaskChain, AssetRegistry)
  → runtime.startCurrent()                  ← 启动第一个二级
  → 回执 accepted
```

### 入口B：游戏 /goal（GoalSinks 接管）

```text
游戏内 /goal X → GoalSinks.dispatch
  → RddDecomposer.decompose（LLM 分解 或 占位链）
  → bind → startCurrent
```

### 检测驱动：RddDetector.onServerTick（每 1 秒）

```text
遍历 NumenPlayer → 有 RddRuntime 且 primary=ACTIVE
  → 二级 PENDING → startCurrent()
  → 🔴 assist 下（bodySubmissionEnabled=false）跳过 maybeSubmitBody（不自动提交工具）
     驾驶模式（true）→ maybeSubmitBody 自动提交（collect_items/mine）
  → countInventory（读真实背包）
  → HardCodedEvaluator.matches（判定）
      ├─ 满足 → completeSubtask → subtask_completed → 全二级完成 → goal_completed
      └─ 不满足 → maybeRetryOrFail
          → assist 下不重提，直接判失败提醒
          → 驾驶模式重试≤3 再 markFailed
```

### 关键分支：assist 下 RDD 只做 3 件事

```text
✅ 资产检测：countInventory + HardCodedEvaluator.matches
✅ 目标完成判定：completeSubtask / goal_completed
✅ 异常提醒：RddMonitor（subtask_failed 等）

❌ 不再做：自动提交工具（maybeSubmitBody / maybeRetryOrFail 提交）
  → 工具执行交还 NUMEN 内置 AI
  → 双驾驶解除
```

## 状态机（TaskChain）

```text
Subtask:  PENDING → RUNNING → COMPLETED / FAILED
Primary:  PENDING → ACTIVE → AWAITING_SUPERVISOR → COMPLETED/REPLANNING/WAITING
推进：applyHardCodedResult（HARD_CODED）/ applyAiAssistedResult（AI_ASSISTED）
Supervisor：CONFIRM→COMPLETED / REJECT/REPLAN→REPLANNING / NEED_MORE_EVIDENCE→WAITING
```

## 观测出口（✅ 已接）

```text
RddMonitor → config/numen/monitor/rdd.jsonl
  类型：body_submitted / body_submit_failed / subtask_completed /
        goal_completed / subtask_retry / subtask_failed
→ 监测台 AC 分页显示
```

## 与 NUMEN 的协作（assist）

```text
RDD（军师）                    NUMEN（将军）
  │ enqueue 注入目标/提示  ───────→ 自主感知/规划/执行
  │ 检测背包/判定完成       ←────── 工具执行结果
  │ 异常提醒               ───────→ 重新规划
  │ 监察（监测台）          ←────── 干活状态
```

## 关键契约（assist 后）

```text
· RDD 不碰工具执行（bodySubmissionEnabled=false 时）
· 状态唯一写入者 = TaskChain；AssetRegistry 只管资产快照
· 防假成功：检测读真实背包（countInventory），不认 AI 自述
· 开关：RddPlugin.setBodySubmissionEnabled（外部在 assist 时调用）
```

## 已探明的点

```text
· assist 下内置 AI 自主完成：感知→挖矿→做工具→熔炉→GUI熔炼→汇报 ✅
· RDD 检测闭环：golden_sword 提交→检测→COMPLETED ✅
· 长线能力：连续多任务 ✅
```
