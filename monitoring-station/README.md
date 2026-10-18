# RDD 监测台：运行与维护

在本目录运行 `node server.mjs`，访问 `http://127.0.0.1:8776/`。需要 Node.js 22 或更新版本；前端无需安装依赖。

## 页面结构：五个顶层区块（2026-10-03 重组）

原先 15 个平铺页签改成 **6 个区块 / 23 个子块**的两级导航。区块与子块的唯一真源是 `js/monitor-nav.js`（`MonitorNav`）——加页签只改那一个文件，导航、路由、页面白名单都从它派生。

| 区块 | 子块 | 看什么 |
|---|---|---|
| ① Agent Context | 1.1 supervisor / 1.2 numen / 1.3 learner / 1.4 context / 1.5 ai / **1.6 tokens** | 两个 AI 各自**收到什么、交出了什么**，加上上下文、学习者行为与 token 消耗 |
| ② 工具 | 2.1 tools / 2.2 rddtools / 2.3 instrumentation | MCP 目录与实际调用证据 / RDD·学习者·ACX 工具面 / 埋点 |
| ③ 任务链 | 3.1 chainbody / 3.2 assets / 3.3 branches / 3.4 gaps / 3.5 supervision | 链本体 / 资产库 / 支线与停滞 / 断点与缺口 / 监督开关 |
| ④ ACX | 4.1 acx-content / 4.2 acx-lifecycle / 4.3 acx-runs | 脚本内容（按 `ac_name` 聚合）/ 步骤生命周期日志 / 每次运行 |
| ⑤ 经验携带器 | 5.1 explib / 5.2 expchain / 5.3 queue / 5.4 carrier-assess | 经验库 / 经验链数据流 / 统一队列 MemoQueue / 携带器判定 |
| 附 ops | health / intro / docs | 脚手架，不是分析线 |

- **深链**：地址栏 `#<子块id>` 可直达（例 `#acx-runs`），`MonitorNav.fromHash()` 解析，未知 id 回落 1.1。
- **常驻宿主**：`#page-body` 之外的 `#chain-host` / `#experience-chain` / `#explib-host` / `#acx-host` / `#token-host` 常驻在 `#monitor-content` 里，靠 `hidden` 切换。**不要改回「整块 innerHTML 重建」**——2026-10-03 那四个空白页的根因就是宿主被重建抹掉。
- 区块切换只换渲染目标，不改数据源；`MonitorStore` 每页仍各自封顶 500 条。
- **「任务已移除」不等于「没观测到」**：`taskchain_snapshot` 带 `reason:'task_removed'` 时快照里**链是完整的**，3.1 会照实画出那一刻的链，另加一条「任务已移除」卡片，并明确标注「移除那一刻的快照 · 不代表任务现在还在跑」。只有确实没有阶段数据时才回空态，而且空态文案分三种（已移除 / 有快照但没阶段数据 / 根本没观测到），不混成一句「等待任务链观测」。
- **默认观察对象按「最近有链快照的同伴」选**，不是按事件到达顺序的最后一只。游戏里有两只同伴时，只有一只在写 `numen_context`，按到达顺序选会让 3.1 间歇性空态。

### 1.6 Token 消耗速率（2026-10-04 新增）

原版有三套 token 读数，网页上一套都没有：游戏内 `/usage`（读 `companions/<uuid>/stats.json`，只给累计不给速率）、逐轮 `usage`（`monitor/{rdd,context}*.jsonl` 的 `llm_response`，带时间戳可差分）、上下文水位与自动压缩闸（`brain.jsonl` 的 `gate`）。走 `GET /api/token-rate`，聚合在 `token-view.mjs`。

四条不许省的纪律：

1. **两套台账不可相加**：`stats.json` 的 `totalTokens` 只累加 `u.fresh()`（缓存命中不重复计），与逐轮 `totalTokens` 之和不是同一个数。页面分区摆放并显式说明，不合成一个「总消耗」。
2. **输出是推算值**：`llm_response` 里没有 `completionTokens` 字段，「输出」= `totalTokens - promptTokens`，页面每处都标「推算」。
3. **速率分母是窗口全长（含空闲）**：5 分钟窗口里只跑了 30 秒，用活跃时长当分母会显示 10 倍假速率。因此同时给出 `turnsPerMin` 和每分钟柱。
4. **缺 usage 的轮次跳过，不当 0**：否则速率会凭空少一截却看不出来。

`brain.jsonl` 不在 `server.mjs` 的 `CATEGORIES` 里（前端拿不到），且速率要看跨小时走势而浏览器每页只留 500 条——所以这一页必须走服务端读盘，不能走浏览器事件队列。数据源读不到时，除了说读不到，下面**一个数字都不渲染**。

默认首页为“1.1 Supervisor 输入/输出”，展示最近采集的实际请求，历史默认折叠。

配置环境变量 `NUMEN_MONITOR_DIR` 指向所选实例的 `config/numen/monitor`，`NUMEN_LOG_PATH` 指向该实例的 `logs/latest.log`；`RDD_MONITOR_PORT` 默认 8776。不同实例使用不同目录与端口。先检查端口已有进程，避免重复启动。

## 数据与边界

- RDD 页：Supervisor 和 Numen 的 `llm_request` 分开展示，保留系统规则、全部可见 messages 和本次实际发送 tools。按 actor / companionId / requestId 匹配 `llm_response` 或 `llm_failure`；不同请求的输出不会混用。`dispatched` 仅表示交给 HTTP 客户端，不能证明上游已收到。凭据与隐藏推理不显示。旧局部注入按局部记录展示，不冒充完整请求。
- 任务量 / 完成条件 / 当前资产在请求卡之后，完整阶段与历史在后方折叠。日期明确标出今天或非今天，游戏暂停时不把最后一次记录称为实时运行。
- Context 页：已记录的 Numen 上下文。旧 `user_prompt` 是摘要，不等于整个模型请求。
- Tools 页：只通过所选实例本地 MCP 的 `initialize` / `notifications/initialized` / `tools/list` 读取目录，缓存 30 秒；显示工具描述与参数。不会调用身体工具或触发 LLM。MCP 目录与模型本次请求的 tools 是两个集合，不能混同。
- 健康页：网页连通和 RDD 观测时效分别显示。`RECENT` 仅表示近 30 秒存在快照，不能证明渲染线程、模型 API、任务推进健康。暂停/退出时可显示 `STALE`。
- 介绍页复用 `../docs/rdd-eight-modules.md`，维护八模块定义只改一处。Markdown 代码块保留换行，文档正文可滚动。

## 日志读取与限额

`rdd-journal.mjs` 在首次读取时逐块扫描现有 `rdd.jsonl`，之后按字节偏移增量读取；不写另一份任务状态。每个同伴保留最新任务快照及四类上下文，另保留最近 300 条普通事件。最多 32 个快照、128 个上下文，内容总预算 8 MiB，单行 1 MiB。超预算先淘汰普通事件，再淘汰旧上下文；原日志不删除。截断、文件替换、半行追加、跨块 UTF-8、损坏行均有测试。读取失败不会把旧缓存当成功返回。

`context.jsonl` 也使用相同增量索引，避免 Numen 完整请求被普通上下文事件挤掉。请求 / 响应 / 失败按同伴、actor、类型分别保留最新；回复只在 requestId 匹配时展示。其余类别沿用 `tail-jsonl.mjs`：最近 4 MiB / 500 条。网页只展示有界近期记录，不是全历史检索系统。底部“窗口淘汰”不代表删除磁盘记录。读取失败或超大 / 坏行在首页警示，不能据旧记录判断最新完整性。

## 采集端部署

真实请求采集要求同步构建、部署 Numen api、core、RDD 插件三个 JAR；仅刷新网页无法补回旧日志缺失的请求。先部署独立 test 实例验证，再依项目交付门禁处理 gpt 基准。Java 契约见 Numen 仓库 `docs/rdd-request-observation.md`。目前 Supervisor 的知识库召回尚未接入，页面不能凭插件已安装就显示“知识已注入”。

## 验证与恢复

运行 `node --test tests/monitor.test.mjs tests/rdd-journal.test.mjs tests/numen-catalog.test.mjs`。

浏览器验证复用已有 Playwright：设置 `PLAYWRIGHT_MODULE` 为安装路径下的 `playwright/index.mjs`，运行 `node tests/browser-smoke.mjs`。它点遍全部 22 个子块（含原先空白的四个宿主页）、走深链 `#acx-runs`、验证折叠开合在异步刷新后保持、验证 390px 手机宽度无横向溢出、断言零 JS 错误。

服务异常先查 `/api/health`；MCP 不在线时 Tools 目录应显示不可用，不影响任务链历史阅读。后台启动被运行环境拒绝时可在终端运行服务器。该进程不是开机自启服务，关闭终端或重启电脑后需重新启动。

回滚只还原此模块的对应提交并重启 Node；网页改动不需要重编译游戏 JAR。暂停监督按钮是现有控制入口，会写实例 `rdd-supervision.flag`，与纯只读的目录查询不同。
