# RDD / Numen 监测台交接文档（2026-08-31）

> 给后续接手方（自编译系统 / 其它 AI）的第一份事实清单。
> 所有“已完成”都以当前文件与已产生的真实日志为准；带 ⏳ 的都是未完成/待验证。

---

## 1. 这轮做了什么

给 Numen 宿主加了一个低侵入的监测出口，并把外部浏览器监测台接到真实 JSONL 数据上。

### 已完成

| 项 | 位置 | 状态 |
|---|---|---|
| Numen 监测出口 `MonitoringJournal` | `D:\rdd\workspace\minecraft-numen\api\common\src\main\java\com\dwinovo\numen\monitor\MonitoringJournal.java` | ✅ |
| 世界事件接入 | `...\event\NumenEvents.java` | ✅ |
| 同伴状态接入 | `...\entity\CompanionStateWatch.java` | ✅ |
| 工具调用入口/拒绝接入 | `...\network\payload\ExecuteToolPayload.java` | ✅ |
| 游戏内监测页 | `D:\rdd\workspace\minecraft-numen\api\common\src\client\java\com\dwinovo\numen\client\screen\MonitoringScreen.java` | ✅ 源码已写 |
| 游戏内 M 键入口 | `...\client\NumenKeys.java` | ✅ 源码已写 |
| NeoForge / Fabric 按键注册 | `NumenNeoForgeClient.java` / `NumenFabricClient.java` | ✅ 源码已写 |
| 中英文语言键 | `...\data\ModLanguageData.java` | ✅ |
| 外部浏览器监测台 | `D:\rdd\upstream\TouhouLittleMaid-HeartPact\monitoring-station\`（index/server/css/js） | ✅ |
| 浏览器实时读取 Numen JSONL | `js\numen-adapter.js` + `server.mjs` | ✅ |
| 接口/契约/回归文档 | `INTERFACES.md` / `CONTRACT.md` / `REGRESSION.md` / `ARCHITECTURE.md` | ✅ |
| 架构定位文档 | `D:\rdd\docs\rdd-docs\rdd架构\rdd-monitoring-station.md` | ✅ |

### 真实数据链路（当前已通）

```text
Numen 运行
  → config/numen/monitor/events.jsonl
  → config/numen/monitor/state.jsonl
  → config/numen/monitor/tools.jsonl
  → monitoring-station/server.mjs (127.0.0.1:8776)
  → GET /api/snapshot
  → 浏览器监测台
```

当前 `/api/health` 返回真实数据（本会话最后一次实测）：

```json
{
  "ok": true,
  "source": "numen-jsonl",
  "monitorDir": "D:\\rdd\\mc\\The Best of Twilight Forest\\config\\numen\\monitor",
  "total": 325
}
```

---

## 2. 监测台页面

浏览器地址：

```text
http://127.0.0.1:8776/
```

页面结构（2026-10-03 起两级导航，**6 区块 / 22 子块**；区块与子块的唯一真源是 `js/monitor-nav.js`，
加页签只改那一个文件，导航 / 路由 / `MonitorStore` 页面白名单都从它派生）：

```text
① Agent Context   1.1 supervisor / 1.2 numen / 1.3 learner / 1.4 context / 1.5 ai
② 工具            2.1 tools / 2.2 rddtools / 2.3 instrumentation
③ 任务链          3.1 chainbody / 3.2 assets / 3.3 branches / 3.4 gaps / 3.5 supervision
④ ACX             4.1 acx-content / 4.2 acx-lifecycle / 4.3 acx-runs
⑤ 经验携带器      5.1 explib / 5.2 expchain / 5.3 queue / 5.4 carrier-assess
附 ops（脚手架）  health / intro / docs
```

默认首页 = 1.1 Supervisor 输入/输出。地址栏 `#<子块id>` 可深链（例 `#acx-runs`），
未知 id 回落 1.1。要点：`index.html` 里 `#page-body` 之外的 `#chain-host` / `#experience-chain` /
`#explib-host` / `#acx-host` 是**常驻宿主**，靠 `hidden` 切换 —— 四个外部 view 往这里写，
`monitor-app.js` 的 `render()` **只写 `#page-body`**。改回「整块 innerHTML 重建」那四个页就又白了。
另外 `index.html` 里四个外部 view（chain / experience-chain / experience-library / acx）
的 `<script>` **必须排在 `monitor-app.js` 之前**，否则它们的 `init()` 调不到。

状态显示（已改为动态，不再写死）：

```text
无数据：Adapter WAITING · RDD 未接入 · WAITING FOR NUMEN
有数据：Adapter NUMEN JSONL · RDD 数据已发现 · NUMEN LIVE
```

游戏内入口：

```text
按 M 键打开 MonitoringScreen（只读，滚动查看，ESC 关闭，每秒刷新）
```

浏览器与游戏内都读取同一实例目录：

```text
D:\rdd\mc\The Best of Twilight Forest\config\numen\monitor\
```

---

## 3. 重要运维命令

启动监测台服务（必须指定游戏实例目录，否则会读空目录）：
> 现行 live 副本在 `D:\rdd\workspace\rdd-monitor-publish-20260913\monitoring-station`（2026-09-24 hash 比对，8776 服务的即此份）；下方旧 HeartPact 路径已不再使用。

```powershell
$env:NUMEN_MONITOR_DIR="D:\rdd\mc\The Best of Twilight Forest\config\numen\monitor"
node "D:\rdd\workspace\rdd-monitor-publish-20260913\monitoring-station\server.mjs"
```

API：

```text
GET /api/health
GET /api/snapshot
GET /api/architecture（若前端需要）
```

---

## 4. 已知问题 / 未完成

### 4.1 当前游戏实例是否已包含最新 Numen JAR 尚未最终确认

之前部署过：

```text
numen_api-neoforge-1.21.1-0.1.3-dev.jar
```

但**这次改动（MonitoringJournal + MonitoringScreen + M 键）是否已完整构建并部署到当前实例，仍未最终确认**。需要由熟悉构建流程的 AI 用正确环境重新构建并部署，再进游戏按 M 验证。

### 4.2 游戏内是原生 Screen，不是把浏览器 HTML 嵌入

- 游戏内 `MonitoringScreen.java` 是 Numen UI 风格的原生客户端 `Screen`；
- 它读取与浏览器相同的 JSONL 分区日志；
- 但**不是**把 `monitoring-station/index.html` 直接嵌入 Minecraft；
- 真正的“同一份 HTML 双端加载”需要 ApricityUI 是否提供外部 HTML/Document 加载 API，尚未确认，本轮未强求。

### 4.3 AI / Context / AC 事件尚未接入

当前 Numen 真实接入只覆盖：

```text
世界事件（events）
同伴状态（state）
工具调用/拒绝（tools）
```

以下仍是待接入：

```text
AI thinking / prompt / context / turn
tool_result
AC start / step / finish
RDD subtask / goal 事件
```

外部浏览器前端已经预留了这些分页和事件类型，但 Numen 侧尚未埋点。前端会显示空态，不会伪造。

### 4.4 控制按钮当前全部 UNSUPPORTED

- 监测台现在只读；
- `sendCommand` 一律返回 `UNSUPPORTED`；
- 不会出现“按钮点了看起来生效但没有真实后端”的假功能。

### 4.5 AUI LocalStorage 报错（真实问题，不是本轮引入）

游戏日志持续出现：

```text
[ApricityUI] Failed to reflectively persist LocalStorage
NoSuchMethodException: net.minecraft.nbt.NbtIo.writeCompressed(net.minecraft.nbt.CompoundTag, java.io.File)
```

这是 ApricityUI 与当前 MC 1.21.1 `NbtIo` 方法签名不匹配。与 Numen 监测台无关，但值得在健康页作为真实告警记录。

### 4.6 Numen 完整构建仍被 Gradle 下载卡住

```text
services.gradle.org
gradle-9.2.0-bin.zip
SocketTimeoutException
```

本地 Gradle 8.10.2 与当前 Fabric Loom 1.14.10 的 Gradle plugin API 版本不匹配。**我没有修改 settings.gradle / 构建配置**，因为它正被另一位 AI 修改。

---

## 5. 边界（最重要的部分）

监测台是**观察与诊断**模块，不承担以下主权：

- ❌ 不直接写 RDD `TaskChain`
- ❌ 不直接写 `AssetRegistry`
- ❌ 不代替 `DetectionScheduler` / Supervisor
- ❌ 不实现 Self-Compile
- ❌ 不把 NUMEN 内部类 / 线程 / Future / 旧任务链格式变成公共契约
- ❌ 不实现工具健康资料库 / 崩溃库 / 经验库（后续再接入）

---

## 6. 给自编译系统的起点

`rdd-selfcompile` 技能已存在。接手后优先：

1. 用正确 Numen 构建流程（Java 21 + Gradle 9.2）重新构建并部署包含 MonitoringJournal + MonitoringScreen 的 JAR；
2. 进游戏按 M 验证游戏内监测页；
3. 浏览器打开 `http://127.0.0.1:8776/` 验证实时状态；
4. 后续按 `CONTRACT.md` 把 AI / AC / RDD 事件继续埋进对应生命周期；
5. 保持“监测台只观察、不写任务主状态”的边界。

---

## 7. 2026-10-05 追加：⑥ 实验统计 + 全站视觉统一

### 7.1 新增了什么

| 项 | 位置 | 说明 |
|---|---|---|
| 实验统计服务端聚合器 | `experiment-stats.mjs` | 扫 9 个日志文件，产出「AI × 目标」交叉表 + 曲线序列 + 学习曲线 + 死因分布 |
| 新路由 | `server.mjs` → `GET /api/experiment-stats` | 一次给全（约 300ms / 35KB，自带 12 秒缓存） |
| 前端 view | `js/experiment-view.js` | 常驻宿主 `#experiment-host`，形态照 token/acx 两页 |
| 样式 | `css/experiment.css` | 面板台 + 纯 SVG 折线/堆叠柱 + 「未接入」的视觉语言 |
| 共享设计层 | `css/panel.css` | 老页面（①②③）只换外观，**不改 render 函数 / DOM 结构** |
| 测试 | `tests/experiment-stats.test.mjs` | 12 条，钉住归属三档、ISO 时间戳、null≠0、坏 localStorage |
| 导航 | `js/monitor-nav.js` / `index.html` / `js/monitor-app.js` | 新 zone「⑥ 实验统计」，sub id = `exp` |

### 7.2 接手方必须知道的三条口径

1. **「每个目标」= 按 `goalId` 切的目标段**，不是小步。切段来源是 `rdd.jsonl` 的
   `taskchain_snapshot.data.taskChain`（**JSON 字符串**，不是对象）；没有快照才退化到
   `goal_staged.objective`，再没有就是「（目标未记录）」。
2. **AC 调用与「经验新增」事件没有同伴字段**（实测 `acx_event` / `ac_event` / `learned`
   的键集里都没有 `companionId`）。页面上的 AC 列是**推断**出来的，分强推断 / 弱推断 /
   无法判定 / 未归属四档并逐档报数。改动这一页时不要把它们合并成一个数。
3. **只有被 RDD 监督、写过 `taskchain_snapshot` 的那只 AI 有「目标」**（实测 6 只里 1 只）。
   其它同伴的行是「目标未接入」—— 那是**这段时间的表现**，不是「某个目标下的表现」，
   页面上必须继续这么写。

### 7.3 改这一页时容易再犯的

- 内部比大小的毫秒时间戳（`firstMs/lastMs`）不要直接放进对外字段；对外一律 ISO。
- 「读不到」和「0」在 UI 上是两件事：来源读不到 ⇒ `未接入`，不是 `0`。
- 派生比率分母为 0 ⇒ `null`，页面上显示 `?`。
- SVG 不要 `preserveAspectRatio="none"`；刻度字号是 viewBox 单位。
- 中文标题不要用 `--font-brand`（MC 字形，缺中文字符）。

---

## 8. 2026-10-05 追加：⑦ 世界与经济（两个骨架页）

**性质：骨架页。** 对应第三批 T3-01（共同事实）与 T3-07（经济/开关）——
架构里有，监测台原来一个数字都看不到。**数据源留给后来的 AI 接**，这一轮只把
「接上了什么 / 没接什么 / 怎么接」立起来。

| 项 | 位置 |
|---|---|
| 环境事实服务端 | `world-facts.mjs`（扫 `config/numen/rdd-{assets,facts,history,ledger}/<uuid>.json`） |
| 经济与开关服务端 | `economy.mjs`（`config/numen/acx/*` + `monitor/acx.jsonl`） |
| 前端 | `js/world-facts-view.js` / `js/economy-view.js`，宿主 `#world-facts-host` / `#economy-host` |
| 样式 | `css/skeleton.css`（两页共用；「未接入」的视觉语言在这里） |
| 路由 | `GET /api/world-facts` / `GET /api/economy` |
| 测试 | `tests/world-economy.test.mjs`（14 条） |
| **接线清单（先看这份）** | `接线清单-世界与经济-20261005.md`，或页面「附 · 文档」下拉里的「世界与经济 · 接线清单」 |

### 8.1 接手方只需要知道的一件事

**「已接入 / 未接入」是服务端运行时探测出来的**（`WIRING[].probe`）。
所以接数据源的正确顺序是：

1. 让数据源真的写出那个字段；
2. 改 `WIRING` 里那一条的 `probe`；
3. 页面**自动**翻绿，一行渲染代码都不用动。

反过来手改页面上的状态文字 = 写死一个一定会骗人的状态。

### 8.2 现在的事实（省得重新发现）

- 世界事实 458 条，**全部已过期**（> 6 小时）；世界事实**没有 `companionId` 字段**，归属来自文件名。
- `rdd-history.purpose` 全是 UNKNOWN；`rdd-ledger.state` 21 条里 18 条 UNVERIFIED。
- `acx/records.jsonl` **不记 SUCCESS**（101 条里 0 条）⇒ 成功率只能从 `monitor/acx.jsonl` 算，两边不可相加。
- `records.jsonl` 的 `progress` **恒为 `{}`** ⇒ AcxProgress 11 个产量字段一个都没有真实读数。
- **经济配置文件一个都不存在** ⇒ 页面不显示任何默认值。
- 72 号八个经营脚本 **acx 库里一个都没有**。

### 8.3 改这两页容易再犯的

- `probe` 收到的是**整个探针上下文**，不是单条数据 —— 写成 `(f) => f.value…)` 会永远 false。
- 配置键收集时小写化，判据里必须用 `hasKey()`（大小写无关）。
- `state !== 'UNVERIFIED'` 会被「未标注」这种兜底值骗成 true。
- **空目录 = 读到 0 个**，不是「读不到」；判成 unreadable 会把真的 0 说成观测面坏了。
- 架构文档里的 `**强调**` 要用 `escb()`（先 esc 再换粗体），否则页面上漏星号。
