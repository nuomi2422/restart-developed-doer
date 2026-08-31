# AC 多步执行 NPE 缺陷报告（自编译系统 2026-08-31 精确探明）

> 状态：已定位根因边界，待修复
> 修复归属：AC 模块核心（get_self_status/get_owner_status 在 core）

## 症状

```text
AC 多步执行，若步骤包含 get_self_status 或 get_owner_status → ac_status 返回
"invalid arguments: java.lang.NullPointerException"
```

## 精确复现（对比实验）

| 组合 | 结果 |
|---|---|
| 单步 get_self_status | ✅ 正常 |
| 单步 equip_item | ✅ SUCCESS |
| A: equip_item + rdd_whereami | ✅ SUCCESS（completed_steps=2）|
| B: rdd_whereami + get_self_status | 🔴 NPE |
| C: get_self_status + get_owner_status | 🔴 NPE |

**规律：只要第二步含 get_self_status / get_owner_status 就 NPE。**

## 排除的因素

```text
· 不是多步引擎问题（A 组合两步 SUCCESS）
· 不是参数校验问题（collect_items 用对参数名 item_ids/radius 能过）
· 不是 equip_item 问题（单步+多步都正常）
```

## 根因推断

```text
get_self_status / get_owner_status 是"读自身/主人状态"工具。
单步时 ExecutionContext 有 HOST_ENTITY_UUID（companion），工作正常。
多步执行时，这两工具在某步的 ExecutionContext / companion 解析出 null → NPE。

具体：
  · AcExecutor 每步调 tool.execute(params, context)
  · NumenToolBridge 从 context.attribute(HOST_ENTITY_UUID) 取 UUID 做 anchor
  · 若多步时 context 未正确携带 UUID，或工具自身状态字段在第二次调用时为 null
```

## 验证建议（修复后测）

```text
1. 单步 get_self_status → SUCCESS
2. get_self_status + rdd_whereami → SUCCESS（顺序换）
3. equip_item + get_self_status → SUCCESS
4. get_self_status + get_owner_status → SUCCESS
```

## 修复方向（给 AC 模块）

```text
方案1：确保每步 ExecutionContext 都携带 HOST_ENTITY_UUID
  → AcExecuteTool 的 ExecutionContext 已注入 companion UUID，检查多步时是否透传

方案2：get_self_status/get_owner_status 的 onServerCall 判空 companion/owner
  → 不该 NPE，应返回明确的 TaskResult.fail

方案3：BridgeResultMapper 对这两工具返回的嵌套结构容错
```

## 当前过渡（未修复前可用）

```text
AC 里避免用 get_self_status/get_owner_status 做多步步骤
用 rdd_whereami（自变异工具）替代位置查询
单步用 get_self_status 可以（不 NPE）
```
