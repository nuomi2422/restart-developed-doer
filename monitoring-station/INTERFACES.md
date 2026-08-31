# RDD Monitoring Station 接口清单（V0.1）

本目录是 AUI 监测台样板，不代表 RDD 已经接通。

## 适配器

| 接口 | 方向 | 语义 |
|---|---|---|
| `subscribe(listener)` | 宿主→台 | 推送 `snapshot`、`patch`、`event`、`commandResult` |
| `requestSnapshot()` | 台→宿主 | 请求当前完整观察快照 |
| `sendCommand(command, params, command_id)` | 台→宿主 | 请求真实控制；必须返回回执 |
| `requestHistory(page, cursor)` | 台→宿主 | 请求指定数据域历史（待实现） |
| `requestHealth()` | 台→宿主 | 请求连接和看门狗状态（待实现） |

## 数据域

`overview`、`ai-thinking`、`context`、`tools`、`ac-execution`、`ac-graph`、`environment`、`health`、`commands`、`architecture`、`operations`。

## 能力边界

没有真实适配器处理的动作必须返回 `UNSUPPORTED`；Mock 回执只用于前端回归，不能作为 RDD/MC 验收证据。
