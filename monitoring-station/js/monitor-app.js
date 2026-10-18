(function () {
var DOCS = ["INTRO", "ARCHITECTURE", "CONTRACT", "INTERFACES", "REGRESSION", "HANDOFF", "WIRING-worldeco"];
var docNames = { INTRO: "项目介绍", ARCHITECTURE: "架构总览", CONTRACT: "模块契约", INTERFACES: "接口清单", REGRESSION: "回归清单", HANDOFF: "交接文档", "WIRING-worldeco": "世界与经济 · 接线清单" };
  // 「⑦ 世界与经济」那两页是骨架页，接线清单放在 .md 里。
  // 那个文件名带连字符，过不了 /api/doc 的防穿越正则，所以这里传的是**别名**
  // `WIRING-worldeco`，由 server.mjs 的 DOC_ALIAS 映射成真实文件名。
  // ★ 别改成传真文件名 —— 那样会读到 404，而页面只显示 ok:false，读的人看不出是别名错了。
  // 标题不再在本文件写一份 —— 取自 monitor-nav.js（单一真源，见那份文件头）。
  var page = MonitorNav.fromHash();
  var content = document.getElementById("monitor-content");
  var body = document.getElementById("page-body");
  var title = document.getElementById("page-title");
  var gloss = document.getElementById("sub-gloss");
  var zoneBar = document.getElementById("zone-tabs");
  var subBar = document.getElementById("sub-tabs");
  // 四个独立 view 各自 fetch 自己、往自己的容器里写。它们不走 SSE —— 游戏没跑的
  // 时候也该看得到证据。host 必须是**常驻元素**（在 index.html 里），因为它们
  // init() 时抓住容器句柄；容器被 innerHTML 冲掉 = 把它们的输出擦掉。
  // ★ 2026-10-03 修的就是这个：原来 render() 无条件 content.innerHTML = '…' + h，
  //   而这四页的分支只调 view.render() 不给 h 赋值 → 写入字符串 "undefined"。
  var HOSTS = ["chain-host", "experience-chain", "explib-host", "acx-host", "token-host", "experiment-host", "world-facts-host", "economy-host"];
  // mount 一律走 window.X 守卫：本文件在这四个 view 之前加载，
  // 直接写 ChainView.render() 会在「URL 带 #acx-runs」首屏时抛 ReferenceError。
  var EXTERNAL = {
    "gaps": { host: "chain-host", mount: function () { if (window.ChainView) ChainView.render(); } },
    "expchain": { host: "experience-chain", mount: function () { if (window.ExperienceChainView) ExperienceChainView.render(); } },
    "explib": { host: "explib-host", mount: function () { if (window.ExperienceLibraryView) ExperienceLibraryView.render(); } },
    "acx-content": { host: "acx-host", mount: function () { if (window.AcxView) AcxView.render("content"); } },
    "acx-lifecycle": { host: "acx-host", mount: function () { if (window.AcxView) AcxView.render("lifecycle"); } },
    "acx-runs": { host: "acx-host", mount: function () { if (window.AcxView) AcxView.render("runs"); } },
    // 1.6 Token：走 /api/token-rate 而不是浏览器里的事件队列 —— 速率要跨小时差分，
    // 而浏览器每页只留 500 条、brain.jsonl（上下文水位）压根不在 CATEGORIES 里。
    "tokens": { host: "token-host", mount: function () { if (window.TokenView) TokenView.render(); } },
    // ⑥ 实验统计：走 /api/experiment-stats。要扫 9 个日志文件做「AI × 目标」的聚合，
    // 浏览器侧那 500 条 × 一类的缓冲拼不出来。
    "exp": { host: "experiment-host", mount: function () { if (window.ExperimentView) ExperimentView.render(); } },
    // ⑦ 世界与经济（骨架页）：架构里有、数据源大半没接，走独立路由显示「未接入」。
    "world-facts": { host: "world-facts-host", mount: function () { if (window.WorldFactsView) WorldFactsView.render(); } },
    "economy": { host: "economy-host", mount: function () { if (window.EconomyView) EconomyView.render(); } }
  };
  function showHost(id) {
    for (var i = 0; i < HOSTS.length; i++) {
      var el = document.getElementById(HOSTS[i]);
      if (el) { el.hidden = HOSTS[i] !== id; }
    }
  }
  function buildNav() {
    var zones = MonitorNav.zones, cur = MonitorNav.zoneOf(page).id, h = "";
    for (var i = 0; i < zones.length; i++) {
      var z = zones[i];
      // 「① Agent Context」拆成 序号 + 名字，序号单独一个 span 便于弱化显示。
      var m = /^([①-⑨])\s*(.*)$/.exec(z.label);
      h += '<button data-zone="' + z.id + '"' + (z.id === cur ? ' class="active"' : '') + '>'
        + (m ? '<span class="z-no">' + m[1] + '</span>' + esc(m[2]) : esc(z.label))
        + '</button>';
    }
    zoneBar.innerHTML = h;
    var subs = MonitorNav.zoneOf(page).subs, hs = "";
    for (var j = 0; j < subs.length; j++) {
      var s = subs[j];
      hs += '<button data-sub="' + s.id + '"' + (s.id === page ? ' class="active"' : '') + '>' + esc(s.label) + '</button>';
    }
    subBar.innerHTML = hs;
    var z2 = MonitorNav.zoneOf(page), s2 = MonitorNav.subOf(page);
    title.textContent = s2.label;
    gloss.innerHTML = '<b>' + esc(z2.label) + '</b>' + esc(z2.gloss) + '　│　' + esc(s2.desc);
  }
  function goto(id) {
    page = MonitorNav.resolve(id).sub.id;
    if (location.hash !== MonitorNav.anchorOf(page)) {
      history.replaceState(null, "", MonitorNav.anchorOf(page));
    }
    buildNav();
    MonitorStore.setPage(page);
    render(true);
  }
  zoneBar.addEventListener("click", function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest("button[data-zone]") : null;
    if (!b) return;
    // 注意：MonitorNav.zoneOf() 收的是**子块** id（它内部是 resolve(id).zone），
    // 这里手上的是**区块** id，所以要在 zones 里自己找 —— 2026-10-03 踩过：
    // 传 'taskchain' 进去找不到就回落第一个区块，点「③ 任务链」会跳回 1.1。
    var zid = b.getAttribute("data-zone"), z = null;
    for (var i = 0; i < MonitorNav.zones.length; i++) {
      if (MonitorNav.zones[i].id === zid) { z = MonitorNav.zones[i]; break; }
    }
    if (z && z.subs.length) goto(z.subs[0].id);
  });
  subBar.addEventListener("click", function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest("button[data-sub]") : null;
    if (b) goto(b.getAttribute("data-sub"));
  });
  window.addEventListener("hashchange", function () { var id = MonitorNav.fromHash(); if (id !== page) goto(id); });
  // 画面冻结（暂停按钮）：游戏数据进来会整块重建视图，横拖任务链 / 展开折叠会被打回；
  // 冻结后跳过 render 的 internalHTML 重建，data 照常进 store（各页封顶 500 条）。
  var frozen = false;

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function kv(k, v, cls) { return '<div class="kv"><span class="k">' + esc(k) + '</span><span class="v ' + (cls || "") + '">' + esc(v) + '</span></div>'; }
  function card(t, b, cls) { return '<div class="card ' + (cls || "") + '"><div class="card-title">' + esc(t) + '</div>' + b + '</div>'; }
  // ★ 页面导语（2026-10-05 新增）。owner 原话：「上下文那一整个页面，我都不容易
  //   看出来究竟是什么东西」。根因不是配色，是**没有任何一句话说明这块在看什么**。
  //   这里只在既有卡片**上方**加一段导语，不动任何卡片结构与数据流 ——
  //   属于「方便看懂」，不属于改骨架。导语里一律带上数据来源，
  //   以及「没记录」与「0」的区分（本工程反复被坑的那一条）。
  function lead(text) { return '<div class="page-intro"><p>' + text + '</p></div>'; }
  function rows(events) {
    var h = "";
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i];
      h += '<div class="event-row"><span class="time">' + esc(e.timestamp.slice(11, 19)) + '</span><span class="type">' + esc(e.type) + '</span><span class="text" title="' + esc(e.text) + '">' + esc(e.text) + '</span></div>';
    }
    return h || '<div class="empty">暂无真实数据 · 等待 RDD Adapter 接入</div>';
  }
  // 埋点（第八系统）渲染：type + task + reason + game_time（独立日志区）
  var INSTR_ANOM = { loop_detected: 1, death: 1, starvation_death: 1, recovery_failed: 1, resource_waste: 1, asset_mismatch: 1, repeat_gather: 1 };
  function instrRows(events) {
    var h = "";
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      var gt = p.game_time != null ? " · t=" + p.game_time : "";
      var note = p.reason || p.learn || p.message || "";
      var anom = INSTR_ANOM[e.type] ? " instr-anom" : "";
      var mark = anom ? '⚠ ' : '';
      h += '<div class="event-row' + anom + '"><span class="time">' + esc((e.timestamp || "").slice(11, 19)) + '</span><span class="type">' + esc(e.type) + '</span><span class="text" title="' + esc(JSON.stringify(p)) + '">' + mark + esc((p.task ? p.task + " · " : "") + note + gt) + '</span></div>';
    }
    return h || '<div class="empty">暂无埋点事件 · 等待 instrumentation.jsonl（第八系统）</div>';
  }
  // 基地与设施（settlement.jsonl，2026-10-10 接入）：survey / place / place_rejected / inspect / resume。
  function settlementPage() {
    var ev = MonitorStore.getEvents('settlement');
    var rows = "";
    for (var i = ev.length - 1; i >= 0; i--) {
      var e = ev[i], p = e.payload || {};
      var detail = Object.keys(p).map(function (k) { return k + "=" + p[k]; }).join("  ");
      rows += '<div class="event-row"><span class="time">' + esc((e.timestamp || "").slice(11, 19)) + '</span>'
        + '<span class="type">' + esc(e.type) + '</span>'
        + '<span class="text" title="' + esc(JSON.stringify(p)) + '">' + esc(detail) + '</span></div>';
    }
    return lead('这一页是<b>基地与设施的语义事件</b>（<code>settlement.jsonl</code>）：'
        + 'survey 勘测选格 / place 放置受理与被拒 / inspect 施工账与收口 / resume 续建。'
        + '比起 <code>tools.jsonl</code> 里的通用工具调用，这里带「哪个模板 / 哪一格 / 底座基准点 / 受理还是被拒 / 还差几格」。')
      + card('基地与设施 · 事件流（settlement.jsonl）',
          rows || '<div class="empty">暂无 settlement 事件 · 等 settlement 工具被调用后写入</div>', 'wide');
  }
  // ── RDD 任务链视图：关卡流程条 + 当前级详情树 ──────────────────────────
  // 数据 = taskchain_snapshot 最新一条（rdd.jsonl 每 5s / 每次状态迁移都发）。
  // 一级=阶段主题(可能 unexpanded 待懒展开)；二级=小步(状态+资产检测条件)。
  var PS_LABEL = { PENDING: "待启动", ACTIVE: "执行中", AWAITING_SUPERVISOR: "等一级确认", WAITING: "等前置资产", REPLANNING: "重规划中", COMPLETED: "已完成", FAILED: "失败" };
  var SS_LABEL = { PENDING: "待执行", RUNNING: "执行中", COMPLETED: "完成", FAILED: "失败", STALLED: "卡住", COOLDOWN: "冷却中", INVALIDATED: "作废" };
  function psClass(st) {
    if (st === "COMPLETED") return "st-done";
    if (st === "FAILED") return "st-err";
    if (st === "ACTIVE") return "st-ok";
    if (st === "AWAITING_SUPERVISOR" || st === "REPLANNING" || st === "WAITING") return "st-wait";
    return "st-off";
  }
  function ssClass(st) {
    if (st === "RUNNING") return "st-run";
    if (st === "COMPLETED") return "st-ok";
    if (st === "FAILED" || st === "STALLED") return "st-fail";
    if (st === "COOLDOWN") return "st-wait";
    return "st-pend";
  }
  function ssIcon(st) {
    if (st === "COMPLETED") return "✓";
    if (st === "RUNNING") return "▶";
    if (st === "FAILED") return "✕";
    if (st === "STALLED") return "⚠";
    if (st === "COOLDOWN") return "…";
    if (st === "INVALIDATED") return "−";
    return "○";
  }
  function rddTrunc(s, n) { s = String(s == null ? "" : s); return s.length > n ? s.slice(0, n - 1) + "…" : s; }
  function rddCond(c) {
    if (!c) return "";
    if (typeof c === "object") {
      var k = c.asset_key || c.key || c.id || "";
      var m = c.minimum;
      return k ? (k + (m != null ? " ≥ " + m : "")) : JSON.stringify(c);
    }
    return String(c);
  }
  // 关卡流程条：全部一级阶段横排，完成绿 / 当前亮 / 待做灰 / 未展开标注
  function rddFlow(chain) {
    var ps = chain.primaries || [];
    if (!ps.length) return '<div class="empty">暂无一级阶段</div>';
    var h = "";
    for (var i = 0; i < ps.length; i++) {
      var p = ps[i];
      var passed = i < (chain.primaryIndex || 0);
      var isCur = i === (chain.primaryIndex || 0);
      var cls = isCur ? psClass(chain.primaryStatus) : (passed ? "st-done" : "st-off");
      if (isCur) cls += " cur";
      var subs = p.subtasks || [];
      var tag = "";
      if (p.unexpanded) tag = passed ? "" : "到达后生成小步";
      else if (subs.length) {
        var d = 0; for (var j = 0; j < subs.length; j++) if (subs[j].status === "COMPLETED") d++;
        tag = d + "/" + subs.length + "步";
      }
      h += '<span class="node ' + cls + '" title="' + esc(p.description) + (p.waitFor && p.waitFor.length ? "\n等待: " + esc(p.waitFor.map(rddCond).join(", ")) : "") + '">'
        + '<span class="idx">' + (i + 1) + '</span>' + esc(p.description)
        + (tag ? '<span class="tag">' + esc(tag) + '</span>' : "") + '</span>';
    }
    return h;
  }
  // 当前级详情：unexpanded 诚实提示 / 已展开则渲染二级小步树
  function rddCurrentDetail(p, ps) {
    if (!p) return '<div class="empty">当前无可执行阶段</div>';
    if (p.unexpanded) {
      return '<div class="empty" style="text-align:left;padding:14px">「' + esc(p.description) + '」还没有生成具体任务。执行到这一阶段后，任务链 AI 才会依据当时的资产拆成小步。</div>';
    }
    var subs = p.subtasks || [];
    if (!subs.length) return '<div class="empty">该阶段没有二级小步（异常：已展开却为空）</div>';
    var h = '<div class="rdd-detail">';
    for (var i = 0; i < subs.length; i++) {
      var s = subs[i];
      var cond = (s.detectionMode === "HARD_CODED" ? "资产 " : "检测 ") + rddCond(s.condition);
      h += '<div class="sub-row ' + ssClass(s.status) + '"><span class="ic">' + ssIcon(s.status) + '</span>'
        + '<span class="ds">' + esc(s.description) + '</span>'
        + '<span class="cv">' + esc(cond) + '</span>'
        + '<span class="st">' + esc(SS_LABEL[s.status] || s.status) + '</span></div>';
    }
    h += '</div>';
    return h + (ps === "AWAITING_SUPERVISOR"
      ? '<div style="margin-top:8px;color:var(--amber)">▸ 当前级所有小步完成，等 Supervisor 确认后推进到下一级</div>' : "");
  }
  // RDD 事件流：滤掉周期性快照刷屏，只留 推进/失败/监督 有信息量的事件
  function rddEventRows(events) {
    var h = "";
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i];
      if (e.type === "taskchain_snapshot") continue;
      var cls = (e.type === "expansion_exhausted" || e.type === "subtask_failed") ? "error" : "";
      h += '<div class="event-row ' + cls + '"><span class="time">' + esc(e.timestamp.slice(11, 19)) + '</span>'
        + '<span class="type">' + esc(e.type) + '</span><span class="text" title="' + esc(e.text) + '">' + esc(e.text) + '</span></div>';
    }
    return h || '<div class="empty">暂无推进 / 监督事件（快照已绘成上方树）</div>';
  }
  // 轻量 markdown 渲染（标题/代码块/列表/表格）
  function md(s) {
    if (!s) return '<div class="empty">暂无内容</div>';
    var lines = s.split(/\r?\n/);
    var out = [];
    var inCode = false;
    var inTable = false;
    function esc2(x) { return String(x == null ? "" : x).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (l.trim().startsWith("```")) {
        if (inTable) { out.push('</table>'); inTable = false; }
        inCode = !inCode;
        out.push(inCode ? '<pre class="pre">' : '</pre>');
        continue;
      }
      if (inCode) { out.push(esc2(l) + '\n'); continue; }
      if (/^\|/.test(l)) {
        var cells = l.replace(/^\||\|$/g, "").split("|").map(function (c) { return c.trim(); });
        if (cells.every(function (c) { return /^:?-+:?$/.test(c); })) continue;
        if (!inTable) { out.push('<table class="table"><tr>' + cells.map(function (c) { return '<th>' + esc2(c) + '</th>'; }).join("") + '</tr>'); inTable = true; }
        else { out.push('<tr>' + cells.map(function (c) { return '<td>' + esc2(c) + '</td>'; }).join("") + '</tr>'); }
        continue;
      }
      if (inTable) { out.push('</table>'); inTable = false; }
      if (/^#\s/.test(l)) out.push('<div class="md-h1">' + esc2(l.replace(/^#\s/, "")) + '</div>');
      else if (/^##\s/.test(l)) out.push('<div class="md-h2">' + esc2(l.replace(/^##\s/, "")) + '</div>');
      else if (/^###\s/.test(l)) out.push('<div class="md-h3">' + esc2(l.replace(/^###\s/, "")) + '</div>');
      else if (/^\s*[-*]\s/.test(l)) out.push('<div class="md-li">· ' + esc2(l.replace(/^\s*[-*]\s/, "")) + '</div>');
      else if (/^\s*$/.test(l)) out.push('<div class="md-gap"></div>');
      else out.push('<div class="md-text">' + esc2(l) + '</div>');
    }
    if (inCode) out.push('</pre>');
    if (inTable) out.push('</table>');
    return out.join("");
  }
  // 通用文档加载 + 缓存（INTRO / ARCHITECTURE / CONTRACT / INTERFACES / REGRESSION / HANDOFF）
  var docsCache = {};
  var catalog = null, catalogLoading = false, catalogChecked = 0;
  function loadCatalog() {
    if (catalogLoading || Date.now() - catalogChecked < 30000) return;
    catalogLoading = true;
    fetch('/api/tool-catalog').then(function (r) { return r.json(); }).then(function (value) { catalog = value; })
      .catch(function () { catalog = { ok: false, error: 'MONITOR_UNAVAILABLE' }; })
      .then(function () { catalogLoading = false; catalogChecked = Date.now(); if (page === 'tools') render(); });
  }
  function catalogRows() {
    if (!catalog) return '<div class="empty">正在读取 Numen MCP 工具目录…</div>';
    if (!catalog.ok) return '<div class="empty">工具目录未取得：' + esc(catalog.error) + '。历史调用记录不代表当前可用工具。</div>';
    return '<p class="sup-hint">MCP 当前暴露 ' + catalog.tools.length + ' 个工具；游戏配置隐藏的内部工具不在此目录。观测时间：' + esc(catalog.observedAt) + '</p>'
      + catalog.tools.map(function (tool) { return '<details><summary>' + esc(tool.name) + '</summary><p>' + esc(tool.description) + '</p><pre class="pre">' + esc(JSON.stringify(tool.inputSchema, null, 2)) + '</pre></details>'; }).join('');
  }
  var curDoc = "ARCHITECTURE";
  function loadDoc(name) {
    if (docsCache[name]) return;
    fetch("/api/doc?name=" + encodeURIComponent(name)).then(function (r) { return r.json(); }).then(function (d) {
      docsCache[name] = (d && d.markdown) ? d.markdown : "（文档加载失败：" + ((d && d.error) || "未知") + "）";
      render();
    }).catch(function () { docsCache[name] = "（文档加载失败：网络错误）"; render(); });
  }
  function eventsCard(name, events) { return card(name, '<div class="event-list">' + rows(events) + '</div>', "wide"); }
  function healthRows() {
    var health = MonitorStore.health(), observation = health.observation || {};
    var state = health.connected ? (observation.state || 'NO_DATA') : 'DISCONNECTED';
    return kv('监测台服务', health.connected ? '已连接' : '未连接', health.connected ? 'state-good' : 'state-warn')
      + kv('RDD 数据', state, state === 'RECENT' ? 'state-good' : 'state-warn')
      + kv('最后观测', observation.lastObservedAt || '无')
      + kv('日志读取', health.rddJournal ? (health.rddJournal.readable ? '可读' : health.rddJournal.error || '读取失败') : '未知')
      + kv('异常日志行', health.rddJournal ? health.rddJournal.invalidLines : '未知')
      + kv('游戏进程 / 模型 API', '未由网页验证');
  }
  // ── RDD 空转止血开关：暂停/恢复监督拍醒（写游戏端 flag，Detector ~1s 心跳生效）──
  var rddSupState = "running"; // running | paused（从 /api/rdd-command?action=status 读）
  function rddSupCard() {
    var p = rddSupState === "paused";
    var stateCls = p ? "state-warn" : "state-good";
    var stateTxt = p ? "⏸ 已暂停 · 空转止血中" : "▶ 监督运行中";
    var actBtn = p
      ? '<button class="btn" data-rdd-cmd="resume">恢复监督</button>'
      : '<button class="btn" data-rdd-cmd="pause">暂停监督 · 止血</button>';
    return card("监督开关 · 空转止血", '<div class="sup-bar">'
      + '<span class="sup-state ' + stateCls + '">' + stateTxt + '</span>'
      + actBtn
      + '<span class="sup-hint">暂停 = Detector 不再拍醒 AI / 自动重试 / 判失败；真实资产检测与推进照常。游戏端 ~1 秒心跳生效。</span>'
      + '</div>', "wide");
  }
  function rddCmd(action) {
    fetch("/api/rdd-command?action=" + encodeURIComponent(action))
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d && d.ok) { rddSupState = d.state === "paused" ? "paused" : "running"; render(); }
      }).catch(function () {});
  }
  function rddSupSync() {
    fetch("/api/rdd-command?action=status")
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (!d || !d.ok) return;
        var s = d.state === "paused" ? "paused" : "running";
        if (s !== rddSupState) { rddSupState = s; if (SUP_PAGES.indexOf(page) >= 0) render(true); }
      }).catch(function () {});
  }
  // ===== 2026-10-01 学习者 / 携带器页 =====
  // 起因：第 1、2 批前半段的判据全是**内部 jsonl**，游戏里看不出任何变化。
  // 这一页把「学习者写了什么、复盘判了什么、携带器判了什么、它的健康状态」摆出来。
  // 缺失一律**如实说「无」**，不编默认值（B21 同一类：缺失要看起来像缺失）。
  var LRN_FIELDS = [
    ["mechanism", "机制"], ["preconditions", "前置条件"], ["failureConditions", "失效条件"],
    ["observableSignal", "可观察信号"], ["derivation", "推导步骤"], ["efficiency", "效率"], ["evidence", "证据"]
  ];
  function learnerExperience(exp) {
    if (!exp) return "";
    var h = '<div class="table-scroll"><table class="table"><tr><th>字段</th><th>内容</th></tr>';
    for (var i = 0; i < LRN_FIELDS.length; i++) {
      var k = LRN_FIELDS[i][0], label = LRN_FIELDS[i][1], v = exp[k];
      var ok = (typeof v === "string") && v.trim().length > 0;
      h += '<tr><td>' + esc(label) + '</td><td>' + (ok ? esc(v) : '<span class="state-warn">（缺）</span>') + '</td></tr>';
    }
    h += '</table></div>';
    return h;
  }
  function learnerStatus(events) {
    var depth = null, lastReview = "", committed = 0, reviewed = 0, verdicts = 0, wexp = 0, noSnap = 0, shortCircuit = 0;
    for (var i = 0; i < events.length; i++) {
      var p = events[i].payload || {};
      if (events[i].type === "noted" && p.queue_depth != null) depth = p.queue_depth;
      if (events[i].type === "noted" && p.snapshot_present === false) noSnap++;
      if (events[i].type === "reviewed") {
        lastReview = events[i].timestamp || "";
        reviewed = p.reviewed || 0;
        committed = p.committed || 0;
        var vs = p.verdicts || [];
        verdicts += vs.length;
        for (var j = 0; j < vs.length; j++) {
          if ((vs[j].actions || []).indexOf("WRITE_EXPERIENCE") >= 0) wexp++;
        }
      }
      if (events[i].type === "carrier_assessment" && typeof p.why === "string" && p.why.indexOf("未求值") >= 0) shortCircuit++;
    }
    return kv("待复盘备忘（队列深度）", depth == null ? "无数据" : String(depth), (depth == null ? "state-warn" : (depth > 0 ? "state-good" : "")))
      + kv("累计判定条数", String(verdicts))
      + kv("建议沉淀（WRITE_EXPERIENCE）", String(wexp))
      + kv("最近一次复盘", lastReview ? lastReview.slice(11, 19) : "尚无")
      + kv("最近一次入库/恢复", committed + " / " + (events.length ? "—" : "—"))
      + kv("无环境快照的备忘", String(noSnap), noSnap > 0 ? "state-warn" : "")
      + kv("携带器发生短路的次数", String(shortCircuit), shortCircuit > 0 ? "state-warn" : "");
  }
  function learnerVerdicts(events) {
    var h = "";
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      if (e.type !== "reviewed") continue;
      var vs = p.verdicts || [];
      h += '<div class="ai-row ai-decision"><span class="ai-time">' + esc((e.timestamp || "").slice(11, 19))
        + '</span><span class="ai-role">复盘</span><span class="ai-text">'
        + '判定 ' + (p.reviewed || 0) + ' 条 · 入库 ' + (p.committed || 0) + ' · 恢复 ' + (p.restored || 0)
        + ' · review_id ' + esc(p.review_id || "?") + '</span></div>';
      for (var j = 0; j < vs.length; j++) {
        var v = vs[j];
        var acc = (v.experience_acceptable === true);
        h += '<div class="card"><div class="card-title">'
          + esc(v.memo_id || "?")
          + ' · ' + esc((v.actions || []).join("+"))
          + ' · confidence ' + esc(String(v.confidence))
          + (v.experience ? (acc ? ' · <span class="state-good">七字段齐</span>' : ' · <span class="state-warn">不合格：' + esc(v.experience_unacceptable_reason || v.experience_missing_fields || "") + '</span>') : ' · <span class="state-warn">无结构化经验</span>')
          + '</div>';
        if (v.reasoning) h += '<p class="sup-hint">判断依据：' + esc(v.reasoning) + '</p>';
        h += learnerExperience(v.experience);
        if (v.rewritten_query && v.rewritten_query.length) {
          h += '<p class="sup-hint">检索词：' + esc(v.rewritten_query.join(" / ")) + '</p>';
        }
        h += '</div>';
      }
    }
    return h || '<div class="empty">尚无复盘记录 —— 学习者需要先攒够备忘才会调 learner_review</div>';
  }
  function learnerCarrier(events) {
    var h = "";
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      if (e.type !== "carrier_assessment" && e.type !== "noted") continue;
      var d = e.payload || {};
      var why = d.why || "";
      var sc = why.indexOf("未求值") >= 0;
      h += '<div class="event-row' + (sc ? ' instr-anom' : '') + '">'
        + '<span class="time">' + esc((e.timestamp || "").slice(11, 19)) + '</span>'
        + '<span class="type">' + esc(e.type) + '</span>'
        + '<span class="text" title="' + esc(JSON.stringify(p)) + '">'
        + esc(d.memo_id || "?")
        + ' · target=' + esc(d.carrier_target || d.target || "无")
        + ' · hp=' + esc(String(d.carrier_hp != null ? d.carrier_hp : (d.hp != null ? d.hp : "无")))
        + ' · 携带=' + ((d.carry_list || d.carry || []).length) + ' 项'
        + (d.snapshot_source ? ' · 快照来源=' + esc(d.snapshot_source) : '')
        + (sc ? ' · <strong>已短路（后续级未求值）</strong>' : '')
        + '</span></div>';
    }
    return h || '<div class="empty">尚无携带器判定记录</div>';
  }
  function learnerPage(events) {
    return card("状态 · 链路健康", learnerStatus(events), "wide")
      + card("复盘判定 · 携带器判了什么 · 七字段经验", learnerVerdicts(events), "wide")
      + card("携带器每次判定（含是否短路）", '<div class="event-list">' + learnerCarrier(events) + '</div>', "wide")
      + card("这一页的边界", '<p class="audit-gap">'
        + '数据源：<code>config/numen/monitor/learner.jsonl</code>（plugins/learner 写出，事件类型 noted / carrier_assessment / reviewed）。'
        + '本页<b>只读观察</b>，不写任务主状态、不投 MCP 指令。'
        + '<br>⚠️ <b>本页显示的全是「AI 内部」发生的事</b>。'
        + '第 1 批与第 2 批前半段（学习者/携带器本体）的改动<b>在游戏画面上本来就是看不见的</b> —— '
        + '能看到行为变化的时点是<b>第 2 批后半段</b>（携带器真正接进规划者/执行 AI 的上下文）。'
        + '<br>⚠️ <b>AC 写入口本批不做</b>（38号v3 B16）：<code>ArtifactSink</code> 三个方法<b>全部显式抛错</b>，'
        + '所以「AC 中断」这件事当前是<b>设计如此</b>，不是故障。'
        + '</p>', "wide");
  }
  // ── 事件 text 兜底：rdd-journal 那边并非每种事件都带 text，
  //    没有就按 payload 的键值摊平，别让一行日志只剩一个时间戳。
  function evText(e) {
    if (e && e.text) return e.text;
    var p = (e && e.payload) || {}, parts = [];
    for (var k in p) {
      if (k === "companionId" || k === "companion_id") continue;
      var v = p[k];
      parts.push(k + "=" + (typeof v === "object" ? JSON.stringify(v) : String(v)));
    }
    return parts.join(" · ") || (e && e.type) || "";
  }
  // ── ②.2 任务链工具面 ──────────────────────────────────────────────
  // 问题：2.1 看的是「MCP 现在暴露什么」，这一块看的是「RDD / ACX / learner
  // 这一族在游戏里到底被调过没有、调了几次」。前者是能力表，后者是事实。
  var TOOL_FAMILIES = [
    { re: /^rdd_/, label: "RDD 任务链工具", note: "rdd_status / rdd_submit / rdd_assets / rdd_standby / rdd_skip_optional / report_task_concern —— 规划 AI 往任务链上动手的那一族" },
    { re: /^acx_/, label: "ACX 状态机工具", note: "acx_execute / acx_status / acx_library —— 执行 AI 驱动状态机的那一族" },
    { re: /^learner_/, label: "学习者工具", note: "learner_intake / learner_note / learner_review / learner_feedback / learner_status —— AI 主动往队列投料的那一族" },
    { re: /^(inspect_block|goto|look_around|get_self_status|scan_blocks|mine|scaffold_materials|task_status|task_stop)$/, label: "基础世界动作", note: "执行 AI 的日常动作族" },
    { re: /^[\s\S]$/, label: "其它", note: "不在上面四族里" }
  ];
  function familyOf(name) {
    for (var i = 0; i < TOOL_FAMILIES.length - 1; i++) { if (TOOL_FAMILIES[i].re.test(name)) return TOOL_FAMILIES[i]; }
    return TOOL_FAMILIES[TOOL_FAMILIES.length - 1];
  }
  function rddtoolsPage(events) {
    var counts = {}, calls = 0, results = 0, i, e, p, name;
    for (i = 0; i < events.length; i++) {
      e = events[i]; p = e.payload || {}; name = p.tool || "";
      if (e.type === "tool_call" && name) { counts[name] = (counts[name] || 0) + 1; calls++; }
      if (e.type === "tool_result") { results++; }
    }
    var fams = {}, k;
    for (k in counts) {
      var f = familyOf(k);
      fams[f.label] = fams[f.label] || { n: 0, tools: {} };
      fams[f.label].n += counts[k];
      fams[f.label].tools[k] = counts[k];
    }
    var h = card("工具面 · 这一轮窗口里真的被调过什么",
      kv("采样窗口", "tools.jsonl 最近 " + events.length + " 条（每页封顶 500）")
      + kv("tool_call 条数", String(calls))
      + kv("tool_result 条数", String(results))
      + kv("调过几种工具", String(Object.keys(counts).length))
      + kv("数据源", "tools.jsonl 的 tool_call / tool_result（page=tools）"), "wide");
    var th = '<div class="table-scroll"><table class="table"><tr><th>工具族</th><th>调用次数</th><th>族内明细</th></tr>';
    for (i = 0; i < TOOL_FAMILIES.length; i++) {
      var g = fams[TOOL_FAMILIES[i].label];
      if (!g) continue;
      var detail = Object.keys(g.tools).sort(function (a, b) { return g.tools[b] - g.tools[a]; })
        .map(function (t) { return esc(t) + "×" + g.tools[t]; }).join("、");
      th += "<tr><td>" + esc(TOOL_FAMILIES[i].label) + "</td><td>" + g.n + "</td><td>" + (detail || "—") + "</td></tr>";
    }
    th += "</table></div>";
    var notes = "";
    for (i = 0; i < TOOL_FAMILIES.length; i++) { notes += "<b>" + esc(TOOL_FAMILIES[i].label) + "</b>：" + esc(TOOL_FAMILIES[i].note) + "<br>"; }
    h += card("按工具族汇总", th + '<p class="audit-gap">' + notes + "</p>", "wide");
    var rowsHtml = "", seen = 0;
    for (i = events.length - 1; i >= 0 && seen < 60; i--) {
      e = events[i];
      if (e.type !== "tool_call") continue;
      p = e.payload || {};
      rowsHtml += '<div class="event-row"><span class="time">' + esc((e.timestamp || "").slice(11, 19))
        + '</span><span class="type">' + esc(p.tool || "?")
        + '</span><span class="text" title="' + esc(JSON.stringify(p)) + '">'
        + esc(p.args_chars != null ? "参数 " + p.args_chars + " 字符" : evText(e)) + "</span></div>";
      seen++;
    }
    h += card("最近调用", '<div class="event-list">' + (rowsHtml || '<div class="empty">本窗口内没有 tool_call 记录</div>') + "</div>", "wide");
    return h;
  }
  // ── ③.2 资产 ─────────────────────────────────────────────────────
  function pickAssets(events) {
    for (var i = events.length - 1; i >= 0; i--) {
      var p = events[i].payload || {};
      if (Array.isArray(p.assets)) return events[i];
    }
    return null;
  }
  function assetsPage() {
    var snap = pickAssets(MonitorStore.getEvents("rdd"));
    var assets = snap && snap.payload.assets;
    var total = Array.isArray(assets) ? assets.length : null;
    return card("资产库 · 本次观测",
      kv("asset_snapshot 时间", snap ? (snap.timestamp || "?") : "无")
      + kv("资产条数", total == null ? "未知（没收到 asset_snapshot）" : String(total), total ? "state-good" : "state-warn")
      + kv("数据源", "rdd.jsonl 的 asset_snapshot —— 独立事件类型，不在 taskChain 字段里"), "wide")
      + card("逐条资产", RddView.assetsRows(assets), "wide")
      + card("这一页的边界", '<p class="audit-gap">资产观测是<b>某一刻的快照</b>，不是当前库存。'
        + "旧日志不能还原资产库存 —— 没收到 asset_snapshot 就如实说没收到，不用别的字段凑。"
        + "数量在 value.count 里；kind=base/world_base 这类没有 count，只能报「重要物品 N 类」。</p>", "wide");
  }
  // ── ③.3 支线 ─────────────────────────────────────────────────────
  var BRANCH_SIG = { subtask_stalled: 1, subtask_resumed: 1, subtask_work_wait: 1, expansion_exhausted: 1, subtask_failed: 1 };
  function latestChain(events) {
    for (var i = events.length - 1; i >= 0; i--) {
      var p = events[i].payload || {};
      if (p.taskChain && p.reason !== "task_removed") return events[i];
    }
    return null;
  }
  function branchesPage() {
    var rdd = MonitorStore.getEvents("rdd");
    var snap = latestChain(rdd);
    var chain = snap && snap.payload.taskChain;
    var prim = (chain && Array.isArray(chain.primaries)) ? chain.primaries : null;
    var cur = prim ? prim[chain.primaryIndex || 0] : null;
    var h = card("支线总览",
      kv("快照时间", snap ? (snap.timestamp || "?") : "无")
      + kv("一级阶段数", prim ? String(prim.length) : "无", prim ? "" : "state-warn")
      + kv("当前阶段", cur ? cur.description : "无")
      + kv("当前状态", chain ? (chain.primaryStatus || "?") : "无")
      + kv("前置资产（当前阶段）", cur && cur.waitFor && cur.waitFor.length ? cur.waitFor.map(rddCond).join("、") : "无"), "wide");
    if (prim) {
      for (var i = 0; i < prim.length; i++) {
        var p = prim[i], subs = p.subtasks || [], done = 0, j;
        for (j = 0; j < subs.length; j++) { if (subs[j].status === "COMPLETED") done++; }
        var bodyHtml;
        if (p.unexpanded) {
          bodyHtml = '<div class="empty" style="text-align:left;padding:10px">这一阶段<b>还没生成小步</b> —— 执行到它时，任务链 AI 才依据当时的资产拆。</div>';
        } else if (!subs.length) {
          bodyHtml = '<div class="empty" style="text-align:left;padding:10px">已展开却是空的（异常）。</div>';
        } else {
          bodyHtml = '<div class="rdd-detail">';
          for (j = 0; j < subs.length; j++) {
            var s = subs[j];
            var cond = (s.detectionMode === "HARD_CODED" ? "资产 " : "检测 ") + rddCond(s.condition);
            bodyHtml += '<div class="sub-row ' + ssClass(s.status) + '"><span class="ic">' + ssIcon(s.status) + '</span>'
              + '<span class="ds">' + esc(s.description) + '</span>'
              + '<span class="cv">' + esc(cond) + '</span>'
              + '<span class="st">' + esc(SS_LABEL[s.status] || s.status) + '</span></div>';
          }
          bodyHtml += "</div>";
        }
        h += card("支线 " + (i + 1) + " · " + p.description + "（" + done + "/" + subs.length + " 步）",
          bodyHtml + (p.waitFor && p.waitFor.length ? '<p class="audit-gap">前置资产：' + esc(p.waitFor.map(rddCond).join("、")) + "</p>" : ""), "wide");
      }
    } else {
      h += card("支线", '<div class="empty">等待任务链观测；本页不会自行创建游戏任务。</div>', "wide");
    }
    var sig = "", n, e;
    for (n = rdd.length - 1, j = 0; n >= 0 && j < 60; n--) {
      e = rdd[n];
      if (!BRANCH_SIG[e.type]) continue;
      var bad = (e.type === "subtask_stalled" || e.type === "subtask_failed" || e.type === "expansion_exhausted");
      sig += '<div class="event-row' + (bad ? " error" : "") + '"><span class="time">' + esc((e.timestamp || "").slice(11, 19))
        + '</span><span class="type">' + esc(e.type) + '</span><span class="text" title="' + esc(JSON.stringify(e.payload || {})) + '">' + esc(evText(e)) + "</span></div>";
      j++;
    }
    h += card("支线信号 · 卡住 / 恢复 / 等资产 / 展开耗尽", '<div class="event-list">' + (sig || '<div class="empty">本窗口内没有支线异常信号</div>') + "</div>", "wide");
    return h;
  }
  // ── ③.5 监督开关 ─────────────────────────────────────────────────
  function supervisionPage() {
    return rddSupCard()
      + card("这一页的边界", '<p class="audit-gap">暂停 = Detector 不再拍醒 AI / 不再自动重试 / 不再判失败；'
        + "<b>真实资产检测与推进照常</b>。写的是游戏端 flag（<code>&lt;monitor&gt;/../rdd-supervision.flag</code>），~1 秒心跳生效。"
        + "监测台只写这一个 flag —— 不写任务主状态、不投 MCP 指令。</p>", "wide");
  }
  // ── ⑤.3 统一队列与学习者 ──────────────────────────────────────────
  function queuePage() {
    var lrn = MonitorStore.getEvents("learner");
    var mem = MonitorStore.getEvents("expmem");
    var depth = null, noSnap = 0, intakes = 0, i, e, p;
    for (i = 0; i < lrn.length; i++) {
      e = lrn[i]; p = e.payload || {};
      if (e.type === "noted" && p.queue_depth != null) depth = p.queue_depth;
      if (e.type === "noted" && p.snapshot_present === false) noSnap++;
      if (e.type === "intake") intakes++;
    }
    var m = { planning_knowledge: 0, recalled: 0, learned: 0 };
    for (i = 0; i < mem.length; i++) { if (m[mem[i].type] != null) m[mem[i].type]++; }
    return card("统一队列 MemoQueue · 深度",
      kv("队列深度（最后一条 noted）", depth == null ? "无数据" : String(depth), depth == null ? "state-warn" : (depth > 0 ? "state-good" : ""))
      + kv("投料口 intake 次数", String(intakes), intakes ? "" : "state-warn")
      + kv("备忘里没有环境快照的条数", String(noSnap), noSnap > 0 ? "state-warn" : "")
      + kv("设计上限 MAX_QUEUE", "64")
      + kv("数据源", "learner.jsonl 的 noted / intake"), "wide")
      + card("经验检索与学习（expmem.jsonl）",
        kv("planning_knowledge（规划知识条目）", String(m.planning_knowledge))
        + kv("recalled（被检索回上下文）", String(m.recalled), m.recalled ? "state-good" : "state-warn")
        + kv("learned（被学到）", String(m.learned)), "wide")
      + card("这一页的边界", '<p class="audit-gap">62 号文档 §2/§3/§5 是<b>设计意图，不是代码现状</b>：'
        + "真正落地的字段只有 problem / tried / snapshot 三个；signature 的真实键是 <code>fingerprint</code>；"
        + "MemoQueue <b>没有合并逻辑</b>，只有 MAX_QUEUE=64 的上限；成功样本的投料口<b>不存在</b>。<br>"
        + "三条投料线（AC / 经验库 / 携带器）里，<b>游戏内 AI 至今没有主动往队列写过</b> —— "
        + "本块深度长期为 0 不是故障，是真现状。</p>", "wide");
  }
  // ── ⑤.4 携带器判定 ───────────────────────────────────────────────
  function carrierPage() {
    var lrn = MonitorStore.getEvents("learner");
    var assess = 0, shortCircuit = 0, carried = 0, i, e, p;
    for (i = 0; i < lrn.length; i++) {
      e = lrn[i]; p = e.payload || {};
      if (e.type === "carrier_assessment") {
        assess++;
        if (typeof p.why === "string" && p.why.indexOf("未求值") >= 0) shortCircuit++;
      }
      if (Array.isArray(p.carry_list) || Array.isArray(p.carry)) { if ((p.carry_list || p.carry).length) carried++; }
    }
    return card("携带器 · 本轮窗口统计",
      kv("carrier_assessment 次数", String(assess))
      + kv("其中短路（后续级别未求值）", String(shortCircuit), shortCircuit > 0 ? "state-warn" : "")
      + kv("携带项非空的记录", String(carried))
      + kv("数据源", "learner.jsonl 的 carrier_assessment / noted"), "wide")
      + card("逐次判定", '<div class="event-list">' + learnerCarrier(lrn) + "</div>", "wide");
  }
  function buildBody(events) {
    var h;
    if (page === "ai") {
      // 对话式 AI 视图：用户输入 / AI 决策 / AI 输出 / 工具调用
      var aiRows = "";
      for (var i = events.length - 1; i >= 0; i--) {
        var ev = events[i];
        var role, cls, label;
        if (ev.type === "user_prompt") { role = "用户"; cls = "ai-user"; label = "→"; }
        else if (ev.type === "assistant") { role = "AI"; cls = "ai-assistant"; label = "←"; }
        else if (ev.type === "tool_call") { role = "工具"; cls = "ai-tool"; label = "⚙"; }
        else if (ev.type === "llm") { role = "决策"; cls = "ai-decision"; label = "◆"; }
        else if (ev.type === "turn") { role = "回合"; cls = "ai-decision"; label = "▸"; }
        else { role = "AI"; cls = "ai-loop"; label = "·"; }
        aiRows += '<div class="ai-row ' + cls + '"><span class="ai-role">' + esc(role) + '</span>'
            + '<span class="ai-time">' + esc((ev.timestamp || "").slice(11, 19)) + '</span>'
            + '<span class="ai-arrow">' + label + '</span>'
            + '<span class="ai-text">' + esc(ev.text || ev.type) + '</span></div>';
      }
      h = card("Numen · 历史对话与日志摘要", aiRows || '<div class="empty">还没有记录到对话。<br>数据来自 <code>ai.jsonl</code>；游戏跑起来并产生回合之后这里才会有内容。<b>「还没记录」不等于「0 条」。</b></div>', "wide");
    } else if (page === "context") {
      h = lead('这一页是<b>执行 AI 实际收到的原文</b>：每一行 = 一条 <code>context.jsonl</code> / <code>rdd.jsonl</code> 记录，'
        + '点开看它发出去的完整文本。<b>这里看到的就是模型看到的</b>，没有任何加工。'
        + '上面那条中文说明是这个事件对应游戏里的什么（英文名保留，方便查原始日志）。'
        + '没有记录 ⇒ 显示「还没记录」，不等于「0 条」。')
        + card("Numen 已记录的上下文", RddView.contextRows(events), "wide");
    } else if (page === "tools") {
      loadCatalog();
      h = lead('上半块是<b>MCP 现在真实暴露的工具目录</b>（只读查询，读不到会明说读不到，不用历史冒充现状）；'
        + '下半块是<b>已经记录下来的调用与结果原文</b>。两者都不是「全部」：目录受服务端开关影响，'
        + '记录受窗口上限影响。')
        + card('Numen 工具目录 · 只读查询', catalogRows(), 'wide') + card('工具调用与结果 · 已记录证据', RddView.contextRows(events), 'wide');
    } else if (page === "ac-placeholder") {
      h = card("AC Execution", '<div class="graph"><span class="node done">inspect</span><span class="arrow">→</span><span class="node">prepare</span><span class="arrow">→</span><span class="node">execute</span><span class="arrow">→</span><span class="node">verify</span></div>' + kv("AC 本体", "等待 RDD 提供") + kv("当前状态", "PENDING", "state-warn"), "wide") + eventsCard("AC 事件", events);
    } else if (page === "supervisor") {
      h = RddView.renderSupervisor(MonitorStore.getEvents('rdd'), MonitorStore.getEvents('context'), { card: card, kv: kv });
    } else if (page === "chainbody") {
      h = RddView.renderTaskChain(MonitorStore.getEvents('rdd'), MonitorStore.getEvents('context'), { card: card, kv: kv, flow: rddFlow, detail: rddCurrentDetail, events: rddEventRows, supervision: rddSupCard });
    } else if (page === "assets") {
      h = assetsPage();
    } else if (page === "branches") {
      h = branchesPage();
    } else if (page === "supervision") {
      h = supervisionPage();
    } else if (page === "rddtools") {
      h = rddtoolsPage(MonitorStore.getEvents('tools'));
    } else if (page === "queue") {
      h = queuePage();
    } else if (page === "carrier-assess") {
      h = carrierPage();
    } else if (page === "numen") {
      h = RddView.renderNumen(MonitorStore.getEvents('rdd'), MonitorStore.getEvents('context'), { card: card, kv: kv });
    } else if (page === "rdd") {
      h = RddView.render(events, MonitorStore.getEvents('context'), { card: card, kv: kv, flow: rddFlow, detail: rddCurrentDetail, events: rddEventRows, supervision: rddSupCard });
      var journalHealth = MonitorStore.health();
      if ([journalHealth.rddJournal, journalHealth.contextJournal].some(function (j) { return j && (!j.readable || j.invalidLines > 0); })) h = '<p class="audit-gap">部分日志不可读或有损坏 / 超过大小限制的记录。以下只代表成功采集的内容，不能保证最新请求完整；请查看实例原日志。</p>' + h;
    } else if (page === "environment") {
      h = card("Environment Snapshot", '<div class="empty">暂无真实环境快照<br>不会用 Mock 文本冒充 MC 世界状态</div>', "wide");
    } else if (page === "health") {
      h = card('链路诊断', healthRows(), 'wide') + card('看门狗边界', '本页显示日志时效；游戏、Monitor 和模型 API 的恢复由外部看门狗负责。数据陈旧可能是暂停、退出或故障，不能仅据此断言游戏崩溃。', 'wide') + eventsCard('Health Events', events);
    } else if (page === "instrumentation") {
      h = lead('这一页是<b>引擎自己写的异常埋点</b>，独立日志区 <code>instrumentation.jsonl</code>，'
        + '红底行是异常：死亡 / 空转（同一动作重复）/ 恢复失败 / 资产不匹配 / 重复采集。'
        + '它记录的是<b>引擎观测到的事实</b>，不是 AI 自述。')
        + card("埋点 · 第八系统（独立日志区 instrumentation.jsonl）", instrRows(MonitorStore.getEvents('instrumentation')) + '<p class="audit-gap">链路：规划/检测/执行/死亡 → RddInstrumentation 写 instrumentation.jsonl → 监测台本页只读展示。事件类型：starvation_death/death/loop_detected/repeat_gather/resource_waste/asset_mismatch/recovery_failed/instrumentation_change。</p>', "wide");
    } else if (page === "settlement") {
      h = settlementPage();
    } else if (page === "learner") {
      h = learnerPage(MonitorStore.getEvents('learner'));
    } else if (page === "intro") {
      loadDoc("INTRO");
      h = card("项目介绍 · RDD 八模块 / 定位 / 验收边界", md(docsCache["INTRO"] || "加载中…"), "wide");
    } else if (page === "docs") {
      loadDoc(curDoc);
      var sel = '<select id="doc-select" style="margin-bottom:10px">' + DOCS.map(function (d) { return '<option value="' + d + '"' + (curDoc === d ? " selected" : "") + '>' + docNames[d] + '</option>'; }).join("") + '</select>';
      h = card("项目文档", sel + '<div class="doc-body">' + md(docsCache[curDoc] || "加载中…") + '</div>', "wide");
    } else {
      h = card("项目介绍", md(docsCache["INTRO"] || "加载中…"), "wide");
    }
    return h;
  }
  function updateFooter(events) {
    var stats = MonitorStore.stats();
    document.getElementById("queue-depth").textContent = stats.queue;
    document.getElementById("dropped").textContent = stats.dropped;
    document.getElementById("stream-badge").textContent = stats.connected ? "日志服务已连接" : "等待日志服务";
    document.getElementById("stream-badge").className = "badge " + (stats.connected ? "ok" : "warn");
    document.getElementById("adapter-status").textContent = stats.connected ? "实例日志" : "等待日志";
    document.getElementById("adapter-dot").className = "dot " + (stats.connected ? "ok" : "warn");
    var observation = MonitorStore.health().observation || {};
    document.getElementById('watch-state').textContent = stats.connected ? (observation.state || 'NO_DATA') : 'DISCONNECTED';
    document.getElementById('watch-state').className = stats.connected && observation.state === 'RECENT' ? 'good' : 'warn';
    var rddEvents = MonitorStore.getEvents('rdd');
    var fresh = stats.connected && rddEvents.some(function (e) { var age = Date.now() - Date.parse(e.timestamp); return age >= 0 && age < 30000; });
    document.getElementById("rdd-status").textContent = fresh ? "RDD 近期观测" : (rddEvents.length ? "RDD 历史记录" : "RDD 未接入");
    document.getElementById("rdd-dot").className = "dot " + (fresh ? "ok" : "warn");
    document.getElementById("last-event").textContent = events.length ? events[events.length - 1].timestamp.slice(11, 19) : "—";
  }
  function render(force) {
    var events = MonitorStore.getEvents();
    if (frozen && !force) { return; }
    var ext = EXTERNAL[page];
    var h = ext ? "" : buildBody(events);
    var previousScroll = content.scrollTop;
    var uiSnap = snapshotUi();
    // ★ 这一段是 2026-10-03 修的核心。原来四个独立页的分支只调 view.render()
    //   不给 h 赋值，然后这里无条件 content.innerHTML = '…' + h → 写入 "undefined"，
    //   把 view 刚画进容器里的东西整个抹掉。宿主常驻 + 只换 innerHTML 才是要的：
    //   #page-body 装本子块的卡片，host 装外部 view 的输出，两者互不覆盖。
    showHost(ext ? ext.host : null);
    body.innerHTML = h;
    content.scrollTop = previousScroll;
    restoreUi(uiSnap);
    if (ext) { ext.mount(); }
    updateFooter(events);
  }
  // ── 视图状态保留：整块重建前 snapshot，重建后恢复（横拖任务链 / 折叠展开不被数据刷新打回）──
  // 覆盖两类易失状态：①可横滚元素（.task-flow / .table-scroll）的 scrollLeft；②全部 <details> 开合；
  // 有 data-detail-id / data-audit-id 的折叠已有 opened 缓存，这里兜底无 id 的细节（如工具目录）。
  // 折叠的稳定标识：有 id 的用 id（rdd-view / request-view 已按 id 缓存开合，
  // 这里只兜底没有 id 的折叠，例如工具目录），没有 id 的退到「第几个 + 摘要文字」。
  function detailKeys() {
    var dl = content.querySelectorAll('details'), keys = [];
    for (var i = 0; i < dl.length; i++) {
      var d = dl[i], sum = d.querySelector(':scope > summary');
      keys.push((d.dataset.detailId || d.dataset.auditId)
        ? 'id:' + (d.dataset.detailId || d.dataset.auditId)
        : 'anon:' + i + ':' + (sum ? sum.textContent : ''));
    }
    return keys;
  }
  function snapshotUi() {
    var snap = { flow: [], scroll: [], details: [], keys: detailKeys() };
    var flows = content.querySelectorAll('.task-flow, .table-scroll');
    for (var i = 0; i < flows.length; i++) { snap.flow.push(flows[i].className); snap.scroll.push(flows[i].scrollLeft); }
    var dl = content.querySelectorAll('details');
    for (var j = 0; j < dl.length; j++) snap.details.push(dl[j].open);
    return snap;
  }
  function restoreUi(snap) {
    if (!snap) return;
    var flows = content.querySelectorAll('.task-flow, .table-scroll');
    for (var i = 0; i < flows.length && i < snap.flow.length; i++) {
      if (flows[i].className === snap.flow[i] && flows[i].scrollLeft !== snap.scroll[i]) flows[i].scrollLeft = snap.scroll[i];
    }
    var dl = content.querySelectorAll('details'), keys = detailKeys();
    // ★ 只在**同一批折叠**上恢复。换子块时折叠集合已经不同，按下标硬套会把上一页的
    //   开合状态盖到这一页上 —— 2026-10-03 实测：切到 3.1 时「历史与排错」明明默认收起，
    //   却被上一页第一条展开的上下文条顶成展开的。id 对不上就整段放弃：
    //   宁可丢一次视图状态，不可把状态串到别的子块上。
    if (keys.length !== snap.keys.length) return;
    for (var j = 0; j < keys.length; j++) {
      if (keys[j] === snap.keys[j] && dl[j].open !== snap.details[j]) dl[j].open = snap.details[j];
    }
  }
  // ── 暂停 / 恢复刷新按钮 ──
  function setFrozen(v) {
    if (frozen === v) return;
    frozen = v;
    var btn = document.getElementById("pause-btn"), badge = document.getElementById("freeze-badge");
    if (btn) { btn.textContent = frozen ? "▶ 恢复刷新" : "⏸ 暂停刷新"; btn.classList.toggle("paused", frozen); }
    if (badge) badge.style.display = frozen ? "inline-block" : "none";
    if (!frozen) render(true);
  }
  // 页签点击已改为 zoneBar / subBar 上的事件委托（见文件头），这里不再逐个绑定。
  // 3.4 断点与缺口第一次被点开时才拉 /api/chain + 起轮询（8s），别拖慢其它子块首屏。
  var chainStarted = false;
  (function () {
    subBar.addEventListener("click", function () {
      if (chainStarted || page !== "gaps") return;
      chainStarted = true;
      if (window.ChainView && ChainView.start) ChainView.start();
    });
  }());
  // 文档下拉切换（docs 子块）
  document.addEventListener("change", function (ev) {
    if (ev.target && ev.target.id === 'rdd-companion') { RddView.select(ev.target.value); render(true); }
    if (ev.target && ev.target.id === 'font-scale') { document.documentElement.dataset.fontScale = ev.target.value; localStorage.setItem('rdd-monitor-font-scale', ev.target.value); }
    if (ev.target && ev.target.id === "doc-select") { curDoc = ev.target.value; render(true); }
  });
  // RDD 空转止血：暂停/恢复按钮（内容每次重建 → 用事件委托）+ 周期同步开关真实状态
  document.addEventListener("click", function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest("[data-rdd-cmd]") : null;
    if (b) rddCmd(b.getAttribute("data-rdd-cmd"));
  });
  rddSupSync();
  var SUP_PAGES = ["chainbody", "branches", "supervision", "gaps", "rdd"];
  setInterval(function () { if (SUP_PAGES.indexOf(page) >= 0) rddSupSync(); }, 4000);
  var pauseBtn = document.getElementById("pause-btn");
  if (pauseBtn) pauseBtn.addEventListener("click", function () { setFrozen(!frozen); });
  MonitorStore.subscribe(render);
  NumenAdapter.subscribe(function (message) { MonitorStore.accept(message); });
  NumenAdapter.start();
  var savedFontScale = localStorage.getItem('rdd-monitor-font-scale') || 'large';
  document.documentElement.dataset.fontScale = savedFontScale;
  document.getElementById('font-scale').value = savedFontScale;
  buildNav();
  MonitorStore.setPage(page);
  // 独立 view 的 init：抓容器句柄 + 起自己的轮询。
  // 依赖 index.html 里的脚本顺序 —— 这些 view 排在**本文件之前**，
  // 所以这里可以直接同步调；容器在 index.html 里常驻，init 只需一次
  // （以前是把它们塞进 render() 分支里调，容器一被 innerHTML 冲掉句柄就悬空，
  //   输出也一并被抹掉 —— 那四个空白页就是这么来的）。
  if (window.ChainView && ChainView.start) { ChainView.onUpdate = function () { if (page === "gaps") render(true); }; }
  if (window.ExperienceChainView && ExperienceChainView.init) ExperienceChainView.init();
  if (window.AcxView && AcxView.init) AcxView.init();
  if (window.ExperienceLibraryView && ExperienceLibraryView.init) ExperienceLibraryView.init();
  if (window.TokenView && TokenView.init) TokenView.init();
  if (window.ExperimentView && ExperimentView.init) ExperimentView.init();
  if (window.WorldFactsView && WorldFactsView.init) WorldFactsView.init();
  if (window.EconomyView && EconomyView.init) EconomyView.init();
  // 首帧推到下一轮任务：日志流首批快照进来会长时间占住主线程，
  // setTimeout(0) 才有机会在两次重建之间跑掉。
  setTimeout(function () { render(); }, 0);
  var previousSequence = 0;
  setInterval(function () {
    var sequence = MonitorStore.stats().sequence;
    document.getElementById("in-rate").textContent = sequence - previousSequence;
    previousSequence = sequence;
  }, 1000);
}());
