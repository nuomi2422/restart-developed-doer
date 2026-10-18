# verify-tools · 我（AI）自己写的验证工具

> **唯一存放位置。** 游戏验证阶段觉得哪里不方便，就自己写工具丢这里 —— 不许散落到
> `scripts\` 根目录、不许丢进 `mutations\`、不许临时丢 `%TEMP%` 然后下次找不到。

## 规矩

| # | 规矩 | 原因 |
|---|---|---|
| 1 | **只放这里** | 散落的工具下次就找不到了，等于白写 |
| 2 | **每个工具一个 `.ps1` 或 `.js` + 同名 `.md`** | 没说明的工具等于没有（别人不知道怎么调） |
| 3 | **不碰 `scripts\` 根目录的既有脚本** | 那是闭环真源，改坏了整条链断。要改根目录的脚本得先问用户 |
| 4 | **参数化，不写死路径/任务/时间** | 下次换个任务还能用 |
| 5 | **退出码有意义**（0=通过，非 0=有问题） | 这样才能接进 `run-mutation.ps1 -CollectEvidence` 当证据 |
| 6 | **`.ps1` 必须 UTF-8 BOM** | PS 5.1 按 ANSI 读无 BOM 会语法错（踩过） |
| 7 | **写完在同名 `.md` 里记**：干什么 / 怎么调 / 依赖什么 / 什么时候该删 | 免得半年后不知道它是干嘛的 |

## 文件名约定

```
<用途>-<被验证对象>.ps1     例：check-jar-parity.ps1 / watch-ai-loop.ps1
<用途>-<被验证对象>.md      同名说明
```

## 现有哪些

| 工具 | 干什么 | 调法 |
|---|---|---|
| `check-doc-anchor-drift.js` + `.md` | 扫文档里的 `Foo.java:123` 行号锚点和「N 行」体量锚点，跟真实源码比对，**给出新行号** | `node check-doc-anchor-drift.js --docs <md目录> --src <源码根>` 退出码 0/2/3 |
| `check-runtime-deployment.js` + `.md` | 查「源码改了但没编译部署」—— 该模块最后一次改 `src/main/java` 的提交时间 vs 部署 jar 的 `LastWriteTime`。**查的是事实（时间戳），不是声明（文档写了什么）** | `node check-runtime-deployment.js` 退出码 0/2/3 |

---

## 和既有脚本的分工

| 放哪 | 放什么 |
|---|---|
| **`scripts\` 根目录** | 闭环真源（`run-mutation.ps1` / `flow-gate.js` / `launch-mc.ps1` …）。**改动要用户批准** |
| **`scripts\verify-tools\`** | **我自己写的、一次性的、服务于某次验证的工具** ← 你在读这页 |
| `rdd-selfcompile\report\` | 外脑审稿、审查报告（不是工具） |
| `rdd-selfcompile\给用户\` | **要给你看 / 你要用的东西**（不是给我自己用的） |
