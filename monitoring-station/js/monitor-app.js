(function () {
  var titles = { overview: "总览", ai: "历史对话", context: "历史上下文", tools: "Numen 工具能力", ac: "AC", supervisor: "任务链 AI", taskchain: "任务链", numen: "Numen AI", environment: "环境", health: "连接诊断", intro: "项目介绍", docs: "文档" };
  var DOCS = ["INTRO", "ARCHITECTURE", "CONTRACT", "INTERFACES", "REGRESSION", "HANDOFF"];
  var docNames = { INTRO: "项目介绍", ARCHITECTURE: "架构总览", CONTRACT: "模块契约", INTERFACES: "接口清单", REGRESSION: "回归清单", HANDOFF: "交接文档" };
  var page = "taskchain";
  var content = document.getElementById("monitor-content");
  var title = document.getElementById("page-title");
  var tabs = document.querySelectorAll("#monitor-tabs button");
  var renderQueued = false;

  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function kv(k, v, cls) { return '<div class="kv"><span class="k">' + esc(k) + '</span><span class="v ' + (cls || "") + '">' + esc(v) + '</span></div>'; }
  function card(t, b, cls) { return '<div class="card ' + (cls || "") + '"><div class="card-title">' + esc(t) + '</div>' + b + '</div>'; }
  function rows(events) {
    var h = "";
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i];
      h += '<div class="event-row"><span class="time">' + esc(e.timestamp.slice(11, 19)) + '</span><span class="type">' + esc(e.type) + '</span><span class="text" title="' + esc(e.text) + '">' + esc(e.text) + '</span></div>';
    }
    return h || '<div class="empty">暂无真实数据 · 等待 RDD Adapter 接入</div>';
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
        if (s !== rddSupState) { rddSupState = s; if (page === "rdd") render(); }
      }).catch(function () {});
  }
  function render() {
    var events = MonitorStore.getEvents();
    title.textContent = titles[page];
    var h;
    if (page === "overview") {
      h = card('连接状态', healthRows(), 'wide') + eventsCard('最近事件', events);
    } else if (page === "ai") {
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
      h = card("Numen · 历史对话与日志摘要", aiRows || '<div class="empty">暂无已记录对话</div>', "wide");
    } else if (page === "context") {
      h = card("Numen 已记录的上下文", RddView.contextRows(events), "wide");
    } else if (page === "tools") {
      loadCatalog();
      h = card('Numen 工具目录 · 只读查询', catalogRows(), 'wide') + card('工具调用与结果 · 已记录证据', RddView.contextRows(events), 'wide');
    } else if (page === "ac") {
      h = card("AC Execution", '<div class="graph"><span class="node done">inspect</span><span class="arrow">→</span><span class="node">prepare</span><span class="arrow">→</span><span class="node">execute</span><span class="arrow">→</span><span class="node">verify</span></div>' + kv("AC 本体", "等待 RDD 提供") + kv("当前状态", "PENDING", "state-warn"), "wide") + eventsCard("AC 事件", events);
    } else if (page === "supervisor") {
      h = RddView.renderSupervisor(MonitorStore.getEvents('rdd'), MonitorStore.getEvents('context'), { card: card, kv: kv });
    } else if (page === "taskchain") {
      h = RddView.renderTaskChain(MonitorStore.getEvents('rdd'), MonitorStore.getEvents('context'), { card: card, kv: kv, flow: rddFlow, detail: rddCurrentDetail, events: rddEventRows, supervision: rddSupCard });
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
    var previousScroll = content.scrollTop;
    content.innerHTML = '<div class="grid">' + h + '</div>';
    content.scrollTop = previousScroll;
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
    for (var j = 0; j < tabs.length; j++) { tabs[j].classList.toggle("active", tabs[j].getAttribute("data-page") === page); }
  }
  function scheduleRender() {
    if (renderQueued) return;
    renderQueued = true;
    window.requestAnimationFrame(function () { renderQueued = false; render(); });
  }
  for (var i = 0; i < tabs.length; i++) { tabs[i].addEventListener("click", function () { page = this.getAttribute("data-page"); MonitorStore.setPage(page); }); }
  // 文档下拉切换（docs 分页）
  document.addEventListener("change", function (ev) {
    if (ev.target && ev.target.id === 'rdd-companion') { RddView.select(ev.target.value); render(); }
    if (ev.target && ev.target.id === 'font-scale') { document.documentElement.dataset.fontScale = ev.target.value; localStorage.setItem('rdd-monitor-font-scale', ev.target.value); }
    if (ev.target && ev.target.id === "doc-select") { curDoc = ev.target.value; render(); }
  });
  // RDD 空转止血：暂停/恢复按钮（内容每次重建 → 用事件委托）+ 周期同步开关真实状态
  document.addEventListener("click", function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest("[data-rdd-cmd]") : null;
    if (b) rddCmd(b.getAttribute("data-rdd-cmd"));
  });
  rddSupSync();
  setInterval(function () { if (page === "taskchain" || page === "rdd") rddSupSync(); }, 4000);
  MonitorStore.subscribe(scheduleRender);
  NumenAdapter.subscribe(function (message) { MonitorStore.accept(message); });
  NumenAdapter.start();
  var savedFontScale = localStorage.getItem('rdd-monitor-font-scale') || 'large';
  document.documentElement.dataset.fontScale = savedFontScale;
  document.getElementById('font-scale').value = savedFontScale;
  MonitorStore.setPage(page);
  var previousSequence = 0;
  setInterval(function () {
    var sequence = MonitorStore.stats().sequence;
    document.getElementById("in-rate").textContent = sequence - previousSequence;
    previousSequence = sequence;
  }, 1000);
  render();
}());
