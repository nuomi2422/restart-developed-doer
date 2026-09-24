# RDD / Numen 监测台模块定位（V0.1）

## 为什么现在先接监测台

RDD 正在借用 Numen 的原生身体、执行和环境能力快速验证。验证期间，如果只有游戏内表现而没有外部观察入口，工具不执行、事件未到达、桥断开、任务停滞和 AI 没有继续推进都无法区分。监测台先提供“眼睛”，不等待整个 RDD 架构完成。

## 模块定位

Monitoring Station 是跨程序的观察与诊断模块：

- 观察 AI thinking、Prompt/Context/Tools 快照、工具调用和 AC 生命周期；
- 观察 Numen 世界事件、同伴状态和工具入口；
- 将观察数据按域分区，提供独立历史、滚动、导出和看门狗指标；
- 诊断 Monitoring UI、Adapter、RDD、Numen、MC 各段连接；
- 对控制命令显示真实 `commandResult`，不把前端状态变化当成执行成功；
- 允许后续接入任意 AI/环境/执行系统。

它不拥有以下状态主权：

- 不直接写 RDD `TaskChain`；
- 不直接写 `AssetRegistry`；
- 不代替 `DetectionScheduler` 或 Supervisor；
- 不实现 Self-Compile；
- 不把 NUMEN 内部类、线程、Future 或旧任务链格式变成公共契约；
- 本轮不实现工具健康资料库、崩溃库和经验库。

## 当前接入

第一阶段接入 Numen 原生观测日志：

```text
Numen → config/numen/monitor/*.jsonl → Monitoring Server → AUI 页面
```

当前 Numen 观测点：

- `events.jsonl`：统一世界事件入口；
- `state.jsonl`：同伴状态变化推送；
- `tools.jsonl`：工具请求和明确拒绝原因。

监测日志使用有界非阻塞队列和单写线程，不能反过来阻塞 Numen 主流程。日志失败只能产生诊断信息，不能伪造任务完成，也不能影响原有工具回执。

## 与 RDD 架构的关系

```text
┌─────────────────────┐
│ RDD TaskChain       │ ← 任务节点和推进主权
└──────────┬──────────┘
           │ 只观察事件/证据
┌──────────▼──────────┐
│ Monitoring Adapter  │ ← 翻译和分区，不写任务主状态
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ AUI Monitoring UI   │ ← 分页、日志、健康、架构/运维查看
└─────────────────────┘

Numen Adapter 是第一宿主适配器；Self-Compile 是独立插件，二者都不并入监测台本体。
```

## 观测与执行的边界

工具自述和环境观察必须分开：

```text
tool_call/tool_result = 工具声称做了什么
observation/state      = 系统实际观察到什么
```

监测台可以把两者并置用于排错，但不能因为工具返回 success 就显示世界已经改变。AC 的 `ac_step` 也必须保留结构和证据，不能只显示最终 SUCCESS。

## 数据流保护目标

旧 DD 监测台已经证明高频事件会拖垮同步写盘；新监测台采用：

- 生产和消费解耦；
- 有界队列；
- 分类 JSONL；
- 文件大小轮转；
- UI 只读当前域窗口；
- 高优先级错误/心跳/ACK 不被普通事件挤掉；
- 丢弃和降级必须可见；
- 真实宿主不可用时明确显示 `WAITING` / `UNSUPPORTED`。

## 后续接入顺序

1. 先验证 Numen 原生日志能稳定落盘和被监测台读取；
2. 再接 Numen/ RDD 的正式 Adapter 和事件 Envelope；
3. 再接 AI thinking、Context、工具结果和 AC 执行证据；
4. 最后根据真实命令契约开放控制按钮；
5. 资料库、自编译和更复杂运维能力另行接入。
