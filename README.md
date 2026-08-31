# restart-developed-doer

> **这是一个开源工程，不是 Mod**：一套住在 Minecraft 里的 **AI Agent Runtime / 自编译系统**——
> AI 自主玩游戏、任务链监督、经验沉淀、缺能力自编译补上，全程可观测可验证。

---

## 📇 改动索引 & 完成状态（先看这里）

- **对宿主改动极小**：144 个改动文件里 **125 个是我们新增的自有代码**，真正改 Numen 原文件的只有 **19 处**（assist 模式 + 观测埋点 + 构建，全部叠加扩展，不动宿主核心语义）。
- **完成状态**：监测台 ✅ ｜ AC 执行 ✅ ｜ RDD 任务链 ✅ ｜ Self-Compile ✅ ｜ **expmem ⚠️ 部分完成**（词法检索✅，语义检索依赖外部 BGE embedding + 灵魂核心向量索引，详见下）
- 完整清单：**[`docs/MODIFICATIONS-INDEX.md`](docs/MODIFICATIONS-INDEX.md)**（索引）｜ **[`MODIFICATIONS.md`](MODIFICATIONS.md)**（逐模块交代）｜ **patch**：[`patches/heartpact-modifications.patch`](patches/heartpact-modifications.patch)
- 纯自有代码仓库：[**restart-developed-doer-core**](https://github.com/nuomi2422/restart-developed-doer-core)（去掉宿主 patch 视角，只看我们自己的代码）

---

## ⚠️ 借用宿主声明（Important）

本项目**暂时借用** [Numen](https://github.com/Dwinovo/minecraft-numen)（NeoForge 1.21.1 的 AI 同伴宿主）作为运行宿主。

- Numen 是**其原作者的开源项目**，本项目**不包含 Numen 原版源码**（需按原项目自行构建部署）；
- 本项目在 Numen 之上构建**五大模块化系统**，经 `Adapter / Port` 与宿主隔离——未来宿主可替换为任意 AI / 环境执行系统；
- 本仓库含**本项目自有源码**（`src/`：ac-api/ac-core/rdd-core/experience-core + plugins/*，共 125 Java 文件，依赖 Numen 编译）+ 监测台 + 架构文档 + 编排脚本 + 对宿主的改动 patch。

---

## 北极星（首要目标）

> 让一个 AI 同伴在 Minecraft 里**从零自主完成复杂任务**（挖钻石 / 铁甲全链），
> 全程可监督、可验证、能力缺失时**自编译补上**——不是"会玩 MC 的 NPC"，而是**自主 Agent Runtime**。

一句话：**军师（任务链）给目标 → 将军（内置 AI）自主执行 → 监测台监察 → 卡死时拍醒 → 缺工具自编译生成。**

---

## 五大系统（模块化，各自独立）

| # | 系统 | 定位 | 观测出口 |
|---|---|---|---|
| 1 | **监测台**（monitoring-station/） | 实时观测 AI 思考/工具/任务/经验/架构文档 | — |
| 2 | **AC 执行** | 多步脚本引擎：底层工具组合成高层调用（降费） | ac.jsonl |
| 3 | **RDD 任务链** | 目标分解 + 资产检测 + **卡死监督**（STALLED→拍醒→恢复） | rdd.jsonl |
| 4 | **expmem 经验** ⚠️ | learn/recall/verify 经验记忆（词法检索✅；语义检索依赖外部 BGE embedding + 灵魂核心向量索引，未做） | expmem.jsonl |
| 5 | **Self-Compile** | 生成工具→编译→部署→生效（自变异） | ai.jsonl |

> 五大系统的纯 JVM 核心在 Numen 仓库（ac-core/rdd-core/experience-core/plugins/*），
> 本仓库收录的是：监测台 + 架构决策文档 + 外部自编译编排脚本（脚本会指引你拉取并构建宿主）。

---

## 能完成什么（真机验证）

- **大型任务**：挖钻石 / 铁甲全链（内置 AI 自主 + RDD 监督，实测挖到 9 颗钻石）
- **异常判断**：路径阻塞换策略 / 卡死自动拍醒 / 工具未展开自查 / 熔炼 GUI 自主
- **降费**：AC 组合（1 次 ac_execute 顶 3 次底层工具调用，AI 已学会用）
- **自扩展**：缺工具 → selfcompile 生成 → 部署 → 生效
- **可恢复**：任务链 / AC 库落盘，游戏重启自动恢复

---

## 怎么用

```text
1. 拉取并构建宿主 Numen（NeoForge 1.21.1，Java 21 + Gradle 9.2，代理 127.0.0.1:7897）
2. 启动游戏（存档 gpt），MCP 在 127.0.0.1:8765
3. 启动监测台：node monitoring-station\server.mjs → http://127.0.0.1:8776
4. 观测在 config/numen/monitor/*.jsonl
5. 外部自编译编排：scripts\（run-mutation 等）
```

---

## 目录

```text
src/                  ★ 五大模块 Java 源码（ac-api/ac-core/rdd-core/experience-core + plugins/*，共 125 文件）
monitoring-station/   监测台（含"介绍"分页：项目定位一目了然）
docs/                 架构决策文档 + 改动索引（MODIFICATIONS-INDEX.md）
scripts/              外部自编译编排脚本（run-mutation 闭环 / 止损循环等 10 个 .ps1）
skills/               自变异系统的 AI 编程技能（rdd-selfcompile：外带 skill 同步收录）
templates/            NumenTool 生成系统提示词模板
patches/              heartpact-modifications.patch（对借用宿主 Numen 的改动 diff）
MODIFICATIONS.md      对 Numen 的修改说明（逐模块交代，含回滚方式）
```

> 🔧 **我们对借用宿主 Numen 改了什么？** 见 [MODIFICATIONS.md](MODIFICATIONS.md)（逐模块交代 + `patches/heartpact-modifications.patch` 全量 diff，可直接 apply / 反向回滚）。

## 许可证

本项目代码 MIT License（监测台 / 文档 / 脚本）。**借用宿主 Numen 遵循其自身许可证。**
