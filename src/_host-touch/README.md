# src/_host-touch/ — 我们写的、但住在宿主模块里的文件

这里放的是**本项目自己写的源码**，只是它们必须落在**宿主 Numen 的模块路径**下，
不是 Numen 原版代码。

保留完整原路径，方便对照真实构建树：

```
src/_host-touch/<宿主模块原路径>
```

例如 `src/_host-touch/api/common/src/main/java/com/dwinovo/numen/agent/goal/GoalSinks.java`
对应构建树里的 `api/common/src/main/java/com/dwinovo/numen/agent/goal/GoalSinks.java`。

## 为什么单独放一个目录

`src/` 下另外五个目录（`ac-api` / `ac-core` / `rdd-core` / `experience-core` / `plugins`）
是本项目的**独立模块**，边界干净，零宿主依赖或只依赖 `Adapter / Port`。

这 18 个文件则是**伸手进宿主模块**的接缝——它们需要宿主内部类型才能工作
（目标出口、埋点通道、GUI 栈），没法搬到独立模块里。单列出来是为了让
"独立模块" 和 "接缝代码" 一眼可分。

## 清单（18 个）

| 宿主位置 | 文件 | 作用 |
|---|---|---|
| `api/common` | `GoalSinks.java` | 目标出口接管（RDD 接入点） |
| `api/common` | `MonitoringJournal.java` | 监测台事件落盘 |
| `api/common` | `PlanningQuery.java` | 规划查询接口 |
| `api/common` | `PlanningKnowledgeContributor.java` | 规划知识注入缝 |
| `api/common` | `FunctionalBlockTypes.java` | 功能方块类型登记 |
| `api/common` | `MonitoringScreen.java` | 监测界面（client） |
| `core/common` | `ClickGuiTool.java` | `click_gui` 工具（自编译闭环产物） |
| `core/common` | `FcControlTool.java` | 同伴控制工具 |
| `core/common` | `LavaEscapeChain.java` | 岩浆逃生链 |
| `core/common` | `SuffocationEscapeChain.java` | 窒息逃生链 |
| `ai` | `LlmObservation.java` | LLM 观测埋点 |
| 各模块 | 6 个 `*Test.java` | 上述文件的单测 |

## 注意

- 这里只有**新增**的文件。对宿主**已有文件**的修改（不是新增）不在这里，
  见 [`patches/heartpact-modifications.patch`](../../patches/heartpact-modifications.patch)。
- ⚠️ 该 patch 目前仍是 **2026-08-31** 的版本，尚未跟上后续改动。
