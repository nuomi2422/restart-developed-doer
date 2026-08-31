# 架构修正计划：任务链协助模式（Assist Mode）

> 日期：2026-08-31
> 性质：架构级修正方案（九步流设计，已批准，✅ 已实现并真机验证，commit 1721a9cb）
> 背景：MCP 驾驶 = 内置 AI 关闭（二选一），不符合"自编译系统=上帝视角，游戏内 AI 自己干活被监察"的预期

## 一、问题

```text
当前：MCP enabled → driving()=true
  → 内置 AI 完全停轮
  → enqueue 返回 TO_EXTERNAL_BRAIN（拒绝注入）
  → 女仆失去说话+自主，只能被外脑逐工具驱动

预期（原 DD 模型）：
  自编译系统 = 上帝视角
  游戏内 AI 自己干活 + 说话
  外部只监察防卡死
```

## 二、核心设计：McpMode 分离"驾驶"与"协助"

```text
驾驶模式（Driving，原样保留）：
  外脑直接操控身体
  driving()=true → 内置 AI 关

协助模式（Assist，新增）：
  外脑喂目标/提示
  driving()=false → 内置 AI 正常开轮
  enqueue 注入目标 → 内置 AI 自己选工具执行
```

## 三、改动清单

```text
【P0 核心】
1. McpConfig：新增 assist 字段（默认 false）
   config/numen/mcp_server.json 加 "assist": true

2. McpMode：
   - driving() 只在外脑驾驶（非 assist）时 true
   - 新增 assistEnabled()

3. NumenGateway.enqueue：
   - 辅助模式下不 TO_EXTERNAL_BRAIN
   - 注入内置 AI prompt 队列（submitPrompt 已做）

4. EntityAgentLoop：
   - isExternallyDriven() 只在驾驶模式 true
   - assist 下内置 AI 正常开轮

【P0b RDD 暂停自动提交（防双驾驶）】
5. RddPlugin 加静态开关 bodySubmissionEnabled（默认 true）
   assist 模式 → false → RddDetector.maybeSubmitBody 跳过
   RDD 只保留：资产检测 / 目标完成判定 / 异常提醒
   工具调用全部交还 NUMEN

【P1 注入内容】
6. 任务链侧（RDD/自编译）：
   用 enqueue 注入"二级目标 + 一级目标 + 一句提示"
   不逐工具驱动
   ✅ MCP 新增 enqueue 工具（NumenActuator.enqueue → NumenGateway.enqueue）
      say 只是让同伴说话；enqueue 是注入任务让内置 AI 自主执行
   ✅ 真机验证：enqueue 注入"挖铁矿石" → 内置 AI 自主感知(scan/inspect)
      → load_skill → goto → 规划工具（完整 assist 链路）
   ✅ 熔炼 GUI 链路：enqueue "熔炼铁锭" → 内置 AI 自主
      inspect_gui → transfer(放铁矿+燃料) → close_gui → set_timer
      → 汇报"放进熔炉了，15秒取铁锭"
      （interact_at+transfer GUI 熔炼自主完成，无需外部驱动）

【P2 监察】
7. 外部只监察（监测台）：看内置 AI 干活，防卡死
```

## 四、边界

```text
· 不重写 EntityAgentLoop 开轮逻辑，只让 driving() 语义变准
· 不碰工具注册/AC/RDD 核心
· 驾驶模式保留原样
· 新增 assist 能力，不改既有行为
· RDD 暂停自动提交：只影响工具提交，检测/判定保留
```

## 五、验证标准

```text
assist 下：
  内置 AI 说话恢复 ✅
  enqueue 注入 → 内置 AI 自己执行 ✅
  不逐工具驱动 ✅
  RDD 不自动提交工具（不抢方向盘）✅
  外部监察防卡死 ✅
  驾驶模式仍可用 ✅
```

## 六、关键边界（用户确认）

```text
1. Assist/Driving 切换：外部可指定，默认 assist
2. NUMEN 自主范围：可偏离任务，但外部有提醒权（不强制）
3. MCP tools/call：assist 下感知/只读可用，动作类拒绝或需 NUMEN 确认
4. 🔴 双驾驶：assist 下 RDD 自动工具提交暂停，只保留检测/判定/提醒
```

## 七、对齐原 DD

```text
原 DD：自编译系统（上帝视角）→ 监察女仆干活
本修正：任务链/自编译（外部）→ enqueue 辅助 → 内置 AI 执行 → 监察
```
