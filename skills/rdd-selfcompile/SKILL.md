---
name: rdd-selfcompile
description: |
  自变异系统（Self-Compile）：外挂式 AI 编程工具，通过启动/关闭游戏 + MCP 实际游玩验证 +
  部署 jar 实现能力自我扩展。覆盖：生成/修改 NumenTool、安全编译、打包 jar、部署 mods、
  启动 gpt 存档、MCP 驱动验证、监测台观测、止损循环。
  Use when user wants to 自编译 / 自变异 / 生成新工具 / AI 自己改代码自己测试 / 让 AI 操作游戏验证.
  Voice triggers: "自编译", "自变异", "生成工具", "自己改代码自己测".
applies_to: [windows, java, neoforge, minecraft, numen, self-compile]
depends_on: []
related: [dd-dev-sop, dd-iron-law-core]
priority: P1
usage_frequency: weekly
---

# rdd-selfcompile — 自变异系统

## 定位（方向铁律）

**自变异系统 = 你的 AI 编程工具本身（类似 Claude CLI / Codex），通过启动/关闭游戏 + MCP 实际游玩验证 + 部署 jar 实现能力自我扩展。编译只是其中一个环节，不是主体。**

曾误做成"Gradle 编译管道"，已纠正。编译（MutationCompiler 等）保留为子环节，主体是"能玩游戏的 AI 编程工具"。

## 目录

```text
外部编排层（不进 Numen 仓库）：
E:\restart developing doer\rdd-selfcompile\
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
E:\restart developing doer\minecraft-numen\plugins\selfcompile\
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
```

## 单轮闭环（期1）

```powershell
& "E:\restart developing doer\rdd-selfcompile\scripts\run-mutation.ps1" -Requirement "你的需求"
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
& "E:\restart developing doer\rdd-selfcompile\scripts\run-mutation-loop.ps1" -Requirement "xxx" -MaxAttempts 3
```

每轮独立 mutation 工作区；编译失败记录错误 → 下一轮重试；N 轮均失败 → 止损 exit 1。

## 生成工具（期2）

代码由 AI 编程工具（你自己）生成，不是套 Claude CLI。步骤：

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

## 硬门禁

```text
1. 编译成功 ≠ 行为成功
2. MCP 返回成功 ≠ 世界真变（监测台 + 世界快照对撞）
3. 未 VERIFIED 不部署生产
4. 部署生产前先 commit
5. 止损：预算耗尽即停，不无限重试
```

## 已知问题

- 中文参数经 MCP 变问号（编码问题，不影响功能）
- mcp_server.json 的 enabled 可能被游戏退出写回 false，重启前检查
- plugins/rdd 与 plugins/ac 都内嵌 ac-core jar → 同装重复类风险

## 交接

完整交接见 `E:\新建文件夹\rdd架构\self-compile-handoff.md`。开工先读它 + `self-compile-module.md`。
