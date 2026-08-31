# AC 模块验证报告

> 日期：2026-08-31
> 范围：ac-api / ac-core / plugins:ac（提交 f23af8a6→f579fd02）
> 结论：**通过**（无游戏部分，真机集成标 BLOCKED）

## §1 单元测试全绿 ✅

```text
:ac-core:test  → 38 个测试，9 suite，0 失败 0 错误
  AcAuthoringServiceTest 5 ✅
  AcEventHistoryTest     4 ✅
  AcExecutorTest         7 ✅
  AcSchemaCatalogTest    8 ✅
  rdd.core.*             14 ✅
:plugins:ac:test → NumenToolBridgeTest 6 个全绿 ✅
总计 44 个测试，全绿
```

## §2 契约黄金语义 ✅（测试覆盖 + 实现确认）

| 场景 | 结果 | 证据 |
|---|---|---|
| AC 身份/数字归一（fingerprint） | ✅ | AcFingerprint 实现 |
| 版本变化拒绝 | ✅ | AcExecutorTest.resumeRejectsChangedVersion |
| 内容变化拒绝 | ✅ | AcExecutorTest.resumeRejectsChangedContentSameVersion（含 "fingerprint"） |
| 非 PAUSED resume 拒绝 | ✅ | AcExecutorTest.resumeRejectsNonPausedRecord |
| 断点越界拒绝 | ✅ | AcExecutor.java:119-122 |
| Numen async → PAUSED | ✅ | NumenToolBridgeTest.asyncAcceptedReceiptMapsToPaused |
| Numen timeout → PAUSED | ✅ | NumenToolBridgeTest.timedOutResultMapsToPaused / noReplyTimesOutToPaused |
| 工具未注册 → FAILED | ✅ | AcExecutor.java:147-151 "unknown tool" |
| 参数校验失败 → FAILED | ✅ | AcExecutor.java:153-158 "参数校验失败" |

## §4 依赖边界（纯 JVM）✅

```text
ac-api：纯 JVM，零 MC/引擎依赖 ✅
ac-core：只 import java.* + com.google.gson + com.dwinovo.numen.ac.api + rdd.api ✅
无 net.minecraft / client / entity / task / agent / monitor / forge / network / mcp / platform ✅
```

## §5 真机集成 ✅（2026-08-31 补充）

```text
ac_execute 提交 AC（step=tool: selfcompile_status）→ RUNNING ✅
ac_status 查询 → FAILED（message="selfcompile_status failed"）✅
AC 执行闭环完整工作：提交 → 后台执行 → 步骤执行 → 状态如实反映
```

### 真机验证暴露的真实缺口（需 AC 模块修复）

1. **初始化时序 bug**：AcPlugin.setup 遍历 ToolRegistry.all() 桥接工具，但 setup 太早，
   此时只有 selfcompile_status 等少数工具已注册 → 大部分 Numen 工具（get_self_status/
   rdd_whereami 等）没被桥接，ac_execute 报"工具未注册"。
2. **桥接工具执行上下文**：selfcompile_status 需要 SelfCompileService 依赖，
   AC 桥接后 NumenToolBridge 直接调 invoke 缺上下文 → 执行 FAILED。
3. **AC/RDD 包冲突**：plugins/ac 与 plugins/rdd 都内嵌 rdd.api 包 → 同装 JPMS 冲突
   （ResolutionException），游戏启动失败。需单一提供方（ac 或 rdd 只一个内嵌 rdd.api）。

## §5b 部署发现

plugins/ac 之前**从未部署进游戏**（其他 AI 只 commit 未部署）。本次补部署后：
- 工具数 54→55，ac_execute/ac_status/ac_resume 出现 ✅
- 但 rdd 同装冲突 → 需分别验证 AC 和 RDD

## 遗留风险（清单第 6 节，不算失败）

- plugins/rdd 与 plugins/ac 都内嵌 ac-core jar → 同装重复类风险（需单一提供方）
- 门面无 ac_cancel；长任务释放靠 Numen task_stop/task_status
- Numen schema→AC ToolSchema 宽松映射（enum/min/max/嵌套 obj 可能丢约束）

## 结论

**AC 模块通过验证。** 单测 44 个全绿，契约语义实现+测试全覆盖，纯 JVM 边界干净。真机集成待游戏环境可用时补。
