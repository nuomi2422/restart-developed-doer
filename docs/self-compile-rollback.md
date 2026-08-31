# Self-Compile 模块化回滚约定

## 原则

Self-Compile 每次变异只产生独立候选版本，不覆盖源代码基线、不覆盖生产 JAR。候选版本必须先完成编译和实验验证，才能进入交付状态。

## 目录

```text
selfcompile/
├── mutations/<mutation-id>/       # 单次变异工作区
├── candidates/<module>/<version>/  # 候选产物
├── stable/<module>/<version>/      # 已验证版本
├── archive/<mutation-id>/          # 失败/停止归档
└── active.json                     # 当前模块活动版本索引
```

## 状态门禁

```text
REQUESTED → WORKSPACE_CREATED → GENERATED → STATICALLY_CHECKED
→ COMPILED → CANDIDATE → VERIFIED → DELIVERED
```

只允许 `VERIFIED` 产物被交付。`FAILED`、`ARCHIVED`、`STOP_LOSS` 产物不得被加载。

## 候选产物门禁

`MutationArtifactStore` 只接受状态为 `COMPILED` 的 mutation，并且只允许复制本次工作区 `classes/` 下的文件。候选产物写入独立的 `candidates/<mutation-id>/`，采用临时文件加原子移动；工作区外的文件和未编译状态都会被拒绝。


模块回滚不是整体回退 Numen，也不是删除其他模块改动。回滚操作只改变目标模块的 `active.json` 指向，并校验目标版本存在且 manifest 状态为 `VERIFIED`。

在回滚脚本落地前，禁止把候选 JAR 复制到生产 `mods/`。当前代码阶段只建立目录和 manifest 契约，不执行生产替换。

## 测试门禁

每个模块至少需要：

- 正常输入测试；
- 空值、边界值和特殊字符测试；
- 并发/重复请求测试；
- 编译失败和产物缺失测试；
- 回滚目标不存在测试；
- 已验证功能的黄金回归测试。
