(function () {
  var titles = { overview: "总览", ai: "AI / Thinking", context: "Context", tools: "Tools", ac: "AC", rdd: "RDD 任务链", environment: "环境", health: "健康 / Watchdog", intro: "项目介绍", docs: "文档" };
  var DOCS = ["INTRO", "ARCHITECTURE", "CONTRACT", "INTERFACES", "REGRESSION", "HANDOFF"];
  var docNames = { INTRO: "项目介绍", ARCHITECTURE: "架构总览", CONTRACT: "模块契约", INTERFACES: "接口清单", REGRESSION: "回归清单", HANDOFF: "交接文档" };
  var page = "overview";
  var content = document.getElementById("monitor-content");
  var title = document.getElementById("page-title");
  var tabs = document.querySelectorAll("#monitor-tabs button");

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
      if (inCode) { out.push(esc2(l)); continue; }
      if (/^\|/.test(l)) {
        var cells = l.replace(/^\||\|$/g, "").split("|").map(function (c) { return c.trim(); });
        if (/^:?-+:?$/.test(cells.join("").replace(/-/g, "").replace(/:/g, ""))) continue;
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
  var curDoc = "ARCHITECTURE";
  function loadDoc(name) {
    if (docsCache[name]) return;
    fetch("/api/doc?name=" + encodeURIComponent(name)).then(function (r) { return r.json(); }).then(function (d) {
      docsCache[name] = (d && d.markdown) ? d.markdown : "（文档加载失败：" + ((d && d.error) || "未知") + "）";
      render();
    }).catch(function () { docsCache[name] = "（文档加载失败：网络错误）"; render(); });
  }
  function eventsCard(name, events) { return card(name, '<div class="event-list">' + rows(events) + '</div>', "wide"); }
  function render() {
    var events = MonitorStore.getEvents();
    title.textContent = titles[page];
    var h;
    if (page === "overview") {
      h = '<div class="grid">' + card("连接状态", kv("UI", "HEALTHY", "state-good") + kv("Adapter", "MOCK / 未接入", "state-warn") + kv("RDD", "等待接入", "state-warn") + kv("Newman", "等待接入", "state-warn") + kv("MC", "等待接入", "state-warn")) + card("AI 当前状态", kv("阶段", "等待真实事件", "state-off") + kv("Thinking", "—") + kv("Task", "—") + kv("最近事件", events.length ? "刚刚" : "—")) + eventsCard("最近事件", events) + '</div>';
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
      h = card("Newman AI · 对话式", aiRows || '<div class="empty">暂无 AI 数据 · 等 AI 对话产生</div>', "wide");
    } else if (page === "context") {
      h = card("Prompt + Context + Tools", events.length ? '<div class="pre">' + esc(JSON.stringify(events[events.length - 1].payload || {}, null, 2)) + '</div>' : '<div class="empty">暂无 Context 快照</div>', "wide");
    } else if (page === "tools") {
      var table = '<table class="table"><tr><th>序号</th><th>类型</th><th>状态</th><th>说明</th></tr>';
      for (var i = 0; i < events.length; i++) { table += '<tr><td>' + events[i].sequence + '</td><td>' + esc(events[i].type) + '</td><td class="state-warn">MOCK / UNSUPPORTED</td><td>' + esc(events[i].text) + '</td></tr>'; }
      h = card("工具调用记录", table + '</table>', "wide") + card("控制能力", kv("工具开关", "UNSUPPORTED", "state-warn") + kv("真实执行", "等待 Adapter") + '<button class="btn" disabled>关闭工具（暂不可用）</button>');
    } else if (page === "ac") {
      h = card("AC Execution", '<div class="graph"><span class="node done">inspect</span><span class="arrow">→</span><span class="node">prepare</span><span class="arrow">→</span><span class="node">execute</span><span class="arrow">→</span><span class="node">verify</span></div>' + kv("AC 本体", "等待 RDD 提供") + kv("当前状态", "PENDING", "state-warn"), "wide") + eventsCard("AC 事件", events);
    } else if (page === "rdd") {
      var latest = null;
      for (var ri = events.length - 1; ri >= 0; ri--) {
        if (events[ri].type === "taskchain_snapshot" || (events[ri].payload && events[ri].payload.taskChain)) { latest = events[ri].payload.taskChain || events[ri].payload; break; }
      }
      var chainHtml = latest ? '<div class="pre">' + esc(JSON.stringify(latest, null, 2)) + '</div>' : '<div class="empty">暂无任务链快照 · 等待 RDD 事件</div>';
      h = card("任务链详情（只读）", chainHtml, "wide") + eventsCard("RDD 事件 / 推进原因 / 监督消息", events);
    } else if (page === "environment") {
      h = card("Environment Snapshot", '<div class="empty">暂无真实环境快照<br>不会用 Mock 文本冒充 MC 世界状态</div>', "wide");
    } else if (page === "health") {
      h = '<div class="grid">' + card("链路诊断", kv("Monitoring UI", "HEALTHY", "state-good") + kv("Monitoring Data", "HEALTHY", "state-good") + kv("RDD Adapter", "NOT CONNECTED", "state-warn") + kv("Newman", "NOT CONNECTED", "state-warn") + kv("MC", "NOT CONNECTED", "state-warn")) + card("看门狗", kv("队列", "见底部指标") + kv("高频保护", "已启用样板计数") + kv("慢消费者", "未检测") + kv("自动恢复", "Adapter 接入后启用")) + '</div>' + eventsCard("Health Events", events);
    } else if (page === "intro") {
      loadDoc("INTRO");
      h = card("项目介绍 · 五大系统 / 定位 / 能力", md(docsCache["INTRO"] || "加载中…"), "wide");
    } else if (page === "docs") {
      loadDoc(curDoc);
      var sel = '<select id="doc-select" style="margin-bottom:10px">' + DOCS.map(function (d) { return '<option value="' + d + '"' + (curDoc === d ? " selected" : "") + '>' + docNames[d] + '</option>'; }).join("") + '</select>';
      h = card("项目文档", sel + '<div class="doc-body">' + md(docsCache[curDoc] || "加载中…") + '</div>', "wide");
    } else {
      h = card("项目介绍", md(docsCache["INTRO"] || "加载中…"), "wide");
    }
    content.innerHTML = h;
    var stats = MonitorStore.stats();
    document.getElementById("queue-depth").textContent = stats.queue;
    document.getElementById("dropped").textContent = stats.dropped;
    document.getElementById("stream-badge").textContent = stats.connected ? "NUMEN LIVE" : "WAITING FOR NUMEN";
    document.getElementById("stream-badge").className = "badge " + (stats.connected ? "ok" : "warn");
    document.getElementById("adapter-status").textContent = stats.connected ? "Adapter NUMEN JSONL" : "Adapter WAITING";
    document.getElementById("adapter-dot").className = "dot " + (stats.connected ? "ok" : "warn");
    document.getElementById("rdd-status").textContent = stats.connected ? "RDD 数据已发现" : "RDD 未接入";
    document.getElementById("rdd-dot").className = "dot " + (stats.connected ? "ok" : "warn");
    document.getElementById("last-event").textContent = events.length ? events[events.length - 1].timestamp.slice(11, 19) : "—";
    for (var j = 0; j < tabs.length; j++) { tabs[j].classList.toggle("active", tabs[j].getAttribute("data-page") === page); }
  }
  for (var i = 0; i < tabs.length; i++) { tabs[i].addEventListener("click", function () { page = this.getAttribute("data-page"); MonitorStore.setPage(page); }); }
  // 文档下拉切换（docs 分页）
  document.addEventListener("change", function (ev) {
    if (ev.target && ev.target.id === "doc-select") { curDoc = ev.target.value; render(); }
  });
  document.getElementById("btn-inject").addEventListener("click", function () { MonitorStore.inject(); });
  document.getElementById("btn-clear").addEventListener("click", function () { MonitorStore.clear(); });
  MonitorStore.subscribe(render);
  NumenAdapter.subscribe(function (message) { MonitorStore.accept(message); });
  NumenAdapter.start();
  MonitorStore.setPage("overview");
  setInterval(function () { document.getElementById("in-rate").textContent = MonitorStore.stats().sequence; }, 1000);
  render();
}());
