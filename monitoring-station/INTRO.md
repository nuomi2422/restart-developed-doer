# Numen 自编译系统 · 项目总介绍

> 一眼看懂：这是什么、五大系统、能完成什么、怎么接入。

## 一句话

一个住在 Minecraft 里的 AI 同伴（**Numen 宿主** = NeoForge 1.21.1 模组），
配五大模块化系统，让 AI **自主玩游戏**，由外部 AI 编程工具作为**自编译系统**监督运行。

## 五大系统（每个独立 jar，可单独部署/回滚）

| # | 系统 | 纯 JVM 核心 | 干什么 | 观测出口 |
|---|---|---|---|---|
| 1 | **Numen 宿主** | — | AI 同伴(ServerPlayer 假玩家) + 54 工具 + MCP 外脑(:8765) | ai.jsonl |
| 2 | **AC 执行** | ac-api + ac-core | 多步脚本引擎，底层工具组合成高层调用 = 降费 | ac.jsonl |
| 3 | **RDD 任务链** | rdd-core | 目标分解 + 资产检测 + **卡死监督**(STALLED→拍醒→恢复) | rdd.jsonl |
| 4 | **expmem 经验** | experience-core | learn/recall/verify 经验记忆 | expmem.jsonl |
| 5 | **Self-Compile** | plugins/selfcompile | 生成工具 → 编译 → 部署 → 生效（自变异） | ai.jsonl |

## 定位（军师 / 将军 / 上帝视角）

```text
外部 AI（自编译系统，上帝视角）
  ├─ enqueue 喂目标（assist 模式，内置 AI 自主执行 = 将军）
  ├─ 监测台监察（http://127.0.0.1:8776）
  ├─ RDD 拍醒（将军发愣时提醒，不抢方向盘）
  └─ 缺工具 → selfcompile 生成 → 部署 → 生效
```

## 能完成什么（全部真机验证）

- **大型任务**：挖钻石 / 铁甲全链（内置 AI 自主 + RDD 监督，实测 rdd 挖到 9 颗钻石）
- **异常判断**：路径阻塞换策略 / 卡死自动拍醒 / 工具未展开自查 / 熔炼 GUI 自主
- **降费**：AC 组合（1 次 ac_execute 顶 3 次底层工具调用，AI 已学会用）
- **自扩展**：缺工具 → selfcompile 生成 → 生效（rdd_get_inventory 已验证）
- **资产感知**：RDD AssetRegistry 报真实背包（rdd_status assets=10）
- **升级路径**：Level 1 卡死 → Level 2 重试 → Level 3 能力不足 → 自编译引导，全触发验证

> 📄 **完整真机成果 + 原始日志**：见 [`docs/REAL-VERIFICATION-RESULTS.md`](../docs/REAL-VERIFICATION-RESULTS.md)（10 项逐条带证据）

## 怎么接入（对别的 AI）

```text
仓库：  E:\restart developing doer\minecraft-numen（分支 1.21.1）
观测：  E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor\*.jsonl
MCP：   127.0.0.1:8765（token 在 config/numen/mcp_server.json）
监测台：node monitoring-station\server.mjs → http://127.0.0.1:8776
外部编排：E:\restart developing doer\rdd-selfcompile\scripts（run-mutation 等）
```

## 详细文档（切到"文档"分页看全文）

```text
ARCHITECTURE.md   架构总览（系统组成/数据流/设计原则/运维）
INTRO.md          本介绍页
CONTRACT.md       模块契约
INTERFACES.md     接口清单
REGRESSION.md     回归清单
HANDOFF.md        交接文档
```

## 关键文件

```text
运行报告（2026-08-31 自主批次）：E:\新建文件夹\rdd架构\batch-run-20260831-autonomous.md
架构决策：E:\新建文件夹\rdd架构\*.md
```
