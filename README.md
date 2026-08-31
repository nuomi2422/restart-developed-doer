# restart-developed-doer

> **这是一个开源工程，不是 Mod**：一套住在 Minecraft 里的 **AI Agent Runtime / 自编译系统**——
> AI 自主玩游戏、任务链监督、经验沉淀、缺能力自编译补上，全程可观测可验证。

---

## ⚠️ 借用宿主声明（Important）

本项目**暂时借用** [Numen](https://github.com/Dwinovo/minecraft-numen)（NeoForge 1.21.1 的 AI 同伴宿主）作为运行宿主。

- Numen 是**其原作者的开源项目**，本项目**不包含、不修改 Numen 本体源码**（需按原项目自行构建部署）；
- 本项目在 Numen 之上构建**五大模块化系统**，经 `Adapter / Port` 与宿主隔离——未来宿主可替换为任意 AI / 环境执行系统；
- 本仓库只含**本项目自有代码与文档**（监测台、架构决策、外部编排脚本），不含 Numen 源码。

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
| 4 | **expmem 经验** | learn/recall/verify 经验记忆 | expmem.jsonl |
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
monitoring-station/   监测台（含"介绍"分页：项目定位一目了然）
docs/                 架构决策文档（五大系统 spec / 数据流 / 运行报告）
scripts/              外部自编译编排脚本
patches/              heartpact-modifications.patch（对借用宿主 Numen 的全部改动 diff）
MODIFICATIONS.md      对 Numen 的修改说明（逐模块交代，含回滚方式）
```

> 🔧 **我们对借用宿主 Numen 改了什么？** 见 [MODIFICATIONS.md](MODIFICATIONS.md)（逐模块交代 + `patches/heartpact-modifications.patch` 全量 diff，可直接 apply / 反向回滚）。

## 许可证

本项目代码 MIT License（监测台 / 文档 / 脚本）。**借用宿主 Numen 遵循其自身许可证。**
