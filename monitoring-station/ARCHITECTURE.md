# RDD / Numen 监测台 · 架构总览

## 项目一句话

一个住在 Minecraft 里的 AI 同伴（Numen 为宿主），外加四套模块化能力（AC 执行 / RDD 任务链 / expmem 经验 / Self-Compile 自变异），全部围绕「AI 自主玩游戏」构建。

## 系统组成

```text
┌─────────────────────────────────────────────────────────┐
│  Numen 宿主（NeoForge 1.21.1）                           │
│  · AI 同伴（ServerPlayer 假玩家）                        │
│  · 工具目录（54 个：移动/挖矿/战斗/合成/感知）             │
│  · MCP 外接大脑（127.0.0.1:8765）                        │
└──────────┬──────────────────────────────────────────────┘
           │ 各插件通过 NumenPlugins.register 挂载
┌──────────▼──────────────────────────────────────────────┐
│  模块化插件（每个独立 jar，可单独部署/回滚）               │
│                                                         │
│  · ac 插件        AC 执行系统（多步脚本引擎）             │
│  · rdd 插件       RDD 资产任务链（目标分解+检测+执行）     │
│  · experience    expmem 经验记忆（learn/recall/verify）  │
│  · selfcompile   自变异系统（生成工具→编译→验证→部署）     │
└──────────┬──────────────────────────────────────────────┘
           │ 观测出口（config/numen/monitor/*.jsonl）
┌──────────▼──────────────────────────────────────────────┐
│  本监测台（127.0.0.1:8776）                              │
│  总览 / AI / Context / Tools / AC / 环境 / 健康          │
└─────────────────────────────────────────────────────────┘
```

## 四模块职责

| 模块 | 纯 JVM 核心 | 宿主桥接 | 观测分类 |
|---|---|---|---|
| AC 执行 | ac-api + ac-core | plugins/ac | ac_* 工具 + ac 事件 |
| RDD 任务链 | rdd-core | plugins/rdd | rdd.jsonl |
| expmem 经验 | experience-core | plugins/experience | expmem.jsonl |
| Self-Compile | plugins/selfcompile | plugins/selfcompile | ai.jsonl + selfcompile |

## 数据流

```text
AI 思考 → user_prompt/assistant/turn（context/ai.jsonl）
  → 调用工具 → tool_call（tools.jsonl）
  → 提交 AC/RDD → subtask/goal 事件（rdd.jsonl）
  → 经验沉淀 → learned（expmem.jsonl）
  → 世界变化 → events/state.jsonl
```

## 设计原则

- **纯 JVM 核心**：ac-core/rdd-core/experience-core 零 MC/LLM 依赖，可独立测试
- **模块化**：每个插件独立 jar，可单独部署/回滚
- **观测低侵入**：监测台不可用时不拖住游戏（有界队列 + 非阻塞）
- **防假成功**：验证必须世界快照对撞，不认 AI 自述

## 运维手册

```text
启动监测台：
  $env:NUMEN_MONITOR_DIR="E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor"
  node "E:\TouhouLittleMaid-HeartPact\monitoring-station\server.mjs"

API：
  /api/snapshot    读所有分类 JSONL
  /api/numen-log   读 latest.log 解析 AI 决策行
  /api/health      健康检查

数据源：
  config/numen/monitor/*.jsonl
  logs/latest.log（GBK）

重启游戏前检查：
  config/numen/mcp_server.json 的 enabled 是否被写回 false
```
