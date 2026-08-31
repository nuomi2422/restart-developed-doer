# RDD / Numen 监测台交接文档（2026-08-31）

> 给后续接手方（自编译系统 / 其它 AI）的第一份事实清单。
> 所有“已完成”都以当前文件与已产生的真实日志为准；带 ⏳ 的都是未完成/待验证。

---

## 1. 这轮做了什么

给 Numen 宿主加了一个低侵入的监测出口，并把外部浏览器监测台接到真实 JSONL 数据上。

### 已完成

| 项 | 位置 | 状态 |
|---|---|---|
| Numen 监测出口 `MonitoringJournal` | `E:\restart developing doer\minecraft-numen\api\common\src\main\java\com\dwinovo\numen\monitor\MonitoringJournal.java` | ✅ |
| 世界事件接入 | `...\event\NumenEvents.java` | ✅ |
| 同伴状态接入 | `...\entity\CompanionStateWatch.java` | ✅ |
| 工具调用入口/拒绝接入 | `...\network\payload\ExecuteToolPayload.java` | ✅ |
| 游戏内监测页 | `E:\restart developing doer\minecraft-numen\api\common\src\client\java\com\dwinovo\numen\client\screen\MonitoringScreen.java` | ✅ 源码已写 |
| 游戏内 M 键入口 | `...\client\NumenKeys.java` | ✅ 源码已写 |
| NeoForge / Fabric 按键注册 | `NumenNeoForgeClient.java` / `NumenFabricClient.java` | ✅ 源码已写 |
| 中英文语言键 | `...\data\ModLanguageData.java` | ✅ |
| 外部浏览器监测台 | `E:\TouhouLittleMaid-HeartPact\monitoring-station\`（index/server/css/js） | ✅ |
| 浏览器实时读取 Numen JSONL | `js\numen-adapter.js` + `server.mjs` | ✅ |
| 接口/契约/回归文档 | `INTERFACES.md` / `CONTRACT.md` / `REGRESSION.md` / `ARCHITECTURE.md` | ✅ |
| 架构定位文档 | `E:\新建文件夹\rdd架构\rdd-monitoring-station.md` | ✅ |

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
  "monitorDir": "E:\\.minecraft\\versions\\The Best of Twilight Forest\\config\\numen\\monitor",
  "total": 325
}
```

---

## 2. 监测台页面

浏览器地址：

```text
http://127.0.0.1:8776/
```

分页：

```text
总览 / AI / Context / Tools / AC / 环境 / 健康 / 架构 / 运维
```

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
E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor\
```

---

## 3. 重要运维命令

启动监测台服务（必须指定游戏实例目录，否则会读 HeartPact 下空目录）：

```powershell
$env:NUMEN_MONITOR_DIR="E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor"
node "E:\TouhouLittleMaid-HeartPact\monitoring-station\server.mjs"
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
