# restart-developed-doer

> **这是一个开源工程，不是 Mod**：一套住在 Minecraft 里的 **AI Agent Runtime / 自编译系统**——
> AI 自主玩游戏、任务链监督、经验沉淀、缺能力自编译补上，全程可观测可验证。

---

## 📇 改动索引 & 完成状态（先看这里）

- **对宿主改动极小**：144 个改动文件里 **125 个是我们新增的自有代码**，真正改 Numen 原文件的只有 **19 处**（assist 模式 + 观测埋点 + 构建，全部叠加扩展，不动宿主核心语义）。
- **完成状态**：监测台 ✅ ｜ AC 执行 ✅ ｜ RDD 任务链 ✅ ｜ Self-Compile ✅ ｜ **expmem ⚠️ 部分完成**（词法检索✅，语义检索依赖外部 BGE embedding + 灵魂核心向量索引，详见下）
- 完整清单：**[`docs/MODIFICATIONS-INDEX.md`](docs/MODIFICATIONS-INDEX.md)**（索引）｜ **[`MODIFICATIONS.md`](MODIFICATIONS.md)**（逐模块交代）｜ **patch**：[`patches/heartpact-modifications.patch`](patches/heartpact-modifications.patch)
- 能力评估（诚实自评：极限/天花板/外部评判/提升路线）：**[`docs/CAPABILITY-ASSESSMENT.md`](docs/CAPABILITY-ASSESSMENT.md)**
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

## 能完成什么（全部真机验证 2026-08-31）

- ⛏️ **空手挖钻石**：大型任务全链，rdd 背包实测 **diamond:9**
- 🕐 **卡死监督**：STALLED→拍醒→恢复全周期（真实日志 11:12:46→11:13:17）
- 🧬 **自编译闭环**：缺工具→生成→部署→生效（rdd_get_inventory MCP 真实返回）
- 💰 **AC 降费**：1 次 ac_execute 顶 3 次底层工具，AI 自主学会用
- 💾 **可恢复**：RDD 任务链 / AC 库落盘，游戏重启自动恢复
- 🧭 **升级路径**：Level 1 卡死→Level 2 重试→Level 3 能力不足→自编译引导，全触发验证

> 📄 **完整成果与原始日志**：见 [`docs/REAL-VERIFICATION-RESULTS.md`](docs/REAL-VERIFICATION-RESULTS.md)（10 项成果逐条带证据）

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


有关于架构师，也就是我本人的一些补充
这个项目5个模块实际上已经说完了，它本身上是一个已经验证了的骨架，以及填充了一部分血液的东西。但是也就是说，它的前身就是我们一直学习前辈voyager，然后，逐步的架构多次迭代，最终形成了一个项目，还有，就是要补充的就是那个自编译系统，实际上目前最稳定的是外部编译，也就是说，就比如说你用codex，或者说，claude，我特地为AI写了一个skills，他只需要读取了之后就会知道如何自动进入mc验证，以及怎么写边界模块，以及怎么回滚，以及怎么做测试。你究竟该怎样才算成功，以及怎么协助游戏内的AI完成任务？怎么去规划？怎么去执行业务，怎么去写工具？自己的边界在哪里，自己的职责在哪里，自己哪些能改，自己哪些不能改，而不是游戏内那个管道。游戏内的管道暂时还没有多处测试，也就是说，这个自编译系统它可以自己跑车，让他自己跑个3个小时，甚至是24个小时，没有，他自己测试这个程序怎么样，稳定性怎么样？自己带AI通关mc探索，然后可以用指令，也可以自己进游戏操作，然后修改代码，再进游戏重新用，他有自己的权利，有自己的工具，有自己的监测台，也就是对游戏内查看日志之类的，而且我们的那个监测台和那个AI用的监测台基本是同一个，也就是说AI能看到什么，我们就能跟着看到什么，同样，这也是为什么我写代码写的这么快的原因，因为我给他加上了一套编程流程。偶尔我会去做一些大的规划，但是现在因为架构基本完全了，所以说规划比较少，大部分都是自己跑，然后，还会给他上一大堆的特定的补充资料，以及我之前测试了这么久找到的短板，然后，其他程序都还好，也就是任务量啊，或者说ac执行器啊，这些东西都是重点干了一些的，而且也能用了，但是都是没有进行细化，因为确实没有时间让AI自己跑测试的，我也没钱了，也要上学了，反正后面有月假了，可能会去弄，再有就是，那个记忆库我是特地不弄的，因为我觉得，嗯，他确实写完了，但是没有接。我确实是故意不让他弄的，因为确实没什么空，所以说就专注于四个模块就可以了。还有就是那个可视化，我是做了自己的优化的推算，在游戏跟游戏外都可以用那个UI看，而且，我检测架构话，我现在只做了数据流检测，没有对代码的整体架构进行大量重申，那个数据流的话，但是没什么问题，符合了我的预期，这接近10000行的代码，应该不是AI拉的石，毕竟我也会自己去检查一下，然后也确实去游戏看了一下，确实能干事儿，也测试了，没有能力退化，嗯，当然，如果说真能力退化了怎么办呢？我们还有模块化回本，随便回本几个版本，然后组合起来看一下究竟是哪个的问题就可以了。

## 许可证

本项目代码 MIT License（监测台 / 文档 / 脚本）。**借用宿主 Numen 遵循其自身许可证。**
