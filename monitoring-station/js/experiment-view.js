/* ============================================================
   ExperimentView — 实验统计（⑥）
   ------------------------------------------------------------
   ★ 这一页要回答的问题（owner 2026-10-05）：
     「每一个 AI 在每一个目标下，死亡的次数、AC 调用次数、行动次数是多少？」

   ★ 为什么不能在前端算：得同时读 9 个日志文件（instrumentation 死因 /
     tools 工具 / events 任务结果 / ai 回合 / acx AC / expmem 经验 / learner 复盘 /
     rdd 目标），而浏览器侧每页只有 500 条 × 一类。所以走服务端聚合
     （/api/experiment-stats），本页只负责「把同一份聚合切成几个可比的视角」。

   ★ 「面板（docker）」是什么：
     下面是若干个**可拖拽重排、可折叠、可开关**的小面板，每个面板自带
     「看哪个指标 / 分组方式 / 线还是柱」三个开关。顺序、开关、每个面板的
     开关都存在 localStorage 里 —— 这样「我平时看的排法」不用每次重排。
     另外给了 ↑/↓ 按钮：拖拽在某些环境里不可用，按钮是无条件可用的那条路。

   ★ 三条不许省的纪律（后端同款，见 experiment-stats.mjs 文件头）：
     ① 数据源读不到 ⇒ 那一格显示「未接入」，**不显示 0**。
     ② 归属是推断的（AC 没有同伴字段）⇒ 数字旁必须标「推断」。
     ③ 派生比率分母为 0 ⇒ 显示「?」，不显示 0。
   ============================================================ */

var ExperimentView = (function () {
  "use strict";

  var DATA = null;
  var ERROR = "";
  var TIMER = null;
  var FETCHING = false;
  var POLL_MS = 12000;   // 与服务端 12 秒缓存对齐，再快也是拿同一份

  /* ── 面板布局（持久化）────────────────────────────────── */
  var LAYOUT_KEY = "rdd-exp-layout-v1";
  var PANELS = [
    { id: "deaths", title: "死亡次数", metric: "deaths", group: "pair", type: "bar",
      hint: "每个 AI×目标 各死了多少次。堆叠柱按时间桶，看死亡集中在哪一段。" },
    { id: "actions", title: "行动次数", metric: "actions", group: "pair", type: "line",
      hint: "工具调用总数。它是死亡率的分母 —— 行动多但死亡也多，说明效率没上去。" },
    { id: "ac", title: "AC 调用", metric: "acRuns", group: "pair", type: "bar",
      hint: "AC 状态机的运行次数。归属为推断（acx 事件本身没有同伴字段），推断不出来的部分会写在图表上方。" },
    { id: "acfail", title: "AC 步骤失败", metric: "acStepFails", group: "pair", type: "bar",
      hint: "每一步的失败数。同样是推断归属。" },
    { id: "rate", title: "死亡率走势", metric: "deathsPer100Actions", group: "pair", type: "line",
      hint: "每百次行动死了几次。分母是行动次数，所以「跑得久」不会被读成「学得好」。" },
    { id: "turns", title: "AI 回合", metric: "turns", group: "companion", type: "line",
      hint: "内脑一共思考了几轮。按 AI 分组，能看出谁在忙。" },
    { id: "learn", title: "学习活动", metric: "recalls", group: "companion", type: "line",
      hint: "经验被检索了几次。按 AI 分组 —— 检索是学习链上唯一被记录到的主动动作。" },
    { id: "review", title: "学习者复盘", metric: "reviews", group: "companion", type: "bar",
      hint: "learner.reviewed 的次数。0 不是「没学」，可能是没接上（看接入表）。" }
  ];

  var state = {
    order: PANELS.map(function (p) { return p.id; }),
    hidden: {},
    collapsed: {},
    panels: {},                 // id → {metric, group, type}
    metricCols: ["deaths", "actions", "acRuns", "acStepFails", "turns", "tasksDone", "recalls", "reviews"],
    sortKey: "lastAt",
    sortDir: -1,
    selected: null,             // 选中的行 key
    seriesCap: 6                // 一个面板最多画几条序列
  };
  for (var pi = 0; pi < PANELS.length; pi++) {
    state.panels[PANELS[pi].id] = { metric: PANELS[pi].metric, group: PANELS[pi].group, type: PANELS[pi].type };
  }

  /* ── localStorage 一定要包起来：沙箱测试里它是 undefined ── */
  function store(key, value) {
    try {
      if (typeof localStorage === "undefined" || !localStorage) return false;
      if (value == null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
      return true;
    } catch (e) { return false; }
  }
  function loadLayout() {
    var raw = null;
    try {
      if (typeof localStorage === "undefined" || !localStorage) return;
      raw = localStorage.getItem(LAYOUT_KEY);
    } catch (e) { return; }
    if (!raw) return;
    var o = null;
    try { o = JSON.parse(raw); } catch (e) { return; }
    if (!o || typeof o !== "object") return;
    // 只接受**已知的**面板 id：localStorage 里的旧值/坏值不许造出空面板
    var known = {};
    for (var i = 0; i < PANELS.length; i++) known[PANELS[i].id] = 1;
    if (o.order && o.order.length) {
      var ord = [];
      for (var j = 0; j < o.order.length; j++) if (known[o.order[j]]) ord.push(o.order[j]);
      for (var k = 0; k < PANELS.length; k++) if (ord.indexOf(PANELS[k].id) < 0) ord.push(PANELS[k].id);
      state.order = ord;
    }
    if (o.hidden && typeof o.hidden === "object") state.hidden = o.hidden;
    if (o.collapsed && typeof o.collapsed === "object") state.collapsed = o.collapsed;
    if (o.panels && typeof o.panels === "object") {
      for (var m = 0; m < PANELS.length; m++) {
        var pid = PANELS[m].id, saved = o.panels[pid];
        if (saved && typeof saved === "object") {
          if (typeof saved.metric === "string") state.panels[pid].metric = saved.metric;
          if (saved.group === "pair" || saved.group === "companion" || saved.group === "goal") state.panels[pid].group = saved.group;
          if (saved.type === "line" || saved.type === "bar") state.panels[pid].type = saved.type;
        }
      }
    }
    if (o.metricCols && o.metricCols.length) state.metricCols = o.metricCols.slice(0, 10);
    if (o.seriesCap) state.seriesCap = Math.max(2, Math.min(10, Number(o.seriesCap) || 6));
  }
  function saveLayout() {
    store(LAYOUT_KEY, JSON.stringify({
      order: state.order, hidden: state.hidden, collapsed: state.collapsed,
      panels: state.panels, metricCols: state.metricCols, seriesCap: state.seriesCap
    }));
  }

  /* ── 小工具 ─────────────────────────────────────────── */
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  function hhmm(iso) {
    if (!iso) { return "?"; }
    var m = String(iso).match(/T(\d\d):(\d\d):(\d\d)/);
    return m ? (m[1] + ":" + m[2] + ":" + m[3]) : String(iso).slice(0, 19);
  }
  function mdhm(iso) {
    if (!iso) { return "?"; }
    var m = String(iso).match(/T(\d\d):(\d\d)/);
    var d = String(iso).slice(5, 10);
    return d + " " + (m ? (m[1] + ":" + m[2]) : "?");
  }
  function span(sec) {
    if (sec == null) { return "?"; }
    var s = Math.abs(Math.round(sec));
    if (s < 90) { return s + " 秒"; }
    var m = Math.round(s / 60);
    if (m < 90) { return m + " 分钟"; }
    var h = m / 60;
    if (h < 36) { return (Math.round(h * 10) / 10) + " 小时"; }
    return Math.round(h / 24) + " 天";
  }
  function grp(n) {
    if (n == null) { return "?"; }
    return String(Math.round(Number(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }
  /** ★ null/undefined 一律显示 ?，绝不显示 0（纪律③）。 */
  function num(v) { return (v == null || !isFinite(v)) ? "?" : String(v); }
  function shortId(id) { return id ? String(id).slice(0, 8) : "?"; }

  /* ── 每个指标依赖哪个日志文件：读不到就显示「未接入」── */
  var METRIC_SOURCE = {
    deaths: ["instrumentation"], loops: ["instrumentation"], assetMismatch: ["instrumentation"],
    actions: ["tools"], rejected: ["tools"],
    tasksDone: ["events"], turns: ["ai"],
    stalls: ["rdd"], repeatDeaths: ["rdd"],
    recalls: ["expmem"], memosLearned: ["expmem"],
    reviews: ["learner"],
    acRuns: ["acx"], acSteps: ["acx"], acStepFails: ["acx"],
    deathsPer100Actions: ["instrumentation", "tools"],
    stepFailRate: ["acx"],
    actionsPerTurn: ["tools", "ai"]
  };
  var SOURCE_LABEL = {
    instrumentation: "instrumentation.jsonl（死因/空转）", tools: "tools.jsonl（工具调用）",
    events: "events.jsonl（任务结果）", ai: "ai.jsonl（回合）", acx: "acx.jsonl（AC）",
    expmem: "expmem.jsonl（经验）", learner: "learner.jsonl（复盘）", rdd: "rdd.jsonl（目标）"
  };
  /** 这个指标此刻能不能算。返回 null = 能；否则返回「为什么不能」的短句。 */
  function metricBlockReason(d, metricId) {
    var deps = METRIC_SOURCE[metricId];
    if (!deps) { return "指标来源未知"; }
    var cov = d.coverage || {};
    var byKey = {};
    for (var i = 0; i < (cov.sources || []).length; i++) byKey[cov.sources[i].key] = cov.sources[i];
    for (var j = 0; j < deps.length; j++) {
      var s = byKey[deps[j]];
      if (!s) { return "没找到数据源 " + deps[j]; }
      if (!s.readable) { return "读不到 " + (SOURCE_LABEL[deps[j]] || deps[j]); }
    }
    return null;
  }
  function metricMeta(d, metricId) {
    var list = d.metrics || [];
    for (var i = 0; i < list.length; i++) if (list[i].id === metricId) return list[i];
    return null;
  }

  /* ── 同伴显示名 ─────────────────────────────────────── */
  var NAME_BY_ID = {};
  function companionLabel(d, cid) {
    var list = d.companions || [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].id !== cid) { continue; }
      // ★ nameHint 是**从死因文本猜的**，引擎没存名字 —— 必须标出来
      return list[i].nameHint
        ? { main: list[i].nameHint, tip: "名字是从死因文本里猜的（引擎没有存注册名）", id: shortId(cid) }
        : { main: shortId(cid), tip: "引擎里没有这个名字字段，只认 uuid 前 8 位", id: shortId(cid) };
    }
    return { main: shortId(cid), tip: "", id: shortId(cid) };
  }

  /* ── 新鲜度 / 接入 ──────────────────────────────────── */
  function freshnessClass(f) {
    if (!f || f.verdict === "unreadable") { return "gone"; }
    if (f.verdict === "fresh") { return "ok"; }
    if (f.verdict === "old_but_consistent") { return "old"; }
    return "bad";
  }
  function renderFreshness(d) {
    var f = d.freshness;
    var cls = freshnessClass(f);
    var head = {
      ok: "这份数据是新的",
      old: "注意：游戏和这份数据一起停的 —— 下面是过去的情况，不是当前",
      bad: "⚠ 这一页的数字不是当前这一局的",
      gone: "⚠ 有数据源读不到 —— 页面上标了「未接入」的格子真的没有数据"
    }[cls];
    var out = '<div class="acx-fresh acx-fresh-' + cls + '"><b>' + esc(head) + "</b>";
    var cov = d.coverage || {};
    out += "<div>覆盖时段 <code>" + esc(hhmm(cov.firstEventAt)) + " ~ " + esc(hhmm(cov.lastEventAt))
      + "</code>（UTC）　共 <b>" + esc((d.companions || []).length) + "</b> 只 AI · <b>"
      + esc((d.rows || []).length) + "</b> 行（AI × 目标）</div>";
    if (f) {
      out += "<div>参照文件最后写入 <code>" + esc(f.this_file) + "</code>（"
        + esc(hhmm(f.this_file_write)) + " UTC，距今 <b>" + esc(span(f.seconds_since_this_file_write))
        + "</b>）　·　游戏最后活动 <code>" + esc(hhmm(f.monitor_newest_write)) + "</code>（"
        + esc(f.monitor_newest_file || "?") + "，距今 " + esc(span(f.seconds_since_monitor_newest_write)) + "）</div>"
        + '<div class="acx-fresh-verdict">判读：' + esc(f.verdict_zh) + "</div>";
    }
    return out + "</div>";
  }

  /** 逐来源接入表 —— 纪律①的落点：读不到的必须点名，而不是让格子显示 0。 */
  function renderSources(d) {
    var cov = d.coverage || {};
    var list = cov.sources || [];
    if (!list.length) { return ""; }
    var rows = list.map(function (s) {
      var cls, mark;
      if (!s.readable) { cls = "gone"; mark = "未接入"; }
      else if (s.verdict === "fresh") { cls = "ok"; mark = "新的"; }
      else if (s.verdict === "old_but_consistent") { cls = "old"; mark = "旧的（自洽）"; }
      else { cls = "bad"; mark = "没跟上"; }
      var extra = "";
      if (!s.readable) extra = "文件读不到";
      else if (s.truncated) extra = "已截断：读到 " + grp(s.eventsRead) + " 条（更早的不在本次窗口里）";
      else extra = "读到 " + grp(s.eventsRead) + " 条";
      if (s.filesSkipped && s.filesSkipped.length) extra += " · 另有 " + s.filesSkipped.length + " 个轮转文件没读";
      return "<tr><td>" + esc(s.label) + "<br><small>" + esc(s.file) + "</small></td>"
        + '<td><span class="exp-pill ' + cls + '">' + esc(mark) + "</span></td>"
        + "<td>" + esc(extra) + "</td>"
        + "<td>" + esc(span(s.secondsSinceWrite)) + "</td></tr>";
    }).join("");
    var notConnected = list.filter(function (s) { return !s.readable; });
    return "<h3>数据接入情况</h3>"
      + '<div class="table-scroll"><table class="table exp-table">'
      + "<tr><th>数据源</th><th>状态</th><th>本次读到</th><th>距今写入</th></tr>"
      + rows + "</table></div>"
      + (notConnected.length
        ? '<div class="acx-caveat"><b>⚠ 这几个没接上，页面上相关格子显示「未接入」</b><div>'
          + notConnected.map(function (s) { return esc(s.label + "（" + s.file + "）"); }).join("　·　")
          + "　—— 那不是「0 次」，是观测面没数据。" + esc(cov.note_zh || "") + "</div></div>"
        : '<div class="acx-sub">' + esc(cov.note_zh || "") + "</div>");
  }

  /* ── 顶部汇总卡 ─────────────────────────────────────── */
  function renderSummary(d) {
    var rows = d.rows || [];
    function sum(id) {
      var t = 0;
      for (var i = 0; i < rows.length; i++) t += rows[i].counts[id] || 0;
      return t;
    }
    var items = [
      { label: "死亡", metric: "deaths", value: sum("deaths"), cls: "bad" },
      { label: "行动", metric: "actions", value: sum("actions"), cls: "" },
      { label: "AC 调用", metric: "acRuns", value: sum("acRuns"), cls: "", note: "推断归属" },
      { label: "AC 步骤失败", metric: "acStepFails", value: sum("acStepFails"), cls: "warn", note: "推断归属" },
      { label: "AI 回合", metric: "turns", value: sum("turns"), cls: "" },
      { label: "任务完成", metric: "tasksDone", value: sum("tasksDone"), cls: "ok" },
      { label: "经验召回", metric: "recalls", value: sum("recalls"), cls: "ok" },
      { label: "学习者复盘", metric: "reviews", value: sum("reviews"), cls: "ok" }
    ];
    var cards = items.map(function (it) {
      var blocked = metricBlockReason(d, it.metric);
      var body = blocked
        ? '<div class="exp-stat-v exp-na">未接入</div><div class="exp-stat-sub">' + esc(blocked) + "</div>"
        : '<div class="exp-stat-v">' + grp(it.value) + '</div><div class="exp-stat-sub">' + esc(it.label)
          + (it.note ? " · " + esc(it.note) : "") + "</div>";
      return '<div class="exp-stat' + (blocked ? " na" : (it.cls ? " " + it.cls : "")) + '">' + body + "</div>";
    }).join("");
    // ★ AC 那两张卡只显示「能归属到某只 AI 的」那部分，所以必须紧跟一句总量对照，
    //   否则「2」会被读成「这一局只调了 2 次 AC」（实测日志里是 128 次）。
    var acn = d.ac || {};
    var acNote = (acn.readable !== false)
      ? '<div class="exp-note">AC 那一列只算<b>能归属到某只 AI</b> 的记录。日志里实际有 <b>'
        + grp(acn.runs) + "</b> 次运行 / <b>" + grp(acn.steps) + "</b> 个步骤 / <b>" + grp(acn.stepFails)
        + "</b> 个失败步骤；归属不了的：无法判定 <b>" + grp(acn.ambiguousEvents) + "</b> 条、未归属 <b>"
        + grp(acn.unattributedEvents) + "</b> 条 —— 那些<b>没有</b>进任何一行。"
        + "原因见「本页口径说明」。</div>"
      : "";
    var gc = d.goalCoverage || {};
    var head = '<h3>总计</h3><div class="exp-stats">' + cards + "</div>";
    if (gc.companions_total) {
      head += '<div class="exp-note">'
        + "<b>「目标」这件事只接上了 " + esc(gc.companions_with_goals) + " / " + esc(gc.companions_total)
        + " 只 AI。</b>" + esc(gc.note_zh || "")
        + (gc.events_in_unknown_goal
          ? "　本次有 <b>" + grp(gc.events_in_unknown_goal) + " 条</b>事件归在「目标未接入」行里"
            + (gc.unknown_percent != null ? "（占 " + gc.unknown_percent + "%）" : "") + "。"
          : "")
        + "</div>";
    }
    return head + acNote;
  }

  /* ── 主交叉表：AI × 目标 ─────────────────────────────── */
  var COL_LABEL = {};
  function buildColLabel(d) {
    COL_LABEL = {};
    var list = d.metrics || [];
    for (var i = 0; i < list.length; i++) COL_LABEL[list[i].id] = list[i];
  }
  function valueCell(d, row, metricId) {
    var blocked = metricBlockReason(d, metricId);
    if (blocked) { return '<td class="exp-na">未接入</td>'; }
    var meta = COL_LABEL[metricId] || {};
    var v = meta.source === "derived" ? row.derived[metricId] : row.counts[metricId];
    var v2 = (v == null || !isFinite(v)) ? "?" : String(v);
    var cls = "exp-n-" + (meta.kind || "action");
    if (v == null) { return '<td class="exp-na ' + cls + '">?</td>'; }
    var mark = "";
    if (meta.source === "timeline") mark = '<sup title="归属是按时间线推断的，不是事件自带">推断</sup>';
    return '<td class="' + cls + '">' + esc(v2) + mark + "</td>";
  }
  function renderMatrix(d) {
    var rows = (d.rows || []).slice();
    var key = state.sortKey, dir = state.sortDir;
    rows.sort(function (a, b) {
      var x, y;
      if (key === "companion") { x = a.companionId; y = b.companionId; }
      else if (key === "goal") { x = a.goalLabel; y = b.goalLabel; }
      else if (key === "lastAt") { x = a.lastAt || ""; y = b.lastAt || ""; }
      else if (COL_LABEL[key] && COL_LABEL[key].source === "derived") { x = a.derived[key]; y = b.derived[key]; }
      else if (COL_LABEL[key]) { x = a.counts[key]; y = b.counts[key]; }
      else { x = a.lastAt || ""; y = b.lastAt || ""; }
      // null/空 一律排在最后：它不是最小值，是「不知道」
      if ((x == null || x === "") && (y == null || y === "")) { return 0; }
      if (x == null || x === "") { return 1; }
      if (y == null || y === "") { return -1; }
      // ★ 时间戳是 ISO 字符串（后端给的就是 ISO），字典序 == 时间序，
      //   不要拿它做减法 —— 那是 NaN，会让这一列的排序整体失效。
      if (x === y) { return 0; }
      return (x < y ? -1 : 1) * dir;
    });

    var chips = (d.metrics || []).filter(function (m) { return m.source !== "derived" || true; }).map(function (m) {
      var on = state.metricCols.indexOf(m.id) >= 0;
      var blocked = metricBlockReason(d, m.id);
      return '<button data-exp-col="' + esc(m.id) + '" class="exp-chip' + (on ? " on" : "") + (blocked ? " na" : "")
        + '" title="' + esc((m.hint || "") + (blocked ? "　⚠ " + blocked : "")) + '">'
        + esc(m.label) + (blocked ? '<sup>未接入</sup>' : "") + "</button>";
    }).join("");

    var cols = state.metricCols.filter(function (id) { return !!COL_LABEL[id]; });
    var head = "<tr><th data-exp-sort=\"companion\">AI</th><th data-exp-sort=\"goal\">目标</th>"
      + "<th data-exp-sort=\"lastAt\">时段（UTC）</th>"
      + cols.map(function (id) {
        var m = COL_LABEL[id];
        return '<th data-exp-sort="' + esc(id) + '">' + esc(m.label) + '<br><small>' + esc(m.unit || "") + "</small></th>";
      }).join("") + "<th>归属</th></tr>";

    var body = rows.map(function (r) {
      var c = companionLabel(d, r.companionId);
      var unknown = r.goalSource === "unknown";
      var sel = state.selected === r.key;
      return '<tr data-exp-row="' + esc(r.key) + '"' + (sel ? ' class="sel"' : "") + ">"
        + '<td title="' + esc(c.tip) + '"><b>' + esc(c.main) + '</b><br><small>' + esc(c.id) + "</small></td>"
        + "<td>" + (unknown
          ? '<span class="exp-pill warn">目标未接入</span> ' + esc(r.goalLabel)
          : esc(r.goalLabel))
        + (r.goalSource && r.goalSource !== "unknown" ? '<br><small>来源 ' + esc(r.goalSource) + "</small>" : "")
        + "</td>"
        + "<td>" + esc(mdhm(r.firstAt)) + "<br><small>~ " + esc(mdhm(r.lastAt)) + "</small></td>"
        + cols.map(function (id) { return valueCell(d, r, id); }).join("")
        + "<td>" + attributionCell(r) + "</td></tr>";
    }).join("");

    return "<h3>AI × 目标 交叉表<span class=\"exp-h3note\">点任意一行看明细</span></h3>"
      + '<div class="exp-chips">' + chips + "</div>"
      + '<div class="table-scroll"><table class="table exp-table exp-matrix">'
      + head + body + "</table></div>"
      + (rows.length ? "" : '<div class="acx-empty">没有任何（AI × 目标）的组合 —— 看上面的接入表与下面的空原因。</div>');
  }
  function attributionCell(r) {
    var a = r.attribution || {};
    var bits = [];
    if (a.attributed_events) bits.push("事件自带 " + grp(a.attributed_events));
    if (a.inferred_single) bits.push("推断 " + grp(a.inferred_single));
    var cls = a.inferred_single && !a.attributed_events ? "warn" : "ok";
    return '<span class="exp-pill ' + cls + '">' + esc(bits.join(" · ") || "无") + "</span>";
  }

  /* ── 选中行明细 ─────────────────────────────────────── */
  function renderRowDetail(d) {
    if (!state.selected) {
      return '<div class="acx-empty">上面点任意一行，这里展开那一行的原始构成（死因、工具、任务、AC 脚本、归属口径）。</div>';
    }
    var r = null;
    for (var i = 0; i < (d.rows || []).length; i++) if (d.rows[i].key === state.selected) r = d.rows[i];
    if (!r) { return '<div class="acx-empty">选中的那一行已经不在这一批数据里了（窗口滚过去了）。</div>'; }
    var c = companionLabel(d, r.companionId);
    var out = "<h3>明细 · " + esc(c.main) + " · " + esc(r.goalLabel) + "</h3>";
    out += '<div class="exp-detail"><div class="exp-detail-head">'
      + esc(hhmm(r.firstAt)) + " ~ " + esc(hhmm(r.lastAt)) + "（UTC）　·　"
      + '<span class="exp-pill ' + (r.goalSource === "unknown" ? "warn" : "ok") + '">目标来源 ' + esc(r.goalSource) + "</span>　·　"
      + esc(attributionCell(r))
      + "</div>";
    var parts = [];
    var causes = Object.entries(r.causes || {}).sort(function (a, b) { return b[1] - a[1]; });
    if (causes.length) {
      parts.push("<div><b>死因分布</b><div class=\"exp-kv\">"
        + causes.map(function (x) { return "<span>" + esc(x[0]) + " <b>" + grp(x[1]) + "</b></span>"; }).join("")
        + "</div></div>");
    }
    var tools = Object.entries(r.tools || {}).sort(function (a, b) { return b[1] - a[1]; }).slice(0, 18);
    if (tools.length) {
      parts.push("<div><b>工具调用 Top " + tools.length + "</b><div class=\"exp-kv\">"
        + tools.map(function (x) { return "<span>" + esc(x[0]) + " <b>" + grp(x[1]) + "</b></span>"; }).join("")
        + "</div></div>");
    }
    var tasks = Object.entries(r.tasks || {}).sort(function (a, b) { return b[1] - a[1]; });
    if (tasks.length) {
      parts.push("<div><b>完成的任务类型</b><div class=\"exp-kv\">"
        + tasks.map(function (x) { return "<span>" + esc(x[0]) + " <b>" + grp(x[1]) + "</b></span>"; }).join("")
        + "</div></div>");
    }
    var acs = Object.entries(r.acNames || {}).sort(function (a, b) { return b[1] - a[1]; });
    if (acs.length) {
      parts.push("<div><b>调过的 AC 脚本（推断归属）</b><div class=\"exp-kv\">"
        + acs.map(function (x) { return "<span>" + esc(x[0]) + " <b>" + grp(x[1]) + "</b></span>"; }).join("")
        + "</div></div>");
    }
    var mat = Object.entries(r.learnedMaturity || {}).sort(function (a, b) { return b[1] - a[1]; });
    if (mat.length) {
      parts.push("<div><b>新增经验的成熟度（推断归属）</b><div class=\"exp-kv\">"
        + mat.map(function (x) { return "<span>" + esc(x[0]) + " <b>" + grp(x[1]) + "</b></span>"; }).join("")
        + "</div></div>");
    }
    if (r.knowledgeChars) parts.push("<div><b>注入的规划知识</b>　" + grp(r.knowledgeChars) + " 字符</div>");
    if (r.recoveryFailed) parts.push("<div><b>恢复失败</b>　" + grp(r.recoveryFailed) + " 次（不计入上面任何指标，单独报）</div>");
    var deaths = r.deaths || [];
    if (deaths.length) {
      parts.push("<div><b>死亡明细（最近 " + deaths.length + " 次，时间倒序）</b>"
        + '<div class="table-scroll"><table class="table exp-table">'
        + "<tr><th>时刻</th><th>死因</th><th>类型</th><th>攻击者</th><th>事件自带目标</th></tr>"
        + deaths.map(function (x) {
          // ★ data.task 实测是空串 —— 空的时候必须写「空」，不能留白让人以为没这字段
          return "<tr><td>" + esc(hhmm(x.at)) + "</td><td>" + esc(x.cause) + "</td><td>"
            + esc(x.kind || "?") + "</td><td>" + esc(x.attacker || "?") + "</td>"
            + "<td>" + (x.task ? esc(x.task) : '<span class="exp-pill warn">空</span>') + "</td></tr>";
        }).join("") + "</table></div></div>");
    }
    if (!parts.length) parts.push("<div>这一行没有任何可展开的明细。</div>");
    return out + parts.map(function (p) { return '<div class="exp-detail-block">' + p + "</div>"; }).join("") + "</div>";
  }

  /* ── 曲线 / 柱状图（纯 SVG，不引第三方）────────────── */
  /**
   * 派生比率在服务端**没有**逐桶序列（序列只存可累加的原始指标），
   * 所以这里按桶现算：把两个分量先加起来，再相除。
   * ★ 分母为 0 的桶给 null（图上断开），不是 0 —— 与页面其它地方同一纪律。
   */
  var DERIVED_PARTS = {
    deathsPer100Actions: { num: "deaths", den: "actions", mul: 100 },
    stepFailRate: { num: "acStepFails", den: "acSteps", mul: 100 },
    actionsPerTurn: { num: "actions", den: "turns", mul: 1 }
  };

  /**
   * 从 rows + series 算出「序列」。
   * ★ 分组方式三种，这就是「自由调换」的实质：
   *   pair      每条序列 = 一个（AI × 目标）
   *   companion 每条序列 = 一只 AI（把它的所有目标加起来）
   *   goal      每条序列 = 一个目标（把所有在跑它的 AI 加起来）
   */
  function buildSeries(d, panelCfg) {
    var s = d.series || {};
    var buckets = s.buckets || [];
    var byRow = {};
    for (var i = 0; i < (s.rows || []).length; i++) byRow[s.rows[i].key] = s.rows[i];
    var metric = panelCfg.metric;
    var parts = DERIVED_PARTS[metric] || null;

    // 序列分组：pair = 一行一个序列；companion / goal = 把同组的行并起来
    function groupKeyOf(r) {
      return panelCfg.group === "companion" ? r.companionId
        : panelCfg.group === "goal" ? r.goalKey : r.key;
    }
    // 收集出现的分组键（不按数值排，排序要用分量算，见下面）
    var keys = [];
    var seen = {};
    for (var j = 0; j < (d.rows || []).length; j++) {
      var gk0 = groupKeyOf(d.rows[j]);
      if (seen[gk0]) continue;
      seen[gk0] = 1;
      keys.push(gk0);
    }

    var seriesList = keys.map(function (gk, idx) {
      // values / numSum / denSum 三个数组按下标对应 buckets
      var values = new Array(buckets.length);
      var numSum = new Array(buckets.length);
      var denSum = new Array(buckets.length);
      for (var z = 0; z < buckets.length; z++) { values[z] = null; numSum[z] = 0; denSum[z] = 0; }

      for (var m = 0; m < (d.rows || []).length; m++) {
        var rr = d.rows[m];
        if (groupKeyOf(rr) !== gk) continue;
        var sr = byRow[rr.key];
        if (!sr) continue;
        if (!parts) {
          var cell = sr.cells[metric] || {};
          for (var bi in cell) {
            var i2 = Number(bi);
            if (i2 >= 0 && i2 < buckets.length) values[i2] = (values[i2] || 0) + cell[bi];
          }
        } else {
          // ★ 比率不能把每行的比率直接相加 —— 必须先加分量、最后除一次。
          var cn = sr.cells[parts.num] || {}, cd = sr.cells[parts.den] || {};
          for (var kn in cn) {
            var q = Number(kn);
            if (q >= 0 && q < buckets.length) numSum[q] += cn[kn];
          }
          for (var kd in cd) {
            var q2 = Number(kd);
            if (q2 >= 0 && q2 < buckets.length) denSum[q2] += cd[kd];
          }
        }
      }
      if (parts) {
        for (var y = 0; y < buckets.length; y++) {
          // ★ 分母为 0 ⇒ 这个桶留 null（图上断开），不是 0
          values[y] = denSum[y] > 0 ? Math.round((numSum[y] / denSum[y]) * parts.mul * 10) / 10 : null;
        }
      }
      // 图例上的数：
      //   可累加指标 = 各桶之和；派生比率 = 全窗口的分量之比（不是各桶比率之和）
      var tot = null;
      if (parts) {
        var N = 0, D = 0;
        for (var t = 0; t < buckets.length; t++) { N += numSum[t]; D += denSum[t]; }
        tot = D > 0 ? Math.round((N / D) * parts.mul * 10) / 10 : null;
      } else {
        tot = 0;
        for (var t2 = 0; t2 < buckets.length; t2++) if (values[t2] != null) tot += values[t2];
      }
      return {
        key: gk, label: seriesLabel(d, gk, panelCfg.group), cls: "exp-s" + (idx % 6),
        values: values, total: tot, isRate: !!parts
      };
    });

    // 只画「真的有数」的前 N 条，并把没画的条数说出来（不静默截断）
    var withData = seriesList.filter(function (x) {
      for (var i3 = 0; i3 < x.values.length; i3++) if (x.values[i3] != null) return true;
      return false;
    });
    withData.sort(function (a, b) { return (b.total == null ? -1 : b.total) - (a.total == null ? -1 : a.total); });
    var cap = state.seriesCap;
    var shown = withData.slice(0, cap);
    // 排序后重新分配配色，避免颜色跟着位置乱跳
    for (var k = 0; k < shown.length; k++) shown[k].cls = "exp-s" + (k % 6);
    return {
      seriesList: shown,
      hiddenCount: Math.max(0, withData.length - shown.length),
      buckets: buckets,
      bucketLabel: s.bucketLabel || "桶"
    };
  }
  function seriesLabel(d, gk, group) {
    if (group === "companion") {
      var c = companionLabel(d, gk);
      return c.main;
    }
    if (group === "goal") {
      for (var i = 0; i < (d.goals || []).length; i++) if (d.goals[i].key === gk) return d.goals[i].label;
      return gk === (d.unknownGoal || {}).key ? (d.unknownGoal || {}).label : gk;
    }
    // pair：AI + 目标
    for (var j = 0; j < (d.rows || []).length; j++) {
      if (d.rows[j].key !== gk) continue;
      var c2 = companionLabel(d, d.rows[j].companionId);
      return c2.main + " · " + d.rows[j].goalLabel;
    }
    return gk;
  }

  // ★ 不要 preserveAspectRatio="none"：那会把 SVG 横向拉伸，
  //   里面的刻度数字跟着被拉变形（实测 Y 轴刻度糊成一团，读不出是 5 还是 50）。
  //   默认的 xMidYMid meet + CSS height:auto ⇒ 等比缩放，字始终清楚。
  var CH = { w: 1000, h: 190, pl: 92, pr: 16, pt: 16, pb: 34 };
  function chartSvg(seriesList, buckets, type) {
    var iw = CH.w - CH.pl - CH.pr, ih = CH.h - CH.pt - CH.pb;
    var max = 0;
    var any = false;
    for (var i = 0; i < seriesList.length; i++) {
      for (var j = 0; j < seriesList[i].values.length; j++) {
        var v = seriesList[i].values[j];
        if (v == null) continue;
        any = true;
        if (type === "bar" && j > 0) {
          var sum = 0;
          for (var k = 0; k < seriesList.length; k++) sum += (seriesList[k].values[j] || 0);
          if (sum > max) max = sum;
        } else if (v > max) { max = v; }
      }
    }
    if (!any || max <= 0) {
      return '<div class="exp-chart-empty">这一段时间没有任何可画的记录。</div>';
    }
    var n = buckets.length;
    var step = n > 1 ? iw / (n - 1) : iw;
    var y = function (v) { return CH.pt + ih - (v / max) * ih; };
    var out = '<svg class="exp-chart" viewBox="0 0 ' + CH.w + " " + CH.h + '" role="img">';
    // 横向网格 + Y 轴刻度（3 档）。刻度值取「能读出来的最短写法」，太长会挤到轴外。
    for (var g = 0; g <= 2; g++) {
      var gv = Math.round(max * g / 2);
      var gy = y(gv);
      out += '<line class="exp-grid" x1="' + CH.pl + '" y1="' + gy + '" x2="' + (CH.w - CH.pr) + '" y2="' + gy + '"/>';
      out += '<text class="exp-tick" x="' + (CH.pl - 8) + '" y="' + (gy + 4) + '" text-anchor="end">' + esc(grp(gv)) + "</text>";
    }
    if (type === "bar") {
      var bw = n > 1 ? Math.max(1.5, Math.min(14, step * 0.7)) : 26;
      for (var b = 0; b < n; b++) {
        var acc = 0;
        var bx = CH.pl + (n > 1 ? b * step : iw / 2 - bw / 2);
        for (var s = 0; s < seriesList.length; s++) {
          var bv = seriesList[s].values[b];
          if (bv == null || bv <= 0) continue;
          var y0 = y(acc), y1 = y(acc + bv);
          acc += bv;
          out += '<rect class="' + seriesList[s].cls + '" x="' + (bx - bw / 2).toFixed(1) + '" y="' + y1.toFixed(1)
            + '" width="' + bw.toFixed(1) + '" height="' + Math.max(0.5, y0 - y1).toFixed(1) + '">'
            + "<title>" + esc(seriesList[s].label) + " · " + esc(hhmm(buckets[b].iso)) + " = " + esc(bv) + "</title></rect>";
        }
      }
    } else {
      for (var t = 0; t < seriesList.length; t++) {
        var d = "", started = false;
        for (var p = 0; p < n; p++) {
          var pv = seriesList[t].values[p];
          if (pv == null) { started = false; continue; }   // ★ 断线：不给值就断开，不补 0
          var px = CH.pl + p * step, py = y(pv);
          d += (started ? " L" : " M") + px.toFixed(1) + " " + py.toFixed(1);
          started = true;
        }
        if (d) out += '<path class="' + seriesList[t].cls + '" d="' + d.slice(2) + '"/>';
      }
    }
    // X 轴：只标首尾（中间标不清）。y 用 baseline 对齐，别贴在边上被裁掉。
    if (n) {
      out += '<text class="exp-tick" x="' + CH.pl + '" y="' + (CH.h - 8) + '">' + esc(mdhm(buckets[0].iso)) + "</text>";
      if (n > 1) {
        out += '<text class="exp-tick" x="' + (CH.w - CH.pr) + '" y="' + (CH.h - 8) + '" text-anchor="end">'
          + esc(mdhm(buckets[n - 1].iso)) + "</text>";
      }
    }
    return out + "</svg>";
  }

  function legend(seriesList, hiddenCount) {
    var items = seriesList.map(function (s) {
      return '<span class="exp-legend-item"><i class="' + s.cls + '"></i>' + esc(s.label)
        + " <b>" + esc(num(s.total)) + "</b></span>";
    }).join("");
    if (hiddenCount > 0) {
      items += '<span class="exp-legend-item muted">另有 ' + hiddenCount + " 组没画（只画前 "
        + esc(state.seriesCap) + " 组）</span>";
    }
    return '<div class="exp-legend">' + items + "</div>";
  }

  /* ── 面板台（可拖拽重排）───────────────────────────── */
  /**
   * ★ 面板存的指标必须**对当前这份数据校验过**才能用。
   *   两个来源会给出不存在的指标：① localStorage 里是旧值（服务端改过指标字典）
   *   ② 手改坏的值。第一版只在 loadLayout 里检查 group/type（那三个是本文件自己的
   *   固定集合），metric 却放行任意字符串 ⇒ 面板会一直盯着一个不存在的指标，
   *   `<option>` 一个都不选中，看起来就是「这个面板没在画」。
   */
  function saneCfg(d, pid, p) {
    var cfg = state.panels[pid];
    if (!metricMeta(d, cfg.metric)) cfg.metric = p.metric;
    return cfg;
  }
  function panelById(id) {
    for (var i = 0; i < PANELS.length; i++) if (PANELS[i].id === id) return PANELS[i];
    return null;
  }
  /** 某个「推断类」指标一共归到了多少条（用来跟日志总数对照）。 */
  function attributedCount(d, metricId) {
    var n = 0;
    for (var i = 0; i < (d.rows || []).length; i++) {
      n += (d.rows[i].counts[metricId] || 0);
    }
    return n;
  }
  function renderPanelControls(d, pid) {
    var cfg = state.panels[pid];
    var opts = (d.metrics || []).map(function (m) {
      var blocked = metricBlockReason(d, m.id);
      return '<option value="' + esc(m.id) + '"' + (m.id === cfg.metric ? " selected" : "")
        + (blocked ? " disabled" : "") + ">" + esc(m.label) + (blocked ? "（未接入）" : "") + "</option>";
    }).join("");
    var groups = '<option value="pair"' + (cfg.group === "pair" ? " selected" : "") + ">按 AI × 目标</option>"
      + '<option value="companion"' + (cfg.group === "companion" ? " selected" : "") + ">按 AI 汇总</option>"
      + '<option value="goal"' + (cfg.group === "goal" ? " selected" : "") + ">按目标汇总</option>";
    var types = '<option value="line"' + (cfg.type === "line" ? " selected" : "") + ">折线</option>"
      + '<option value="bar"' + (cfg.type === "bar" ? " selected" : "") + ">堆叠柱</option>";
    return "<select data-exp-pmetric='" + esc(pid) + "' title='这个面板看哪个指标'>" + opts + "</select>"
      + "<select data-exp-pgroup='" + esc(pid) + "' title='序列按什么分组'>" + groups + "</select>"
      + "<select data-exp-ptype='" + esc(pid) + "' title='线还是柱'>" + types + "</select>";
  }
  function renderDock(d) {
    var shownIds = state.order.filter(function (id) { return !state.hidden[id]; });
    var out = "<h3>面板<span class=\"exp-h3note\">拖面板标题可换位置 · 也可用 ↑↓ · 排法会记住</span></h3>";
    var hiddenList = state.order.filter(function (id) { return state.hidden[id]; });
    var toggles = state.order.map(function (id) {
      var p = panelById(id);
      return '<button data-exp-toggle="' + esc(id) + '" class="exp-chip' + (state.hidden[id] ? "" : " on") + '">'
        + esc(p ? p.title : id) + "</button>";
    }).join("");
    out += '<div class="exp-chips"><span class="exp-chips-label">显示：</span>' + toggles
      + '<button data-exp-reset="1" class="exp-chip reset">全部复位</button></div>';
    if (hiddenList.length) {
      out += '<div class="exp-sub">已关掉 ' + hiddenList.length + " 个面板（上面点名字可以再打开）。</div>";
    }
    if (!shownIds.length) {
      return out + '<div class="acx-empty">所有面板都被关掉了 —— 上面点名字就能重新打开。</div>';
    }
    out += '<div class="exp-dock">';
    for (var i = 0; i < shownIds.length; i++) {
      var pid = shownIds[i];
      var p = panelById(pid);
      if (!p) continue;
      var cfg = saneCfg(d, pid, p);
      var collapsed = !!state.collapsed[pid];
      var built = buildSeries(d, cfg);
      var meta = COL_LABEL[cfg.metric] || {};
      var blocked = metricBlockReason(d, cfg.metric);
      out += '<section class="exp-panel' + (collapsed ? " collapsed" : "") + '" data-exp-panel="' + esc(pid) + '">'
        + '<header class="exp-panel-head" draggable="true">'
        + '<span class="exp-grip" title="拖动换位置">⠿</span>'
        + "<b>" + esc(p.title) + "</b>"
        + '<span class="exp-panel-metric">' + esc(meta.label || cfg.metric)
        + (meta.source === "timeline" ? '<sup title="归属是推断的">推断</sup>' : "") + "</span>"
        + '<span class="exp-panel-ctl">'
        + '<button data-exp-move="-1" data-exp-pid="' + esc(pid) + '" title="上移">↑</button>'
        + '<button data-exp-move="1" data-exp-pid="' + esc(pid) + '" title="下移">↓</button>'
        + '<button data-exp-fold="' + esc(pid) + '" title="折叠 / 展开">' + (collapsed ? "▸" : "▾") + "</button>"
        + "</span></header>";
      if (!collapsed) {
        out += '<div class="exp-panel-body">'
          + '<div class="exp-panel-selects">' + renderPanelControls(d, pid) + "</div>"
          + '<div class="exp-panel-hint">' + esc(p.hint || "") + "</div>";
if (blocked) {
          out += '<div class="exp-na-box">⚠ 这个指标现在是<b>未接入</b>：' + esc(blocked)
            + "。不是 0 次，是观测面没数据。</div>";
        } else {
          if (meta.source === "timeline") {
            // ★ 归属是推断的面板，必须把「推断不出来的那部分」也摆出来。
            //   只画可归属的几条、其余不吭声 ⇒ 读者会以为「这段时间只调了 2 次 AC」。
            var ac = d.ac || {};
            out += '<div class="exp-inferred">⚠ 这条曲线的归属是<b>按时间线推断</b>的 —— '
              + (meta.id === "memosLearned" ? "经验新增事件" : "AC 事件") + "本身不带同伴字段。"
              + '<br>日志里共 <b>' + grp(ac.runs) + "</b> 次 AC 运行 / <b>" + grp(ac.memosLearnedTotal || d.memosLearnedTotal)
              + "</b> 条经验新增，其中<b>能归到某只 AI</b> 的只有 <b>" + grp(attributedCount(d, meta.id))
              + "</b> 条；<b>无法判定</b> <b>" + grp(ac.ambiguousEvents) + "</b> 条、<b>未归属</b> <b>"
              + grp(ac.unattributedEvents) + "</b> 条（不计入任何一行）。</div>";
          }
          var body = chartSvg(built.seriesList, built.buckets, cfg.type);
          if (!built.seriesList.length && meta.source === "timeline") {
            out += '<div class="exp-na-box">能归属到任何 AI 的记录<b>为 0</b> —— 上面那几条才是原因'
              + "（不是「这段时间没有 AC 调用」）。</div>";
          } else {
            out += body
              + legend(built.seriesList, built.hiddenCount)
              + '<div class="exp-sub">横轴每格 ' + esc(built.bucketLabel)
              + "（UTC）。折线在某个桶没有值时是<b>断开</b>的，不是补 0。</div>";
          }
        }
        out += "</div>";
      }
      out += "</section>";
    }
    return out + "</div>";
  }

  /* ── 学习曲线 ───────────────────────────────────────── */
  function renderLearning(d) {
    var L = d.learning || {};
    var rows = L.rows || [];
    var head = "<h3>学习情况 · 同一目标下越打越稳吗<span class=\"exp-h3note\">"
      + esc("把行动次数切成 " + (L.slices || 4) + " 段，看死亡率是升还是降") + "</span></h3>";
    if (!rows.length) {
      return head + '<div class="acx-empty">还没有任何一个目标的行动次数多到能切段（至少要 8 次行动）。<br>'
        + esc(L.note_zh || "") + "</div>";
    }
    var body = rows.map(function (r) {
      var t = r.trend;
      var cls = t == null ? "unknown" : (t < 0 ? "better" : (t > 0 ? "worse" : "flat"));
      var txt = t == null ? "?（有一段没行动）" : (t < 0 ? "↓ " + Math.abs(t) + "（后段死得更少）"
        : (t > 0 ? "↑ " + t + "（后段死得更多）" : "持平"));
      var cells = r.slices.map(function (s) {
        return "<td>" + esc(num(s.actions)) + " / " + esc(num(s.deaths)) + " / "
          + '<b class="' + (s.deathsPer100Actions == null ? "exp-na" : "") + '">'
          + esc(num(s.deathsPer100Actions)) + "</b></td>";
      }).join("");
      return "<tr><td>" + esc(companionLabel(d, r.companionId).main) + "</td><td>" + esc(r.goalLabel) + "</td>"
        + cells
        + '<td><span class="exp-pill ' + cls + '">' + esc(txt) + "</span></td>"
        + "<td>" + esc(span((Date.parse(r.lastAt) - Date.parse(r.firstAt)) / 1000)) + "</td></tr>";
    }).join("");
    return head
      + '<div class="table-scroll"><table class="table exp-table">'
      + "<tr><th>AI</th><th>目标</th>"
      + r_slicesHead(L.slices || 4)
      + "<th>趋势</th><th>跨度</th></tr>" + body + "</table></div>"
      + '<div class="exp-sub">' + esc(L.note_zh || "") + "</div>";
  }
  function r_slicesHead(n) {
    var out = "";
    for (var i = 0; i < n; i++) out += '<th>第 ' + (i + 1) + " 段<br><small>行动/死亡/每百次</small></th>";
    return out;
  }

  function renderCaveats(d) {
    var list = d.caveats || [];
    var out = "<h3>本页口径说明</h3>";
    // ★ AC 归属三档必须**逐档报数**（这是本页最容易被误读的地方）
    var ac = d.ac || {};
    var s1 = 0, s2 = 0;
    for (var i = 0; i < (d.rows || []).length; i++) {
      var at = (d.rows[i].attribution || {});
      s1 += at.inferred_single || 0;
      s2 += at.inferred_leader || 0;
    }
    out += '<div class="exp-caveats"><div class="exp-caveat"><b>·</b> AC 与「经验新增」没有同伴字段，归属分三档：'
      + "强推断（那一刻只有一只的目标段开着）<b>" + grp(s1) + "</b>　"
      + "弱推断（多只同时开着，看谁刚活动过，领先超过 " + esc((ac.leadThresholdMs || 60000) / 1000)
      + " 秒）<b>" + grp(s2) + "</b>　"
      + "无法判定（不猜，不计入任何一行）<b>" + grp(ac.ambiguousEvents) + "</b>　"
      + "未归属（那一刻一只目标段都没开）<b>" + grp(ac.unattributedEvents) + "</b>"
      + '<div class="exp-sub">' + esc(ac.note_zh || "") + "</div></div></div>";
    out += '<div class="exp-caveats">';
    for (var j = 0; j < list.length; j++) {
      out += '<div class="exp-caveat"><b>·</b> ' + esc(list[j].note_zh) + "</div>";
    }
    return out + "</div>";
  }

  /* ── 主渲染 ─────────────────────────────────────────── */
  function render() {
    var host = document.getElementById("experiment-host");
    if (!host) { return; }
    if (ERROR) {
      host.innerHTML = '<div class="acx-err"><b>读不到实验统计</b><br>' + esc(ERROR)
        + "<br><br>⚠️ 「读不到」<b>不是</b>「没有数据」—— 前者是观测面坏了。</div>";
      return;
    }
    if (!DATA) {
      host.innerHTML = '<div class="acx-empty">正在读实验统计（要扫 9 个日志文件，约 0.4 秒）…</div>';
      if (!FETCHING) load();
      return;
    }
    if (DATA.ok === false) {
      host.innerHTML = '<div class="acx-err">' + esc(DATA.error || "未知错误") + "</div>";
      return;
    }
    buildColLabel(DATA);
    var reasons = DATA.emptyReasons || [];
    var empty = reasons.length
      ? '<div class="acx-caveat"><b>⚠ 这一页有指标拿不到</b><div>'
        + reasons.map(function (r) { return esc(r); }).join("<br>") + "</div></div>"
      : "";
    host.innerHTML = "<h2>实验统计 · 每个 AI 在每个目标下的表现</h2>"
      + '<p class="acx-sub">一行 = 一只 AI × 一个目标。<b>点行看明细、拖面板换排法</b>；'
      + "凡是没接上的格子一律写「未接入」，不写 0。</p>"
      + renderFreshness(DATA)
      + empty
      + renderSummary(DATA)
      + renderSources(DATA)
      + renderMatrix(DATA)
      + renderRowDetail(DATA)
      + renderDock(DATA)
      + renderLearning(DATA)
      + renderCaveats(DATA);
  }

  /* ── 交互（事件委托：内容每次重建，所以只能挂在 document 上）── */
  var DRAG = null;
  function onEvent(ev) {
    var t = ev.target;
    if (!t || !t.closest) { return; }

    var col = t.closest("[data-exp-col]");
    if (col) {
      var mid = col.getAttribute("data-exp-col");
      var at = state.metricCols.indexOf(mid);
      if (at >= 0) { if (state.metricCols.length <= 1) return; state.metricCols.splice(at, 1); }
      else { if (state.metricCols.length >= 10) return; state.metricCols.push(mid); }
      saveLayout(); render(); return;
    }
    var sortH = t.closest("[data-exp-sort]");
    if (sortH) {
      var sk = sortH.getAttribute("data-exp-sort");
      if (state.sortKey === sk) state.sortDir = -state.sortDir;
      else { state.sortKey = sk; state.sortDir = -1; }
      render(); return;
    }
    var rowEl = t.closest("[data-exp-row]");
    if (rowEl) {
      var rk = rowEl.getAttribute("data-exp-row");
      state.selected = state.selected === rk ? null : rk;
      render(); return;
    }
    var tog = t.closest("[data-exp-toggle]");
    if (tog) {
      var tid = tog.getAttribute("data-exp-toggle");
      state.hidden[tid] = !state.hidden[tid];
      saveLayout(); render(); return;
    }
    if (t.closest("[data-exp-reset]")) {
      state.order = PANELS.map(function (p) { return p.id; });
      state.hidden = {}; state.collapsed = {};
      for (var i = 0; i < PANELS.length; i++) {
        state.panels[PANELS[i].id] = { metric: PANELS[i].metric, group: PANELS[i].group, type: PANELS[i].type };
      }
      state.seriesCap = 6;
      saveLayout(); render(); return;
    }
    var fold = t.closest("[data-exp-fold]");
    if (fold) {
      var fid = fold.getAttribute("data-exp-fold");
      state.collapsed[fid] = !state.collapsed[fid];
      saveLayout(); render(); return;
    }
    var mv = t.closest("[data-exp-move]");
    if (mv) {
      var pid = mv.getAttribute("data-exp-pid");
      var step = Number(mv.getAttribute("data-exp-move")) || 0;
      movePanel(pid, step);
      saveLayout(); render(); return;
    }
    var sel = null;
    if (t.getAttribute && t.getAttribute("data-exp-pmetric")) sel = { pid: t.getAttribute("data-exp-pmetric"), prop: "metric" };
    else if (t.getAttribute && t.getAttribute("data-exp-pgroup")) sel = { pid: t.getAttribute("data-exp-pgroup"), prop: "group" };
    else if (t.getAttribute && t.getAttribute("data-exp-ptype")) sel = { pid: t.getAttribute("data-exp-ptype"), prop: "type" };
    if (sel && state.panels[sel.pid]) {
      state.panels[sel.pid][sel.prop] = t.value;
      saveLayout(); render(); return;
    }
  }
  function movePanel(pid, step) {
    var i = state.order.indexOf(pid);
    var j = i + step;
    if (i < 0 || j < 0 || j >= state.order.length) return;
    state.order.splice(j, 0, state.order.splice(i, 1)[0]);
  }
  function onDragStart(ev) {
    var h = ev.target && ev.target.closest ? ev.target.closest(".exp-panel-head") : null;
    if (!h) { return; }
    var panel = h.closest ? h.closest("[data-exp-panel]") : null;
    if (!panel) { return; }
    DRAG = panel.getAttribute("data-exp-panel");
    if (ev.dataTransfer && ev.dataTransfer.setData) {
      try { ev.dataTransfer.setData("text/plain", DRAG); } catch (e) { /* 沙箱里可能没有 */ }
    }
  }
  function onDragOver(ev) {
    var h = ev.target && ev.target.closest ? ev.target.closest(".exp-panel-head") : null;
    if (!h || !DRAG) { return; }
    if (ev.preventDefault) ev.preventDefault();
    var panel = h.closest("[data-exp-panel]");
    if (panel) panel.classList.add("dragover");
  }
  function onDrop(ev) {
    if (!DRAG) { return; }
    if (ev.preventDefault) ev.preventDefault();
    var h = ev.target && ev.target.closest ? ev.target.closest(".exp-panel-head") : null;
    if (h) {
      var panel = h.closest("[data-exp-panel]");
      var to = panel ? panel.getAttribute("data-exp-panel") : null;
      if (to && to !== DRAG) {
        var from = state.order.indexOf(DRAG);
        var at2 = state.order.indexOf(to);
        if (from >= 0 && at2 >= 0) state.order.splice(at2, 0, state.order.splice(from, 1)[0]);
        saveLayout();
      }
    }
    DRAG = null;
    render();
  }
  function onDragEnd() {
    DRAG = null;
    render();
  }

  function load() {
    if (FETCHING) return;
    FETCHING = true;
    return fetch("/api/experiment-stats", { cache: "no-store" })
      .then(function (r) {
        if (!r.ok) { throw new Error("HTTP " + r.status); }
        return r.json();
      })
      .then(function (j) { DATA = j; ERROR = ""; render(); })
      .catch(function (e) {
        ERROR = String(e && e.message ? e.message : e);
        render();
      })
      .then(function () { FETCHING = false; }, function () { FETCHING = false; });
  }

  function init() {
    loadLayout();
    if (typeof document !== "undefined" && document.addEventListener) {
      document.addEventListener("click", onEvent);
      document.addEventListener("change", onEvent);
      document.addEventListener("dragstart", onDragStart);
      document.addEventListener("dragover", onDragOver);
      document.addEventListener("drop", onDrop);
      document.addEventListener("dragend", onDragEnd);
    }
    load();
    if (!TIMER) TIMER = setInterval(load, POLL_MS);
  }

  return { render: render, load: load, init: init, state: state, PANELS: PANELS };
})();

if (typeof window !== "undefined") { window.ExperimentView = ExperimentView; }