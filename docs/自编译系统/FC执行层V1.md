# FC 执行层 V1（Numen）

FC 是 Numen 的自动生存防线，不是第二个任务调度器。它复用现有
`CompanionBrain -> TaskSelector` 单身体仲裁，每个 tick 最多一个身体控制者，
显式任务仍由任务槽负责。

## 当前能力

| 能力 | 实现 | 边界 |
| --- | --- | --- |
| 摔落救援 | `MLGChain` | 有水桶或软方块时才触发；使用真实射线；水会在短窗口内回收 |
| 缺氧上浮 | `BreathChain` | 低氧或创造模式长时间沉底时上浮；封顶水域搜索附近出气口 |
| 近身自卫 | `MobDefenseChain` + `AttackCompanionTask` | 只处理正在攻击同伴或刚造成伤害的敌对生物 |
| 战斗举盾 | `AttackCompanionTask` + `ShieldPlan` | 只在近身威胁和攻击冷却窗口举盾；不打断弓、吃饭等其他物品使用 |
| 脱困 | `UnstuckChain` | 仅在持续移动却没有位移时做短促脱困，不会唤醒一个静止身体 |

优先级固定为：摔落 > 换气 > 自卫 > 脱困 > 显式任务。更高优先级抢占后，
低优先级链通过 `stop(PREEMPTED)` 让出身体；不会建立第二套并发控制器。

## 开关

通过 Numen 工具 `fc_control`：

- `status`（或省略 `action`）：读取当前状态和能力清单；
- `enable`：恢复自动 FC；
- `disable`：关闭自动 FC，下一次 brain tick 让出身体，不取消显式任务。

开关保存在同伴 playerdata 的 `NumenFcEnabled` 字段。旧存档没有该字段时默认
开启，避免升级后意外失去救命能力。`get_self_status` 的 `fc` 字段同时返回
`enabled` 和能力列表。

## 验证入口

1. 头戴/背包放置水桶或软方块，使用 `test` 实例验证坠落救援；观察世界位置、
   生命值和水桶回收事实。
2. 用 `fc_control disable` 后制造威胁，确认 FC 不再抢身体；再用 `enable` 恢复。
3. 检查 `config/numen/monitor` 的事件日志，不能只以 JAR 编译成功或 MCP 回执
   作为行为成功。

实现文件位于 Numen：

- `api/common/.../NumenPlayer.java`
- `core/common/.../NumenCore.java`
- `core/common/.../task/chain/{MLGChain,BreathChain,MobDefenseChain,UnstuckChain}.java`
- `core/common/.../tools/survival/FcControlTool.java`

