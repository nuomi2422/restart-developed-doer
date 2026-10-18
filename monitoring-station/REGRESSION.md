# 监测台回归矩阵 V0.1

- [ ] AUI Ore 样式和 ES5/Rhino 静态检查通过
- [ ] 九个分页可切换，当前分页独立滚动
- [ ] 各数据域事件不会混入其他分页
- [ ] thinking/context/tools/ac/environment 关联字段可追踪
- [ ] AC 图展示结构和执行状态，不冒充已执行
- [ ] 注入正常事件、高频事件、未知域事件后页面仍响应
- [ ] 看门狗显示队列、速率、丢弃和最后事件
- [ ] 控制按钮无真实能力时显示 `UNSUPPORTED`
- [ ] 真实命令验证 `command_id → commandResult`
- [ ] 断线、恢复、超大消息、慢消费者有明确状态
- [x] ~~原有 `agent-console` 聊天、工作区和任务控制不退化~~ → **2026-10-03 作废**：`agent-console/`
      在本仓库是 404 陈旧副本（其 `HANDOFF.md` 里写的还是 `E:\TouhouLittleMaid-HeartPact\agent-console\`），
      6 个页面从未被主控接起来，已按用户决定 `git rm -r` 删除。

本轮样板只能验收 UI/契约/分区数据层；RDD/Newman/MC 端到端项目需提供真实 Adapter 后另行验收。

## 本轮修复（2026-09-24）

- 修复：`js/rdd-view.js` 的 `renderSupervisor` / `renderNumen` 调 `RequestView.renderActor` 时参数顺序写反
  （签名 `renderActor(actor, selected, events, legacy, ui)`，误传成 `(actor, events, events, selected, ui)`），
  导致 `actorCard` 内 `latest(legacy)` 对字符串调用 `.filter` 抛 `TypeError`，两个分页渲染中断、点了点不开。
- 缓解：`index.html` 将 `rdd-view.js` 版本号 `?v=4→?v=5` 强制浏览器取新版。
- 验证证据：CDP 复现（`TypeError: events.filter is not a function` → 修复后 `任务链 AI`/`Numen AI` 均有内容、`EXCEPTIONS: []`）；`node --test` 13/13 通过。
- 覆盖：`tests/browser-smoke.mjs` 第 14–18、31–33 行本就断言这两页内容，可防回归（需 `PLAYWRIGHT_MODULE` 的 Playwright）。
- 未解决（非本 UI bug）：monitor 日志中 `llm_request` 仅有 `actor=numen`，**没有 supervisor 记录** → 「任务链 AI」页为诚实空态，属采集端埋点缺口（见 `HANDOFF.md` 4.3）。

## 本轮修复（2026-09-28 · 画面冻结按钮 + 视图状态保留）

- **问题（用户复现）**：游戏数据每 ~5s 进一条 → `monitor-app.js` 的 `render()` 整块重建 `#monitor-content` 的
  innerHTML。重建只保竖滚动（`content.scrollTop`），**横向拖动的 `.task-flow` 进度条被打回左端**、
  **无 data-id 的 `<details>`（如「任务链」页、工具目录）开合状态被重置** → 用户看不到后面 / 折叠自动收回。
- **改 `js/monitor-app.js`**：
  - 新增 `frozen` 冻结开关 + 顶部 `render(force)` 守卫：`frozen && !force` 时 skip 整块重建（数据照常进
    `MonitorStore`，各页封顶 500 条；恢复时一次 `render(true)` 补全）。
  - 新增 `snapshotUi()` / `restoreUi()`：重建**前**快照 `.task-flow` / `.table-scroll` 的 `scrollLeft`
    与全部 `<details>` 开合，重建**后**恢复 —— 治本，**不暂停也能保住横拖与折叠**（与已有
    data-detail-id / data-audit-id 缓存互补，兜底无 id 的细节）。
  - `setFrozen(v)` 切按钮文案与黄色 `freeze-badge`；tab 切换、`rdd-companion` 下拉、`doc-select`
    显式用户操作走 `render(true)`，冻结中仍可换页。
- **改 `index.html`**：工具栏加 `<button id="pause-btn" class="btn">⏸ 暂停刷新</button>`
  + `<span id="freeze-badge">画面已冻结 · 数据仍在收</span>`；`monitoring.css?v=2→3`、`monitor-app.js?v=4→5`（强制浏览器取新版）。
- **改 `css/monitoring.css`**：追加 `.btn.paused`（琥珀色高亮）+ `#freeze-badge` 样式。
- **验证证据**：`node --check` JS 语法 OK；`node --test tests/{monitor,numen-catalog,rdd-journal}.test.mjs` **13/13 PASS**；
  运行中的 8776 服务（PID 6052，此 publish 副本）**无需重启**即返回新文件（`monitor-app.js` 含
  `snapshotUi/restoreUi/setFrozen/pause-btn`，`index.html` 含按钮，均 HTTP 实测确认）。
- **未做**：浏览器真实点击（`tests/browser-smoke.mjs` 需 Playwright，本机未装）——暂停按钮交互逻辑
  仅经静态审查 + 语法/单测，建议用户在已打开的页面**硬刷新一次**（Ctrl+F5）后手动点「暂停刷新」验证。
- **回滚点**：git 未提交本轮改动；如要回滚直接还原 `js/monitor-app.js` / `index.html` / `css/monitoring.css`
  三个文件即可（服务无需重启，改完即生效）。

## 本轮重构（2026-10-03 · 五个顶层区块 + 22 子块 + 修四个空白页）

### 结构

- 15 个平铺页签 → **6 区块 / 22 子块**两级导航。唯一真源新增 `js/monitor-nav.js`（`MonitorNav`）：
  `zones / defaultSub / resolve / zoneOf / subOf / subIds / anchorOf / fromHash`。
  `index.html` 只留两个空壳 `<nav>`，导航、路由、`MonitorStore` 页面白名单全部从 `MonitorNav` 派生
  （`setPage` 改成调用时问 `navSubs()`，因为 store 比 nav 先加载）。
- 区块：① Agent Context(5) ② 工具(3) ③ 任务链(5) ④ ACX(3) ⑤ 经验携带器(4) 附 ops(3)。详见 `README.md`。
- `css/monitoring.css` 追加 `.zone-tabs` / `.sub-tabs` / `.sub-gloss`。注意 `.grid{display:flex}` 会盖掉
  `[hidden]` 的 `display:none`，所以必须显式写 `.monitor-content .subview[hidden]{display:none}`。
- `MonitorStore.pages` 补 `expmem`（此前 `expmem.jsonl` 的 225 条规划知识事件全被打到 overview 并 `dropped++`）。

### 修掉的四个空白页（`chain` / `expchain` / `explib` / `acx`）

根因两条，都不是「页面没写」：

1. `monitor-app.js` 的 `render()` 对这四个页只调 `XxxView.render()` 却**不给 `h` 赋值**，末尾无条件写
   `content.innerHTML = '<div class="grid">' + h + '</div>'` → 页面渲染出字面量 `"undefined"`；
   而四个 view 又各自往 `#chain-host` / `#experience-chain` / `#explib-host` / `#acx-host` 里写，
   这些宿主在改造前的 `index.html` 里**根本不存在**。
2. 修法：内容区改成**常驻宿主**结构（`#page-body` 写内联页，四个 host 常驻靠 `hidden` 切换），
   `render()` 不再碰 `#monitor-content` 的 innerHTML。**不要改回去**。

### 本轮同时挖出并修掉的真 bug

- `monitor-app.js` `buildBody()` 缺 `return h;` → 所有内联子块渲染成 `"undefined"`。
- `monitor-app.js` 点区块没切页：`MonitorNav.zoneOf()` 语义是 `resolve(子块id).zone`（收的是子块 id），
  传 zone id 找不到 → 回落第一个区块。改成在 `MonitorNav.zones` 里按 zone id 遍历。
- `AcxView.init()` 永远调不到：`monitor-app.js` 在那四个 view **之前**加载，同步调 init 时 `window.AcxView`
  还是 undefined。**正解是调 `index.html` 脚本顺序**，把四个 view 排到 `monitor-app.js` 之前。
  （曾试过加 `window.X &&` 守卫，但守卫分不清「还没加载」和「不该调」。）
- `js/rdd-view.js` `reason:'task_removed'` 的快照被当「没有链」→ 任务一结束 3.1 就显示「等待任务链观测」，
  把「已结束」说成「没观测到」，还扔掉快照里完整的链。现在照实画 + 「任务已移除」卡片 + 标注
  「移除那一刻的快照 · 不代表任务现在还在跑」。
- `js/rdd-view.js` 默认观察对象用 `ids[ids.length-1]`（**事件到达顺序**，不是活跃度）。实测有两只同伴，
  只有一只写 `numen_context`，它排在窗口后面就抢走默认值 → 3.1 间歇性空态。改成 `lastSeen()` 按
  `Date.parse(timestamp)` 挑最新，且 `renderTaskChain` 优先在「真有 `payload.taskChain` 的同伴」里选。
- `monitor-app.js` `snapshotUi()/restoreUi()` 按 `<details>` **下标**存取开合 → 换子块时折叠集合变了，
  按下标硬套会把上一页的展开串到这一页。改成按 `data-detail-id`/`data-audit-id` 组成的 key 比对，
  长度或任一 key 不符就整段放弃（宁可丢一次视图状态，不可串页）。

### 验证证据

- `node --check` 全部 10 个前端 js 通过。
- `node tests/monitor.test.mjs` **8/8 PASS**（新增第 3 项「a removed task still shows the chain it left
  behind and says so」；`snapshot()` 夹具补上 `primaries`，因为新门槛是 `primaries.length > 0`）。
- `node tests/browser-smoke.mjs` **连续两轮 PASS**：
  `two-level nav (6 zones / 22 subs), real task chain, assets/branches/gaps, all four formerly
  blank hosts render, deep link by hash, persistent expansion, mobile width, no JS errors`。
  该文件由 53 行重写到 167 行，新增 `openSub()` / `waitHost()` / `expectText()` / `assertNoUndefined()`。
  诊断过程中发现 `emit()` 的 `try{}catch(e){}` 会吞掉 render 异常，**页面上看不到 JS 报错**，
  所以 debug 脚本改成劫持 `MonitorStore.subscribe` 把 `render()` 包起来记录抛错。
- 备份对照：`E:\新建文件夹\monitoring-station-备份-20261003\`（43 文件，不在任何 git 仓库里）。
  用它跑测试得 `pass 6 / fail 1`，证明 test 2 与 smoke 里两条断言**改造前就已经红**，改的是测试不是代码。

### 本轮踩过的坑（写下来免得再犯）

- **不要用 PowerShell 的 `Get-Content | Set-Content` 改监测台任何文件**：PS 5.1 的 `Get-Content` 默认按 GBK 读，
  把 UTF-8 中文读成乱码再写回**不可逆**（非法尾字节被替换成 `?`，`</title>` 变 `?/title>`，页面全白）。
  一律用 Edit/Write 工具；要批量改正则就写 `.mjs` 用 node 改。
- `.acx-step` 的子元素顺序约定是 `-n → -id → -st → -t → -note`，别调换。
- 4.1 的脚本表用 `.table-scroll > .table`（monitoring.css 的类），**acx.css 里没有表格类**。
- 「页面上出现 undefined」在数据里是合法的（真有一个 AC 脚本叫 `b9_vars_undefined`），
  所以判「旧 bug 是否还在」只查 `#page-body`，不要查整个 `#monitor-content`。

## 本轮新增（2026-10-04 · ①.6 Token 消耗速率）

- [x] Token 速率页接上真实数据（`GET /api/token-rate` → `token-view.mjs`）
- [x] 台账 / 逐轮 / 水位三套口径分区摆放，页面明说**不可相加**
- [x] 缺 `usage` 的轮次跳过，**不当 0**
- [x] 速率分母用窗口全长（含空闲），同时给 `turnsPerMin` 与每分钟柱
- [x] 数据源读不到时，除了说读不到，下面**一个数字都不渲染**
- [x] `brain.jsonl`（上下文水位 / 自动压缩闸）不在 `CATEGORIES` 里 → 必须服务端读盘

### 验证证据

- `node tests/monitor.test.mjs` **14/14 PASS**（新增两项：服务端聚合器的速率差分 / 口径纪律，
  以及前端在 `freshness.readable === false` 时拒绝渲染数字）。
  - 测试夹具里踩过一次：原来把时间基线写死成 `2026-10-04T08:00Z`，而 `all()` 内部用 `Date.now()`
    算窗口边界 → 所有轮次落进「未来」，5 分钟窗口把 100 分钟前那一轮也算进去，红得莫名其妙。
    改成用真实 `Date.now()` 造夹具。
  - 同一处坑：`fetch` 是 vm 沙箱的**全局**，view 闭包抓的是它。改 `box.window.fetch` 不生效
    （view 根本不读 `window.fetch`），第二段换数据必须改可变引用 `payload`。
- `node tests/browser-smoke.mjs` PASS：
  `two-level nav (6 zones / 23 subs), ..., token rate + ledger + context water, ... no JS errors`。
  `#sub-tabs button` 计数由 5 改 6（agent 区多了 1.6）。
- 实测（8776 现跑）：读到 639 轮 / 扫 15 / 29 份文件，`all` 窗 5,311 token 每分（0.27 轮每分，跨度 2349.1 分）；
  台账 4 只同伴，`ed1606db` 缓存命中 65.5%、`7a5a9659` 95.2%；水位 `ed1606db` 2.3%（22678 / 1000000）、
  `8d8d379b` 34.6%。5m / 30m / 2h 三个窗是 `idle`（最新一轮停在 7.1 小时前）——**这是诚实空态，不是 bug**。
- 新增 `/api/token-rate` 路由需要**重启 `server.mjs`** 才生效（静态文件不用）。
- 视觉取证：`C:\Users\lenovo\AppData\Local\Temp\opencode\tok-full.png`（全页 1600×1100）。

## 本轮新增（2026-10-05 · ⑥ 实验统计 + 全站视觉统一）

- [x] 新增 zone「⑥ 实验统计 / 6.1 统计台（可重排面板）」，独立 view 走常驻宿主 `#experiment-host`
- [x] 每只 AI × 每个目标一行：死亡 / 行动 / AC 调用 / AC 步骤失败 / 回合 / 空转 / 任务完成 /
      经验召回 / 学习者复盘 / 经验新增 / 支线卡住 / 重复死亡 / 资产不匹配 / 工具被拒 + 3 个派生比率
- [x] 「目标」按 `taskchain_snapshot.taskChain.goalId` 切段（**不切小步**），退化到 `goal_staged.objective`，
      再退化到「（目标未记录）」并由页面标成「目标未接入」
- [x] 一个记录都没有的目标**照样成行**（否则「没动作」与「没这个目标」在页面上长得一样）
- [x] AC 与「经验新增」**没有同伴字段** ⇒ 归属分三档并逐档报数：
      强推断（那一刻只有一只的目标段开着）/ 弱推断（多只开着，看谁刚活动过、领先 > 60 秒）/ 无法判定 / 未归属
- [x] 面板可**拖拽重排 + ↑↓ 按钮 + 折叠 + 开关**，每个面板自带「指标 / 分组（AI×目标·按AI·按目标）/ 线·柱」三个开关，
      排法存 `localStorage`（坏值会被退回默认，且未知面板 id 会被补全）
- [x] 读不到的格子一律写「未接入」，并附「数据接入情况」逐来源表（读不到 / 已截断 / 跳过几个轮转文件 / 距今写入）
- [x] 派生比率分母为 0 时显示 `?`，**不显示 0**
- [x] 新增 `css/panel.css` 共享设计层：老页面（①②③）只改外观，**不动任何 render 函数 / DOM 结构**
- [x] `rdd-view.js` 事件类型补中文说明 + 可读元信息行 + `request/response` 正文优先
      （原来 `llm_request/llm_response` 只印一个事件名，等于没显示内容）
- [x] 1.4 / 1.5 / 2.1 / 2.3 加页面导语，说明「这块在看什么、数据来自哪个文件、没记录 ≠ 0」

### 验证证据

- `node --test tests/*.test.mjs` **37/37 PASS**（其中新增 `tests/experiment-stats.test.mjs` 12 条）。
  写测试时真被抓住的问题：
  - **行的 `firstAt/lastAt` 原本是毫秒数**（内部比对用的字段直接外泄），前端按 ISO 显示
    ⇒ 交叉表出现「54169 ?」。已拆成 `firstMs/lastMs`（内部）+ `firstAt/lastAt`（ISO），
    并加了一条测试钉死类型。
  - **AC 归属第一版只有「唯一打开」一档**，而一段目标在没有「结束事件」时会一直开着 →
    只要有两只 AI 被监督过，那一档就几乎永不成立，AC 那几列会全空。
    补了「最近活动领先者」弱推断（阈值 60 秒，严格大于），并加了「某只同伴此前毫无活动 ⇒ 不可比 ⇒ 判无法判定」。
  - **目标段零记录的行原本不生成** ⇒ 「这个目标下什么都没做」与「没有这个目标」无法区分。
  - **localStorage 里一个不存在的指标会被面板一直盯着**（`<option>` 一个都不选中，看起来像「没在画」）。
    改成渲染时对当前数据校验并退回默认指标。
- `node --check` 通过：`experiment-view.js`、`monitor-app.js`、`rdd-view.js`。
- 实测（8776 现跑，`GET /api/experiment-stats`）：7 行（AI × 目标）、6 只 AI、2 个目标、
  序列 39 个桶 × 6 小时一格、单次 `all()` **约 300ms / 35KB**、自带 12 秒服务端缓存。
  - 目标只接上 **1 / 6** 只 AI（只有被 RDD 监督的那只写过 `taskchain_snapshot`），
    11,970 条事件里 91% 归在「目标未接入」行 —— 页面顶部与口径说明都写明这件事。
  - AC：日志里 128 次运行 / 1,027 步 / 83 步失败，而**能归属的只有 6 条**，
    未归属 1,240 条 ⇒ 汇总卡后面必须紧跟一句总量对照，否则「2」会被读成「只调了 2 次」。
- 视觉取证：`exp2.png`（实验统计全页）、`ctx.png`（1.4 历史上下文）、`chain.png`（3.1 任务链本体）。
- 新增 `/api/experiment-stats` 路由需要**重启 `server.mjs`** 才生效（静态文件不用）。

### 本轮踩过的坑（写下来免得再犯）

- **SVG 里不要 `preserveAspectRatio="none"`**：它把 viewBox 横向拉伸，刻度数字跟着变形
  （5 看着像 50）。要等比缩放 + CSS `height:auto`，且刻度字号是 **viewBox 单位**（1000 → 约 520px，
  所以 10 要写成 22 才落到 11px）。
- 中文标题**不要用 `--font-brand`**（OreDisplay 是 MC 字形，缺中文字符会掉字形）；
  卡片标题、面板标题、`h2/h3` 一律用 `--font-body`。
- 内部用来比大小的**毫秒时间戳不能直接外泄**：本工程对外一律给 ISO，混用过一次（见上面「验证证据」）。

## 本轮新增（2026-10-05 · ⑦ 世界与经济 两个骨架页）

对应第三批 T3-01（共同事实）与 T3-07（经济/开关）。架构里有，监测台原来一个数字都看不到。
**先搭骨架，数据源留给后来的 AI 接。**

- [x] 新增 zone「⑦ 世界与经济」，两个子块 `world-facts` / `economy`，常驻宿主 `#world-facts-host` / `#economy-host`
- [x] `world-facts.mjs`：把散在 `rdd-assets` / `rdd-facts` / `rdd-history` / `rdd-ledger` 四处的
      「世界是什么样」拼成同一个视图 + 事实分级（类型/维度/刷新策略/观测年龄）
- [x] `economy.mjs`：AC 脚本台账（库 ∪ 磁盘 ∪ 两个日志）+ AcxProgress 11 个产量字段 + 72 号八脚本 roster
- [x] **接线契约表**：每条写「架构要求 / 现在拿什么代替 / 怎么自测」，状态**运行时探测**
- [x] 「已接入/未接入」是**服务端探测**出来的，接上数据源后页面自动翻绿，渲染代码不用改
- [x] 「现在拿什么代替」可以是**函数**，跟着实测数据变（写死的话数据一变页面就在骗人）
- [x] 页面自带「给下一个接手的 AI」交接块 + 新文档 `接线清单-世界与经济-20261005.md`
      （挂在「附 · 文档」下，别名 `WIRING-worldeco`）

### 骨架页实测到的真实情况（写下来免得下一个人重新发现）

- 世界事实 458 条：structure 173 / entity 144 / village 96 / machine 39 / base 6；
  维度 overworld 440 / nether 18；刷新策略 LAZY 285 / STATIC 173；**全部 458 条都已过期**（> 6 小时）。
- **世界事实没有 `companionId` 字段** ——「这条属于哪只同伴」来自文件名约定。页面标出来了。
- `rdd-history.purpose` 实测 **64 条全是 UNKNOWN** ⇒ 资产用途语义整层没接。
- `rdd-ledger.state` 21 条里 **18 条 UNVERIFIED、3 条 RECOVERED** ⇒ 大部分死亡没被确认过丢了什么。
- AC 侧：37 个脚本、11 个 stable + 6 个 stale 旧版；`records.jsonl` 101 条里 **SUCCESS 0 条**
  ⇒ **成功率只能从 `monitor/acx.jsonl` 的 RUN_FINISHED 算，两个来源不可相加**。
- `records.jsonl` 的 `progress` **恒为 `{}`** ⇒ AcxProgress 11 个字段一个都没有真实读数
  ⇒ 页面**不画产量曲线**（一条贴着 0 的线就是拿 0 冒充「没有产量」）。
- **经济配置文件一个都不存在**（试过 economy/economy-config/rdd-economy/injection/policy/module-toggles 六个名字）
  ⇒ 页面不显示任何默认值。
- 72 号八个经营脚本 acx 库里**一个都没有** ⇒ 她现在不会自己种田 / 吃饭 / 睡觉。

### 验证证据

- `node --test tests/*.test.mjs` **52/52 PASS**（新增 `tests/world-economy.test.mjs` 14 条）。
  写测试时真被抓住的问题：
  - **`probe` 收到的是整个探针上下文，不是单条事实。** `fact-confidence` / `fact-coverage`
    两条判据写成了 `(f) => f.value…` ⇒ 永远读到 `undefined` ⇒ 这两条永远翻不了绿。
    改成扫 `ctx.valueKeys`（`observation.value` 里出现过的键名集合）。
    ★ 症状是「判据永远 false」，看起来像「数据没接上」，极易误判成环境问题。
  - **`collectKeys` 收集时小写化，判据里却写了驼峰**（`configKeys.has('deniedGoals')`）
    ⇒ 配置明明写了 `deniedGoals` 却显示未接入。加了大小写无关的 `hasKey()`。
  - **「未标注」这种兜底值会把判据骗成 true**：`state !== 'UNVERIFIED'` 在 `state` 缺失时成立
    ⇒ 「死亡丢失的确认」被显示成已接入。改成判 `verified === true`（终态**且** evidence 非空）。
- `node --check` 通过：`world-facts.mjs`、`economy.mjs`、两个 view、`server.mjs`、`monitor-app.js`。
- 实测（8776 现跑）：`/api/world-facts` 142KB / 约 50ms；`/api/economy` 32KB / 约 80ms；均自带 12 秒缓存。
- 视觉取证：`facts.png`（7.1 全页 1600×2600）、`eco.png`（7.2 全页 1600×2400）。

### 本轮踩过的坑（骨架页特有）

- **空的目录是「读到了 0 个」，不是「读不到」。** `lib/stable/` 空着时 economy 页判
  `old_while_game_alive` 而不是 `unreadable` —— 后者会把「真的 0 个 stable 脚本」说成「观测面坏了」。
  （测试第一版就断言错了。）
- **架构文档里的 `**强调**` 经过 `esc()` 会漏成星号。** 这两页的说明文字大量来自架构文档，
  加了 `escb()`（先 esc 再把 `**x**` 换粗体）；顺序反过来就是 XSS 口子。
- **事实原文（`value` / `observation`）不许进响应**：留着会让 payload 从 35KB 涨到 200KB+。
  探针改用 `valueKeys`（只有键名）就够判了。
- 数据里合法存在一个 AC 脚本叫 `b9_vars_undefined` ⇒ 判「页面没有 undefined」不能用整串禁掉，
  要用 `(?<!_)undefined`。
- **子视图 id ≠ 文件名**：`experiment-view.js` 的宿主是 `#experiment-host`、子视图 key 是 `exp`
  （**不是** `experiment-stats-host` / `experiment-stats`）。我按文件名猜 id，验收脚本报了两个 FAIL，
  一度以为 ⑥ 那页没接线。教训：**验收脚本也得先读代码再写判据**，别照文件名推 id。
- **截图工具挂掉时不要留「没验」**：改在 HTTP 层核对（取 `/`、`/js/monitor-nav.js`、`/js/monitor-app.js`，
  解析 `DOCS` / `docNames` 两个数组并比对键集合，再打 `/api/doc?name=…`）。
  本轮靠这个把「文档下拉多了一项但没人点过」验掉了，24/24 PASS。
- 顺带验出一条**负向判据**：传真文件名 `接线清单-世界与经济-20261005.md` 取不到（防穿越正则剥掉中文与点号），
  必须走别名 `WIRING-worldeco`。这条不写下来，下次有人「顺手改成传真文件名」就会得到一个静默 404。
