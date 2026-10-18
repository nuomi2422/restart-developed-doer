# Numen MCP 运行机制（源码核对版）

> 替代先前外脑口述版。本文件每一条都对照真实源码，
> 依据 AGENTS 铁律「外脑回答当参考不当真理，涉及函数名必须对真实源码核」。
> 外脑（DS/GPT 网页）口述版本中臆造的工具名已在此纠正。

## 核心机制（McpServer.java:40-94, INSTRUCTIONS）

NumenMCP 是跑在游戏客户端内的一个 MCP 服务器（JSON-RPC over HTTP，
`POST /mcp`，Bearer token 鉴权）。外部 AI（Claude/Codex/opencode 等）通过它
**直接驱动游戏内 Numen 同伴的身体**——外部 AI 当大脑，同伴当手和眼。

**没有接管/交还握手**（`McpServer.java:41-44` 原文）：
> Three management tools — `list_companions`, `create_companion`, `delete_companion`
> — are advertised with an extra `companion` argument... There is no take-control
> handshake: 模式开启期间内置大脑一轮都不开, 所以外部驱动者直接派活即可。

即：**MCP 模式开启期间，内置 AI 恒停牌（`McpMode`/`isExternallyDriven`），
外部驱动者直接派活，无需 acquire/release。** 这正是 AGENTS「关 MCP 止投」与
日志 031 A 结论的设计本源——MCP 驱动与内脑是互斥的两条体。`enqueue` 发给内脑的
话在这种模式下被路由给外脑（`NumenGateway.java:89` → `Delivery.TO_EXTERNAL_BRAIN`）。

## 生命周期（真实循环，非 acquire→release）

1. `list_companions` — 列出在线同伴（名字+id）；没人可 `create_companion`（点名召唤）/ `delete_companion`（解散）。
2. **感知**（每次行动前后）：`get_self_status`(坐标/血/饿/背包/装备/维度)、`scan_blocks`、`scan_nearby_entities`、`look_around`(近场高密图)、`inspect_block`/`inspect_block_storage`、`get_world_info`、`lookup_recipe`。
3. **行动**：`goto`(移动)、`mine`(按类型量采掘)、`craft`、`equip_item`、`attack`(按 scan_nearby_entities 的实体 id)、`interact_at`(世界方块右键/使用/激活/拆)、`interact_entity`(追踪实体的交互)、`collect_items`(拾取)、`place_block`/`break_block`(单格放/破)、`locate_structure`、`locate_biome`、`sleep`、`follow`、`inspect_gui`+`transfer`+`click_gui`(容器/工作台/GUI 链，配合 craft)。
4. 行动工具立即返回 task_id → 轮询 `task_status` 直到身体空闲 → 再感知确认。

## 关键约束（INSTRUCTIONS + 源码）

- **无 give / setblock**：工具只做真玩家能做的事，生存规则硬限制（`McpServer.java:81`「there is no give or setblock」）。材料要自己采。
- **每次调用必须带 `companion` 参数**（名字或 id，`companion` 参数内建广告在 `McpServer.java:354` 附近）。一个 agent 可并行驱动多个同伴，每具身体独立跑任务。
- **每次调用之间是"盲"的**：行动前后必须用感知工具确认世界状态。
- **调用是阻塞式的**：动作类 tools/call 等任务完成/超时才返回（默认 300s 超时，`config timeout`）。
- **要陪聊**：驱动期间每 ~2s 调一次 `get_events(companion)` 长轮询（默认等 2s，急件立即返回），玩家在游戏里说话以 `<query>` 到达，世界事件以 `<event>` 到达；回话用 `say(companion, text)`。`wait_seconds` 最多 50s。
- 模组方块/物品/GUI（Create、AE2、Mekanism）原生可用。

## 工具真名义（源码 name() 实证，纠正外脑口述版）

| 类别 | 真实工具名 | 源码证据 |
|---|---|---|
| 管理 | `list_companions` `create_companion` `delete_companion` | `McpServer.java:252/41-43` — **无 acquire/release** |
| 移动 | `goto`（非 move_to） | `MoveToTool.java:25` |
| 采掘 | `mine`（非 auto_mine） | `AutoMineTool.java:26`；描述 38-40 亦用 "mine/goto" |
| 攻击 | `attack`（按实体 id） | `AttackTool.java:25` |
| 感知 | `get_self_status` `scan_blocks` `scan_nearby_entities` `look_around` `inspect_block_storage` `get_world_info` `lookup_recipe` `inspect_gui` | 各 `perception/`...`Tool.java` + `ModLanguageData.java:351-373` |
| 交互 | `interact_at` `interact_entity` `transfer` `click_gui` | `InteractAtTool.java:25` `InteractEntityTool.java:25` `GuiOps` |
| 其他 | `craft` `equip_item` `collect_items` `locate_structure` `locate_biome` `sleep` `follow` `place_block` `break_block` | 各 TaskRecord `TOOL_NAME` + `ModLanguageData` |

> 注：`execute_tool` 层的异步任务名（`mine`/`build`/`craft`）与 RDD 词表
> （`mine/craft/equip_item/collect_items`）见 `RddBodyTools.java`，别名 `mine_block/move_to/equip`
> 为旧臆造名，已归一（`RddBodyTools.java:24`）。

## 与 AGENTS「关 MCP 止投」的关系

- **默认/探索态**：本 V3 免费集群按 AGENTS 铁律不向 MC/MCP 投执行指令，只读观察。
- **用户授权态**（自编译 V3「数字1」/Supervisor MCP 通关路线）：按本文件生命周期用 MCP 驱动，
  仍必须遵守「MCP 返回成功 ≠ 世界真变，配世界快照对撞确认」硬门禁。
- 任何时候给 Numen 的代码修改必须标注，不搞乱 Numen 执行层。

## 下一步

- 回传「外脑口述版的 acquire/release、move_to、auto_mine 三个幻觉工具名」给外脑池复训。
- 需要时可按本文件生成给任意接 MCP 的 AI 的驱动提示词模板。
