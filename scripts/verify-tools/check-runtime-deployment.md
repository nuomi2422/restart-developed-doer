# check-runtime-deployment · 源码改了，jar 部署了吗

> **干什么**：比对「该模块最后一次改 `src/main/java` 的提交时间」与
> 「部署到实例的 jar 的 `LastWriteTime`」。jar 更早 = **源码领先运行时** = 假完成。
>
> **为什么需要**：2026-10-01 出了一次真实的假完成 ——
> RL-19 在文档里写「已改」、测试全绿，但部署的 jar 比提交早 **56 小时**。
> **源码改了 ≠ 游戏里变了**，中间隔着 编译 → 部署 jar → 重启游戏。

## 怎么调

```powershell
# 默认扫 gpt 生产实例 + test 实例
node .\check-runtime-deployment.js

# 只扫生产实例
node .\check-runtime-deployment.js --mods "E:\.minecraft\versions\The Best of Twilight Forest\mods"

# 换个源码仓库/实例
node .\check-runtime-deployment.js --src "<minecraft-numen 路径>" --mods "<mods 目录>" --mods "<另一个>"
```

**退出码**：`0` 全部已部署 ｜ `2` 有源码领先于 jar ｜ `3` 环境错。
可当证据用（`run-mutation.ps1 -CollectEvidence`）。

## ★ 判据为什么不是「看最新提交时间」

第一版我直接比「模块最后一次提交 vs jar 时间」，**结果满屏假警报**：

| 误报 | 真相 |
|---|---|
| `rdd-core` 领先 9 小时 | 那次提交改了 **6 个 `*Test.java`，main 一行没动** → jar 本来就不需要重部署 |
| `learner` 领先 0 小时 | 提交后 3 分钟就构建了，**在 30 分钟容差内** |
| `AC jar 比源码新 584h` | jar 是复制过去的，保留了旧时间戳（无害） |

**所以核心判据必须是「最后一次改 `src/main/java` 的提交」**，不是「最后一次提交」。
这个区分是本工具的全部价值 —— 分错了就会让人去部署根本不用部署的东西。

## 它能证明什么、不能证明什么

| ✅ 能证明 | ❌ 不能证明 |
|---|---|
| jar 的时间戳晚于该模块最后改动 | **游戏在跑这个 jar**（可能跑的是别的实例） |
| 源码里的改动「按时间」应该已经进去了 | **jar 内容真的对**（时间戳可能骗人） |
| 哪些模块该重新部署 | **行为真的生效**（要实机验） |

> **时间戳是弱证据。** 想确凿证明「新版生效了」，解包 jar 找新写的特征字符串：
> ```powershell
> # 例：RL-19 写了「外层专用」，旧版是「只创建审计目录」
> Add-Type -AssemblyName System.IO.Compression.FileSystem
> [System.IO.Compression.ZipFile]::ExtractToDirectory("<jar>", "$env:TEMP\jarx")
> $c = [System.IO.File]::ReadAllBytes("$env:TEMP\jarx\<类路径>.class")
> [System.Text.Encoding]::UTF8.GetString($c) -match "外层专用"
> ```
> 2026-10-01 RL-19 就是这么验出「jar 里是旧文案」的。

## ⚠️ 已知局限

1. **模块映射写死在文件头**（`JAR_TO_MODULE`）。新增插件要手动加一条，
   否则该 jar 会被静默跳过（输出里看不到它）。
2. **`numen-neoforge` 映射到 `core/common`** —— 模组本体一个大 jar 含所有 core 代码，
   但 `api/common` 是单独的 jar。改 `core/common` 时两个 jar 都可能要重打。
3. **不检查「游戏是否在跑」** —— jar 更新了但没重启游戏，一样不生效。
   这一项要靠 `14-运维手册` 的判据（java 进程 / latest.log / session.lock）。

## 建议接到哪里

- **每轮收口**（`stager close` 前）：`VERDICT=STALE` 就不许写「已改完」，
  先部署 + 重启 + 实机，再收。
- **文档里标「已改」之前**：先跑一遍，把结果连同 jar 时间一起写进 evidence。

> **这条比任何文档纪律都硬** —— 因为它查的是**事实**（文件时间戳），
> 不是**声明**（文档写了什么）。文档可以是错的，时间戳不会说谎。