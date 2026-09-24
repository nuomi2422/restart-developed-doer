# rdd 自编译 V3 · 暗号装配与零成本线（索引台账）

> 位置：`E:\新建文件夹\rdd架构\`（AGENTS.md 七/八 的完整内容落点，不内联在 AGENTS 里）。
> 每个暗号对应一个 skill；内容放规定位置，AGENTS 只放「触发词→落点」索引。

## 暗号索引

| 暗号 | 含义 | skill 落点 | 触发词 |
|---|---|---|---|
| 数字1 | 长任务轮询通关 MC：跑数小时真实游戏，遇可用 MCP 指令就推进，记录并完善 Numen 能力；死亡/失败经验收集为规划一手资料（全量注入，暂不做检索） | `C:\Users\lenovo\.config\opencode\skills\mcp-mc-quest\SKILL.md` | 通关MC / 数字1 / 长任务轮询 |
| 数字2 | 目标边界补齐模块模式：自动补全架构细节，只写单模块 | `C:\Users\lenovo\.config\opencode\skills\module-blueprint\SKILL.md` | 模块模式 / 补齐模块 / 数字2 |

## 分工链（代码升级降级）

| 档位 | 谁写 | 谁审 | 门禁 |
|---|---|---|---|
| 工具级 | 免费 AI 自主 | 测试记录必写 | 直接面向上线行为验证 |
| 模块级(Bounded) | 外脑池(DS/GPT 网页)参议 + 免费执笔 | 外脑 + 免费交叉审 | 工作流文档先行 |
| 难复杂 | Codex(GPT-6-Astra) | 多轮后 GPT 必须审码 | 完整九步流 + 证据 |
| 最高难 / 高风险 | 留人工待批 | 人工 | 先跳过，不越权 |

- 免费模型额度 429 或不可用 → 自动换下一个 provider（`opencode.jsonc` 多 provider 轮换池模板）。
- Codex / 外脑没接好前，最高难任务以「留下清晰交接与人工待批标记」收尾。

## 零成本线（外脑全传工作流）

1. **代码全传**：核心代码工作职责文件全传 DeepSeek 网页端（项目开源无所谓；发送描述细一点、不丢数据）。记下会话上下文。
2. **参议修法**：把已知 Bounded 问题发过去，两边（外脑 + 免费 AI）讨论出修法计划 → 免费 AI 写代码 → 测试。
3. **审改轮回**：测试完把全部代码更新回传 DeepSeek 审核；有问题继续改，直到稳定再进下一轮。
4. **升级</dev>降级**：遇到架构级（Architectural）→ 降级降档：给 Codex(GPT-6-Astra) 记档，不硬写；CoDex 可加入讨论/代写。干完难档再降级回工具级路线。
5. **外脑铁律**：回答当参考不当真理（会编造函数名），涉及代码必须对着真实源码核；记录意见摘要与来源。

## Codex(GPT-6-Astra) 预留（当前仅记下，未接通）

- **固定审查窗口**：每 1 小时 或 每 4 轮激活一次审查，用输入脚本触发；审查后再继续下一轮。
- **重试机制**：改多次重试（100 次）应对中转 API 不稳、回复缓慢。
- **能力边界**：容易高质量写代码/审架构；没接好前最高难任务留人工待批。
- 开放大脑链路：Claude/opencode 外脑当前仅 DeepSeek 可用；Codex 属「全城」档，暂不激活因预算。

## 干完一轮必须

1. 查软件工程七规范（分类自报/查消费者/边界测试/debug/真实证据/模块 commit/不无证据宣称）
2. 关 MC（防空转耗电；含 MC 进程 + MCP 端口 + 监视台）
3. 写测试日志（`E:\新建文件夹\rdd架构\测试日志.md`）
4. 全源码更新传 DeepSeek 审（零成本线第 3 步）
5. 回 AGENTS.md 完成索引逐条勾选

## 复活脚本（防死第一序）

- `E:\restart developing doer\rdd-selfcompile\scripts\every-10min-drive.ps1`：
  常驻 watchdog，每 600s 向 opencode 窗口注入「继续」，防免费模型限流后长任务静默停摆。
- 启动：`Start-Process -WindowStyle Hidden powershell ... every-10min-drive.ps1 -Loop -IntervalSec 600`
- 核查：`Get-Process -Id <watchdogPid>` 或 `drive-heartbeat.log` 递增。
- 长任务（数字1 通关/通宵）没有它不得开。