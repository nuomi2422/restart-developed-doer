# 监测台回归矩阵 V0.1

- [ ] AUI Ore 样式和 ES5/Rhino 静态检查通过
- [ ] 九个分页可切换，当前分页独立滚动
- [ ] 各数据域事件不会混入其他分页
- [ ] thinking/context/tools/ac/environment 关联字段可追踪
- [ ] AC 图展示结构和执行状态，不冒充已执行
- [ ] 注入正常事件、高频事件、未知域事件后页面仍响应
- [ ] 看门狗显示队列、速率、丢弃和最后事件
- [ ] 控制按钮无真实能力时显示 `UNSUPPORTED`
- [ ] 真实命令验证 `command_id → commandResult`
- [ ] 断线、恢复、超大消息、慢消费者有明确状态
- [ ] 原有 `agent-console` 聊天、工作区和任务控制不退化

本轮样板只能验收 UI/契约/分区数据层；RDD/Newman/MC 端到端项目需提供真实 Adapter 后另行验收。
