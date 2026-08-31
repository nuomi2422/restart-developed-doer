# RDD / Numen 经验记忆库施工进度

**负责人：经验记忆库模块**  
**日期：2026-08-31**  
**状态：✅ 第一批全部完成并**真机验证通过**、精细模块化 commit 完成；监测台已对接；使命移交自编译系统。遗留：push 待团队协调。**

## 已完成

### 第一批（代码落地，2026-08-31）

- [x] 明确模块归属：Numen 独立仓库（`E:\restart developing doer\minecraft-numen`）。
- [x] 明确旧项目边界：不修改 `E:\MaidSoulCore1`；不把旧 MaidSoulCore1 作为新架构宿主。
- [x] 明确 ChatCore/现有记忆能力的角色：只作为存储/索引/召回底座，以实际可访问公共接口为准。
- [x] 新建纯 JVM 核心模块 `experience-core`（零 MC / Numen 依赖）：
  - API 契约：`ExperienceType` / `ExperienceMaturity`（OBSERVED→ATTEMPTED→VERIFIED→GENERALIZED）/ `ExperienceEntry`（builder + JSON codec + 稳定去重键）/ `ExperienceQuery` / `ExperienceHit` / `ExperienceRetriever`（检索接缝）。
  - 核心实现：`ExperienceStore`（JSONL 原子落盘 + 稳定键去重 + 成熟度证据升级 + 反例封顶）、`LexicalExperienceRetriever`（纯词法默认检索，命中词透明）、`ExperienceMemory`（学/验/查门面）、`ExperienceStats`。
  - 单测 20 个全绿：去重合并 / 成熟度升级 / 反例封顶 / 持久化重载 / 坏行容错 / 词法召回排序 / 成熟度+标签过滤 / JSON round-trip。
- [x] 新建 NeoForge 适配器 `plugins:experience`（mod id `expmem`）：
  - `ExperienceMod`（@Mod 注册插件）。
  - `ExperiencePlugin`（实现 NumenPlugin：注册 3 工具 + contributeState 上报 total/verified/generalized）。
  - `experience_learn`：AI 写一条经验（type/title/description/root_cause/recommended/trigger_strings/tool_names/tags/priority）。
  - `experience_recall`：按当前任务/失败/异常检索，返回 现象/根因/推荐处理/成熟度/得分。
  - `experience_verify`：报告真实结果，成功升级成熟度、失败加反例。
  - build.gradle 按 `plugins:rdd` 模式内嵌 `experience-core`，插件 jar 独立自包含。
- [x] `settings.gradle` 注册 `experience-core` 与 `plugins:experience`。
- [x] 编译验证：`:experience-core:test`（20/20 通过）+ `:plugins:experience:build`（BUILD SUCCESSFUL）。
  - 构建命令（JDK21 + 本地 gradle-9.2.0，wrapper 下载超时）：
    ```powershell
    $env:JAVA_HOME = "E:\jdk21\jdk-21.0.11+10"; $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
    cd "E:\restart developing doer\minecraft-numen"
    & "E:\restart developing doer\gradle-9.2.0\bin\gradle.bat" :experience-core:test :plugins:experience:build --no-build-cache --no-daemon
    ```
- [x] jar 内嵌验证：`numen-plugin-experience-1.21.1-0.1.3-dev.jar` 同时含 `experience/api`、`experience/core`、`plugins/experience` 三类 class；neoforge.mods.toml 变量已展开（`version=0.1.3-dev`、`loaderVersion=[4,)`）。
- [x] 部署：jar 已复制到游戏 3 路径（`mods/`、版本根目录、版本 `mods/`），时间戳一致。
  - 🔴 **未重启游戏**：其他 AI 正在同一 Numen 仓库并行开发，重启会打断；且游戏内 AI 带动会耗 token（A7）。加载验证等下次游戏启动时做。

### 精细模块化 commit（2026-08-31 晚，✅ 完成）

- [x] 两个模块各自独立提交，可单独回滚：
  - `93a4bcd2` `[experience] experience-core 纯JVM经验记忆核心模块…` — 15 文件，只含核心。
  - `64832050` `[experience] plugins:experience NeoForge适配器(expmem)…` — 8 文件，只含插件。
  - `git revert 93a4bcd2` / `git revert 64832050` 各自只回滚对应模块，不影响另一个模块与别人提交。
- [x] **并行 git 冲突已化解**：另一个 AI 的 `git commit --amend` 曾把 experience-core 吸进他的 rdd 提交（64d24017）；后其自行 reset 重做干净 rdd 提交（469dde59/072406d5），我的文件回到未跟踪干净状态，未混入任何他人 commit。
- [x] **共享文件 `settings.gradle` 处理**：备份完整版 → 临时只留我的 include 行 → `git commit <pathspec>` 只提交我的行 → 恢复完整版。提交历史里 settings.gradle 每个 commit 只含自己那一行；`plugins:selfcompile` 行仍留在工作区未提交（归其主人）。
- [x] 最终验证：`git log` = 64832050 → 93a4bcd2 → 其他 AI 的 rdd/selfcompile 提交；剩余未提交工作全是他人的（api/common 修改、monitoring、AcEvent、selfcompile include 行），未被动过。

### 真机验证通过（测试 AI 报告，2026-08-31，见 `expmem-module-verification.md`）

- [x] **全部通过**：单测 20/20；`:plugins:experience:build` SUCCESSFUL；jar 内嵌三类 class 齐全、mods.toml 展开无 `${...}`。
- [x] 游戏加载：工具总数 54（含 `experience_learn/recall/verify`）；与 rdd/selfcompile 共存无冲突。
- [x] 全闭环：learn → `failure|向下挖矿前检查岩浆` OBSERVED；recall("挖矿时小心岩浆") 命中(score=1.56, matched=岩浆)、recall("熔炉") 不命中；verify success×3 → GENERALIZED；verify fail → 反例+1 不降级；同 title 去重（1→1 行）；重启后经验仍在（§12 重启持久化 ✅）。

### 监测台对接（监测台 AI 的进行中工作，未提交）

- [ ] 监测台 AI 新增 `ExperienceMonitor.java`（往 `config/numen/monitor/expmem.jsonl` 发事件）+ 在 `ExperienceLearnTool` 加 `publish("learned", ...)`。
- [ ] 这些改动**未提交**（git `M ExperienceLearnTool.java` + `?? ExperienceMonitor.java`），归监测台 AI 提交；经验模块负责人未动。

## 当前尚未完成

- [ ] **游戏端加载验证**：下次启动游戏确认 `expmem` 加载、3 工具注册进目录、`registered N tool(s)` 数量增加、`experience_recall` 能被 AI 实际调用。
- [ ] 接入现有向量记忆：`ExperienceRetriever` 接缝已留，`LexicalExperienceRetriever` 只是第一块实现；将来写 `VectorExperienceRetriever` 代理 ChatCore/Numen 向量检索即可，模型/工具不改。
- [ ] 事件自动提炼：目前经验由 AI 主动 `experience_learn` 写入；自动从任务链/工具结果/失败事件提炼候选（RDD TaskChain / 监测台事件）未做。
- [ ] 注入消费者：目前靠 AI 主动 `experience_recall`；自动注入 Chat AI / RDD 任务链 / 监测台的 Prompt 块未做。
- [ ] 版本/Mod 过滤：`mcVersion`/`modLoader` 字段在模型里有预留，检索过滤逻辑未做。
- [ ] 写回聊天上下文与 ChatCore 并档：未接。
- [ ] 向量检索接缝落地（`VectorExperienceRetriever` 接 ChatCore/Numen 向量记忆）。
- [ ] 版本/Mod 过滤 + `environmentTags` 补字段（模型未做这些字段）。
- [ ] 自动提炼/自动注入（见架构文档 §12.6 下一批）。
- [ ] **push**：本地分支领先 origin 多 commit（含其他 AI 的提交）。push 会把所有人一起推上去，需要团队协调后统一 push。

## 使命移交（2026-08-31）

本模块（expmem）第一批的开发、构建、部署、精细提交、验证已全部完成并由测试 AI 真机确认。**之后经验记忆库的后续迭代由自编译系统负责**。接手入口：

- 架构 + 设计/实现对照 + 下一批建议：`rdd-experience-memory-architecture.md` **§12**。
- 验证报告：`expmem-module-verification.md`。
- 未提交的监测台对接（`ExperienceMonitor`）归监测台 AI。
- 遗留阻塞：push 需团队协调。

## 预定施工顺序（下一批）

1. 游戏端加载验证（expmem 加载 + 工具注册数 + AI 实际调用 experience_learn/recall/verify）。
2. 自动提炼：接 RDD TaskChain / 工具结果事件 → 经验候选（`experience_learn` 保留为手动/LLM 通道）。
3. 自动注入：把 `experience_recall` 结果按需注入 Chat AI / 任务链 / Supervisor 上下文（有界，防淹没）。
4. 向量检索接缝落地：写 `VectorExperienceRetriever` 接 ChatCore/Numen 已有向量记忆。
5. 成熟度与版本过滤补全 + 更完整测试。

## 明确不做

- 不改 `E:\MaidSoulCore1`。
- 不把旧 DD 的 `KnowledgeBase` 当作当前现成代码。
- 不复制或重写 Memory V3、向量索引和底层 JSONL 存储。
- 不把每条聊天、日志或工具调用都写成经验。
- 不以工具返回 `success` 代替真实环境验证（`experience_verify` 由 AI/检测方用真实观察调用）。
- 不让多个 AI 在第一阶段同时抢自然语言聊天出口。
- 不把经验层变成监测台、任务链或 Self-Compile 的替代品。

## 阻塞 / 需要协调

- ✅ **commit 已解决**：经验两模块已精细提交、可单独回滚，未裹入他人改动。
- 🔴 **push 待协调**：仓库本地领先 origin，push 会带上其他 AI 的未 push 提交，需团队统一决定。
- 游戏重启待协调：加载验证需下次启动游戏。
- 接入向量记忆需要确认 ChatCore/Numen 当前可访问的向量检索公共协议（以实际接口为准，不猜旧项目内部类）。

## 证据规则

后续进度严格区分：

```text
代码存在 ✅
已编译 ✅
宿主加载 ✅（游戏实测）
单元测试通过 ✅（20/20）
游戏运行时接线 ✅（测试 AI 真机验证）
真实世界结果验证 ✅（测试 AI 真机验证）
```

任一层不能冒充更高层证据。第一批全部六层证据已由测试 AI 真机确认。

## 关联文档

- `rdd-experience-memory-architecture.md`：本模块架构和边界。
- `rdd-monitoring-station.md`：监测台旁路观察边界。
- `rdd-asset-task-chain-spec.md`：RDD 任务链和资产状态主权。
- `self-compile-module.md`：Self-Compile 外部主控和能力缺口升级边界。
