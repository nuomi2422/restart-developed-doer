---
name: mcp-mc-quest
description: |-
  数字1 · 长任务轮询通关 MC：跑数小时真实 MC 游戏（gpt 存档，MCP 8765 驱动），
  遇可用 MCP 指令就推进，记录并完善 Numen 能力；把各种死亡/失败/失误经验收集
  为 AI 规划的一手资料（资料全量注入，暂不做检索）。通关目标按用户设定（如击杀末影龙）。
  Use when user wants 通关MC / 数字1 / 长任务轮询 / 代替Numen通关 / MCP驱动游戏.
  Voice triggers: "通关MC", "数字1", "长任务轮询", "代替Numen", "MCP玩MC".
  Metadata:
    role: "skills 级能力（暗号数字1），配套复活脚本 every-10min-drive 使用"
    requires_watchdog: "必须确认 every-10min-drive watchdog 在跑（AGENTS 〇-b），否则不得开长任务"
    mcp_port: "127.0.0.1:8765（gpt 生产实例，token 见 mcp_server.json）"
    output_root: "E:\\新建文件夹\\rdd架构\\"
---

# 数字1 · 长任务轮询通关 MC

## 定位

数小时在真实 MC 游戏里用 MCP 驱动同伴/玩家推进进度直到用户定义的通关目标
（缺省：击杀末影龙）。**这是 MCP 外部驱动（外部大脑=我们），不是 enqueue 内脑**
——进入 MCP 驱动后内置大脑自然停牌，直接派活即可（`McpServer.java:43` 无接管握手）。

## 前置（不满足不得开长任务）

1. **AGENTS 〇-b 复活脚本必须在跑**：`Get-Process -Id <pid>` or `drive-heartbeat.log`
   递增；断档先 `Start-Process ... every-10min-drive.ps1 -Loop -IntervalSec 600` 拉起。
2. **目标实例就绪**：gpt 生产 `E:\.minecraft\versions\The Best of Twilight Forest`，
   `config\numen\mcp_server.json` 的 `enabled=true`，端口 8765，存档 quickPlay "gpt"。
   默认 `-AutoStopMinutes 180` 看门狗防 MC 空转耗电。
3. **授权**：用户明确批示「代替 Numen 通关 MC」；此为对 AGENTS「关 MCP 止投」的
   **定向豁免**（仅本任务可投执行指令），干完仍按「② 关 MCP 止投」勾选还原只读。
4. 读熟 `E:\新建文件夹\rdd架构\numen-mcp-机制-源码核对版.md`（工具真名义，避免外脑幻觉名）。
5. **监测台必须先开（进游戏即开）**：`E:\restart developing doer\rdd-monitor-publish-20260913\monitoring-station`
   以 `NUMEN_MONITOR_DIR="E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor"` 跑
   `node server.mjs` → `http://127.0.0.1:8776/`；启动后明确告知用户打开它看 AI 实际输入/任务链/世界上下文
   （详见全局 AGENTS「十、监测台」）。**没开监测台不得进游戏开长任务**——用户要能实时看到 AI 干了啥。

## 工作流（感知 → 行动 → 核实 → 记录 循环）

1. **启动并握手**：`launch-mc.ps1 -Instance gpt`（或直接跑启动 bat）→ 等世界加载
   （1-3 分钟）→ `initialize` + `tools/list` 拿真实工具面（不要凭记忆猜工具名）。
2. **点名接管**：`list_companions` 看在线；`create_companion` 召唤；目标 id/名记下。
3. **感知基线**：`get_self_status`（坐标/血/饿/背包/装备/维度）、`scan_blocks`、
   `scan_nearby_entities`。行动前后各一次。
4. **推进进度**（每步一个 MCP action，核实时用世界事实）：
   - 生存起步链：采集→木/石/铁→钻石→装备（`mine` → `craft` → `equip_item`）
   - 下界：`mine` 烈焰粉 + 末影珍珠 → `craft` 末影之眼
   - 定位要塞：`locate_structure` / `mine` 攻势 → 末地传送门激活 → 进末地
   - 击杀末影龙（验收：世界里龙死，非 MCP 文案）
5. **轮询动作完成**：行动工具返 task_id → `task_status` 直到身体空闲（默认 300s 超时）。
6. **死亡/失败经验收集**：死亡→记录死因/环境/背包/坐标到 `data/quest-failures-<date>.jsonl`
   作为规划一手资料（全量注入，暂不做检索）。复活后恢复基线再续。
7. **止损**：连续 2 次同环节失败→换策略记录在案；预算/时间到→保全现场写日志退出。
8. **闭环记录**：每次会话结束写 `E:\新建文件夹\rdd架构\测试日志.md`（档位/目标/改动/
   测试/事实证据/下一步）；干完确认关闭 MC（防空转）+ 按 AGENTS 完成索引勾选。

## 九步流（用户要求「走九部编程流」时的骨架）

1. 分类（工具/模块/架构）→ 2. 探索环境与源码 → 3. 逐问澄清（目标/验收/止损）→
4. 方案比较 → 5. 外脑意见（DeepSeek，当参考不当真理）→ 6. 设计 → 7. 用户批准 →
8. 实现计划 → 9. 闭合验证（真实世界证据）。

## 工具真名义速查（杜绝幻觉名）

- 管理：`list_companions` `create_companion` `delete_companion`（无 acquire/release）
- 移动：`goto`（不是 move_to）；采掘：`mine`（不是 auto_mine）
- 感知：`get_self_status` `scan_blocks` `scan_nearby_entities` `look_around`
  `inspect_block_storage` `get_world_info` `lookup_recipe` `inspect_gui`
- 交互：`interact_at` `interact_entity` `transfer` `click_gui`
- 其他：`craft` `equip_item` `collect_items` `attack` `locate_structure`
  `locate_biome` `sleep` `follow` `place_block` `break_block`

## 经验资料格式（失败样本首行）

```json
{"ts":"ISO","instance":"gpt","phase":"生存起步","action":"mine iron_ore",
 "outcome":"died_hunger","pos":[x,y,z],"inv":{...},"learn":"反射只逃生不觅食；需备足前期食物"}
```

## 纪律

- 不伪造工具名；不确定先 `tools/list`。
- MCP 返回 OK ≠ 世界真变：核实用 `get_self_status`/`scan_blocks` 对撞。
- 给 Numen 的任何代码修改必须标注 diff 与理由，不搞乱执行层。
- 长任务必须配复活脚本；缺氧（429/限流）按 AGENTS 七/八换 provider 或止损记录。