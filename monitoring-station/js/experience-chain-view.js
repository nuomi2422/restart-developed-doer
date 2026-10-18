/* ============================================================
   experience-chain-view.js — 经验链数据流（B18）

   这个页回答一个问题：**数据流对不对**。不是「有多少条」。

   三条设计前提（写在最前面，因为它们决定了下面每行代码的形状）：

   1. 数据不是从 Store 来的。本页自己 fetch('/api/experience-chain')，
      与同目录 chain-view.js 对 /api/chain 的做法一致。不往共享 Store 塞东西：
      塞进去会污染别处状态，而 applyPatch 有版本连续性检查，本页没有版本可管。

   2. **断边要显式画出来，不许用「0 条」暗示。**
      「0」有三种含义，混起来会读成错误的东西：
        · 真的没有
        · 采样窗口没覆盖
        · **上游断了导致读不到** ← 最危险，显示 0 会让人以为「功能没做」
      所以边有三种态：measured（量到了）/ empty（量到了，是 0）/ blind（看不见，不许假装）。

   3. 盲区写在图上，不藏。看不见的东西画成虚线 + 一句话说明「真源在哪」。

   用户要的是「能点开看内容，确认内容对不对」—— 所以每个节点都有浮层，
   里面是数据源、条数、最近一条、**内容键值**和样本。计数证明不了内容对不对。
   ============================================================ */

var ExperienceChainView = (function () {
  var els = {};
  var data = null;
  var loadError = null;
  var timer = null;
  var tipEl = null;
  var busy = false;

  /* ---------- 工具 ---------- */

  function esc(v) {
    if (v === null || v === undefined) { return ""; }
    return String(v).replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function hhmm(ts) {
    if (!ts) { return "—"; }
    var s = String(ts);
    var m = s.match(/T(\d{2}:\d{2}:\d{2})/);
    return m ? m[1] : s.slice(0, 19);
  }

  function clip(v, n) {
    var s = v === null || v === undefined ? "" : String(v);
    return s.length > n ? s.slice(0, n) + "…" : s;
  }

  /** B21：缺失就是缺失。用「键不出现」表达，不拿空串/哨兵值冒充数据。 */
  function kv(obj) {
    var out = [];
    if (!obj || typeof obj !== "object") { return out.join(""); }
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      var v = obj[k];
      if (v === null || v === undefined) { continue; }
      if (typeof v === "object") { continue; }
      out.push('<span class="tok"><b>' + esc(k) + "</b>=" + esc(clip(v, 160)) + "</span>");
    }
    return out.join(" ");
  }

  /* ---------- 加载 ---------- */

  function load() {
    if (busy) { return; }
    busy = true;
    fetch("/api/experience-chain", { cache: "no-store" })
      .then(function (r) {
        if (!r.ok) { throw new Error("HTTP " + r.status); }
        return r.json();
      })
      .then(function (j) {
        /* 只认明确 ok:true。返回体不对时宁可画「读不到」，也不把半截东西当数据。 */
        if (!j || j.ok !== true) { throw new Error("返回体不是预期的形状"); }
        data = j;
        loadError = null;
      })
      .catch(function (e) {
        /* 失败原因留在这里画出来 —— 不留空白页让人以为「没数据」。 */
        loadError = String(e && e.message ? e.message : e);
      })
      .then(function () {
        busy = false;
        paint();
      });
  }

  /* ---------- 区 1：链条 ---------- */

  function nodeBox(n) {
    if (!n) { return ""; }
    /* ★★ 第 5 组红线第四条「未知 ≠ 零」：
       显示口径**由后端给**（`n.measure.display`），本页不再自己猜。
       原来这里是 `n.present ? String(n.count) : "?"` ——
       它把「读不到」与「结构上没有这个观测点」都显示成一个 `?`，
       而这两件要修的地方不一样（一个修读法/文件，一个要改引擎）。
       顺带：count 现在是 null 而不是 0，`String(null)` 会变成字符串 "null"。
       ⚠️ 保留 present 那一支只是兜底 —— 后端已保证 count 为 null 时 measure 存在。 */
    var ms = n.measure || null;
    var countTxt = ms ? ms.display
      : (n.present ? String(n.count) : "?");
    var cls = "expn" + (n.present ? "" : " blind");
    var msCls = ms ? (" exp-unk-" + ms.state) : "";
    return '<div class="' + cls + '" data-node="' + esc(n.id) + '">' +
      '<span class="expn-idx">' + esc(n.idx || "") + "</span>" +
      '<span class="expn-label">' + esc(n.label) + "</span>" +
      '<span class="expn-count' + msCls + '" title="' + esc(ms && ms.reason_zh ? ms.reason_zh : "") + '">'
        + esc(countTxt) + "</span>" +
      '<span class="expn-fresh ' + freshCls(n.freshness) + '">' + esc(freshMark(n.freshness)) + "</span>" +
      '<span class="expn-src">' + esc(clip(n.source, 40)) + "</span>" +
      "</div>";
  }

  /* ---------- 逐节点新鲜度（第 5 组红线「历史 ≠ 当前」）----------
     为什么每个节点都要有自己的：九个节点读九个不同的文件，停更时间可以差很远。
     实测就有 —— learner.jsonl 停在 10-03 16:49、context.jsonl 停在 10-04 01:56，
     而 events.jsonl 只落后 24 秒。整页一个新鲜度会把「某一格很旧」藏起来。 */

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
  /** null = 结构上不可观测（没有文件可查时间），与「旧」是两回事，所以单独一档。 */
  function freshCls(f) {
    if (!f) { return "none"; }
    if (f.verdict === "unreadable") { return "gone"; }
    if (f.verdict === "fresh") { return "ok"; }
    if (f.verdict === "old_but_consistent") { return "old"; }
    return "bad";
  }
  function freshMark(f) {
    var c = freshCls(f);
    if (c === "none") { return "无源"; }
    if (c === "gone") { return "读不到"; }
    if (c === "ok") { return "新"; }
    return "旧·" + span(f.seconds_since_this_file_write);
  }
  function freshBlock(f) {
    if (!f) {
      return '<div class="tsub">★ 这一环<b>结构上不可观测</b> —— 没有落盘的文件，'
        + "所以既没有时间也没有条数。那是事实，不是页面没读。</div>";
    }
    var h = '<div class="tdl">这一格的数字是什么时候的</div>';
    h += '<div class="tsub">文件：<code>' + esc(f.this_file) + "</code>"
      + (f.file_dir && f.reference_dir && f.file_dir !== f.reference_dir
          ? "（在 config/numen 下，不在 monitor/ 里）" : "")
      + "</div>";
    h += '<div class="tsub">文件最后写入：<code>' + esc(hhmm(f.this_file_write)) + "</code>（UTC，距今 <b>"
      + esc(span(f.seconds_since_this_file_write)) + "</b>）</div>";
    h += '<div class="tsub">游戏最后活动：<code>' + esc(hhmm(f.monitor_newest_write)) + "</code>（"
      + esc(f.monitor_newest_file || "?") + "，距今 " + esc(span(f.seconds_since_monitor_newest_write)) + "）";
    if (f.behind_monitor_seconds > 0) {
      h += "　·　别的文件比它新 <b>" + esc(span(f.behind_monitor_seconds)) + "</b>";
    }
    h += "</div>";
    if (f.events_read === null) {
      h += '<div class="tsub">本次<b>没有量</b>这一格的窗口条数（它的数据由别的读函数提供）—— '
        + "所以「读到了多少条」这里不给数，而不是给 0。</div>";
    } else {
      h += '<div class="tsub">本次读到 <b>' + esc(f.events_read) + "</b> 条";
      if (f.first_event_at) {
        h += "，覆盖 <code>" + esc(hhmm(f.first_event_at)) + " ~ " + esc(hhmm(f.last_event_at)) + "</code>（UTC）";
      }
      h += "</div>";
    }
    if (f.window) {
      h += '<div class="tsub">' + (f.window.truncated
          ? '<span class="exp-fresh-badnote">⚠ 窗口截断了：' + esc(f.window.note_zh) + "</span>"
          : esc(f.window.note_zh)) + "</div>";
    }
    h += '<div class="tsub exp-fresh-verdict">判读：' + esc(f.verdict_zh) + "</div>";
    return h;
  }

  function edgeArrow(e) {
    var blind = e.blind || e.state === "blind";
    var cls = "expe" + (blind ? " blind" : "");
    var line = blind
      ? '<span class="expe-txt">' + esc(clip(e.measured || "看不见", 60)) + " ⚠</span>"
      : '<span class="expe-txt">' + esc(clip(e.measured || "", 60)) + "</span>";
    return '<div class="' + cls + '" data-edge="' + esc(e.from + "->" + e.to) + '">' +
      '<span class="expe-line"></span>' + line + "</div>";
  }

  /* ---------- 区 2：内脑门禁时间线 ---------- */

  /* 九格 = 九个判定项。每一格的「亮」含义不同，所以在 gateRow 里分开判，
     统一用 !!v 会把「turnPause=NONE」和「toolsBusy=false」都算成问题。 */
  var GATES = [
    { key: "dead", label: "死着", on: function (v) { return !!v.dead; }, why: "身体没了" },
    { key: "externallyDriven", label: "外脑驾驶", on: function (v) { return !!v.externallyDriven; }, why: "McpMode.driving() 为真" },
    { key: "turnPause", label: "停牌", on: function (v) { return v.turnPause && v.turnPause !== "NONE"; }, why: "AgentTurnPause 处于暂停态" },
    { key: "awaitingLlm", label: "等响应", on: function (v) { return !!v.awaitingLlm; }, why: "上一轮还没回" },
    { key: "compacting", label: "整理中", on: function (v) { return !!v.compacting; }, why: "正在压上下文" },
    { key: "toolsBusy", label: "工具在飞", on: function (v) { return !!v.toolsBusy; }, why: "还有未收的工具结果" },
    { key: "conversationMsgs", label: "无对话", on: function (v) { return v.conversationMsgs === 0; }, why: "对话为空（不是有内容就不亮）" },
    { key: "endpointProblem", label: "端点问题", on: function (v) { return !!v.endpointProblem; }, why: "provider 没绑或没 key" },
    { key: "autoCompactArmed", label: "待整理", on: function (v) { return !!v.autoCompactArmed; }, why: "上下文已近窗口但还没触发整理" }
  ];

  function gateRow(b) {
    var cells = "";
    for (var i = 0; i < GATES.length; i++) {
      var g = GATES[i];
      var lit = false;
      try { lit = g.on(b); } catch (e) { lit = false; }
      cells += '<span class="gcell' + (lit ? " on" : "") + '" title="' +
        esc(g.label + "：" + (lit ? g.why : "未触发")) + '"></span>';
    }
    var probs = b.problems || [];
    return '<div class="grow" data-brain="' + esc(b.timestamp) + '">' +
      '<span class="gtime">' + esc(hhmm(b.timestamp)) + "</span>" +
      '<span class="gwho">' + esc(clip(b.companion || "", 8)) + "</span>" +
      '<span class="gcells">' + cells + "</span>" +
      '<span class="gblock' + (b.blocker && b.blocker !== "none" ? " bad" : "") + '">' +
      esc(b.blockerLabel || b.blocker || "?") + "</span>" +
      (probs.length ? '<span class="gwarn">problems ' + probs.length + "</span>" : "") +
      "</div>";
  }

  function gateHeader() {
    var h = '<span class="gtime"></span><span class="gwho">同伴</span><span class="gcells">';
    for (var i = 0; i < GATES.length; i++) {
      h += '<span class="gcell hdr" title="' + esc(GATES[i].why) + '"></span>';
    }
    return h + '</span><span class="gblock">被哪道闸挡住</span></div>';
  }

  function statusBar(o) {
    if (!o || !o.byStatus) { return ""; }
    var keys = Object.keys(o.byStatus);
    if (!keys.length) { return ""; }
    var total = 0;
    var i;
    for (i = 0; i < keys.length; i++) { total += o.byStatus[keys[i]]; }
    if (!total) { return ""; }
    var cls = { done: "stseg ok", failed: "stseg bad", timeout: "stseg warn", stopped: "stseg off" };
    var segs = "";
    var legend = "";
    for (i = 0; i < keys.length; i++) {
      var k = keys[i];
      var n = o.byStatus[k];
      var pct = Math.round((n / total) * 1000) / 10;
      segs += '<span class="' + (cls[k] || "stseg off") + '" style="width:' + pct + '%"></span>';
      legend += '<span class="tok"><b>' + esc(k) + "</b>=" + esc(n) + "</span>";
    }
    return '<div class="exp-stbar-wrap">' +
      '<div class="stbar">' + segs + "</div>" +
      '<div class="exp-legend">task_finished 共 ' + esc(total) + " 条 · " + legend + "</div>" +
      '<div class="exp-sub">status_absent=' + esc(o.statusAbsent) +
      "（没有 status 的老行，B10 之前那批）· withStatus=" + esc(o.withStatus) + "</div>" +
      "</div>";
  }

  /* ---------- 浮层：点开看内容 ---------- */

  function hideTip() {
    if (tipEl) { tipEl.style.display = "none"; }
  }

  function showTip(html) {
    if (!tipEl) {
      tipEl = document.createElement("div");
      tipEl.className = "exp-tip";
      document.body.appendChild(tipEl);
      document.addEventListener("click", function (e) {
        if (tipEl && tipEl.contains(e.target)) { return; }
        if (e.target && e.target.closest && e.target.closest("[data-node],[data-edge],[data-brain]")) { return; }
        hideTip();
      });
    }
    tipEl.innerHTML = html;
    tipEl.style.display = "block";
  }

  function nodeDetail(id) {
    if (!data) { return ""; }
    var n = null;
    for (var i = 0; i < (data.nodes || []).length; i++) {
      if (data.nodes[i].id === id) { n = data.nodes[i]; }
    }
    if (!n) { return ""; }
    var h = '<div class="tdl"><b>' + esc(n.label) + "</b></div>";
    h += '<div class="tsub">' + esc(n.source) + "</div>";
    // ★ 新鲜度排在「条数」之前：条数是「有多少」，新鲜度是「这是哪一段的」。
    //   顺序反了 = 让读的人先看数字再被告知那是过去的。
    h += freshBlock(n.freshness);
    h += '<div class="tsub">条数：<b>' + esc((n.measure && n.measure.is_number)
      ? (n.present ? n.count : "?")
      : esc((n.measure && n.measure.display) || "?")) + "</b>";
    if (n.measure && !n.measure.is_number) {
      h += '　<span class="exp-unk-note">这不是 0 —— ' + esc(n.measure.reason_zh || n.measure.state) + "</span>";
    }
    h += "</div>";
    if (n.lastAt) { h += '<div class="tsub">最近一条：' + esc(hhmm(n.lastAt)) + "</div>"; }
    if (n.latest) {
      h += '<div class="tdl">最近一条的内容</div><div class="tsub">' + (kv(n.latest) || "（这一行只有计数，没有内容字段）") + "</div>";
    }
    if (n.sample && n.sample.length) {
      h += '<div class="tdl">样本 ' + esc(n.sample.length) + " 条</div>";
      for (var j = 0; j < Math.min(3, n.sample.length); j++) {
        h += '<div class="tsub">· ' + (kv(n.sample[j]) || esc(clip(JSON.stringify(n.sample[j]), 200))) + "</div>";
      }
    }
    return h;
  }

  function edgeDetail(key) {
    if (!data) { return ""; }
    var e = null;
    for (var i = 0; i < (data.edges || []).length; i++) {
      if ((data.edges[i].from + "->" + data.edges[i].to) === key) { e = data.edges[i]; }
    }
    if (!e) { return ""; }
    var h = '<div class="tdl">' + esc(e.from) + " → " + esc(e.to) + "</div>";
    h += '<div class="tsub">状态：<b>' + esc(e.state) + "</b></div>";
    h += '<div class="tsub">量到：' + esc(e.measured || "—") + "</div>";
    if (e.why) { h += '<div class="tsub">' + esc(e.why) + "</div>"; }
    if (e.evidence && e.evidence.length) {
      h += '<div class="tdl">证据文件</div>';
      for (var j = 0; j < e.evidence.length; j++) {
        h += '<div class="tsub">· ' + esc(e.evidence[j]) + "</div>";
      }
    }
    return h;
  }

  function brainDetail(ts) {
    if (!data || !data.brain) { return ""; }
    var b = null;
    for (var i = 0; i < (data.brain.sample || []).length; i++) {
      if (data.brain.sample[i].timestamp === ts) { b = data.brain.sample[i]; }
    }
    if (!b) { return ""; }
    var h = '<div class="tdl">' + esc(hhmm(b.timestamp)) + " · " + esc(clip(b.companion || "", 8)) + "</div>";
    h += '<div class="tsub">type=<b>' + esc(b.type) + "</b> blocker=" + esc(b.blocker) + "</div>";
    var probs = b.problems || [];
    if (probs.length) {
      h += '<div class="tdl">problems（这一行自带的问题）</div><div class="tprobs">';
      for (var j = 0; j < probs.length; j++) { h += '<div>· ' + esc(probs[j]) + "</div>"; }
      h += "</div>";
    } else {
      h += '<div class="tsub">problems：无</div>';
    }
    h += '<div class="tdl">全部输入（读数自带，不靠推断）</div><div class="tsub">' + kv(b) + "</div>";
    return h;
  }

  function bindTips() {
    var nodes = document.querySelectorAll("[data-node]");
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].addEventListener("click", function () {
        showTip(nodeDetail(this.getAttribute("data-node")));
      });
    }
    var edges = document.querySelectorAll("[data-edge]");
    for (var j = 0; j < edges.length; j++) {
      edges[j].addEventListener("click", function () {
        showTip(edgeDetail(this.getAttribute("data-edge")));
      });
    }
    var brains = document.querySelectorAll("[data-brain]");
    for (var k = 0; k < brains.length; k++) {
      brains[k].addEventListener("click", function () {
        showTip(brainDetail(this.getAttribute("data-brain")));
      });
    }
  }

  /* ---------- 画 ---------- */

  function paint() {
    if (!els.container) { return; }

    if (loadError) {
      els.container.innerHTML =
        '<div class="exp-blindbox"><b>读不到 /api/experience-chain</b>' +
        esc(loadError) +
        '<div class="exp-sub">这不等于「没有数据」—— 只等于本页此刻读不到。' +
        "服务端进程可能还在跑旧代码（改完 .mjs 必须重启才生效）。</div></div>";
      return;
    }
    if (!data) {
      els.container.innerHTML = '<div class="exp-sub">正在读 /api/experience-chain …</div>';
      return;
    }

    var h = "";

    /* 盲区先说在前面：这是本页最重要的一段，用户要靠它判断哪些格子能信 */
    if (data.blindSpots && data.blindSpots.length) {
      h += '<div class="exp-blindbox"><b>本页有 ' + data.blindSpots.length + " 处看不见（图上画虚线的地方）</b>";
      for (var b = 0; b < data.blindSpots.length; b++) {
        var s = data.blindSpots[b];
        h += "<div>· <b>" + esc(s.id) + "</b> — 看得见的：" + esc(s.what) +
          "；所以：" + esc(s.so) + "；真源在：" + esc(s.truth) + "</div>";
      }
      h += "</div>";
    }

    /* 区 1：链 */
    var nodes = data.nodes || [];
    h += '<div class="exp-head"><b>经验链数据流</b>' +
      '<span class="exp-head2">点每个方框看内容 · 点每条边看量到多少 · 点虚线看为什么看不见</span></div>';
    h += '<div class="exp-chain">';
    for (var i = 0; i < nodes.length; i++) {
      h += nodeBox(nodes[i]);
      var e = edgeAfter(nodes[i].id, data.edges);
      if (e) { h += edgeArrow(e); }
    }
    h += "</div>";

    // ★★ 第 5 组红线第四条：先说清「这一列里有多少格是真量出来的」，再说那些数字。
    //   不先说这个，一列里混着 ? 与 0 会被整列读成「0」。
    if (data.audit) {
      var a = data.audit;
      h += '<div class="exp-audit"><b>这一列 ' + esc(a.total) + " 格里：</b>"
        + "量到了 <b>" + esc(a.known) + "</b>"
        + (a.truncated ? "、只覆盖一部分 <b>" + esc(a.truncated) + "</b>" : "")
        + "、读不到 <b>" + esc(a.unknown) + "</b>"
        + "、没有观测点 <b>" + esc(a.unobservable) + "</b>"
        + "　·　其中<b>真的是 0</b> 的 <b>" + esc(a.zeros) + "</b> 格"
        + '<div class="exp-audit-note">' + esc(a.note_zh) + "</div></div>";
    }

    h += '<div class="exp-legend">' +
      '<span class="tok"><b>实线</b>=量到了</span>' +
      '<span class="tok"><b>虚线灰</b>=看不见（不是 0 条）</span>' +
      '<span class="tok"><b>方框里的 ?</b>=这个节点的数据源本页读不到</span>' +
      "</div>";

    h += statusBar(data.outcomes);

    /* 区 2：门禁时间线 */
    var brain = data.brain;
    if (brain && brain.sample && brain.sample.length) {
      h += '<div class="exp-head"><b>内脑门禁时间线</b>' +
        '<span class="exp-head2">' + esc(brain.source) + " 共 " + esc(brain.total) +
        " 行（下面是最新的 " + brain.sample.length + " 行）· 点行看全部输入</span></div>";
      h += '<div class="grows"><div class="grow head">' + gateHeader() + "</div>";
      /* brain.sample 的最新在最后（readJsonlTail 的形状），倒着画 = 新的在上面 */
      for (var r = brain.sample.length - 1; r >= 0; r--) {
        h += gateRow(brain.sample[r]);
      }
      h += "</div>";
      h += '<div class="exp-legend">亮格 = 那一项当时成立；空白 = 未触发。' +
        '九格的含义各不相同（例如「无对话」是 conversationMsgs===0 才亮，「停牌」是 turnPause!==\'NONE\'）。' +
        "点行展开看那一行的全部输入。</div>";
    }

    if (data.sampling) {
      // ★ 采样上限改成**逐文件**之后，这里必须跟着改。
      //   原来读的是 data.sampling.maxBytes / .maxEvents（一个数），
      //   改成 perFile 之后那两个键没了 —— 而这一行还照旧在读，
      //   页面就会打印「采样窗口：undefined 字节 / undefined 条」。
      //   这是本批唯一一处「改了后端形状、忘了改前端读法」的地方，
      //   探针只测了 .mjs 没测 .js 才漏掉 —— 所以显示层也要有测试。
      var pf = data.sampling.perFile || {};
      var names = Object.keys(pf);
      if (names.length) {
        h += '<div class="tsub">采样上限<b>逐文件</b>给（不是一刀切）：';
        var parts = [];
        for (var si = 0; si < names.length; si++) {
          parts.push(esc(names[si]) + " " + esc(pf[names[si]].maxBytes) + "B/"
            + esc(pf[names[si]].maxEvents) + "条");
        }
        h += parts.join("　·　") + "</div>";
      }
      var win = data.windows || {};
      var wnames = Object.keys(win);
      var cut = [];
      for (var wi = 0; wi < wnames.length; wi++) {
        if (win[wnames[wi]] && win[wnames[wi]].truncated) {
          cut.push(esc(wnames[wi]) + "（文件共 " + esc(win[wnames[wi]].size_bytes)
            + " 字节，本次只读尾部 " + esc(win[wnames[wi]].window_bytes) + " 字节）");
        }
      }
      if (cut.length) {
        h += '<div class="tsub exp-fresh-badnote">⚠ 本次被窗口截断的文件：'
          + cut.join("　·　") + " ⇒ 下面与它们相关的数字只覆盖这一段，<b>不是全量</b>。</div>";
      }
      if (data.sampling.note) { h += '<div class="tsub">' + esc(data.sampling.note) + "</div>"; }
    }
    h += '<div class="exp-sub">读数时间 ' + esc(hhmm(data.timestamp)) + "</div>";

    els.container.innerHTML = h;
    bindTips();
  }

  /** 找到「从某个节点出发」的那条边。链是线性的，所以每个节点最多一条出边。 */
  function edgeAfter(id, edges) {
    if (!edges) { return null; }
    for (var i = 0; i < edges.length; i++) {
      if (edges[i].from === id) { return edges[i]; }
    }
    return null;
  }

  /* ---------- 对外 ---------- */

  function init() {
    els.container = document.getElementById("experience-chain");
    load();
    /* 5 秒一次，和同目录 numen-adapter 的节奏一致。游戏在写 jsonl，静态页面不会自己更新。 */
    if (!timer) { timer = setInterval(load, 5000); }
  }

  function render() {
    /* 与 monitor-app.js 的分页约定一致：切到本页才画。
       render() 不管有没有新数据都调 paint()，paint 里自己判断该画什么。 */
    paint();
  }

  function reload() {
    load();
  }

  return { init: init, render: render, reload: reload };
})();

if (typeof window !== "undefined") { window.ExperienceChainView = ExperienceChainView; }
