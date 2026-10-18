# RDD / Numen 经验记忆库架构

**状态：✅ 已实现并真机验证通过（2026-08-31）**  
**日期：2026-08-31（本文件先写架构，后按实现现状补充 §12）**  
**负责模块：经验记忆库（Experience Memory）**  
**归属：Numen 独立仓库**

> 本文档前 11 节是架构设计与验收标准（设计稿语气）。**实际实现与设计的对照、代码位置、验证结果、未实现项，见 §12。** 后续接手（尤其自编译系统）请以 §12 为准。

---

## 1. 这份文档负责什么

本模块负责把 AI 执行过程中的高价值经历提炼成一条条可复用的经验，并在之后的任务、异常、规划和复盘阶段检索、过滤和注入。

本模块不是监测台、不是任务链、不是 Self-Compile，也不是旧 MaidSoulCore1 的继续开发。

```text
旧 MaidSoulCore1
  不继续堆新架构；只作为历史参考或兼容端

ChatCore / 现有 Numen 记忆能力
  复用其已有的存储、索引和召回能力

Numen Experience Memory
  新增经验语义层，负责经验提炼、验证、应用和生命周期
```

---

## 2. 核心定位

> **Memory V3 / 现有记忆能力负责找出“过去发生过什么”；Experience Memory 负责把它提炼成“以后应该怎么做”。**

经验记忆库不是把每条聊天消息或每条日志都保存为经验，而是将有复用价值的执行经历转化为结构化经验：

```text
任务 / 工具 / 环境 / 思考 / 结果
              ↓
          经验候选
              ↓
    反思、去重、分类、验证
              ↓
       一条 Experience Entry
              ↓
  未来任务或异常时自动检索和注入
```

---

## 3. 与其他模块的边界

```text
┌─────────────────────────────────────┐
│ Numen                               │
│                                     │
│  Chat AI                            │
│  RDD TaskChain / Executor           │
│  Monitoring / Supervisor            │
│  Experience Memory  ← 本模块         │
└────────────────┬────────────────────┘
                 │ 适配器 / 公共接口
                 ▼
┌─────────────────────────────────────┐
│ 现有记忆底座                         │
│ Memory V2/V3、LifeMemory、向量/关系检索│
└─────────────────────────────────────┘
```

### 本模块拥有的职责

- 定义经验条目的结构和类型；
- 接收任务、工具、环境、异常和结果事件；
- 从原始事件中提炼经验候选；
- 去重，避免重复失败刷屏；
- 记录根因、处理方式和适用条件；
- 管理经验成熟度、验证次数和失败反例；
- 调用现有记忆检索能力召回候选；
- 按上下文、版本、Mod、成熟度和优先级过滤；
- 输出有界的经验投影给 Chat AI、TaskChain/Executor、Monitoring/Supervisor；
- 根据真实结果升级、降级或修正经验。

### 本模块不拥有的职责

- 不执行 Minecraft 工具；
- 不拥有 RDD `TaskChain` 状态；
- 不拥有 `AssetRegistry` 状态；
- 不代替 Monitoring Station 观察当前运行；
- 不代替 Supervisor 做当前任务的最终判断；
- 不让多个 AI 抢占同一个聊天出口；
- 不重写 Memory V3、向量数据库或 JSONL 基础存储；
- 不修改 `E:\MaidSoulCore1` 旧灵魂核心架构。

---

## 4. 经验条目定义

“一条经验”不是一句日志，而是经过提炼后可独立检索、可验证、可复用的单元。

建议字段：

```text
ExperienceEntry
├── identity
│   ├── id
│   ├── stableKey / dedupKey
│   └── schemaVersion
├── meaning
│   ├── type
│   ├── title
│   ├── description
│   ├── rationale
│   ├── rootCause
│   └── recommendedResponse
├── triggers
│   ├── taskIntent
│   ├── triggerStrings
│   ├── environmentTags
│   ├── toolNames
│   └── errorCodes / symptoms
├── evidence
│   ├── sourceEventIds
│   ├── observedAt
│   ├── verifiedAt
│   ├── verifiedCount
│   ├── failureReasons
│   └── counterexamples
├── applicability
│   ├── mcVersion
│   ├── modLoader
│   ├── activeMods
│   ├── dimension / environmentId
│   └── requiredCapabilities
├── relations
│   ├── linkedActions
│   ├── linkedPerceptions
│   ├── causalRequires
│   └── causalEnables
└── ranking
    ├── maturity
    ├── priority
    ├── confidence
    ├── lastAccessedAt
    └── decay / reactivation data
```

### 经验类型

第一版可以先支持：

- `EXECUTION`：某类任务如何完成；
- `FAILURE`：某类失败的现象、根因和恢复方式；
- `TOOL_DEFECT`：工具假成功、参数歧义、错误不完整等缺陷；
- `WORLD_RELATION`：物品、地点、前置条件之间的关系；
- `POLICY`：遇到某类情况时应采用的判断规则。

---

## 5. 成熟度与可信度

经验成熟度使用旧 DD 已确定的四级语义：

```text
OBSERVED     观察到 / 被教过
ATTEMPTED    自己尝试过，但未证明有效
VERIFIED     真实环境中成功并确认结果
GENERALIZED  跨位置、任务、种子或环境重复成功
```

写入不等于学会。单次观察和单次失败不能直接成为高优先级规则。

建议将以下概念分开：

```text
maturity   验证到了哪一级
confidence 当前有多大把握
priority   出错时有多严重
verifiedCount 成功证据次数
```

失败必须先区分：

```text
观察错误
规划错误
执行错误
工具假成功
环境不可用
真实能力缺失
```

只有重新观察、修正参数、替换已有能力和重规划都无法解决，并且明确确认能力缺口时，才允许把问题升级给 Self-Compile。

---

## 6. 与现有 Memory V3 的关系

```text
Experience Layer
  经验模型 / 提炼 / 成熟度 / 过滤 / 注入
                ↓
Memory Adapter
  将经验查询翻译成现有记忆查询
                ↓
现有 Memory V2/V3 能力
  rule + sparse + relation + vector + RRF（以实际可用接口为准）
                ↓
原始记忆、生活记忆、事件、关系和向量索引
```

### V3/现有记忆能力负责

- 候选召回；
- 关键词、稀疏、关系、向量检索；
- 多路结果融合；
- 原始记忆和事件的持久化/索引。

### Experience Layer 负责

- 判断候选是否是可复用经验；
- 把原始记忆转换成经验条目；
- 经验去重和质量门控；
- 成熟度、验证证据和失败反例；
- 当前版本/Mod/环境过滤；
- 经验应该注入哪个消费者；
- 经验是否足以影响当前决策。

**重要：**实际接入前必须以 Numen 当前可访问的公共接口或 Bridge 契约为准，不能假定某个旧仓库中的内部类仍可直接引用，也不能为接入而修改旧 MaidSoulCore1。

---

## 7. 事件与数据流

### 7.1 经验产生

```text
TaskChain / Executor
  → task_started / progress / tool_result / task_finished

Numen / MC 环境
  → observation / world_changed / inventory_changed

Monitoring / Supervisor
  → timeout / stalled / contradiction / abnormal

Chat AI / 主人
  → correction / instruction / feedback

以上事件
  → ExperienceCandidateExtractor
  → dedup + quality gate
  → ExperienceStore / Memory Adapter
```

### 7.2 经验使用

```text
当前任务 / 异常 / 工具失败
            ↓
ExperienceRetriever
            ↓
现有 Memory V3 或其他实际可用检索接口
            ↓
上下文过滤和成熟度排序
            ↓
┌───────────┼───────────┐
▼           ▼           ▼
Chat AI   TaskChain   Supervisor
```

### 7.3 结果回流

```text
经验被注入
  → AI 选择方案
  → 执行并观察真实结果
  → VerificationEvent
  → verifiedCount / maturity / counterexamples 更新
```

---

## 8. 与聊天 AI 的关系

第一阶段不让执行 AI 和监测 AI 直接抢聊天出口。

```text
TaskChain / Executor
  只发布结构化进度、结果和异常

Monitoring / Supervisor
  只发布结构化诊断和处理建议

Experience Memory
  提供相关经验和可信度

Numen Chat AI
  暂时保持主要自然语言表达出口
  读取进度、异常和经验后决定是否说话
```

推荐的进度事件：

```json
{
  "type": "task_progress",
  "taskId": "...",
  "status": "RUNNING",
  "stage": "MINING",
  "currentStep": "寻找铁矿",
  "completedSteps": [],
  "lastResult": "观察到目标区域",
  "needsIntervention": false
}
```

---

## 9. 经验写入质量门

禁止以下写法：

```text
每条聊天消息都写经验
每次工具调用都写经验
每次失败都无条件写经验
同一失败不断追加相同条目
只有 AI 自述，没有真实结果证据
```

写入前至少判断：

```text
□ 是否有跨任务复用价值？
□ 是否能说清现象和根因？
□ 是否有明确触发条件？
□ 是否记录了真实结果，而非工具自述？
□ 是否已存在同一 stableKey 的经验？
□ 是否需要新增证据而不是新增条目？
□ 是否带来源事件和环境上下文？
□ 是否明确当前只是 OBSERVED/ATTEMPTED？
```

---

## 10. 第一版建议范围

### 应做

1. 经验条目模型和 JSON 序列化；
2. 稳定去重键和质量门；
3. 经验候选事件接口；
4. V3/现有记忆能力适配器；
5. 经验查询与成熟度过滤；
6. 有界经验 Prompt 投影；
7. 验证事件和成熟度更新；
8. 单元测试、检索测试和注入测试。

### 后置

- 自动离线重放；
- 跨世界自动泛化；
- 复杂因果图推断；
- 自动生成新的 AC/工具；
- Self-Compile 自动闭环；
- 多 AI 自然语言互聊；
- 复杂向量模型迁移和重建。

---

## 11. 验收标准

最小闭环必须满足：

```text
输入一组真实或模拟执行事件
  → 只生成高价值经验候选
  → 稳定去重并落盘
  → 经验能通过现有检索适配器被召回
  → 按成熟度和环境过滤
  → 注入目标消费者
  → 新结果能反馈并更新验证证据
```

必须额外验证：

- 同一失败不会无限制造重复经验；
- 未验证经验不会以高可信规则注入；
- 工具 `success` 不能替代真实观察证据；
- 检索服务不可用时明确报告 `UNAVAILABLE`，不能伪装成“没有相关经验”；
- 经验块有长度上限，不淹没 Chat AI 或任务链上下文；
- 不修改 MaidSoulCore1；
- 不破坏 Numen 原生聊天、任务链和监测台。

---

## 12. 实现现状与交接（2026-08-31 终版）

> **给后续接手者（自编译系统）的话：本模块第一批已完成、已验证、已提交。下面是设计 → 实现 → 验证的对照与接缝。**

### 12.1 代码落地位置（已提交 commit 93a4bcd2 + 64832050）

```text
E:\restart developing doer\minecraft-numen
├── experience-core/                     纯 JVM，零 MC/Numen 依赖，可独立单测
│   └── src/main/java/com/dwinovo/numen/experience/
│       ├── api/    ExperienceType / ExperienceMaturity / ExperienceEntry
│       │           / ExperienceQuery / ExperienceHit / ExperienceRetriever(接缝)
│       └── core/   ExperienceStore / LexicalExperienceRetriever
│                   / ExperienceMemory / ExperienceStats
└── plugins/experience/                  NeoForge 适配器（mod id = expmem）
    └── src/main/java/com/dwinovo/numen/plugins/experience/
        ├── ExperienceMod        @Mod("expmem") 注册插件
        ├── ExperiencePlugin     NumenPlugin：注册 3 工具 + contributeState 上报统计
        ├── ExperienceLearnTool  experience_learn  写经验
        ├── ExperienceRecallTool experience_recall 检索经验
        ├── ExperienceVerifyTool experience_verify 报告真实结果
        └── ExperienceMonitor   观测出口（监测台 AI 加，未提交，见 12.4）
```

### 12.2 设计 vs 实现对照（诚实标注，别当全实现了）

| 设计点（§4-§10） | 实现状态 | 说明 |
|---|---|---|
| 经验条目模型 + JSON 序列化 | ✅ 已实现 | `ExperienceEntry` record + builder + toJson/fromJson |
| 稳定去重键 | ✅ 已实现 | `type\|规范化title`，同键合并不追加 |
| 质量门（写入门槛） | ✅ 部分 | 工具层校验 type/title/description 非空；无自动候选提炼器 |
| 成熟度四级 + 证据升级 | ✅ 已实现 | OBSERVED→ATTEMPTED→VERIFIED→GENERALIZED；success 累计、失败加反例 |
| 反例封顶 | ✅ 已实现 | `MAX_COUNTEREXAMPLES=5` |
| 原子落盘 | ✅ 已实现 | temp + ATOMIC_MOVE 全量重写 |
| 检索接缝 `ExperienceRetriever` | ✅ 已实现（接口） | 默认 `LexicalExperienceRetriever`（词法）；向量实现待接 |
| 词法检索 | ✅ 已实现 | 字段加权 + 成熟度/优先级系数 + 命中词透明 |
| `environmentTags` 环境标签过滤 | ❌ 未实现 | 模型里没做这个字段（只做了 triggerStrings/toolNames/tags） |
| `mcVersion`/`modLoader` 版本过滤 | ❌ 未实现 | 字段未做，检索无版本过滤 |
| `linkedActions`/`linkedPerceptions`/因果边 | ❌ 未实现 | 设计里有，第一版没落 |
| 自动事件提炼（TaskChain/工具结果→候选） | ❌ 未实现 | 目前只靠 AI 主动调 `experience_learn` |
| 自动注入 Chat AI / TaskChain / Supervisor | ❌ 未实现 | 目前靠 AI 主动调 `experience_recall` |
| 向量记忆接入（ChatCore/Numen V3） | ❌ 未实现 | 接缝已留，写 `VectorExperienceRetriever` 即可换 |
| 检测服务不可用时明确 `UNAVAILABLE` | ❌ 未实现 | 词法检索本地执行，无外部依赖，天然不适用 |

### 12.3 真机验证结果（测试 AI 报告，见 `expmem-module-verification.md`）

**结论：全部通过。**

- 单测 20/20；`:plugins:experience:build` SUCCESSFUL。
- jar 内嵌三类 class 齐全，mods.toml 变量展开无 `${...}`。
- 游戏加载：工具总数 54（含 `experience_learn/recall/verify`）；与 rdd/selfcompile 共存无冲突。
- 闭环：learn → id=`failure|向下挖矿前检查岩浆`，maturity=OBSERVED；recall("挖矿时小心岩浆") 命中（score=1.56, matched=岩浆），recall("熔炉") 不命中；verify success×3 → VERIFIED→VERIFIED→GENERALIZED；verify fail → 反例+1 不降级；同 title 再 learn 去重（文件行 1→1）；重启后经验仍在。

### 12.4 监测台对接（监测台 AI 的进行中工作，未提交）

- 新增 `plugins/experience/.../ExperienceMonitor.java`：往 `config/numen/monitor/expmem.jsonl` 追加 JSON 行（schema_version/envelope/type/data），行格式与 MonitoringJournal 对齐。
- `ExperienceLearnTool` 已加 `ExperienceMonitor.publish("learned", ...)`。
- ⚠️ 这些是**未提交**状态（git `M ExperienceLearnTool.java` + `?? ExperienceMonitor.java`），归监测台 AI 负责提交；我未动。

### 12.5 提交与回滚

- `93a4bcd2` = experience-core（15 文件）；`64832050` = plugins:experience（8 文件）。
- 各自 `git revert` 独立回滚；`settings.gradle` 每个 commit 只含自己一行 include。
- 未 push（本地领先 origin 多 commit，需团队协调）。

### 12.6 下一批建议（给自编译系统/后续 AI）

1. 自动提炼：接 RDD TaskChain / 工具结果事件 → 经验候选（`experience_learn` 保留为手动通道）。
2. 自动注入：`experience_recall` 结果按需注入 Chat AI / TaskChain / Supervisor（有界、防淹没）。
3. 向量检索接缝落地：`VectorExperienceRetriever` 接 ChatCore/Numen 已有向量记忆。
4. 版本/Mod 过滤 + `environmentTags` 补字段。
5. 写回 ChatCore 并档共享。
