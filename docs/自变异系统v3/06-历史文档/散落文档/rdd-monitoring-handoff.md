# RDD / Numen 监测台交接（2026-08-31）

> 给自编译系统 / 后续 AI 的交接。已完成项以当前文件与真实日志为准；⏳ 为未完成/待验证。

## 已完成

- Numen 监测出口 `MonitoringJournal`（有界队列 + 非阻塞 + 单写线程 + 分类 JSONL + 16MB 轮转）
- 真实接入：世界事件 / 同伴状态 / 工具调用 / 工具拒绝
- 外部浏览器监测台：`E:\TouhouLittleMaid-HeartPact\monitoring-station\`（127.0.0.1:8776）
- 游戏内 M 键只读监测页：`MonitoringScreen.java` + `NumenKeys.OPEN_MONITOR` + 双加载器注册 + 中英语言键
- 文档：`ARCHITECTURE.md` / `INTERFACES.md` / `CONTRACT.md` / `REGRESSION.md` / `HANDOFF.md`（均在 monitoring-station 目录）

## 真实数据链路（已通）

```text
Numen → config/numen/monitor/{events,state,tools}.jsonl
      → server.mjs (127.0.0.1:8776)
      → /api/snapshot → 浏览器 + 游戏内 M 键
```

## 启动监测台（必须带实例目录）

```powershell
$env:NUMEN_MONITOR_DIR="E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor"
node "E:\TouhouLittleMaid-HeartPact\monitoring-station\server.mjs"
```

## ⏳ 未完成 / 待自编译系统接手

1. 用正确 Numen 构建（Java21+Gradle9.2）重新构建并部署当前 JAR，进游戏按 M 验证
2. 继续接入 AI thinking / prompt / context / turn / tool_result / AC / RDD 事件
3. 控制按钮目前全 UNSUPPORTED（只读）；真实命令契约到位后再开放
4. AUI LocalStorage `NbtIo.writeCompressed` 报错是独立真实问题，非本轮引入
5. 保持边界：监测台只观察，不写 RDD TaskChain / AssetRegistry / 不实现 Self-Compile
