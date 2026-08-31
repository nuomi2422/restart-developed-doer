# 改动索引（Modifications Index）

> **一眼看清三件事：我们对宿主改了多少 / 自己写了多少 / 完成到哪一步。**
> 对应 `patches/heartpact-modifications.patch`（144 文件，base = Numen 原版接入点 `ea38daa1`）。
> 详细逐模块交代见 [MODIFICATIONS.md](../MODIFICATIONS.md)。

---

## 一句话结论

- **对宿主 [Numen](https://github.com/Dwinovo/minecraft-numen) 改动极小**：144 个改动文件里，
  **125 个是我们新增的自有代码**，真正修改 Numen 已有文件的只有 **19 个**
  （集中在 `api/common` 的 assist 协助模式 + 观测埋点 + build 配置）。
- 全部是**叠加扩展**，不修改 Numen 核心语义；经 `Adapter / Port` 与宿主隔离，未来可换宿主。
- **我们的代码已完整上传**：`src/`（125 Java）+ `monitoring-station/` + `docs/` + `scripts/` + `skills/` + `templates/`。
- 独立仓库 [restart-developed-doer-core](https://github.com/nuomi2422/restart-developed-doer-core) 收录**纯自有代码**（去掉宿主 patch 视角）。

---

## A. 对宿主 Numen 原文件的改动（19 处，按区域）

| 区域 | 文件 | 我们改了什么 |
|---|---|---|
| **assist 协助模式** | `api/common/.../McpMode.java`、`McpConfig.java`、`McpServer.java` | 驾驶/协助模式分离；MCP enqueue 注入任务给内置 AI |
| **观测埋点** | `api/common/.../EntityAgentLoop.java`、`ExecuteToolPayload.java`、`CompanionEvent.java`、`NumenActuator.java`、`NumenGateway.java` | AI 上下文 / 工具结果 / 事件写监测台 |
| **AC 核心缺陷修复** | `ac-api/.../AcDefinition.java`、`ExecutionRecord.java`、`ToolRegistry.java`、`ToolSchema.java`；`ac-core/.../AcExecutor.java`、`AcJson.java`、`DefaultToolRegistry.java` | `Map.copyOf` null-value NPE → `LinkedHashMap`；step 级异常护栏；身份契约 |
| **回归测试** | `ac-core/test/.../AcExecutorTest.java` | 上述修复的回归用例 |
| **构建** | `settings.gradle`、`buildSrc/.../api-common.gradle`、`ac-core/build.gradle` | 注册/配置我们的模块 |

> 这 19 处全部是**叠加**，改的是边界与埋点，不动 Numen 的 AI 内核语义。

---

## B. 自有代码全量清单（125 个新增 Java 文件，完整上传）

| 模块 | Java 数 | 定位 | 上传位置 |
|---|---|---|---|
| **ac-api** | 12 | AC 执行身份契约 / 事件 / 工具注册 | `src/ac-api/` |
| **ac-core** | 13 | AC 多步脚本引擎（执行/校验/版本库落盘） | `src/ac-core/` |
| **rdd-core** | 27 | RDD 任务链：目标分解/卡死监督/持久化/资产检测 | `src/rdd-core/` |
| **experience-core** | 13 | expmem 经验记忆（learn/recall/verify + 成熟度） | `src/experience-core/` |
| **plugins/ac** | 14 | AC 工具（ac_execute/status/resume/publish）+ Numen 工具桥 | `src/plugins/ac/` |
| **plugins/experience** | 6 | 经验工具三件套 | `src/plugins/experience/` |
| **plugins/rdd** | 8 | RDD 检测/监督/状态/提交 | `src/plugins/rdd/` |
| **plugins/selfcompile** | 32 | Self-Compile 自变异全套（Mutation* + 证据链 + 生成工具） | `src/plugins/selfcompile/` |
| **合计** | **125** | — | 全量在 `src/` |

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
