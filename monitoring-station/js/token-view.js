/* ============================================================
   TokenView — Token 消耗速率（①.6）

   ★ 为什么加这一页（owner 2026-10-04：「加个 token 消耗速率，原版 numen 有但没覆盖完」）

   原版 Numen 有三套 token 读数，网页上一套都没有：
     ① 游戏内 `/usage`            → TokenLedger → companions/<uuid>/stats.json
     ② 会话台账                   → 同上文件的累计值（含 cacheRead / cacheWrite）
     ③ 逐轮 usage                → monitor/{rdd,context}*.jsonl 的 llm_response
     ④ 上下文水位 / 自动压缩闸     → brain.jsonl 的 gate 事件
   也就是说「这一局烧了多少、烧得多快、上下文还剩多少」只能在游戏里问 /usage，
   而 /usage **只给累计，不给速率**，也不给逐轮。

   ★ 三套口径不可相加（详见 token-view.mjs 文件头）：
     台账 totalTokens 只累加 fresh（缓存命中不重复计），逐轮 totalTokens 是 provider
     报的本次请求总量（含缓存命中）。所以本页**分区摆放**，绝不合成一个「总消耗」。
     逐轮事件里也没有 completionTokens 字段，「输出」= total - prompt 是推算值。

   ★ 形状照 acx-view.js：独立 view 自己 fetch，走常驻宿主 #token-host。
   ============================================================ */

var TokenView = (function () {
  "use strict";

  var DATA = null;
  var ERROR = "";
  var TIMER = null;
  var FETCHING = false;

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
  /** 相对时间（人话，与时区无关）；绝对时间一律标 UTC。 */
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

  /* ---- 数据是读不到还是旧了：第 5 组红线「历史 ≠ 当前」 ---- */
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
      bad: "⚠ 这份数据不是当前这一局的",
      gone: "⚠ 读不到数据源 —— 下面的数字全部无效"
    }[cls];
    var cov = d.coverage || {};
    var out = "";
    if (f && f.readable === false) {
      return '<div class="acx-fresh acx-fresh-gone"><b>' + esc(head) + "</b>"
        + "<div>读不到 <code>" + esc(f.this_file) + "</code>。"
        + "这不是「0 token」，是观测面坏了 —— 本页其它数字请一律当作无效。</div></div>";
    }
    out += '<div class="acx-fresh acx-fresh-' + cls + '"><b>' + esc(head) + "</b>"
      + "<div>逐轮覆盖 <code>" + esc(hhmm(cov.firstTurnAt)) + " ~ " + esc(hhmm(cov.lastTurnAt))
      + "</code>（UTC）　共 <b>" + esc(cov.turnsRead) + "</b> 轮</div>";
    if (f) {
      out += "<div>数据源最后写入 <code>" + esc(hhmm(f.this_file_write))
        + "</code>（UTC，距今 <b>" + esc(span(f.seconds_since_this_file_write))
        + "</b>）　·　游戏最后活动 <code>" + esc(hhmm(f.monitor_newest_write))
        + "</code>（" + esc(f.monitor_newest_file || "?") + "，距今 "
        + esc(span(f.seconds_since_monitor_newest_write)) + "）</div>"
        + '<div class="acx-fresh-verdict">判读：' + esc(f.verdict_zh) + "</div>";
    }
    if (cov.filesSkipped && cov.filesSkipped.length) {
      // ★ 少读了哪几个文件必须说出来，否则「速率」看起来覆盖了全部历史
      out += '<div class="acx-fresh-badnote">' + esc(cov.note_zh) + "</div>";
    } else if (cov.note_zh) {
      out += "<div>" + esc(cov.note_zh) + "</div>";
    }
    return out + "</div>";
  }

  /* ---- 速率卡：五个时间窗并排 ---- */
  function renderRate(d) {
    var rate = d.rate || [];
    if (!rate.length) { return ""; }
    var cards = rate.map(function (w) {
      var cls = w.idle ? "" : (w.id === "all" ? " run" : " ok");
      return '<div class="acx-stat' + cls + '">'
        + '<div class="acx-stat-k">' + esc(w.label) + "</div>"
        + '<div class="acx-stat-v">' + (w.idle ? "空闲" : grp(w.tokensPerMin)) + "</div>"
        + '<div class="acx-stat-k">token / 分钟</div>'
        + "<div>" + esc(w.turns) + " 轮 · " + esc(w.turnsPerMin) + " 轮/分"
        + " · 跨度 " + esc(w.spanMinutes) + " 分</div>"
        + "</div>";
    }).join("");
    return '<h3>消耗速率 · token / 分钟</h3><div class="acx-cards">' + cards + "</div>"
      + '<div class="acx-sub">分母是<b>窗口全长（含空闲）</b>。想看「真在跑时有多快」，'
      + "看每行第二行的「轮/分钟」和下面的每分钟柱。</div>";
  }

  /* ---- 每分钟柱 ---- */
  function renderBuckets(d) {
    var b = d.buckets || {};
    var list = b.buckets || [];
    if (!list.length) {
      return '<h3>每分钟走势</h3><div class="acx-empty">没有可画的分段数据。</div>';
    }
    var peak = b.peak || 1;
    var bars = list.map(function (x) {
      var h = Math.max(1, Math.round((x.total / peak) * 46));
      var tip = hhmm(x.minute) + " UTC · " + grp(x.total) + " token · " + x.turns + " 轮";
      return '<i class="tok-bar" title="' + esc(tip) + '" style="height:' + h + 'px"></i>';
    }).join("");
    return '<h3>每分钟走势（最近 ' + list.length + " 个有消耗的分钟）</h3>"
      + '<div class="tok-chart">' + bars + "</div>"
      + '<div class="acx-sub">峰值 ' + grp(peak) + " token/分钟。"
      + (b.truncatedMinutes > 0 ? "更早的 " + b.truncatedMinutes + " 个分钟没画（超过上限）。" : "")
      + "<b>中间的空白是真的没在烧</b>（游戏没跑那一分钟），不是丢数据。</div>";
  }

  /* ---- 谁在烧 ---- */
  function renderGroups(d) {
    var out = "";
    var byActor = d.byActor || [];
    if (byActor.length) {
      out += "<h3>按角色</h3><div class=\"table-scroll\"><table class=\"table\">"
        + "<tr><th>角色</th><th>轮数</th><th>输入</th><th>输出（推算）</th><th>合计</th><th>模型</th></tr>"
        + byActor.map(function (g) {
          return "<tr><td>" + esc(g.key) + "</td><td>" + g.turns + "</td><td>" + grp(g.prompt)
            + "</td><td>" + grp(g.output) + "</td><td>" + grp(g.total) + "</td><td>"
            + esc(Object.keys(g.models || {}).join("、") || "?") + "</td></tr>";
        }).join("") + "</table></div>";
    }
    var byModel = d.byModel || [];
    if (byModel.length > 1) {
      out += "<h3>按模型</h3><div class=\"table-scroll\"><table class=\"table\">"
        + "<tr><th>模型</th><th>轮数</th><th>合计</th></tr>"
        + byModel.map(function (g) {
          return "<tr><td>" + esc(g.key) + "</td><td>" + g.turns + "</td><td>" + grp(g.total) + "</td></tr>";
        }).join("") + "</table></div>";
    }
    return out;
  }

  /* ---- 台账（游戏内 /usage 的同一份文件） ---- */
  function renderLedger(d) {
    var L = d.ledger || {};
    if (!L.readable || !(L.companions || []).length) {
      return '<h3>会话台账</h3><div class="acx-empty">'
        + esc(L.note_zh || "读不到台账。") + "</div>";
    }
    var rows = L.companions.map(function (c) {
      if (c.unreadable) {
        return "<tr><td>" + esc(c.id) + "</td><td colspan=\"6\">文件读不出内容（可能正在写）</td></tr>";
      }
      return "<tr><td>" + esc(c.id) + "</td>"
        + "<td>" + grp(c.totalTokens) + "</td>"
        + "<td>" + grp(c.input) + "</td>"
        + "<td>" + grp(c.output) + "</td>"
        + "<td>" + grp(c.cacheRead) + "</td>"
        + "<td>" + (c.cacheHitRate == null ? "?" : c.cacheHitRate + "%") + "</td>"
        + "<td>" + esc(hhmm(c.mtime)) + "<br><small>距今 " + esc(span(c.secondsSinceWrite)) + "</small></td>"
        + "</tr>";
    }).join("");
    return '<h3>会话台账 · 累计（原版 /usage 的同一份文件）</h3>'
      + '<div class="table-scroll"><table class="table">'
      + "<tr><th>同伴</th><th>totalTokens</th><th>input</th><th>output</th><th>cacheRead</th>"
      + "<th>缓存命中率</th><th>文件最后写入</th></tr>"
      + rows + "</table></div>"
      + '<div class="acx-caveat"><b>⚠ 这块不能和上面的逐轮合计相加</b><div>'
      + esc(L.note_zh) + "</div></div>";
  }

  /* ---- 上下文水位 / 自动压缩闸 ---- */
  function renderContext(d) {
    var c = d.contextWindow || {};
    if (!c.readable || !c.has_records) {
      return '<h3>上下文水位</h3><div class="acx-empty">'
        + esc(c.note_zh || "读不到 brain.jsonl。") + "</div>";
    }
    var cards = (c.companions || []).map(function (x) {
      var pct = x.waterPercent;
      var w = pct == null ? 0 : Math.min(100, Math.max(0, pct));
      var cls = pct == null ? "" : (w >= 90 ? " bad" : (w >= 70 ? " warn" : " ok"));
      return '<div class="acx-stat' + cls + '">'
        + '<div class="acx-stat-k">' + esc(x.id) + "</div>"
        + '<div class="acx-stat-v">' + (pct == null ? "?" : pct + "%") + "</div>"
        + '<div class="tok-water"><i style="width:' + w + '%"></i></div>'
        + "<div>" + (x.contextTokens == null ? "用量 ?" : grp(x.contextTokens) + " token") + " / "
        + (x.modelWindow == null ? "窗口 ?" : grp(x.modelWindow)) + "</div>"
        + "<div>压缩闸 " + (x.autoCompactThreshold == null ? "?" : grp(x.autoCompactThreshold))
        + "　余量 " + (x.headroomTokens == null ? "?" : grp(x.headroomTokens))
        + (x.compacting ? "　<b>正在压缩</b>" : "") + "</div>"
        + '<div class="acx-stat-k">' + esc(hhmm(x.at)) + " UTC · "
        + esc(x.conversationMsgs == null ? "?" : x.conversationMsgs) + " 条对话</div>"
        + "</div>";
    }).join("");
    return "<h3>上下文水位 · 离自动压缩还有多远</h3>"
      + '<div class="acx-cards">' + cards + "</div>"
      + '<div class="acx-sub">' + esc(c.note_zh) + "</div>";
  }

  /* ---- 逐轮明细 ---- */
  function renderTurns(d) {
    var turns = d.turns || [];
    if (!turns.length) {
      return '<h3>逐轮明细</h3><div class="acx-empty">没有带 usage 的轮次。</div>';
    }
    var rows = turns.map(function (t) {
      return "<tr><td>" + esc(hhmm(t.at)) + "</td><td>" + esc(t.actor) + "</td>"
        + "<td>" + esc(t.model) + "</td>"
        + "<td>" + grp(t.promptTokens) + "</td>"
        + "<td>" + grp(t.outputTokens) + " <small>推算</small></td>"
        + "<td>" + grp(t.totalTokens) + "</td>"
        + "<td>" + esc(t.finishReason || "") + "</td></tr>";
    }).join("");
    return "<h3>逐轮明细（最近 " + turns.length + " 轮，时间倒序）</h3>"
      + '<div class="table-scroll"><table class="table">'
      + "<tr><th>时刻</th><th>角色</th><th>模型</th><th>promptTokens</th><th>输出</th><th>totalTokens</th><th>finish</th></tr>"
      + rows + "</table></div>";
  }

  function renderCaveats(d) {
    var list = d.caveats || [];
    if (!list.length) { return ""; }
    return list.map(function (c) {
      return '<div class="acx-sub">· ' + esc(c.note_zh) + "</div>";
    }).join("");
  }

  function render() {
    var host = document.getElementById("token-host");
    if (!host) { return; }
    if (ERROR) {
      host.innerHTML = '<div class="acx-err"><b>读不到 token 数据</b><br>' + esc(ERROR)
        + "<br><br>⚠️ 「读不到」<b>不是</b>「没烧 token」—— 前者是观测面坏了。</div>";
      return;
    }
    if (!DATA) {
      host.innerHTML = '<div class="acx-empty">正在读 token 消耗…</div>';
      if (!FETCHING) load();
      return;
    }
    if (DATA.ok === false) {
      host.innerHTML = '<div class="acx-err">' + esc(DATA.error || "未知错误") + "</div>";
      return;
    }
    // ★ 数据源读不到 ⇒ 除了说读不到，下面**一个数字都不许渲染**。
    //   本页会显示「12,475,833 token / 分钟」。文件读不到却还显示它 =
    //   在替不存在的东西背书 —— 这是第 5 组红线「读不到 ≠ 0」。
    //   （只 gate「明确 readable===false」；freshness 整个算不出来时不越权替它下结论。）
    var f = DATA.freshness;
    if (f && f.readable === false) {
      host.innerHTML = "<h2>Token 消耗</h2>" + renderFreshness(DATA)
        + '<div class="acx-sub">数据源读不到，下面的数字没有依据，因此一个都不显示。</div>';
      return;
    }
    var reasons = DATA.emptyReasons || [];
    var empty = "";
    if (reasons.length) {
      empty = '<div class="acx-caveat"><b>⚠ 这一页没有速率可显示</b><div>'
        + reasons.map(function (r) { return esc(r); }).join("<br>") + "</div></div>";
    }
    host.innerHTML = "<h2>Token 消耗</h2>"
      + '<p class="acx-sub">速率来自逐轮 usage（带时间戳，可差分）；累计与缓存来自会话台账；'
      + "上下文水位来自 brain.jsonl。三块<b>口径不同、不可相加</b>。</p>"
      + renderFreshness(DATA)
      + empty
      + renderRate(DATA)
      + renderBuckets(DATA)
      + renderGroups(DATA)
      + renderLedger(DATA)
      + renderContext(DATA)
      + renderTurns(DATA)
      + "<h3>本页口径说明</h3>" + renderCaveats(DATA);
  }

  function load() {
    if (FETCHING) return;
    FETCHING = true;
    return fetch("/api/token-rate", { cache: "no-store" })
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

  return { render: render, load: load, init: function () { load(); if (!TIMER) TIMER = setInterval(load, 8000); } };
})();

if (typeof window !== "undefined") { window.TokenView = TokenView; }