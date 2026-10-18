# 对借用宿主 Numen 的修改说明（Modifications）

> 本项目（restart-developed-doer）在 **[Numen](https://github.com/Dwinovo/minecraft-numen)（NeoForge 1.21.1）** 之上做**叠加扩展**。
> 本文交代我们对宿主源码的改动；全量 diff 见
> [`patches/numen-host-modifications.patch`](patches/numen-host-modifications.patch)
> （base = 上游 `origin/1.21.1` @ `ff743c53`）。
> **新增的接缝文件**（必须住宿主包路径下的）另收在 [`src/_host-touch/`](src/_host-touch/)（59 个）。

---

## 概览

- 自有模块：**4 个纯 JVM core** + **8 个插件**（见 `src/`）。
- 对宿主已有文件的改动：**146 个文件**的 diff（见 patch）。
- 全部为**叠加扩展**；模块化独立 jar + 独立 commit，可单独回滚。

| 模块 | 内容 | 层 |
|---|---|---|
| `rdd-core` + `plugins/rdd` | 任务链：目标分解 + 资产检测 + 卡死监督（STALLED→拍醒→恢复）+ 持久化 + 支线决策 | ① + ② |
| `experience-core` + `plugins/experience` | 经验记忆：learn / recall / verify，成熟度升级 | ① + ② |
| `settlement-core` + `plugins/settlement` | 基地/设施：模块化 base 网格 + 设施注册 + 位置保护 + 三层验收 | ① + ② |
| `world-state-core` + `plugins/worldstate` | 世界观测：统一观测契约 + 存储 + 新鲜度/覆盖 + 读/取分离 | ① + ② |
| `plugins/acx` | AC 执行层**新代**：非线性控制流 / 前置条件 / 参数映射 / 生命周期（第一代 `ac` 已删） | ② |
| `plugins/learner` | 学习：从观察 → 草稿 → 复审 → 进库 | ② |
| `plugins/camera`（numencam） | 客户端相机：5 机位 / 绑定 / 牵引 / 传送 / 飞行加速 / 录像出 mp4 + `/cam` 指令 | ②（客户端） |
| `plugins/selfcompile` | 自编译：变异生命周期 + 自动改码 + 证据链 + 生成工具 | ② |
| `_host-touch/`（宿主内接缝） | 我们写、但必须住在 `ai/ api/common/ core/common/` 包路径下的文件 | 宿主 |

---

## 对宿主的主要改动（按域）

```text
ai/      LLM 链路：预算策略(BudgetPolicy) / 活动观测(LlmActivity,LlmObservation) /
         端点拒收护栏(EndpointRejectionGuard) / 会话头解析(SessionHeaderResolver) + 测试
api/     common：载体告警(CarrierAlarms/CarrierChain/CarrierRuleStore/ItemSemantics) /
         规划知识贡献(PlanningKnowledgeContributor,PlanningQuery) / 监测埋点(MonitoringJournal) /
         客户端：BrainGate / StopCommand / MonitoringScreen / ToolLabels + 测试
core/    common：寻路执行(OscillationPolicy) / 任务链(呼吸/岩浆/窒息逃生、吸散规则) /
         工具(ClickGuiTool,FcControlTool) / MoveToTool 微调 + 大量测试
build     settings.gradle 注册新模块；gradle.properties / buildSrc 适配
```

> **共同点**：都是「加能力 / 加埋点 / 修宿主缺陷」，不改宿主核心语义。
> 例：曾修 `Map.copyOf` 对 null 抛 NPE（`get_self_status` 的 `target:null` 触发）→ 换 `LinkedHashMap`。

---

## 回滚方式

- 模块级：独立 jar + 独立 commit，`git revert <commit>` 或直接删 `mods/` 下对应 jar。
- 全量：反向 apply patch —— `git apply -R patches/numen-host-modifications.patch`。

## 已知边界（不隐瞒）

```text
· 生成工具（plugins/selfcompile/generated/）不进 git（可再生成）
· AC 自写能力未验证；新经验「按任务召回→采用→改变重规划」未证明（存储✓ 浮现✓ 采用✗）
· 农田/交易/自动挖铁 AC 无实机验收证据
· 放置选位、不躲苦力怕、爆炸掉落不主动捡 —— 8 项盲点已记录在案
```

## 许可证

本项目代码 MIT。**Numen 本体遵循其自身许可证**；本 patch 是叠加扩展，**不含 Numen 原版源码**。
