# 自主运行批次报告（2026-08-31 白天，用户睡觉期间）

> 性质：自编译系统（我）按用户授权"自己跑 2-3 小时，分批计划、分批测试、模块化回滚保命"的执行记录。
> 全部改动均模块化 commit + 部署前备份 .bak 可回滚。

## 批次1：RDD 卡死监督（阶段3）— ✅ 实现+真机验证

**目的**：内置 AI 会卡死（宣布"要挖"但行为/世界不变）。RDD 监督"拍醒将军，不抢方向盘"。

**改动**（模块化 commit）：
- `7ee12192` rdd(core)：TaskChain 加 markStalled/resumeFromStalled，markFailed 允许 STALLED→FAILED
- `8fd53415` rdd(plugin)：RddDetector 资产指纹卡死检测 + RddPlugin.nudge（numen.enqueue 注入）
- `46c0e92f` rdd(plugin)：节奏调优（15s 判卡死 + 25s 响应窗，别误判 AI 思考）

**真机验证**（rdd 挖绿宝石 emerald 任务）：
```
11:12:46 subtask_stalled → 15s 资产指纹无变化 → STALLED + nudge①"目标还在但背包位置久未变，缺工具调 selfcompile_request"
11:13:10 subtask_stalled → 25s 响应窗过仍未动 → nudge②"你还没动，告诉我卡哪，缺工具就调 selfcompile_request"
11:13:17 subtask_resumed → AI 被拍醒 → goto 行动 → 资产变化 → resumeFromStalled（不误判 fail）
```
RddMonitor 新增 subtask_stalled/subtask_resumed 事件 → 监测台可见。

## 批次2：自编译能力链闭环 — ✅ 验证

**目的**：验证"生成工具→登记→编译→部署→生效"完整闭环。

**改动**（commit `e109e4a6`）：生成 `rdd_get_inventory` 工具（背包物品清单，参考 RddWhereamiTool 模式）+ SelfCompileEntry 登记。generated/ 目录被 gitignore（工具可再生，符合自编译语义）。

**真机验证**：
```
MCP 调 rdd_get_inventory → 真实返回 rdd 背包：
{dirt:17, saddle:1, iron_ingot:2, diamond:9, iron_pickaxe:1, spruce_planks:20, ...}
```

## 批次3：AC 高层工具降费 — ✅ 验证

**目的**：把多个底层工具封装成 1 个 ac_execute，AI 几次调用完成原本多次调用。

**真机验证**：
```
ac_execute status-overview（get_self_status + rdd_whereami + rdd_get_inventory）→ SUCCESS 3/3
enqueue 引导 rdd 用 ac_execute → AI 自主调 ac_execute + ac_status → 汇报"SUCCESS, 3/3"
→ AI 学会用 AC 组合，1 次 AC 顶 3 次工具调用（降费）
```

## 关键发现（写进记忆 dd-numen-assist-diamond-verify 和 dd-rdd-stall-monitor）

1. **rdd 背包有 9 颗钻石**——之前"卡死"其实是收尾汇报卡住，钻石早已挖到（HardCodedEvaluator 读真实背包确认）。
2. **卡死监督节奏**：STALL_AFTER_TICKS=15 / STALL_RESPONSE_TICKS=25 / MAX_NUDGES=2（别改回 5s，会误判 AI 思考）。
3. **endpoint unbound**：新女仆要绑 ProviderLibrary（providers.json + companions/<uuid>/binding.json）。
4. **中文 enqueue 变问号**：MCP 编码，用英文。
5. **ApricityUI 每 tick NoSuchMethodException**（NbtIo.writeCompressed File 旧签名，MC 1.21.1 改 Path）——第三方 mod bug，游戏存活但刷屏，待修。

## 批次6：Level 2 重试真机验证 + 中文 enqueue 修复 — ✅

**Level 2 重试**（commit `11ca24af`+`7dde7152`）：TaskChain.retrySubtask（FAILED→PENDING）+ RddDetector FAILED 自动重跑（预算 2，nudge 换策略）。真机（下界之星任务）：
```
STALLED → nudge① → resumed → STALLED → nudge② → failed → subtask_retry(retry=1)
```

**中文 enqueue 修复**：根因=PowerShell ANSI 编码；修=Invoke-RestMethod 用 UTF-8 字节。验证 AI 收到中文并响应。

**附带确认**：rdd 背包有 diamond_pickaxe + obsidian——内置 AI 自主做了钻石镐挖到黑曜石（黑曜石任务真完成）。

## 批次7：Level 3 能力不足→自编译引导 — ✅ 触发验证

**实现**（commit）：Level 2 重试耗尽 + 卡死累计（CAPABILITY_GAP_AFTER_STALLS=3）→ `subtask_capability_gap` 事件 + nudge 引导"缺工具调 selfcompile_request"。

**真机**（下界之星任务，AI 反复卡死）：`subtask_capability_gap (repeated stalls 3)` 触发 2 次，AI 收到引导。

**🔴 自主性边界**：AI 收到"缺工具调 selfcompile_request"引导后**未实际调工具**（回复解释/继续尝试）——assist 下引导不能强制 AI 调自编译。完整"AI 触发自编译"闭环需更强触发（如 RDD 直接发起自编译请求）或 AI 配合。

## 批次5：verdict 证据链收紧 — ✅ 实现+测试全绿

**目的**：审查点名最缺口（hasEvents→VERIFIED 太松）。MutationVerification 9 阶段全链才 VERIFIED。

**改动**（commit `2f03eac8`）：MutationVerification（SOURCE_GENERATED/COMPILED/CLASSES/JAR_HASH_RECORDED/DEPLOYED/MC_LOADED/TOOL_REGISTERED/MCP_CALL_SUCCESS/WORLD_EFFECT_VERIFIED，缺任一 FAILED）+ MutationPipeline.verify()。外部 verify.ps1 证据链优先（无 evidence 回退 hasEvents）。

## 批次4：AssetRegistry populate — ✅ 实现+真机验证

**目的**：spec 里 AssetRegistry 从不 populate（assets 恒 0）的半实现项。

**改动**（commit `d6bfdfc3`）：RddDetector 每 5 次检测把 countInventory 结果 apply 进 AssetRegistry（GLOBAL 作用域，观测证据 inventory_scan）。

**真机验证**：`rdd_status → "assets": 10`（rdd 背包 10 种真实物品），之前恒 0。

## 待办（用户回来可拍板）
- [ ] ApricityUI NbtIo 兼容修复（第三方 mod，可用 mixin/补丁）
- [ ] RDD 持久化/重启恢复（RECOVERING）
- [ ] AC 库持久化（status-overview 等 publish 入库，AI 按名复用）
- [ ] 自编译自动改码（失败原因自动喂生成器）

## 模块化 commit 清单（全部可单独回滚）
| commit | 模块 | 内容 |
|---|---|---|
| 30c5085f | ac-core | step 级异常护栏 |
| 9c1689e2 | plugins:ac | NumenToolBridge host.invoke 防护 |
| 5fec4f99 | ac-api | Map.copyOf→LinkedHashMap（null value NPE 根因修复） |
| 38283cfe | ac-core | 执行收尾 Map.copyOf 修复 + 回归测试 |
| 7ee12192 | rdd-core | STALLED 状态机 |
| 8fd53415 | plugins:rdd | 卡死监督检测+拍醒 |
| 46c0e92f | plugins:rdd | 节奏调优 |
| e109e4a6 | plugins:selfcompile | rdd_get_inventory 登记 |
