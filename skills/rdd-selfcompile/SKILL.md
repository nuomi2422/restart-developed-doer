---
name: rdd-selfcompile
description: |
  自变异系统（Self-Compile）：外挂式 AI 编程工具，通过启动/关闭游戏 + MCP 实际游玩验证 +
  部署 jar 实现能力自我扩展。覆盖：生成/修改 NumenTool、安全编译、打包 jar、部署 mods、
  启动 gpt 存档、MCP 驱动验证、监测台观测、止损循环。
  Use when user wants to 自编译 / 自变异 / 生成新工具 / AI 自己改代码自己测试 / 让 AI 操作游戏验证.
  Voice triggers: "自编译", "自变异", "生成工具", "自己改代码自己测",
  "观察者", "并行观察", "看看工具有没有bug", "检索AI行为", "工具调用情况".
applies_to: [windows, java, neoforge, minecraft, numen, self-compile]
depends_on: []
related: [dd-dev-sop, dd-iron-law-core]
priority: P1
usage_frequency: weekly
---

# rdd-selfcompile — 自变异系统

## 定位（方向铁律）

**自变异系统 = 你的 AI 编程工具本身（类似 Codex CLI / Codex），通过启动/关闭游戏 + MCP 实际游玩验证 + 部署 jar 实现能力自我扩展。编译只是其中一个环节，不是主体。**

曾误做成"Gradle 编译管道"，已纠正。编译（MutationCompiler 等）保留为子环节，主体是"能玩游戏的 AI 编程工具"。

## 目录

```text
外部编排层（不进 Numen 仓库）：
D:\rdd\workspace\rdd-selfcompile\
├── scripts\
│   ├── config.ps1             ← 公共配置（路径/端口/token/代理）
│   ├── run-mutation.ps1       ← 单轮闭环入口
│   ├── run-mutation-loop.ps1  ← 止损循环入口（-MaxAttempts N）
│   ├── new-mutation.ps1       ← 创建 mutation 工作区
│   ├── gen-code.ps1           ← 生成工具源码落盘 + 登记
│   ├── build-jar.ps1          ← 安全编译 + 打包 jar
│   ├── deploy.ps1             ← 部署到 mods（实验）
│   ├── launch-mc.ps1          ← 启动/关闭 gpt 存档
│   ├── mcp-drive.ps1          ← 通过 Numen MCP 调用工具
│   └── verify.ps1             ← 读监测台 + 判定 verdict
├── templates\numentool-sys.txt ← NumenTool 生成系统提示词
└── mutations\                  ← 每次变异工作区

Numen 侧插件：
D:\rdd\workspace\minecraft-numen\plugins\selfcompile\
└── src\main\java\...\selfcompile\
    ├── SelfCompileEntry.java   ← 插件注册 + 生成工具登记
    ├── SelfCompileMonitor.java ← 自写 ai.jsonl 观测
    ├── Mutation*.java          ← 子环节（工作区/状态机/预算/静态检查/编译/产物）
    └── generated\              ← 生成工具落点（RddWhereamiTool 等）
```

## 关键配置

```text
MCP:      config/numen/mcp_server.json → 127.0.0.1:8765（enabled=true 需重启游戏生效）
监测台:   http://127.0.0.1:8776/（读 config/numen/monitor/）
存档:     gpt（原「新的世界 (3)」）
构建:     JDK 21 + Gradle 9.2 + 代理 127.0.0.1:7897
浏览器:   @playwright/mcp 全局 + Edge CDP；外脑=DeepSeek 专家模式 + ChatGPT 免登录（见「外脑环节」）
看门狗:   launch-mc.ps1 -AutoStopMinutes 120 自动在游戏启动后 2h 关 MC 防掉线耗电
观察者:   rdd-selfcompile\scripts\verify-tools\analyze-ai-behavior.js（只读行为分析）
视觉:     skill mc-visual-verify（操控视角）/ mc-companion-cam（镜头挂同伴身上）
```

## 单轮闭环（期1）

```powershell
& "D:\rdd\workspace\rdd-selfcompile\scripts\run-mutation.ps1" -Requirement "你的需求"
```

流程：

```text
创建 mutation-id
→ build-jar 安全编译 + 打包 jar
→ deploy 部署到 mods
→ launch-mc 启动 gpt 存档
→ mcp-drive 通过 MCP 调用工具
→ verify 读监测台判定 verdict
→ 产出 verdict.json
```

跳过游戏：`-SkipLaunch -SkipMcp`（只编译+部署+验证）。

## 止损循环（期3）

```powershell
& "D:\rdd\workspace\rdd-selfcompile\scripts\run-mutation-loop.ps1" -Requirement "xxx" -MaxAttempts 3
```

每轮独立 mutation 工作区；编译失败记录错误 → 下一轮重试；N 轮均失败 → 止损 exit 1。

## 外脑环节（Playwright 网页外脑，2026-09-19 已接入）

**模块接口已开启**（曾误写「未开启」，已修正）：本机 `@playwright/mcp@0.0.79` 全局安装 + `npx playwright` 1.63.0 + Edge CDP 驱动通道。外脑固定两家：

| 外脑 | 窗口/登录态 | 链路要点 |
|---|---|---|
| DeepSeek（专家模式） | chat.deepseek.com，登录态在浏览器窗口 | 输入框 placeholder="给 DeepSeek 发送消息"，submit 后 ~15s，抓回复正文；模式选择器只在首页，确认专家模式 checked |
| ChatGPT（免登录） | chatgpt.com | 先清 openai 域 cookie（chatgpt.com/openai.com/auth0.openai.com），否则残留 OAuth 会跳 Google 登录死循环 |

**外脑铁律**：
1. **单窗口单对话**：每个网页只用固定一个专属自编译对话，养好上下文；补充完上下文后后续都复用该对话，不每次重开。
2. 开启思考/推理模式后再问；记录摘要意见和来源，不索要隐藏思维链。
3. 外脑回答当参考不当真理（会编造函数名）；涉及代码必须对着真实源码核。
4. 干完后记录会话摘要，后续都用这个已养好的窗口继续。
5. 用法详见记忆 `dd-deepseek-web-playwright`；GitHub/网页验证可用 `websearch`/`webfetch`。

**驱动脚本**（`scripts\brain-drive.js`，依赖 `@playwright\mcp\node_modules` 内的 playwright，经 `channel:'msedge'` 驱动 Edge 持久化 profile `C:\Users\admin\.playwright-profile`）：

```powershell
$env:NODE_PATH="D:\rdd\tools\node\global\node_modules\@playwright\mcp\node_modules"
node scripts\brain-drive.js probe                                   # 枚举窗口/标签
node scripts\brain-drive.js ds "问题"                               # DeepSeek 发问（首次需在浏览器手签登录）
node scripts\brain-drive.js gpt "问题"                              # ChatGPT 免登录发问（自动清 openai cookie）
# 回复落盘 %TEMP%\opencode\brain-replies\<tag>-<ts>.json，读该文件取正文
```

注意：DeepSeek 登录态在 `--sign_in` 页（需人工登录一次，登录态持久化到 .playwright-profile）；ChatGPT 免登录无需账号（先清 cookie 防跳 Google 登录）。

## 生成工具（期2）

代码由 AI 编程工具（你自己）生成，不是套 Codex CLI。步骤：

1. 写 NumenTool 源码到 `plugins\selfcompile\src\main\java\...\generated\<ToolClass>.java`
2. `gen-code.ps1` 落盘 + 登记 SelfCompileEntry（或在 SelfCompileEntry 手动加 `numen.registerTool(new <ToolClass>())`）
3. build-jar → deploy → 重启游戏
4. MCP 调 `tools/list` 确认工具出现，`tools/call` 真实调用

NumenTool 接口（瘦 API jar 隔离）：

```java
public interface NumenTool extends IToolSpec {
    String name();                          // snake_case [a-z0-9_]{1,64}
    String description();
    Map<String,Object> parameterSchema();   // JSON Schema
    default void invoke(ToolCall call) { ServerToolTransport.ship(call); }
    default void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) { ... }
}
```

生成模板见 `templates\numentool-sys.txt`。

## MCP 验证要点（硬铁律）

1. **MCP 工具调用必须带 companion 参数**（name 或 UUID），纯查询工具也要。
2. **世界未就绪时 create_companion 返回 "is the game in a world?"** → 等世界加载完（通常 MCP 端口起来早于世界）。
3. **list_companions 加载阶段会超时** → 启动后留足世界加载时间（1-3 分钟）。
4. **MCP 返回成功 ≠ 世界真变** → 必须用世界快照对撞（如 rdd_whereami 移动前后坐标变化）。
5. **编译成功 ≠ 行为成功**。

## 监测台观测

```text
/api/numen-log   读 latest.log（GBK）→ 解析 Numen AI 决策行 → ai/context/tools/state
/api/snapshot    读 config/numen/monitor/*.jsonl → events/state/tools/ai
SelfCompileMonitor 每次 selfcompile_request 写 ai.jsonl（自变异活动可见）
```

## ★ 观察者（就是这一套流程，不是别的什么东西）

```text
① 视觉一直开着，镜头锁定它
② 一直看它干活
③ 看到问题了（卡住、上不来、乱传参数、报错）
④ 直接去改代码
⑤ 改完再看一遍，确认问题没了
```

就这样，没有别的。

**改代码走安全通道**（唯一入口，自动编译 + 部署 + 重启）：

```powershell
& "D:\rdd\workspace\rdd-selfcompile\scripts\run-mutation.ps1" -Requirement "<刚才看到的问题>" -Module <模块>
```

**改之前必做三件**：备份原文件 · 说清改了多少、会不会引起什么问题 · 打版本标记。不能乱改。

### 三条别搞混的

- **修好工具 ≠ 解决了经验注入。** 工具坏（卡住、上不来、报错）是工具层；「经验带进来了没用上」是注入层，另一回事。
- **看之前先翻一遍经验库的「已知的坑」**，带着假设去看，省得看见了不认得。
- **别把「改工具」和「换模型/API」放一起做。** 之前量出来的效果全是旧模型的，换了就作废；而且免费模型可能压根不发 `tool_call`。先把在验的事收口，再单独换。

### 看不清的时候，捞日志（辅助，不是主路径）

**视觉是主路径。** 日志只在「看不明白它到底干了什么」时用来捞线索：

```powershell
node rdd-selfcompile\scripts\verify-tools\analyze-ai-behavior.js --since 6h   # 说明见同名 .md
```

- 监测台 `http://127.0.0.1:8776/`（判中文功能必须看这里，游戏日志是 GBK）
- 视角操作：skill `mc-visual-verify`（操控窗口）／`mc-companion-cam`（镜头挂它身上）
- 键鼠注入：`oc-send.js --probe` / `--burst`。⚠ `pressKey` 只实现了 7 个键，T（聊天）/ F5（第三人称）注不进去

## 硬门禁

```text
1. 编译成功 ≠ 行为成功
2. MCP 返回成功 ≠ 世界真变（监测台 + 世界快照对撞）
3. 未 VERIFIED 不部署生产
4. 部署生产前先 commit
5. 止损：预算耗尽即停，不无限重试
6. 没备份 + 没版本标记 → 不许改任何文件
7. 修工具与换模型不许混着做（换模型 = 重置基线）
8. 观察者不许不带清单下场（先读经验库已知坑 → 带假设 → 验复现）
```

## 已知问题

### 已确认的工具层缺陷（2026-10-01 在 live 数据上实测，不是猜的）

**7 个工具永久零结果** —— 从日志里根本判断不出它们成功没有：

`interact_at`(929 调/894 零) · `equip_item`(230/227) · `drop_items`(83/65) · `locate_structure`(28/27) · `locate_biome`(27/27) · `use_portal`(9/6) · `interact_entity`(7/7)
（对照：`goto` 1327 调 / 1313 结果，正常）

**Java 崩溃（铁 bug）**：`ac_status` NPE ×16 · `transfer` `Expected BEGIN_ARRAY but was BEGIN_OBJECT at $.moves` ×5 · `craft`+`lookup_recipe` `String.indexOf(int) because "p_352469_" is null` ×4 · `rdd_whereami` / `rdd_submit` NPE ×2 each

**校验脱节**：`scan_nearby_entities` 声明要 `type_filter` 但实际只认 `filter` · `scan_blocks` / `mine` 报 `block_ids` 无效但结果用的是 `filter` · `equip_item` 必填 `item_id` 没强制 · `interact_at` 会收 `unknown item: minecraft:air`

### 埋点层缺陷（不是工具 bug，但让人判不了）

- **`tool_result` 的 result 被截断到 303 字符** → 长失败原因只剩半句，判失败要靠 `tool_rejected` 事件
- **`tool_call_id` 不唯一** —— `mcp-N` 每会话重置，`mcp-1` 复用 114 次。不能当全局主键
- **`tool_call` 只记 `args_chars` 不记参数内容** → 看不到传的坐标/物品名

### 其他

- 中文参数经 MCP 变问号（编码问题，不影响功能）
- mcp_server.json 的 enabled 可能被游戏退出写回 false，重启前检查
- 第一代 AC（`plugins/ac` + `ac-api` + `ac-core`）已于 2026-10-08 删除，AC 执行层只有 `plugins:acx`；插件彼此不可见（`numen-plugin.gradle` 刻意封死跨插件 import），唯一门面 = `NumenApi`
- ⚠ **Numen MCP 驱动会令内置 AI 停轮**，无法与同伴 AI「共存」（`12-进游戏验证工具集.md:185`）

## 交接

完整交接见 `D:\rdd\docs\rdd-docs\rdd架构\self-compile-handoff.md`。开工先读它 + `self-compile-module.md`。
