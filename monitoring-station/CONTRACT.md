# 监测台事件契约 V0.1

## 事件 Envelope

```json
{
  "schema_version": 1,
  "event_id": "evt-001",
  "sequence": 1,
  "timestamp": "2026-08-30T00:00:00.000Z",
  "source": "rdd",
  "runtime": "newman",
  "category": "tools",
  "type": "tool_call",
  "interaction_id": "int-001",
  "task_id": "task-001",
  "payload": {}
}
```

## 命令和回执

命令必须携带 `command_id`。回执状态只能是：`APPLIED`、`REJECTED`、`FAILED`、`UNSUPPORTED`、`TIMEOUT`。

```json
{
  "schema_version": 1,
  "command_id": "cmd-001",
  "source": "monitoring-ui",
  "target": "rdd",
  "command": "tool_visibility",
  "params": {"tool": "tool_a", "enabled": false}
}
```

严禁只修改前端按钮状态而不等待回执。

## 流量约束

事件入口应实施消息大小、字段、字符串、数值、批量数量限制；每个数据域拥有独立缓存和日志。健康、错误、ACK、AC 失败优先保留；环境高频快照允许合并或采样，并报告丢弃数量。
