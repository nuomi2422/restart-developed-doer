# Self-Compile 自变异系统 · 交接文档

> 日期：2026-08-31
> 状态：期1-期3 已实现并真实验证；监测台 AI 观测已接入
> 本文件是自变异系统的**权威交接**，后续 AI 开工先读本文件 + `self-compile-module.md` + `self-compile-loop.md`。

---

## 0. 一句话定位（方向纠正后）

**自变异系统 = 你的 AI 编程工具本身（类似 Claude CLI / Codex），通过启动/关闭游戏 + MCP 实际游玩验证 + 部署 jar 实现能力自我扩展。**

编译只是其中一个环节，不是主体。曾误做成"Gradle 编译管道"，已纠正。

---

## 1. 已实现的三个期次（全部真实验证）

### 期1：单轮闭环骨架 ✅

```text
创建 mutation-id
→ build-jar 安全编译 + 打包 jar
→ deploy 部署到 mods
→ launch-mc 启动 gpt 存档
→ mcp-drive 通过 Numen MCP 调用工具
→ verify 读监测台判定 verdict
```

真实验证：MCP 端口 8765、工具列表 50 个、`selfcompile_status` 真实调用返回。

### 期2：AI 生成工具 → 真实生效 ✅

```text
我（AI 编程工具）生成 RddWhereamiTool.java
→ gen-code.ps1 落盘 + 登记 SelfCompileEntry
→ 编译打包 jar → 部署 → 重启游戏 → MCP 调用
→ 返回真实世界坐标
```

真实验证（mutation-20260831-002937）：

```text
rdd_whereami → {"dimension":"minecraft:overworld","x":165,"y":64,"z":-128,"on_ground":true}
```

### 期3：失败 → 改码 → 重试 → 止损 ✅

```text
run-mutation-loop.ps1 -MaxAttempts N
→ 每轮独立 mutation 工作区
→ 编译失败记录错误 → 下一轮重试
→ N 轮均失败 → 止损 exit 1
```

真实验证：

```text
失败路径：BrokenDemoTool 故意写错 → 捕获编译错误 → 2 轮止损 ✅
修复路径：删坏文件 + 修 bug → 编译通过 → exit 0 ✅
```

---

## 2. 目录结构

### 外部编排层（不进 Numen 仓库）

```text
E:\restart developing doer\rdd-selfcompile\
├── README.md                  ← 用法说明
├── scripts\
│   ├── config.ps1             ← 公共配置（路径/端口/token/代理）
│   ├── run-mutation.ps1       ← 单轮闭环入口
│   ├── run-mutation-loop.ps1  ← 止损循环入口（期3）
│   ├── new-mutation.ps1       ← 创建 mutation 工作区
│   ├── gen-code.ps1           ← 生成工具源码落盘 + 登记（期2）
│   ├── build-jar.ps1          ← 安全编译 + 打包 jar
│   ├── deploy.ps1             ← 部署到 mods（实验）
│   ├── launch-mc.ps1          ← 启动/关闭 gpt 存档
│   ├── mcp-drive.ps1          ← 通过 Numen MCP 调用工具
│   └── verify.ps1             ← 读监测台 + 判定 verdict
├── templates\
│   └── numentool-sys.txt      ← NumenTool 生成系统提示词
├── mutations\                 ← 每次变异工作区（自动生成）
└── report\                    ← 报告归档
```

### Numen 侧生成物

```text
E:\restart developing doer\minecraft-numen\plugins\selfcompile\
├── src\main\java\...\selfcompile\
│   ├── SelfCompileMod.java          ← NeoForge @Mod 入口
│   ├── SelfCompileEntry.java        ← NumenPlugins.register + 生成工具登记
│   ├── SelfCompileService.java
│   ├── SelfCompileStatusTool.java   ← selfcompile_status 工具
│   ├── SelfCompileRequestTool.java  ← selfcompile_request 工具
│   ├── SelfCompileMonitor.java      ← 插件自写观测（ai.jsonl）
│   ├── Mutation*.java               ← 工作区/清单/状态机/预算/静态检查/编译/产物（子环节）
│   └── generated\
│       └── RddWhereamiTool.java     ← 期2 生成的真实工具
└── src\test\java\...\               ← 测试矩阵
```

---

## 3. 关键配置

```text
MCP:      config/numen/mcp_server.json → 127.0.0.1:8765
监测台:   http://127.0.0.1:8776/
存档:     gpt（原「新的世界 (3)」已重命名，备份 gpt-backup-20260830-235328）
构建:     JDK 21 + Gradle 9.2 + 代理 127.0.0.1:7897
```

## 4. 硬门禁

```text
1. 编译成功 ≠ 行为成功
2. MCP 返回成功 ≠ 世界真变（必须监测台 + 世界快照对撞）
3. 未 VERIFIED 不部署生产
4. 部署生产前先 commit
5. 止损：预算耗尽即停，不无限重试
```

---

## 5. 监测台 AI 观测（本轮新增）

### 后端

```text
E:\TouhouLittleMaid-HeartPact\monitoring-station\server.mjs
新增 /api/numen-log 接口：
  读 latest.log（GBK）→ 解析 Numen AI 决策行
  → ai / context / tools / state 分类
```

### 前端

```text
js\numen-adapter.js 新增 ingestLog()
轮询 /api/numen-log 喂进 MonitorStore
```

### 自变异观测

```text
SelfCompileMonitor.java（插件自写 ai.jsonl，不依赖引擎内部类）
每次 selfcompile_request → 记录 mutation_id/state/requirement
```

### 真实验证

```text
AI / Thinking 分页：真实 AI 决策 + selfcompile_request 事件
Context 分页：user prompt / 上下文
看门狗：IN 125/s、队列 125
```

---

## 6. 当前已知问题（待处理）

1. **中文参数经 MCP 变问号**：`selfcompile_request` 的 requirement 中文显示为 `????`。Numen MCP 参数编码问题，不影响功能。
2. **`mcp_server.json` enabled 被写回 false**：可能被其他 AI 或游戏退出时改回，需注意。
3. **RDD 任务链无停止接口**：`rdd_submit` 提交后无法用 `task_stop` 停（那是 RDD 模块边界）。
4. **`rdd_whereami` 的 y 坐标略低**（64→63）：移动后着地高度，正常。

---

## 7. 交接给下一个 AI

### 你接手什么
- 维护/扩展 `plugins\selfcompile`（自变异插件）
- 维护 `rdd-selfcompile\scripts`（外部编排）
- 继续深化：自动改码（失败原因自动喂生成器）、更多工具模板、实验/生产双 MC 隔离

### 你不碰什么
- `ac-api` / `ac-core`（RDD 资产任务链，另一 AI）
- Numen 核心工具注册（除 selfcompile 插件自己的）
- 监测台前端结构（只读接入已做）

### 开工第一步
```powershell
# 读交接 → 读模块文档
Get-Content "E:\新建文件夹\rdd架构\self-compile-handoff.md"
Get-Content "E:\新建文件夹\rdd架构\self-compile-module.md"
# 测试自变异闭环
& "E:\restart developing doer\rdd-selfcompile\scripts\run-mutation-loop.ps1" -Requirement "xxx" -MaxAttempts 2 -SkipLaunch -SkipMcp
```

---

## 8. 相关文档

```text
self-compile-module.md     ← 模块定位 + 方向纠正 + 边界
self-compile-loop.md       ← 主循环设计
self-compile-rollback.md   ← 模块化回滚约定
self-compile-workspace.md  ← 工作区与请求阶段
ac-module-verification.md  ← AC 模块验证报告（44 单测全绿）
expmem-module-verification.md ← expmem 验证报告（20/20 + 游戏闭环）
rdd-module-verification.md ← RDD 验证报告（背包检测闭环跑通）
rdd-implementation-progress.md  ← 其他 AI 的进度
```
