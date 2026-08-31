# 全部模块 · 真实进度总表（交叉核对版）

> 日期：2026-08-31
> 性质：各线 AI 收尾交接后，自编译系统（我）亲自交叉核对 + 真机实测的**权威总表**。
> 原则：只认「代码存在 / 已编译 / 已部署 / 真机跑通」四层中实际到的那层，不拿交接声明冒充。

---

## 一、总览

| 模块 | 代码 | 编译 | 部署 | 真机验证 | 交接文档 |
|---|---|---|---|---|---|
| AC 执行系统 | ✅ | ✅ | ✅ | ⚠️ 部分 | ✅ |
| RDD 任务链 | ✅ | ✅ | ✅ | ✅ | ✅ |
| expmem 经验 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Self-Compile 自变异 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 监测台 | ✅ | ⚠️ | ⚠️ | ✅ | ✅ |

---

## 二、逐模块真实状态（交叉核对）

### AC 执行系统（ac-api/ac-core/rdd-core + plugins/ac）

```text
✅ 代码：五批次（身份+resume/事件/schema/authoring/Numen桥接）
✅ 3 缺口修复已提交：惰性桥接(2c3f004e) / 裸JSON(2c3f004e) / rdd-core迁移(ebf8e8ed)
✅ 编译：BUILD SUCCESSFUL
✅ 部署：numen-plugin-ac jar 在 mods，ac+rdd 同装成功（包冲突解决）
✅ 真机（2026-08-31 过渡验证，绕开 NPE 工具）：
  · ac_execute 受理 6/6 稳定 ✅
  · 3 步多步 AC（equip+rdd_whereami+get_world_info）→ SUCCESS (completed_steps=3) ✅
  · 带参工具正确传参：equip_item(item_id)、collect_items(item_ids/radius) ✅
  · 异步语义：collect_items → PAUSED（正确受理）✅
  · 断点续跑：ac_resume → 续跑成功 ✅
🔴 唯一缺陷：get_self_status / get_owner_status 在多步上下文 NPE
  （已精确探明 + 记录，见 ac-multistep-selfstatus-npe.md）
  · 之前"collect_items 传参→未知参数"是参数名错误（真实是 item_ids），非桥接 bug
```

**真实结论：AC 系统核心完全可用（多步/带参/异步/续跑全验证）。唯一缺陷是 get_self_status/get_owner_status 两个工具多步 NPE，用替代工具可过渡。**

### RDD 任务链（rdd-core + plugins/rdd）

```text
✅ 代码：纯 JVM 核心 + GoalSinks 接管 + LLM 分解 + 身体执行桥 + 检测推进
✅ 编译 + 部署：rdd jar 在 mods
✅ 真机（我实测）：
  · 背包检测闭环：golden_sword 提交→检测→COMPLETED ✅
  · 长线任务：连续 3 任务全完成 ✅
  · 自动执行链：collect_items 自动调→重试≤3→markFailed（容错）✅
  · 身体桥成功/失败/重试路径全实测 ✅
✅ RddMonitor 写 rdd.jsonl（观测已生效）
```

**真实结论：RDD 完全可用，能支撑长线任务，是当前最稳的模块。**

### expmem 经验记忆（experience-core + plugins/experience）

```text
✅ 代码：纯 JVM + 20/20 单测
✅ 编译 + 部署
✅ 真机（我实测）：
  · learn/recall/verify 闭环 ✅
  · 成熟度 OBSERVED→VERIFIED→GENERALIZED ✅
  · 失败反例 counterexamples ✅
  · 去重合并（同 title 同 id）✅
  · 重启持久化 ✅
✅ ExperienceMonitor 写 expmem.jsonl（观测生效）
```

**真实结论：expmem 完全可用，六层验证全过。**

### Self-Compile 自变异（plugins/selfcompile + rdd-selfcompile）

```text
✅ 代码 + 测试（期1-3：单轮闭环/生成工具/止损循环）
✅ 编译 + 部署
✅ 真机：
  · rdd_whereami 生成并真实读坐标 ✅
  · MCP 驱动验证 ✅
  · 止损循环（编译失败→重试→止损）✅
✅ skill 已固化（每个 AI 可用）
```

**真实结论：自变异闭环能跑，生成工具真实生效。但自动改码重试未完全自动化（需外部 AI 参与）。**

### 监测台（monitoring-station）

```text
✅ 代码：server.mjs + 前端 adapter + ARCHITECTURE.md
✅ 服务：127.0.0.1:8776 运行中
✅ 真机数据流：
  · events/state/tools.jsonl 真实产生 ✅
  · rdd.jsonl/expmem.jsonl（模块观测）✅
  · AI 分页对话式 / AC 分页 RDD 事件 / 架构分页 ✅
⚠️ 未完全生效：
  · context.jsonl / AI 上下文埋点：代码已部署（EntityAgentLoop 3 处 publish）
    但 MCP 外部驾驶下内置 AI 停 → 不触发，需内置 AI 对话才出现
  · game 内 M 键 MonitoringScreen：源码写了，未真机按 M 验证
  · tool_result 埋点：未加
  · 控制按钮：全 UNSUPPORTED（只读）
```

**真实结论：监测台能看世界/工具/模块事件，但 AI 上下文和 tool_result 埋点未完全生效（非全功能）。**

---

## 三、各线交接声明 vs 我的实测

| 声明 | 实测 | 一致？ |
|---|---|---|
| AC 3 缺口修复 + 测试全绿 | ✅ 提交在，编译过；但真机带参/多步仍有 NPE | 部分（修复了已列缺口，遗留待办真实存在）|
| RDD 验证通过 | ✅ 完全一致（我复验长线+战斗） | ✅ |
| expmem 六层验证通过 | ✅ 完全一致 | ✅ |
| 监测台"AI thinking/prompt 埋点未完成" | ⚠️ 其实代码已部署，只是未触发 | 需修正（已部署但未生效）|
| 监测台"未完成最终编译部署" | ⚠️ 现已构建部署（我做的），jar 含观测 | 已过时（交接时的状态，现已部署）|
| 监测台 M 键源码写了未验证 | ✅ 属实 | ✅ |

---

## 四、真实待办（自编译系统接手时按优先级）

### P0（阻塞核心价值）
```text
1. AC 多步执行 NPE（get_self_status/get_owner_status 多步上下文）—— 可替代工具过渡
```
> 2026-08-31 已解决：
> · AC 观测接入（AcMonitor → ac.jsonl，commit 4a197be1）
> · tool_result 埋点（ExecuteToolPayload 服务端 → tools.jsonl，commit 74103a0b）
> "AC 带参/多步桥接是短板"已修正——3步AC SUCCESS 验证，多步/带参/异步/续跑全可用。
> 监测台现在能看到工具返回结果（tool_result）。

### P1（重要）
```text
4. AI 上下文/prompt 真机确认（需内置 AI 对话，非 MCP 外脑）
5. 游戏内 M 键 MonitoringScreen 验证
6. AC/RDD 同装启动复验（修复后没全量启动验证过）
```

### P2（增强）
```text
7. ac_cancel 门面
8. 自动改码重试（自变异期3完全自动化）
9. 控制按钮开放（当前只读）
```

---

## 五、一句话总结

**四个模块的纯 JVM 核心全部扎实（单测/编译/部署/真机验证大部分通过），RDD 和 expmem 完全可用，真实战斗可用（scan/goto/attack 击杀闭环）。**

**最大短板是 AC 的带参/多步工具桥接**——它让"一键挖矿"类任务跑不了，这正是交接文档里"遗留待办"对应的真实问题。监测台能看世界/工具/模块事件，但 AI 上下文和 tool_result 埋点未完全生效。
