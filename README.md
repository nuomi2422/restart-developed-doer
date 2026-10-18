# restart-developed-doer（RDD）

> **这是一个开源工程，不是 Mod。**
> 一套「住在 Minecraft 里的 **AI Agent Runtime / 自编译系统**」——
> AI 自主玩游戏、任务链监督、经验沉淀、世界观测、缺能力时自编译补上，**全程可观测、可验证**。

<p align="center"><b>宿主</b>： <a href="https://github.com/Dwinovo/minecraft-numen">Numen</a>（NeoForge 1.21.1）　·　来源：<a href="https://github.com/nuomi2422/minecraft-numen">我们的宿主 fork（redline-1）</a></p>

---

## 0 · 一句话

**军师（任务链）给目标 → 将军（内置 AI）自主执行 → 监测台监察 → 卡死时拍醒 → 缺工具自编译补上。**

不是"会玩 MC 的 NPC"，而是**自主 Agent Runtime**：由外部 AI 工程闭环（写码 / 编译 / 进游戏实测 / 取证）
不断给它加能力。北极星是让同伴**从零自主完成复杂任务**（挖钻石 / 铁甲全链 / 村庄安家），并**自我进化**。

---

## 1 · 借宿主声明（Important）

本项目**借用** [Numen](https://github.com/Dwinovo/minecraft-numen) 作为运行宿主。

- Numen 是**其原作者的开源项目**，本仓**不包含 Numen 原版源码**（需按上游自行拉取/构建）。
- 本项目在 Numen 之上搭一层**模块化扩展系统**，经公共 API / `Adapter·Port` 与宿主隔离——
  未来宿主可替换为任意 AI / 环境执行系统。
- 本仓收录：**自有源码**（`src/`）+ **对宿主的改动 patch**（`patches/`）+ 架构文档 + 编排脚本 + 监测台。

### 我们对宿主改了什么？
- **新增的接缝文件**（必须住在宿主包路径下的，59 个）→ [`src/_host-touch/`](src/_host-touch/)。
- **改动宿主已有文件的差分**（146 个文件的 diff）→ [`patches/numen-host-modifications.patch`](patches/numen-host-modifications.patch)。
- 逐模块交代见 [`MODIFICATIONS.md`](MODIFICATIONS.md)（含回滚方式）。

---

## 2 · 架构（三层，按耦合度分）

| 层 | 内容 | 与宿主的耦合 |
|---|---|---|
| **① 纯 JVM core**（`src/*-core/`） | `rdd-core`（任务链/资产）、`experience-core`（经验记忆）、`settlement-core`（基地/设施）、`world-state-core`（世界观测） | **零**：不依赖 Minecraft / Numen，可独立发 maven 坐标、可离线单测 |
| **② 插件**（`src/plugins/`） | `rdd` `learner` `acx` `experience` `camera` `settlement` `worldstate` `selfcompile` | **低**：走 `NumenGateway` / 公共 API 注册能力 |
| **③ 工程闭环**（宿主之外） | 编译→部署→进游戏→MCP 驱动→读监测台取证的自动化编排 | **无**：完全在宿主外 |

> `acx` = AC 执行层**新代**（非线性控制流/前置条件/参数映射/生命周期）；第一代 `ac` 已删。

---

## 3 · 能做什么（实机验证过的，如实列）

### 3.1 世界观测 V1 —— 实机验收 11/11（2026-10-18，live/ACX）
`world_state` 工具：读不扫描、零/部分/未加载区分、`STALE`/`HISTORICAL` 新鲜度、
有界实体计数、**预算明确拒绝**（`BUDGET_EXCEEDED`）、按世界隔离落盘、
监测事件、**重启保留历史 → 刷新回当前**。四条红线 + 读/取分离全部成立。

### 3.2 任务链 / 学习闭环（2026-10-05 ~ 10-06，live）
- 目标分解 → 重规划 → **首次观测到 `requirements_met` 与整级 `goal_completed`**；
- **内脑自己写结构化经验**（七字段填 6、priority 90）；
- 容器/熔炉：`inspect_gui` + `transfer` + `close_gui`、`set_timer` 管等待；
- **死亡恢复 + 捡包**：跑回死亡点把 104 原铁全捡回；
- 自主 COUNTER/REJECT 不可行子任务、自设定时器、按需加载技能。

### 3.3 自编译闭环（期 2，2026-08-31）
AI 生成 `RddWhereamiTool` → 落盘登记 → 编译打包 → 部署重启 → MCP 真实调用返回真实坐标。

### 3.4 尚未验证 / 盲点（照实写）
AC **自写**能力未验证；新经验「按任务召回→采用→改变重规划」未证明（存储✓ 浮现✓ 采用✗）；
农田/交易/自动挖铁 AC 无验收证据；放置选位、不躲苦力怕等 8 项盲点已记录。

---

## 4 · 目录

```text
src/                      ★ 自有源码
  rdd-core/ experience-core/ settlement-core/ world-state-core/   纯 JVM core（main/ + test/）
  plugins/                rdd learner acx experience camera settlement worldstate selfcompile
  _host-touch/            我们写的、必须住在宿主包路径下的接缝文件（59 个，含 README）
patches/
  numen-host-modifications.patch   对宿主已有文件的全部改动 diff（146 文件，可 apply / 反向回滚）
monitoring-station/      监测台（含「介绍」分页）
docs/                    架构决策文档 + 改动索引
scripts/                 外部自编译编排脚本（run-mutation 闭环 / 止损循环等）
skills/                  自变异系统的 AI 编程技能
templates/               NumenTool 生成系统提示词模板
MODIFICATIONS.md         对宿主的修改说明（逐模块交代 + 回滚）
```

---

## 5 · 怎么用

```text
1. 拉取并构建宿主 Numen（上游 Dwinovo/minecraft-numen，NeoForge 1.21.1，Java 21 + Gradle 9.2）
   或直接用我们的宿主 fork 分支 redline-1（已含本文改动）
2. apply patches/numen-host-modifications.patch，把 src/_host-touch/ 按路径放回，把 src/ 各模块并回
3. 启动游戏（实例 live，存档 live），MCP 在 127.0.0.1:8765
4. 启动监测台：node monitoring-station\server.mjs → http://127.0.0.1:8776
5. 观测在 config/numen/monitor/*.jsonl（干净 UTF-8）
```

---

## 6 · 给宿主作者（怎么介绍这个项目）

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

## 7 · 许可证

本项目代码 **MIT**（`src/` + 监测台 + 文档 + 脚本）。
**借用宿主 Numen 遵循其自身许可证**（源码 LGPL-3.0，公共对接 API MIT）。
