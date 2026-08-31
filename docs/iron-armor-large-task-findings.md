# 铁甲大型任务能力验证 · 发现（自编译系统 2026-08-31）

> 性质：逐步驱动 rdd_probe 从零做铁甲，暴露大型任务能力和文档未实现细节。

## 已验证：工具链真实工作

```text
✅ 砍树 → 原木（mine spruce_log）
✅ 原木 → 木板（craft 4 木板 / 用 1 原木）
✅ 木板 → 木棍（craft 4 木棍 / 用 2 木板）
✅ 木板 → 工作台（craft crafting_table / 用 4 木板）
✅ 放置工作台（build {op:set, block_id, x,y,z} → scan 确认）
✅ 材料不足报错清晰（"missing: 3x planks (have 2)"）
✅ 3x3 配方提示（"needs crafting table... None within 16 blocks → craft one"）
```

## 关键能力边界（文档未实现的细节）

### 1. craft 工具单次只做一件，不自主规划
```text
做木镐时材料不够 → 只报"缺 3 木板(剩2)"
→ 不会自动"再砍一棵树→补木板→再做"
→ 需外部（AI/Planner/任务链）继续驱动
```

### 2. 3x3 配方需工作台，craft 会指引但不自动放
```text
木镐是 3x3 → 需要工作台
craft 报"None within 16 blocks → craft a crafting_table"
→ 需自主做工作台 + build 放置（我手动驱动了）
```

### 3. 熔炼无一键工具（最关键缺口）
```text
工具列表无 smelt/furnace
craft 工具描述明确："smelting/stonecutter/smithing go through interact_at + transfer"
→ 熔炼铁锭需 GUI 交互链路（build熔炉→interact_at打开→transfer放料→取结果）
→ 这是铁甲链路最复杂、最依赖自主规划的一步
```

### 4. 女仆不自主管理工具
```text
砍树后主手是原木，不会自动做成镐/换镐
→ 挖石头慢/无效，需外部指定 craft
```

## 结论

```text
工具链完整且真实工作（mine/craft/build/scan 参数对就行）
但"自主完成大型任务"缺失：女仆不会自己规划
  砍树→做镐→挖矿→熔炼→合成 的完整链路
每一步需要外部驱动（我/Planner/RDD/AC）

这正是 RDD 任务链/AC 系统存在的意义
也验证了用户担心的"能力退化"——工具在，但自主性依赖外部编排
```

## 下一步（如需继续）

```text
熔炼链路（interact_at + transfer GUI）是最复杂的验证
需：挖圆石→石镐→挖铁→build熔炉→interact_at打开→transfer放铁矿+燃料→等→取铁锭
```

## 相关

- ac-multistep-selfstatus-npe.md（AC 缺陷）
- FINAL-REAL-PROGRESS.md（真实进度）
