# RDD 懒展开多级规划 —— 设计（2026-09-06 用户批准）

> 状态：设计已批准，**实现尚未开工、实现位置待用户排**。
> 实现仓库 `D:\rdd\workspace\minecraft-numen`（分支 codex/rdd-monitor-v2）。
> 本会话已交付两批（见文末），本设计是下一批。
> 注：用户另有 test 实例 = 测工具用（编译系统验自己写的代码/进游戏档试工具），**不是**承接本设计的。

## 目标

`/goal` 通关MC → 先生成**全部一级**(阶段主题) → 一级**首次到达时才懒展开**其二级(带资产检测) → 每级能跑、能检测、能跨级依赖(wait_for)。

## 用户确认的 一级/二级 力度规则

```text
一级 = 阶段/主题(有名字的大阶段)   · 先生成所有一级 · 每级有对应检测
二级 = 一级内部具体小步(资产检测)  · 一级首次到达才展开(懒) · 数量依 AI 能力自适应
跨级前瞻 = 前一级备后一级的料(书/水瓶→附魔台/抗火药水) = 一级间依赖 → wait_for
```

## 通关 MC 验收链（10 个一级）

```text
① 一阶物资(石镐石斧/食物/床/书/水瓶/沙子, 预存后面)
② 铁器时代(铁套/铁桶/镐/剑/斧/盾)
③ 钻石+黑曜石+附魔(钻石套保护1/镐斧剑锋利/黑曜石)
④ 下界准备并进入(简易基地/门旁床复活/搭门点火)
⑤ 堡垒抗火药水(2×2卡位留一口/下界疣+烈焰棒/炼8分钟 6+瓶)
⑥ 刷烈焰人(喝抗火/持盾/卡位 攒末影珍珠)
⑦ 末影之眼+要塞(诡异森林平台刷末影人→合成末影之眼)
⑧ 回主世界收基地→出发末地门
⑨ 末地门前终备(基地换装/弓/门前放床)
⑩ 战末影龙(打水晶/放水/稳妥)
总纲稳字诀固定注入；跨级 wait_for: ④⑤ 等 ③ 钻石套保护1
```

## 实现要点（分三批，模块化 commit）

### 批A 数据模型（纯 JVM + 单测）
- `PrimaryGoal` 允许 0 二级 + `unexpanded=true` + theme 提示串；compact 放宽"≥1 subtask"约束
- `TaskChain.currentSubtask()` 遇未展开一级 → 返回占位语义(不 NPE)
- `toJson/fromJson` 兼容 unexpanded（旧链无损）
- 单测：未展开一级推进到边界不崩；展开后注入 subtasks 正常进依赖门/激活

### 批B 展开触发（宿主侧，类比现有 decompose）
- Detector tick 见当前一级 unexpanded → 异步 LLM 展开该级二级(客户端)
- 成功→注入 subtasks→复用依赖门/激活/资产检测/early_achievement(已写)
- 失败/超时→注入占位二级(asset_key=goal)，不吞目标
- 二级数量自适应：提示词按难度/上下文给"简单粗(3-5步)/复杂细(可检测)"

### 批C 通关MC 验收链
- 以 10 一级链作 /goal 真机验收；监测台 RDD 分页看懒展开+依赖等待+推进

## 已交付（本会话，可直接复用）

```text
00aee979 rdd-core: 一级依赖门 wait_for + WAITING(PrimaryGoal.waitFor/AssetRequirement/
         TaskChain.activateCurrent/currentPrimaryReady/snapshot waitingFor) TaskChainTest 9/9
3e1b45f6 plugins/rdd: 资产提前触发 EarlyAchievement + 一级边界过依赖门 +
         修"多一级 CONFIRM 后 PENDING 被吞→第二级永不启动"卡死
jar: plugins/rdd/build/libs/numen-plugin-rdd-1.21.1-0.1.3-dev.jar
已部署三处, 旧包 .bak-20260906-EARLYACH
```

## 注意
- 本仓多线共享，commit 只选本批文件，绝不 git add -A
- gradle: JAVA_TOOL_OPTIONS 带 127.0.0.1:7897 代理（wrapper 下 9.2.0 会裸连超时）
- 数据模型放宽不变式是最大风险点：currentSubtask/持久化 多处引用，先单测锁死
