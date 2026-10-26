# restart-developed-doer（RDD）

> **这是一个开源工程，不是 Mod。**
> 一套「住在 Minecraft 里的 **AI Agent Runtime / 自编译系统**」——
> 宿主 AI 在游戏里自主干活，任务链监督、经验沉淀、世界观测、缺能力时自编译补上，**全程可观测、可验证、可回退**。

<p align="center"><b>宿主</b>：<a href="https://github.com/Dwinovo/minecraft-numen">Numen</a>（NeoForge 1.21.1）　·　我们的宿主 fork：<a href="https://github.com/nuomi2422/minecraft-numen">nuomi2422/minecraft-numen</a>（分支 <code>redline-1</code>）</p>

> **本文是索引**：三条主线路 + 宿主执行层 + 对齐补强 + 11 个模块各一句话意图，细节全部链接到本仓真实文档。
> 依据内部总览《对外展示：架构、数据流、工程纪律与实验数据》（2026-10-25 版）精简重写；旧版 README 已过时，由本文取代。

---

## 0 · 一句话

**军师（任务链）给目标 → 将军（宿主 AI）自主执行 → 监测台监察 → 卡死时拍醒 → 缺工具自编译补上 → 死过的坑沉淀成经验，下次注入。**

不是"会玩 MC 的 NPC"，而是**自主 Agent Runtime**：判定不信任模型的嘴——只认真实背包与世界事实。
北极星：让同伴**从零自主完成复杂任务**（挖钻石 / 铁甲全链 / 村庄安家）并**自我进化**，且「变聪明了没有」每一步都能被**诚实复核**。

规模（截至 2026-10-25 扫盘）：**1,481 个 `.java` / 175,060 行**，381 个测试文件 / 3,070 个 `@Test`；
**1,671 次提交 / 156 个 tag**；186 次变异编译、167 条部署流水；**657 份文档**（含 68 份实机验证档案）；
观测数据 75 个 JSONL / 1.04 GB（事件 13,931 条 rdd + 6,811 条 ACX + 12,425 个 AI 回合）。

---

## 1 · 借宿主声明（Important）

本项目**借用** [Numen](https://github.com/Dwinovo/minecraft-numen) 作为运行宿主。

- Numen 是**其原作者的开源项目**，本仓**不包含 Numen 原版源码**（需按上游自行拉取/构建）。
- 本项目在 Numen 之上搭一层**模块化扩展系统**，经公共 API / `Adapter·Port` 与宿主隔离——未来宿主可替换为任意 AI / 环境执行系统。
- 本仓收录：**自有源码**（`src/`）+ **对宿主的改动 patch**（`patches/`）+ 架构文档 + 编排脚本 + 监测台。
- **我们对宿主改了什么**：
  - 新增的接缝文件（必须住在宿主包路径下的，59 个）→ [`src/_host-touch/`](src/_host-touch/)；
  - 改动宿主已有文件的差分（146 个文件的 diff）→ [`patches/numen-host-modifications.patch`](patches/numen-host-modifications.patch)；
  - 逐模块交代见 [`MODIFICATIONS.md`](MODIFICATIONS.md)（含回滚方式）。

---

## 2 · 主线路：三条线 + 两块底座

### 线路① · 外部编码 AI 自主编译线（宿主外的工程闭环）

**谁在进化系统本身**：外部编码 AI（不是游戏里的 AI）。

```text
需求 → 写码 → run-mutation.ps1 唯一编译通道（6+1 步）→ 部署（落 deploy-ledger.jsonl）
    → 起游戏实机验证 → MCP 实操取证 → 回填验证卡 → 提交
进游戏前 preflight（18 项）→ BLOCK=0 才放行；退游戏后 postflight（17 项）→ 差什么补什么
```

- **禁止手写 gradle / 手拷 jar**：没有变异记录，后面所有门禁都判不了；
- **不信 `DEPLOY_OK` 一句话**：判据是打开目标 jar 查字节码/常量；
- 游戏内侧只有一个接缝：`selfcompile_request` 把游戏内 AI 的需求写进审计目录（只登记，不执行）。
- 深入：[`skills/rdd-selfcompile/SKILL.md`](skills/rdd-selfcompile/SKILL.md) · [`scripts/`](scripts) · [`docs/规范/01-工程规范.md`](docs/规范/01-工程规范.md) · [`docs/运维/开发者手册.md`](docs/运维/开发者手册.md)

### 线路② · 内部学习线（经验怎么攒、怎么送、怎么算数）

```text
自然事件（死亡/工具失败/慢任务）→ 自动备忘（风暴抑制不丢样本，冷却后放回）
    → 复盘（强制 ≥3 个候选原因对反例 + 「缺席行动」显式化）→ 经验草稿 / 可执行脚本草稿
    → 人批准（设计上的闸门）→ 落库（成熟度 OBSERVED → VERIFIED）
    → 检索回注下一轮请求 → 行为出现可测差异 → 使用账五态结算 → 反例门阻止劣质晋级
```

- **七字段经验**（mechanism / preconditions / failureConditions / observableSignal / derivation / efficiency / evidence），`stableKey = type|title` 去重合并；
- **渲染 ≠ 送达**：投递账本只认真实工具回复；晋级只认账本，不认「呈现过」；
- **复盘会自己认出「这是顺序问题」**并产出脚本，不只认「缺知识」。
- 深入：[经验记忆架构](docs/架构/rdd-experience-memory-architecture.md) · [05 记忆库与经验注入](docs/规范/05-记忆库与经验注入.md)

### 线路③ · 内部超长任务链执行线（超长任务怎么自主走完）

```text
三层目标 Goal → PrimaryGoal → Subtask（懒展开：到达该级才生成下一级）
生成期定「形状」（LLM + 三道确定性守卫）→ 检测期「结算」（每拍纯函数判定）
hold：现在手里够不够 ｜ acquire：这一步有没有真弄到（净增量；无基线一律判未达成）
卡死 → 拍醒 → 多次无效升级失败 ｜ 提前达成 → 同 tick 连跳 ｜ 支线 → 冻结主线不重写计划
```

- 判定**只认真实背包**：不认模型自述、不认工具回执；
- 基线三态：UNKNOWN（观测失败，判负）≠ 确认真空（合法零基线）——「没观测到」和「观测到是零」是两件事；
- 深入：[任务链最终架构](docs/架构/任务链最终架构与AI施工说明-20261007.md) · [任务链数据流](docs/架构/taskchain-data-flow.md) · [懒展开设计](docs/架构/rdd-lazy-multilevel-plan-design.md) · [61 功能线全景](docs/规范/61-任务链功能线全景与回归固化方案-20261002.md)

### 底座 A · 宿主自己执行层（将军）

Numen 宿主内脑 `EntityAgentLoop`：**感知 → 上下文组装（必知区 / 可丢段 + 预算降档）→ LLM → 54 个宿主工具 + 插件工具 → 真实世界改变**。
宿主引用公开（上游与本仓 fork 见页首链接）；本仓不含宿主原版源码，只含接缝文件与 patch。
深入：[numen-mcp 机制](docs/架构/numen-mcp-机制-源码核对版.md)

### 底座 B · 对齐补强（凭什么可信）

- **观测面 ≠ 执行面**：监测台只读不写任务状态；`tool_call` 与 `observation` 并置；
- **35 条门禁**查实物与痕迹（jar 时间戳 / 部署流水 / 证据坐标 `文件:行号`），不查文档声明；
- **红线 tag → 回退点 → 回退后必跑**；**时间锁**：决定「不修」也要写到期日，到期即 BLOCK；
- **三态纪律**：✅ 已完成（有证据）／ 🟡 已写码未实测 ／ ⬜ 计划中——编译成功 ≠ 已完成。
- 深入：[13 埋点与监测台链路](docs/规范/13-埋点与监测台链路.md) · [19 游戏内监察方法论](docs/规范/19-游戏内监察方法论.md) · [监测台架构](monitoring-station/ARCHITECTURE.md)

---

## 3 · 模块索引（意图 + 去哪看细节）

所有插件 = 独立 jar，可单独部署与回滚；跨插件只能走文件契约（`numen-plugin.gradle` 封死跨插件 import）——**任何模块坏掉都不会把别的模块带死**。

| # | 模块 | 意图（一句话） | 深入 |
|---|---|---|---|
| 1 | **rdd**（任务链） | 军师：目标分解、硬判定（hold/acquire + 基线三态）、需求缺口、协商、支线、拍醒 | [架构](docs/架构/任务链最终架构与AI施工说明-20261007.md) · [spec](docs/架构/rdd-asset-task-chain-spec.md) |
| 2 | **experience**（经验记忆） | 可审计经验库：七字段、成熟度、发布资格、撤回、反例门、统一检索 | [架构](docs/架构/rdd-experience-memory-architecture.md) |
| 3 | **learner**（学习闭环） | 事件→备忘→复盘→投递箱→使用账；风暴抑制不丢样本；复盘多假设 + 缺席行动 | [05 注入](docs/规范/05-记忆库与经验注入.md) |
| 4 | **acx**（脚本执行层） | 把「按什么顺序做」固化成可执行脚本：契约、熔断、断点续跑、版本闸、人工批准 | [72 脚本规划](docs/规范/72-AC经营脚本第一批-规划-20261004.md) · [73 接线架构](docs/规范/73-最终接线架构与分批实机验收方案-20261004.md) |
| 5 | **worldstate**（世界观测） | 只读世界事实：新鲜度、零/部分/未加载三分、预算明确拒绝、按世界隔离 | [设计](docs/架构/世界观测V1-设计与运维-20261010.md) · [实机](docs/验证/世界观测V1-实机验证-20261018.md) |
| 6 | **settlement**（基地与设施） | 基地登记、三层验收、位置级保护、过门关门 | [交接](docs/架构/settlement-基地与设施-交接-20261009.md) |
| 7 | **camera**（numencam） | 跟拍/附身/录像：视觉取证（故意不进插件系统，保持独立） | [70 相机模块](docs/规范/70-相机独立模块numencam与实测记录-20261004.md) |
| 8 | **selfcompile**（自变异） | 游戏内 AI 提需求 → 外部工程闭环补上能力 | [SKILL](skills/rdd-selfcompile/SKILL.md) |
| 9 | **ai**（宿主大脑接线） | 回合循环、上下文预算、检索注入——宿主侧接缝见 `src/_host-touch/` | [numen-mcp](docs/架构/numen-mcp-机制-源码核对版.md) |
| 10 | **monitoring-station**（监测台） | 观测面不是执行面：事件流 / 请求正文 / 实验统计，只读 | [INTRO](monitoring-station/INTRO.md) |
| 11 | **支线系统**（side tasks） | 睡觉/恢复支线：冻结主线、判据=背包重现、完成自动回主线 | [睡觉任务](docs/架构/正式支线-睡觉任务-架构与源码施工说明-20261007.md) · [支线决策](docs/架构/支线决策与资产损失分析-20261010.md) |

工程外围（不在本仓，私有）：RDD Harness（门禁 / 注册表 / 时间锁 / 部署流水）、stager（四阶段流程机 + 决策收件箱）。

---

## 4 · 近期实验（2026-10，三态如实）

| 实验 | 结论 | 状态 |
|---|---|---|
| **existential 卡 V1–V6**（10-22） | 一条 VERIFIED 经验进真实 executor 请求 → `sleep` 调用 **1→0**（两次 `/clear` 干净复现，唯一变量=知识块）；主人亲判「看出来了」——**只证明机制通了，不证明整体变聪明** | ✅ |
| **学习线最小闭环**（10-26/27） | 死亡→自动备忘→（被抑制则暂存放回）→复盘→**自己认出「顺序问题」并写脚本**→人批准→执行→**世界真的变了**（永久午夜母版上 `game_time` 前进 = 睡觉跳夜成功）；全程只剩批准是人工 | ✅ |
| **投送两处静默截断修复**（10-27） | 「经验没用」与「经验没送到」从外面看一模一样：检索预算按 title+summary+正文计费只装 2 条、聚合上限切在 XML 闭合标签前——修复后 6 条全在场、闭合标签完整 | ✅ |
| **craft A/B**（10-26） | 场景被证伪：失败回执正文自带解法，两组都在 3–4 秒内照做——**主导变量是回执不是经验**；要验学习必须换「回执不给解法」的场景 | 🟡 |
| **夜间经验 A/B**（10-27） | 送达修好后条目稳定进请求；但基线率 ~40%（不给经验她也会围掩体/回床睡），行为增量测不出——「经验能改写行为」尚未证实 | 🟡 |
| **世界观测 V1**（10-18） | 11/11 实机验收：零/部分/未加载三分、STALE/HISTORICAL 新鲜度、预算明确拒绝、重启保留历史 | ✅ |
| **支线决策**（10-18） | 恢复支线完整事件链，全程无 `replanned`——冻结主线而非重写计划 | ✅ |
| **ACX 村庄路线**（10-06） | 第三批 live 验证：任务链 + ACX 走完村庄路线闭环 | ✅ |
| **自编译闭环**（08-31） | AI 生成 `RddWhereamiTool` → 编译打包 → 部署重启 → MCP 真实调用返回真实坐标 | ✅ |

**诚实边界**：不能吹「整体变聪明了」（existential 卡只证明单条经验 → 单次行为差异）；
经验库 26 条是**设计**（垃圾经验注入比没有更坏）；统计为历史窗口快照、非实时；
AC 调用 / 死亡的「目标归属」是时间线推断，页面必须标注。

---

## 5 · 架构（三层，按耦合度分）

| 层 | 内容 | 与宿主的耦合 |
|---|---|---|
| **① 纯 JVM core**（`src/*-core/`） | `rdd-core`（任务链/资产）、`experience-core`（经验记忆）、`settlement-core`（基地/设施）、`world-state-core`（世界观测） | **零**：不依赖 Minecraft / Numen，可独立发 maven 坐标、可离线单测 |
| **② 插件**（`src/plugins/`） | `rdd` `learner` `acx` `experience` `camera` `settlement` `worldstate` `selfcompile` | **低**：走 `NumenGateway` / 公共 API 注册能力 |
| **③ 工程闭环**（宿主之外） | 编译→部署→进游戏→MCP 驱动→读监测台取证的自动化编排 | **无**：完全在宿主外 |

> `acx` = AC 执行层**新代**（非线性控制流/前置条件/参数映射/生命周期）；第一代 `ac` 已删。

---

## 6 · 目录

```text
src/                      ★ 自有源码
  rdd-core/ experience-core/ settlement-core/ world-state-core/   纯 JVM core（main/ + test/）
  plugins/                rdd learner acx experience camera settlement worldstate selfcompile
  _host-touch/            我们写的、必须住在宿主包路径下的接缝文件（59 个，含 README）
patches/
  numen-host-modifications.patch   对宿主已有文件的全部改动 diff（146 文件，可 apply / 反向回滚）
monitoring-station/      监测台（观测面，只读；含「介绍」分页）
docs/                    架构 / 规范 / 验证 / 运维 四类公开镜像（编号文档体系）
scripts/                 外部自编译编排脚本（run-mutation 闭环 / 止损循环 / 验证工具）
skills/                  自变异系统的 AI 编程技能
templates/               NumenTool 生成系统提示词模板
MODIFICATIONS.md         对宿主的修改说明（逐模块交代 + 回滚）
```

---

## 7 · 怎么用

```text
1. 拉取并构建宿主 Numen（上游 Dwinovo/minecraft-numen，NeoForge 1.21.1，Java 21 + Gradle 9.2）
   或直接用我们的宿主 fork 分支 redline-1（已含本文改动）
2. apply patches/numen-host-modifications.patch，把 src/_host-touch/ 按路径放回，把 src/ 各模块并回
3. 启动游戏（实例 live，存档 live），MCP 在 127.0.0.1:8765
4. 启动监测台：node monitoring-station\server.mjs → http://127.0.0.1:8776
5. 观测在 config/numen/monitor/*.jsonl（干净 UTF-8；游戏日志是 GBK，中文检索以监测台为准）
```

---

## 8 · 给宿主作者（怎么介绍这个项目）

> 你好，我在 Numen 上做了一套**上层扩展项目**，想同步一下、并问哪些值得回馈上游。**不是来提大 PR 的。**
>
> **定位**：不改 Numen 的核心语义，而是在它的公共 API（`NumenGateway` / 事件 / 工具注册）之上加一层。
>
> **三层**：① 纯 JVM core（不依赖 MC/Numen，可独立发坐标）：任务链 / 经验记忆 / 基地设施 / 世界观测；
> ② 8 个插件走 `NumenGateway` 注册能力；③ 一套工程验证闭环（自动编译→进游戏→读监测台取证，在宿主之外）。
>
> **我动了宿主 `api/core/ai` 的少量地方**（新增接缝文件 59 个 + 改已有文件若干），
> 全部记在 `patches/numen-host-modifications.patch`。**其中有你愿意收的，我可以单独拆成小 PR**——
> 不需要你合并整个 fork（我们领先上游 300+ 提交，合并不现实）。
>
> **仓库**：项目主体在本仓；宿主 fork 在 [nuomi2422/minecraft-numen](https://github.com/nuomi2422/minecraft-numen)（分支 `redline-1`）。

---

## 9 · 许可证

本项目代码 **MIT**（`src/` + 监测台 + 文档 + 脚本）。
**借用宿主 Numen 遵循其自身许可证**（源码 LGPL-3.0，公共对接 API MIT）。
