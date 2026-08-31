# AC 模块真机实测缺陷（自编译系统 2026-08-31 实测）

> 性质：真机运维问题，不是单测能发现的。供自编译系统 / AC 模块后续修复参考。

## 缺陷1：多步 AC 执行 → ac_status NPE

```text
症状：
  ac_execute 提交多步 AC（equip_item + get_self_status）→ 受理成功
  ac_status 查询 → FAILED "invalid arguments: java.lang.NullPointerException"
  单步 AC（rdd_whereami）→ ac_status 正常 SUCCESS

影响：多步 AC 无法观测结果；执行引擎可能跑完但状态查询崩

根因猜测：多步桥接中某一步上下文（companion/entity）为 null
验证：ac_execute 6/6 受理稳定，问题在状态查询链路
```

## 缺陷2（已澄清非 bug）：collect_items 参数名

```text
症状：AC 步骤传 {item:...} → "未知参数: item"
澄清：collect_items 真实参数是 item_ids（复数，数组）+ radius
  传 item/count 是我用错参数名，AC 校验正确拒绝 ✅
  用 item_ids/radius 提交 → 校验通过 → PAUSED（异步受理正确）
结论：AC 参数校验工作正常，非桥接 bug
```

## 缺陷3：scan_nearby_entities 强制 type_filter

```text
症状：不传 type_filter → "missing required argument: type_filter"
影响：可接受，但空参报错不友好（感知工具应默认全扫）
验证：传 "hostile" 后正常工作（晚上扫到苦力怕/骷髅/僵尸）
```

## 缺陷4：MCP tools/list 时序异常

```text
症状：tools/list 偶尔返回 0 个工具，但直接 tools/call 能用
影响：外部 AI 拿不到工具列表就不知道能调什么
验证：多次出现；rdd_status 等直接调用正常
```

## 已确认可用（非缺陷，记录）

```text
· RDD 长线：连续 3 任务全完成
· 真实战斗：scan 找怪 → goto 靠近 → attack 击杀（苦力怕/骷髅 20→11→0）
· AC 单步：rdd_whereami AC → SUCCESS
· expmem：learn/recall/verify 全闭环 + 持久化
· 四模块同装：57 工具全活
```
