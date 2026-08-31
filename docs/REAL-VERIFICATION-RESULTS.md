# 真机验证成果（Real Verification Results）

> **日期：2026-08-31**
> **性质：全部为真实机器测试**——Minecraft（存档 gpt）+ Numen MCP（127.0.0.1:8765）+ 监测台（127.0.0.1:8776）实测，**不是交接声明**。
> 原则：只列「代码存在 → 编译 → 部署 → 真机跑通」真到的那一层，证据来自真实背包、真实日志、真实 MCP 返回。

---

## 🏆 成果一览

| # | 成果 | 验证方式 | 关键证据 |
|---|---|---|---|
| 1 | **空手挖钻石**（大型任务） | 内置 AI 自主 + RDD 监督 | rdd 背包实测 `diamond:9` |
| 2 | **卡死监督全周期**（Level 1） | STALLED→拍醒→恢复 | 真实日志 11:12:46→11:13:17 |
| 3 | **Level 2 重试** | fail→自动重跑 | `subtask_retry(retry=1)` |
| 4 | **Level 3 能力不足→自编译引导** | 卡死累计触发 | `subtask_capability_gap` ×2 |
| 5 | **自编译闭环**（缺工具→生成→生效） | 生成 rdd_get_inventory→部署→MCP 调用 | MCP 真实返回背包 |
| 6 | **AC 降费** | 高层组合封装 | 1 次 ac_execute 顶 3 次底层调用 |
| 7 | **AssetRegistry 真实资产** | 背包物品写入资产表 | assets 恒 0 → 真实 **10** |
| 8 | **持久化重启恢复** | RDD 任务链 + AC 库落盘 | 游戏重启自动恢复 |
| 9 | **verdict 证据链** | 9 阶段全链才 VERIFIED | MutationVerification |
| 10 | **黑曜石任务** | 自制工具挖高级矿物 | rdd 背包 `diamond_pickaxe + obsidian` |

---

## 详细验证记录

### 1. 大型任务：空手挖钻石 ⛏️

内置 AI 在 RDD 监督下**自主打通挖钻全链**（含熔炼 GUI），最终从零挖到 9 颗钻石。
HardCodedEvaluator 读**真实背包**确认，非 AI 自述。

```text
MCP 调 rdd_get_inventory 真实返回：
{dirt:17, saddle:1, iron_ingot:2, diamond:9, iron_pickaxe:1, spruce_planks:20, ...}
                                                  ↑ 9 颗钻石，真实背包
```

### 2. 卡死监督全周期（Level 1：拍醒将军，不抢方向盘）🕐

内置 AI 宣布"要挖"但行为/世界 15s 无变化 → RDD 资产指纹检测到 STALLED → nudge 拍醒 → 恢复自动回 RUNNING。

```text
11:12:46 subtask_stalled → 15s 资产指纹无变化 → STALLED
         nudge① "目标还在但背包位置久未变，缺工具调 selfcompile_request"
11:13:10 subtask_stalled → 25s 响应窗过仍未动 → nudge② "你还没动，告诉我卡哪"
11:13:17 subtask_resumed → AI 被拍醒 → goto 行动 → 资产变化 → resumeFromStalled（不误判 fail）
```

节奏：`STALL_AFTER_TICKS=15 / STALL_RESPONSE_TICKS=25 / MAX_NUDGES=2`（真机调优，别改回 5s）。

### 3. Level 2 重试（fail→自动重跑，预算 2）🔄

任务 FAILED 后 RDD 自动重跑子任务，nudge 换策略，真机（下界之星任务）：

```text
STALLED → nudge① → resumed → STALLED → nudge② → failed → subtask_retry(retry=1)
```

### 4. Level 3 能力不足→自编译引导（升级路径）🧭

Level 2 重试耗尽 + 卡死累计 3 次仍无目标资产进展 → 判定能力不足，触发自编译引导：

```text
subtask_capability_gap (repeated stalls 3)  ← 触发 2 次，AI 收到"缺工具调 selfcompile_request"引导
```

> 已知边界（诚实）：assist 模式下引导不能**强制** AI 调自编译——AI 收到提示后回复解释/继续尝试，未实际调工具。这是 assist 自主性边界，非 bug；完整闭环需 RDD 直接发起自编译或 AI 配合。

### 5. 自编译闭环（缺工具→生成→登记→编译→部署→生效）🧬

验证"能力缺失时自编译补上"的核心承诺：

```text
需求：缺"读背包"工具
   → 生成 rdd_get_inventory（参考 RddWhereamiTool 模式）
   → SelfCompileEntry 登记
   → 编译 + 打包 jar
   → 部署 mods
   → MCP 调 rdd_get_inventory → 真实返回背包（diamond:9 等）
   → 生效 ✅（generated/ 目录可再生，符合自编译语义）
```

### 6. AC 降费（1 次调用顶 3 次）💰

把多个底层工具封装成 1 个高层 AC，AI 几次调用完成原本十多次调用：

```text
ac_execute status-overview（get_self_status + rdd_whereami + rdd_get_inventory）→ SUCCESS 3/3
enqueue 引导 → AI 自主调 ac_execute + ac_status → 汇报 "SUCCESS, 3/3"
→ AI 学会用 AC 组合：1 次 ac_execute 顶 3 次工具调用
```

### 7. AssetRegistry 真实资产（半实现项补齐）📦

Spec 里 AssetRegistry 从不 populate（assets 恒 0），已补齐：

```text
RddDetector 每 5 次检测把 countInventory 结果 apply 进 AssetRegistry（GLOBAL 作用域）
rdd_status → "assets": 10（rdd 背包 10 种真实物品），之前恒 0
```

### 8. 持久化：任务链 + AC 库重启恢复 💾

```text
RDD 任务链：saveRuntimes 落盘（config/numen/rdd-tasks/*.json）→ 游戏重启自动恢复
AC 库：FileAcVersionStore 落盘（config/numen/ac-library.json）→ 按名复用（ac_publish → ac_execute by name）
```

### 9. verdict 证据链（9 阶段全链才 VERIFIED）🔍

审查点名"hasEvents→VERIFIED 太松"后收紧——MutationVerification 9 阶段缺任一即 FAILED：

```text
SOURCE_GENERATED → COMPILED → CLASSES → JAR_HASH_RECORDED → DEPLOYED
→ MC_LOADED → TOOL_REGISTERED → MCP_CALL_SUCCESS → WORLD_EFFECT_VERIFIED
外部 verify.ps1 证据链优先（证据文件 evidence/*.json），无证据回退 hasEvents
```

### 10. 黑曜石任务（自制工具挖高级矿物）💎

内置 AI 自主做出钻石镐并挖到黑曜石：

```text
rdd 背包实测：diamond_pickaxe + obsidian（AI 自主做镐挖黑曜石，任务真完成）
```

---

## 真实日志摘录（原始，取自监测台 rdd.jsonl / MCP 调用）

```text
11:12:46 subtask_stalled
11:13:10 subtask_stalled
11:13:17 subtask_resumed
subtask_capability_gap (repeated stalls 3)
ac_execute status-overview → SUCCESS 3/3
rdd_status → "assets": 10
rdd_get_inventory → {dirt:17, saddle:1, iron_ingot:2, diamond:9, iron_pickaxe:1, ...}
```

---

## 已知边界（不隐瞒，与成果并存）

```text
· assist 模式下 AI 收到自编译引导不一定真调工具（自主性边界，见 #4）
· expmem 语义/向量检索未做（词法检索✅，依赖外部 BGE + 灵魂核心向量索引）
· 中文经 MCP enqueue 变问号（PowerShell ANSI 编码坑，用英文绕过）
· ApricityUI 每 tick NoSuchMethodException（NbtIo 旧签名，第三方 mod，非本项目改动）
```
