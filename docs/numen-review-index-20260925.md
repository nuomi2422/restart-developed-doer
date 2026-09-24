# minecraft-numen 源码审核包（2026-09-25）

- **源码包**：`E:\新建文件夹\rdd架构\numen-src-20260925.zip`（`git archive HEAD` 全量已跟踪源文件，1710 项，10.76MB；**不含** build/.git/node_modules）
- **基线提交**：`0ec0d060`（本轮末尾）
- 项目：NeoForge 1.21.1 MC mod；模块化 Gradle（api / core / ai / ui / ac-api / ac-core / rdd-core / experience-core / plugins:*）

## 本轮改动文件（相对 `d6f46b24`，供审阅定位）

### AC（原子工具库）
- `ac-api/.../api/ToolRegistry.java` — 3 参注册默认方法改抛 UOE（不再静默丢 schema）
- `ac-core/.../core/AcParamValidator.java` — isInteger 扩 Short/Byte/Float/BigDecimal/BigInteger
- `ac-core/.../core/FileAcVersionStore.java` — save() 原子写（tmp→ATOMIC_MOVE，回退 REPLACE_EXISTING）
- `ac-core/.../test/.../AcSchemaCatalogTest.java`、`api/common/.../McpConfigGuardTest.java`

### RDD 任务链核心（rdd-core）
- `core/TaskChain.java` — 新增 `satisfiedStages`（构造+CONFIRM 跳过已达成一级、全达成→COMPLETED、不伪造二级完成、JSON 兼容、validate 自洽、expandCurrentPrimary 防绕过）
- `core/AssetRegistry.java` — `invalidateByType(type)`（死亡批量失效，不碰 world_，重观测恢复）
- `core/PlanningAssetSnapshot.java`（新）— 统一规划资产口径（缓存背包为底+注册表真相覆盖）
- `fact/`（新 5 文件）— P0 完成事实继承：`StageKeyNormalizer / GoalLineage / StageFact / SubtaskFact / CompletedFactStore`
- `fail/`（新 6 文件）— P2.0 失败诊断：`FailureKind / FailureEvent / RecoveryOutcome / RecoveryDecision / FailureContext / FailureClassifier`
- `policy/`（新 5 文件）— P2.1/P2.2：`AssetRole / AssetPurposeStore / RiskLevel / ResourceBudget / RiskGate`
- 测试：`AssetRegistryTest / CompletedFactStoreTest / PlanningAssetSnapshotTest / FailureClassifierTest / AssetPurposeStoreTest / ResourceBudgetTest / RiskGateTest`

### plugins:rdd（宿主接线）
- `RddPlugin.java` — 死亡事件失效背包资产、planningSnapshot、bindCurrent 注入完成事实、CONFIRM 记事实
- `RddFactStore.java`（新）— rdd-facts/<uuid>.json 原子持久化
- `RddStagePlanner.java` / `RddDecomposer.java` — 规划提示词改用统一快照 + **P2.2 规划期风险前置**（prepHint）
- `RddRiskPlanning.java`（新）— 规划期风险块 + 高风险阶段自动 wait_for 硬门
- `RddOptionalFood.java` / `RddSkipTool.java` — 食物子步放宽为“任意食物默认可跳（保留 optional=false 否决）”
- `RddDetector.java` — 死亡/完成接线

### core/common（Numen 工具修复）
- `tools/CraftOps.java` — hint 扫描带行为判定（模组工作台不再误报 None）
- `tools/BlockActionOps.java` — interact_at 允许对**方块**用消耗品（仅无目标朝前自用才拒；珍珠恒拒）
- `task/interact/InteractAtCompanionTask.java` — 打开工作站 GUI 不再谎报“nothing changed”

## 审阅重点建议
1. **TaskChain.satisfiedStages** 与既有状态机（RECOVERING/retry/expand/activate/CONFIRM）语义是否自洽；旧 JSON 兼容。
2. **CompletedFactStore + GoalLineage**：阶段级事实 vs 二级 condition 粒度；bigram 近似是否应收紧为“同血缘+全等”；缺 forget/invalidateFact。
3. **P2.0 FailureClassifier** 硬分类出口是否符合“代码先分类、AI 只出 REPAIR/REPLAN 方案”；如何接进 RddDetector 失败路径以激活休眠的 REPLANNING。
4. **P2.1/P2.2 AssetPurpose/RiskGate**：事实（AssetEntry）与用途分离是否合理；资源预算数值；规划期风险前置（提示词+wait_for）是否足够替代运行时门。
5. **工具修复**：interact_at 消耗品对块放行、CraftOps 行为扫描的性能与边界。

## 已知边界
- REPLANNING 出口生产不触发（REPLAN/REJECT 仅测试构造）——P2.0 将为其接活触发器（未接线）。
- P0 事实只从新代码 CONFIRM 起积累；旧链完成阶段无事实。
- bigram 近似偏宽；无 forget/invalidateFact；P2.3 Village/World、P2.4 ReplanContextBuilder 未做。
- P2.2 已改 plugins:rdd 并部署，需重启生效（规划期风险前置效果待游戏内验证）。
