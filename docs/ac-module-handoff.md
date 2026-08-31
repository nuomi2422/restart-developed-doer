# AC 执行层模块交接（自编译系统接手）

> 日期：2026-08-31
> 交接方：AC 执行层施工 AI
> 接收方：自编译系统（后续唯一写码 AI）
> 权威文档：`E:\restart developing doer\minecraft-numen\AC_EXECUTION_LAYER_EXTRACTION.md`（仓库内，含完整设计/边界/验证）
> 本文件是**接手索引**：先读本文件 → 再读权威文档 → 再动代码。

## 0. 一句话定位

**AC = 宿主无关的原子工具编排执行层**，是 RDD 五模之一但可完全独立运行。它接收宿主注册的原子工具，允许主 AI/编码 AI 创建、修改、版本化 AC，负责流程调度、PAUSED 后真实 resume、执行事实记录，并把结构化事件旁路给任务链/监测台/经验库。不生成工具、不做任务规划、不替 Supervisor 语义判断、不依赖 RDD 或 NUMEN。

## 1. 模块结构（当前已稳定）

```text
E:\restart developing doer\minecraft-numen\
├── ac-api/        纯契约层（零第三方依赖）
├── ac-core/       纯 JVM AC 实现（仅 Gson）
├── rdd-core/      纯 JVM RDD 任务链核心（2026-08-31 从 ac-core 迁出）
└── plugins/
    ├── ac/        Numen 宿主适配：惰性桥接工具 + ac_execute/ac_status/ac_resume 门面
    └── rdd/       RDD 宿主适配：依赖 rdd-core，不再内嵌 ac-core
```

依赖方向（单向，不许反向）：

```text
ac-api  ←  ac-core  ←  plugins/ac
                      plugins/rdd  → rdd-core（独立）
```

## 2. 我（施工 AI）已完成并提交的工作

### 五批次（提交可单独回退）

| 批次 | 提交 | 内容 |
|---|---|---|
| A | `f23af8a6` | AC 身份契约：name+version+canonical fingerprint；真实 resume（保留 input/output、重试暂停步、拒绝版本/内容变化静默续跑） |
| B | `251f2c98` | 事件流 AcEvent/AcEventListener；稳定 executionId；有界历史；监听器异常隔离 |
| C | `7347601b` | ToolSchema 有限参数契约；稳定工具目录；执行前参数校验；严格 JSON 解析 |
| D | `3efc666b` | AcAuthoringService（validate/publish/listVersions/load），发布原子、非法不破坏当前版本 |
| E | `69e4a274` | plugins/ac Numen 桥接：NumenToolBridge + 门面三工具 |
| docs | `f579fd02` | AC_EXECUTION_LAYER_EXTRACTION.md 建立 |

### 真机验证 3 缺口修复

| 缺口 | 修复 | 提交 |
|---|---|---|
| setup 太早只桥接少数工具 | **惰性桥接** ensureBridged()（ac_execute 前幂等同步） | `2c3f004e` |
| 裸 JSON 结果误判 FAILED | BridgeResultMapper 无 success 字段 → SUCCESS(data) | `2c3f004e` |
| AC/RDD 同装重复包 JPMS 冲突 | rdd 核心迁出 → 独立 rdd-core，plugins/rdd 依赖它 | `ebf8e8ed` |
| 文档更新 | §7 记录 3 缺口修复 | `c0b94e52` |

### 顺手修复（并行 AI 的）
- `c61270fd`：rdd 测试补 `import java.util.Map`（阻断 :ac-core:test 编译的最小修复）

## 3. 关键契约语义（改代码前必读，破坏 = 回归）

### PAUSED 是"可续"的中间态，不是成功也不是失败
```text
AC 执行中断 → PAUSED（保存 executionId/attempt/fingerprint/input/state/断点 step）
主 AI 选择 resume → 从暂停步骤重试，不从头重跑、不重复已完成步骤
版本或内容变化 → resume 明确拒绝（IllegalArgumentException），需迁移/重规划
```

### Numen 桥接的判据（水土不服的补丁）
```text
timed_out / interrupted          → PAUSED（未终态，可续）
success=true 且 data.async=true  → PAUSED（setTask 受理回执 ≠ 步骤完成）
无 success 字段的裸 JSON        → SUCCESS(data)（非身体工具 direct complete 的自定义结果）
其余 success=true               → SUCCESS；success=false → FAILED
工具声称成功 ≠ 世界已验证：AC 只记事实，观察证据由任务链/监测台旁路提供
```

### 其他
- fingerprint：canonical JSON（Map 排序 + 数字归一 1=1.0）→ SHA-256
- 门面工具 ac_* 不注册为 AC 步骤工具（防递归）
- Numen ToolRegistry 与 AC ToolRegistry 语义分离，只经 adapter 转换
- 执行前参数校验：AI 生成的参数不经检查不交给宿主工具

## 4. 验证状态

```text
:ac-core:test   ✅ 全绿（纯 AC）
:rdd-core:test  ✅ 全绿（14 例）
:plugins:ac:test ✅ 全绿（桥 7 例）
:plugins:rdd:test ✅ 全绿
jar 隔离        ✅ clean 重建后 ac jar 无 rdd 包、rdd jar 无 ac 包
真机            ✅ 验证 AI 已跑通 ac_execute→RUNNING→ac_status 链路
               ⏳ 3 缺口修复后的同装启动复验（自编译接手时做）
```

构建命令（wrapper 下载超时，用本地 Gradle）：
```powershell
$env:JAVA_HOME='E:\jdk21\jdk-21.0.11+10'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'E:\restart developing doer\gradle-9.2.0\bin\gradle.bat' -p 'E:\restart developing doer\minecraft-numen' :ac-core:test :rdd-core:test :plugins:ac:test --console=plain --no-daemon
```

## 5. 与其它模块的边界（不许越界）

- **资产任务链（plugins/rdd + rdd-core）**：拥有任务/资产/推进主权。AC 只提供执行事实与事件，不反写任务完成。目标拆解/Supervisor/资产判定在任务链侧。
- **监测台**：旁路订阅 AC 事件（AcEvent）用于观察，不拥有 AC 状态主权。
- **自编译（plugins/selfcompile）**：生成/修改原子工具 → JAR → 宿主重启 → Numen ToolRegistry 注册 → plugins/ac 惰性桥接补注册。AC 不参与编译、不做运行时热加载。
- **经验记忆库（experience-core）**：可订阅 AC 事件/记录归纳经验；AC 不依赖它。

## 6. 自编译系统接手铁律

1. **AC 核心（ac-api/ac-core/rdd-core）保持纯 JVM**，只许加 java.* + gson；碰 NumenTool/Minecraft 的代码一律放 plugins/*。
2. **PAUSED 语义不许改回 fail/ok**；resume 必须校验身份（fingerprint）。
3. **不把 Numen ToolRegistry 当 AC registry**，转换只经 plugins/ac 的 adapter。
4. **惰性桥接不删**：新 Numen 工具（自编译产出）重启后由 ensureBridged 自动补注册。
5. 改动后必须跑 §4 全部测试 + clean 重建验证 jar 隔离（防止重复包回归）。
6. 单模块提交，标注 `ac(...)` 前缀，可单独回退。

## 7. 已知限制 / 下一步

```text
⏳ 3 缺口修复后的同装启动复验（ac + rdd 一起）
⏳ 断点续跑全链真机复验（ac_execute→PAUSED→ac_resume→完成）
⏳ ac_cancel 门面（长任务释放目前靠 Numen task_stop / task_status 轮询）
⏳ Numen schema → ToolSchema 是宽松映射（enum/min/max/嵌套 object 暂不完整）
⏳ AcJson 数字归一为 Long/Double（1 与 1.0 同 fingerprint）
```

## 8. 关键文件路径

```text
AC 文档:     minecraft-numen/AC_EXECUTION_LAYER_EXTRACTION.md
ac-api:      minecraft-numen/ac-api/src/main/java/com/dwinovo/numen/ac/api/
ac-core:     minecraft-numen/ac-core/src/main/java/com/dwinovo/numen/ac/core/
rdd-core:    minecraft-numen/rdd-core/src/main/java/com/dwinovo/numen/rdd/
AC 插件:     minecraft-numen/plugins/ac/src/main/java/com/dwinovo/numen/plugins/ac/
RDD 插件:    minecraft-numen/plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/
```
