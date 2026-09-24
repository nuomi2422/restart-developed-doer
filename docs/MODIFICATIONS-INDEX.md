# 改动索引（Modifications Index）

> **一眼看清三件事：我们对宿主改了多少 / 自己写了多少 / 完成到哪一步。**
> 对应 `patches/heartpact-modifications.patch`（base = Numen 原版接入点 `ea38daa1`）。
> ⚠️ 该 patch 目前仍是 **2026-08-31** 的版本，尚未跟上后续改动。
> 详细逐模块交代见 [MODIFICATIONS.md](../MODIFICATIONS.md)。

---

## 一句话结论

- **对宿主 [Numen](https://github.com/Dwinovo/minecraft-numen) 改动极小**：相对接入点 `ea38daa1` 的
  280 个改动文件里，**227 个是我们新增的自有代码**，真正修改 Numen 已有文件的只有 **44 个**
  （assist 协助模式 + 观测埋点 + 工具护栏 + build 配置）。
- 全部是**叠加扩展**，不修改 Numen 核心语义；经 `Adapter / Port` 与宿主隔离，未来可换宿主。
- **我们的代码已完整上传**：`src/`（206 Java + `_host-touch/` 18）+ `monitoring-station/` + `docs/` + `scripts/` + `skills/` + `templates/`。
- 独立仓库 [restart-developed-doer-core](https://github.com/nuomi2422/restart-developed-doer-core) 收录**纯自有代码**（去掉宿主 patch 视角）。

---

## A. 对宿主 Numen 已有文件的改动（44 个，按区域）

> 口径：相对接入点 `ea38daa1`，`git diff --name-status` 里状态为 `M` 且不属于
> 自有模块 `ac-api/`、`ac-core/` 的文件。

| 区域 | 数量 | 文件 | 我们改了什么 |
|---|---|---|---|
| **assist 协助模式** | 4 | `api/common/.../mcp/server/`：`McpMode` `McpConfig` `McpServer` `McpConfigGuardTest` | 驾驶/协助模式分离；MCP enqueue 注入任务给内置 AI |
| **观测埋点** | 5 | `EntityAgentLoop` `ExecuteToolPayload` `CompanionEvent` `NumenActuator` `NumenGateway` | AI 上下文 / 工具结果 / 事件写监测台 |
| **api/common 其他接缝** | 10 | `NumenApi` `NumenPlugins` `NumenEvents` `NumenPlayer` `CompanionStateWatch` `WorkBlockMemory` `ChatCommands` `NumenKeys` `ModLanguageData` `TaskResultPayload` | 插件注册口、事件分发、同伴状态观测、指令与 i18n 挂点 |
| **ai（LLM 层）** | 3 | `LlmObservation` 挂点相关的 `HttpLlmTransport` `NumenLlmClient` `NumenPrompts` | LLM 请求/响应埋点与提示词扩展 |
| **core/common 任务链与工具** | 14 | `CoreReflexes` `BreathChain` `MLGChain` `MobDefenseChain` `UnstuckChain` `MineCompanionTask` `InteractAtCompanionTask` `MovementHelper` `BlockActionOps` `CraftOps` `GuiOps` `GetSelfStatusTool` `NumenCore` `ReflexOrderTest` | 逃生链护栏、任务执行口径、工具行为修正（hint 扫描 / GUI 反馈 / 消耗品使用等） |
| **平台适配** | 2 | `api/fabric/NumenFabricClient` `api/neoforge/NumenNeoForgeClient` | 两端客户端入口接线 |
| **构建** | 5 | `settings.gradle` `gradle.properties` `buildSrc/api-common.gradle` `buildSrc/numen-plugin.gradle` `core/neoforge/build.gradle` | 注册自有模块、JDK21 钉死、插件构建 |
| **文档** | 1 | `AC_EXECUTION_LAYER_EXTRACTION.md` | 接入说明补注 |

> 这 44 个全部是**叠加**，改的是边界与埋点，不动 Numen 的 AI 内核语义。
>
> 我们**新增**（而非修改）的、住在宿主模块路径下的文件在
> [`src/_host-touch/`](../src/_host-touch/)（18 个），已在 `src/` 内单列。

---

## B. 自有代码全量清单（206 Java 文件，完整上传）

> 每个模块的数字 = `src/main` + `src/test` 的 Java 文件数。

| 模块 | Java 数 | 定位 | 上传位置 |
|---|---|---|---|
| **rdd-core** | 66 | RDD 任务链核心：目标分解 / 完成事实 / 失败诊断 / 风险门 / 重规划 / 卡死监督 / 资产口径 | `src/rdd-core/` |
| **plugins/rdd** | 43 | RDD 的 Numen 适配：检测 / 监督 / 状态 / 提交 / 世界观察 | `src/plugins/rdd/` |
| **plugins/selfcompile** | 32 | Self-Compile 自变异全套（Mutation* + 证据链 + 生成工具） | `src/plugins/selfcompile/` |
| **plugins/ac** | 14 | AC 工具（ac_execute/status/resume/publish）+ Numen 工具桥 | `src/plugins/ac/` |
| **plugins/experience** | 13 | expmem 经验工具三件套 | `src/plugins/experience/` |
| **ac-core** | 13 | AC 多步脚本引擎（执行/校验/版本库落盘） | `src/ac-core/` |
| **experience-core** | 13 | expmem 经验记忆（learn/recall/verify + 成熟度） | `src/experience-core/` |
| **ac-api** | 12 | AC 执行身份契约 / 事件 / 工具注册 | `src/ac-api/` |
| **小计** | **206** | 独立模块，边界干净（零宿主依赖或只走 Adapter/Port） | `src/` |
| **`_host-touch`** | **18** | 我们写的、但必须住在宿主模块路径下的接缝文件 | `src/_host-touch/` |
| **合计** | **224** | — | 全量在 `src/` |

另有非 Java 自有代码：
- **监测台** `monitoring-station/`：实时观测 AI 思考/工具/任务/经验（含"介绍"分页）
- **自编译外部编排** `scripts/`（10 个 .ps1：run-mutation 闭环/止损循环/编译/部署/启动/验证）
- **skill** `skills/rdd-selfcompile/SKILL.md`：自变异系统的 AI 编程技能（外带，本仓库同步收录）
- **生成提示词模板** `templates/numentool-sys.txt`
- **架构文档** `docs/`：五大系统 spec / 数据流 / 真机运行报告

---

## C. 完成状态

| 系统 | 状态 | 真机验证 |
|---|---|---|
| **监测台** | ✅ 完成 | 实时观测 + 介绍页 INTRO |
| **AC 执行** | ✅ 完成 | 3 步 AC SUCCESS；降费（1 次 ac_execute 顶 3 次底层工具） |
| **RDD 任务链** | ✅ 完成 | 大型任务（挖钻石 9 颗）；卡死监督 STALLED→拍醒→恢复；持久化重启恢复 |
| **Self-Compile** | ✅ 完成 | 缺工具→生成→登记→编译→部署→生效闭环（rdd_get_inventory 真机） |
| **expmem 经验** | ⚠️ 部分完成 | 词法检索 ✅（20 单测）；**向量/语义检索未做**（见 D） |

---

## D. expmem 依赖声明（不隐瞒）

`experience-core` 的检索只实现了**词法版**（`LexicalExperienceRetriever`，按命中词加权打分），
代码注释自明：*"已有向量检索能力时，写一个 `VectorExperienceRetriever` 代理过去即可"*。

**未做的部分**——语义/向量召回（中文"同义不同字"召回弱）依赖**外部项目**：

1. **BGE embedding 模型**（句向量生成）
2. **灵魂核心向量索引**（soul-core-bridge 并档共享记忆，`life:` 前缀语义召回）——另一套 Java↔Python 桥接工程

这两个都不是本项目私有能力，当前未接入，故 expmem 标 ⚠️ 部分完成。词法检索可独立工作，语义召回待上述外部能力接入后替换 retriever 即可。

---

## E. 其他已知边界（不隐瞒）

```text
· 生成工具（selfcompile/generated/）不进 git（可再生成，自编译语义）
· 中文经 MCP enqueue 变问号（PowerShell ANSI 编码坑，用英文绕过）
· ApricityUI 每 tick NoSuchMethodException（NbtIo 旧签名，第三方 mod，非本项目改动）
· assist 模式下引导内置 AI 调自编译有自主性边界（AI 收到提示不一定真调工具）
```
