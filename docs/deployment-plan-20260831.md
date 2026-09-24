# 生产部署方案（实验/生产隔离）— 草案待拍板

> 日期：2026-08-31 · 性质：方案草案（不改环境，等用户拍板后实施）
> 目标：自编译候选 jar 验证通过后，安全部署到生产，不污染实验/验证环境。

## 现状
```text
单实例：E:\.minecraft\versions\The Best of Twilight Forest\
  · mods/ 含 5 个 numen 插件 jar（可单独替换/回滚 .bak）
  · 存档 gpt（实验/验证用）
  · config/numen/（mcp_server.json / providers.json / ac-library.json / rdd-tasks/）
```

## 方案 A：同实例双存档（推荐，改动最小）
```text
实验存档 gpt      —— 自编译验证、破坏性测试
生产存档 prod     —— 实际游玩/交付

部署流程：
  VERIFIED 候选 → 备份当前 mods jar (.bak-<date>-<feature>)
  → 复制新 jar 到 mods/ → 重启游戏 → 进 prod 存档验证
  → 验证 OK → commit 记录；验证失败 → 回滚 .bak（1 条命令）

隔离点：
  · 存档隔离（gpt vs prod，互不干扰世界）
  · config/numen/ 共享（providers/ac-library/rdd-tasks 全实例通用——合理，配置就是全局的）
  · 需要改的只有：存档切换（启动脚本 quickPlay 参数）
```

## 方案 B：双实例目录（彻底隔离）
```text
E:\.minecraft\versions\The Best of Twilight Forest\     实验（现有）
E:\.minecraft\versions\The Best of Twilight Forest-Prod\ 生产（新建）

各自独立 mods / config / 存档 / 日志。
部署：候选 jar 复制到 Prod mods/ + 独立启动脚本。

代价：双份 mods/config（磁盘 + 维护），但完全隔离（实验爆炸不影响生产）。
```

## 推荐：方案 A（先 A，B 需要时再升级）
```text
原因：
  · A 改动最小（只加 prod 存档 + 启动参数），1 天内可落地
  · config/numen/ 全局共享本来就是对的（providers/ac 库/任务链都是全局资产）
  · 存档隔离已满足"验证不污染生产"（gpt 破坏不影响 prod）
  · B 的完全隔离值得，但成本高，等 A 跑顺再评估
```

## 部署命令（方案 A 落地时用）
```powershell
# 1. 部署候选 jar（备份 + 复制）
Copy-Item "mods\numen-plugin-xxx.jar" "mods\numen-plugin-xxx.jar.bak-<date>-<feature>" -Force
Copy-Item "<candidate>\xxx.jar" "mods\numen-plugin-xxx.jar" -Force

# 2. 启动到 prod 存档
#    启动脚本 quickPlay 参数改 "prod"（或复制启动脚本 prod 版）

# 3. 验证失败回滚
Copy-Item "mods\numen-plugin-xxx.jar.bak-<date>-<feature>" "mods\numen-plugin-xxx.jar" -Force
```

## 硬门禁（延续自变异铁律）
```text
· 未 VERIFIED 不部署生产（MutationVerification 证据链全满足才算）
· 部署前 commit（防不可回滚）
· 回滚只回滚该模块 jar（不改宿主/其他模块）
· 实验爆炸绝不自动传播到生产（存档隔离天然防）
```

## 待用户拍板
```text
1. 选 A（同实例双存档）还是 B（双实例目录）？—— 建议 A
2. 生产存档名（默认 prod）？
3. 生产游玩用哪个女仆/配置？（默认沿用 rdd 的 provider 绑定）
```
