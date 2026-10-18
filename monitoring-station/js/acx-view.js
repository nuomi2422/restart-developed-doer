/* ============================================================
   AcxView — 状态机 / 脚本（AC）执行页

   ★ 为什么接这一页（owner 2026-10-03：「状态机执行的那一个也是没有接到
     好像也是没有接到监测台那里，导致看不了什么情况」）：
     页签早就在、渲染代码早就有，但显示的是占位符「AC 执行 / 等待 RDD 提供 /
     当前状态 PENDING」—— **而 /api/acx 早就有真实数据了**：
     实测 65 次运行、26 成功 / 21 失败 / 16 暂停 / 1 超时 / 1 运行中。
     ⇒ 不是「没有数据」，是**没有人去读它**。这一页做的就是把它读出来。

   ★ 形状照 experience-chain-view.js：独立 view 自己 fetch，不走 SSE。
   ============================================================ */

var AcxView = (function () {
  "use strict";

  var DATA = null;
  var ERROR = "";
  var OPEN = {};      // 哪些运行展开了（要看每一步）
  var SECTION = "content";   // 当前子块：content / lifecycle / runs（4.1 / 4.2 / 4.3）
  var TIMER = null;
var FETCHING = false;   // 防抖：8s 轮询 + render() 的兜底自取数可能撞在一起

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

  /** 一次运行的状态 → 一个颜色类。★ 状态不认识就说「不认识」，不许归到成功或失败。 */
  function statusClass(s) {
    var v = String(s || "").toUpperCase();
    if (v === "SUCCESS" || v === "OK") { return "ok"; }
    if (v === "FAIL" || v === "FAILED") { return "bad"; }
    if (v === "PAUSED" || v === "TIMEOUT") { return "warn"; }
    if (v === "RUNNING" || v === "PENDING") { return "run"; }
    return "unknown";   // 「状态不认识」是一件事，不许塌进成功/失败
  }
  function stepClass(s) {
    var v = String(s || "").toLowerCase();
    if (v === "ok" || v === "succeeded") { return "ok"; }
    if (v === "failed" || v === "fail") { return "bad"; }
    // ★ waiting = 「动作已受理、还在世界里执行」。它不是失败，也不是暂停：
    //   此刻身体在动，等的是它的回执。塌进 bad 或 warn 都是把两件事说成一件事。
    //   实机依据见 /api/acx 的 caveat：STEP_FAILED 里有 53 条其实是这个。
    if (v === "waiting") { return "wait"; }
    if (v === "refbad") { return "warn"; }        // 引用没解析出来 ≠ 脚本写错了
    if (v === "paused") { return "warn"; }
    if (v === "running") { return "run"; }
    if (v === "skipped" || v === "ignored") { return "muted"; }
    return "unknown";
  }

  /**
   * ★ 顶部必须先说清「失败」这个数字含不含「还在动」（TODO AC-B20）。
   * 不说的话，页面一边报「失败 25」一边在事件分布里报「STEP_FAILED 82」，
   * 读的人无从判断哪个是真的 —— 而这种自相矛盾正是「不可视」的另一种形态。
   */
  function renderCaveat(d) {
    var c = d.caveat;
    if (!c) { return ""; }
    return '<div class="acx-caveat"><b>⚠ 「STEP_FAILED」不等于「失败」</b>'
      + "<div>日志里被记成失败的有 <b>" + esc(c.steps_logged_as_failed) + "</b> 条，"
      + "其中真失败 <b>" + esc(c.of_which_really_failed) + "</b> 条，"
      + "<b>" + esc(c.of_which_still_in_flight) + "</b> 条其实是「动作已受理、还在执行」"
      + "（身体在动，等回执）。</div>"
      + '<div class="acx-caveat-why">' + esc(c.note) + "</div>"
      + "</div>";
  }

  function renderTotals(d) {
    var t = d.totals || {};
    var c = d.caveat || {};
    var cards = [
      { k: "总运行次数", v: t.runs },
      { k: "成功", v: t.success, c: "ok" },
      // ★ 只报真失败。「被记成失败的条数」另有一行在上面的 caveat 里，
      //   两者混在一起就分不出「她失败了」和「她在动」。
      { k: "失败（真失败）", v: c.of_which_really_failed != null ? c.of_which_really_failed : t.failed, c: "bad" },
      { k: "在飞（已受理未完成）", v: c.of_which_still_in_flight, c: "wait" },
      { k: "暂停", v: t.paused, c: "warn" },
      { k: "超时", v: t.timeout, c: "warn" },
      { k: "运行中", v: t.running, c: "run" },
    ];
    return '<div class="acx-cards">' + cards.map(function (c2) {
      return '<div class="acx-stat' + (c2.c ? " " + c2.c : "") + '">'
        + '<div class="acx-stat-k">' + esc(c2.k) + "</div>"
        + '<div class="acx-stat-v">' + esc(c2.v == null ? "?" : c2.v) + "</div></div>";
    }).join("") + "</div>";
  }

  function renderKinds(d) {
    var k = d.kinds || {};
    var split = d.kindsSplit || {};
    var keys = Object.keys(k);
    if (!keys.length) { return ""; }
    return '<h3>事件种类分布（' + keys.length + " 种）</h3>"
      + '<div class="acx-kinds">' + keys.map(function (name) {
        // ★ 这一 kind 内部有多种 status 时必须拆开列 —— 合起来那个数会骗人
        var parts = [];
        Object.keys(split).forEach(function (key) {
          if (key.indexOf(name + "|") === 0) {
            parts.push(key.slice(name.length + 1) + "×" + split[key]);
          }
        });
        return '<span class="acx-kind"><b>' + esc(name) + "</b> " + k[name]
          + (parts.length ? '<i class="acx-kind-split">' + esc(parts.join("  ")) + "</i>" : "")
          + "</span>";
      }).join("") + "</div>";
  }

  function renderSteps(run) {
    var steps = run.steps || [];
    if (!steps.length) { return '<div class="acx-empty">这次运行没有步骤记录。</div>'; }
    var rows = steps.map(function (st, i) {
      var notes = (st.notes || []).map(function (n) {
        // notes 里可能是对象也可能是字符串，两种都要能显示
        if (typeof n === "string") { return esc(n); }
        var k = n.kind || "";
        var d2 = n.detail || n.message || "";
        return esc(k + (d2 ? " " + d2 : ""));
      }).join("；");
      return '<div class="acx-step ' + stepClass(st.status) + '">'
        + '<span class="acx-step-n">' + (i + 1) + "</span>"
        + '<span class="acx-step-id">' + esc(st.id || "?") + "</span>"
        + '<span class="acx-step-st">' + esc(st.status || "?") + "</span>"
        // ★ 在飞的任务 id：让「脚本在等」看得见是「在等哪个任务」，而不是「停了」
        + (st.in_flight_task ? '<span class="acx-step-task">在飞 ' + esc(st.in_flight_task) + "</span>" : "")
        + '<span class="acx-step-t">' + esc(hhmm(st.at)) + "</span>"
        + (notes ? '<span class="acx-step-note">' + notes + "</span>" : "")
        + "</div>";
    }).join("");
    return '<div class="acx-steps">' + rows + "</div>";
  }

  function renderRuns(d) {
    var runs = d.runs || [];
    if (!runs.length) {
      return '<div class="acx-empty">读到 0 次运行。'
        + "<br>⚠️ 三种可能：真的没跑过 / 采样窗口没覆盖 / <b>上游断了读不到</b>。</div>";
    }
    var cards = runs.map(function (r, i) {
      var open = !!OPEN[i];
      var t = r.terminal || {};
      return '<div class="acx-run' + (open ? " open" : "") + '">'
        + '<div class="acx-run-head" data-i="' + i + '">'
        + '<span class="acx-caret">' + (open ? "▾" : "▸") + "</span>"
        + '<span class="acx-run-name">' + esc(r.ac_name || "?") + "</span>"
        + '<span class="acx-badge ' + statusClass(r.status) + '">' + esc(r.status || "?") + "</span>"
        + '<span class="acx-run-id">' + esc(r.run_id || "") + "</span>"
        + '<span class="acx-run-t">' + esc(hhmm(r.lastAt)) + "</span>"
        + '<span class="acx-run-ms">' + (r.elapsed_ms == null ? "?" : r.elapsed_ms) + "ms</span>"
        + "</div>"
        + (open ? '<div class="acx-run-body">'
          + '<div class="acx-run-meta">'
          + "<span>开始 " + esc(hhmm(r.firstAt)) + "</span>"
          + "<span>结束 " + esc(hhmm(r.lastAt)) + "</span>"
          + "<span>步骤 " + ((r.steps || []).length) + "</span>"
          + "<span>终态 " + esc(t.status == null ? "（无）" : t.status) + "</span>"
          + (t.step_index == null ? "" : "<span>停在第 " + (t.step_index + 1) + " 步</span>")
          + "</div>"
          + renderSteps(r)
          + "</div>" : "")
        + "</div>";
    }).join("");
    return '<h3>运行记录（' + runs.length + " 次）</h3>" + cards;
  }

  /* ---- ④.1 内容：脚本本体 ----
     「内容」= 有哪些脚本、各自带哪些步骤、各跑了多少次、成败如何。
     原来这一块只有 totals + kinds（纯数字），看不出「哪个脚本、它的步骤长什么样」。 */
  function renderScripts(d) {
    var runs = d.runs || [];
    if (!runs.length) { return '<div class="acx-empty">没有运行记录，脚本本体无从统计。</div>'; }
    var byName = {};
    for (var i = 0; i < runs.length; i++) {
      var r = runs[i] || {};
      var nm = r.ac_name || "?";
      byName[nm] = byName[nm] || { n: 0, ok: 0, bad: 0, other: 0, last: "", ids: {} };
      var g = byName[nm];
      g.n++;
      if (r.status === "SUCCESS") { g.ok++; }
      else if (r.status === "FAIL" || r.status === "FAILED") { g.bad++; }
      else if (r.status) { g.other++; }
      if (r.lastAt && (!g.last || r.lastAt > g.last)) { g.last = r.lastAt; }
      var steps = r.steps || [];
      for (var j = 0; j < steps.length; j++) {
        var sid = (steps[j] && steps[j].id) || "?";
        g.ids[sid] = (g.ids[sid] || 0) + 1;
      }
    }
    var rows = Object.keys(byName).sort(function (a, b) { return byName[b].n - byName[a].n; }).map(function (nm) {
      var g2 = byName[nm];
      var steps = Object.keys(g2.ids).sort(function (a, b) { return g2.ids[b] - g2.ids[a]; })
        .map(function (s) { return esc(s) + "×" + g2.ids[s]; }).join("、");
      return "<tr><td>" + esc(nm) + "</td><td>" + g2.n + "</td>"
        + '<td><span class="acx-badge ok">' + g2.ok + '</span> <span class="acx-badge bad">' + g2.bad
        + '</span> <span class="acx-badge warn">' + g2.other + "</span></td>"
        + "<td>" + esc(hhmm(g2.last)) + "</td><td>" + (steps || "—") + "</td></tr>";
    }).join("");
    return '<h3>脚本本体 · 哪些脚本、各自带哪些步骤</h3>'
      + '<div class="table-scroll"><table class="table"><tr><th>脚本</th><th>运行次数</th><th>成功 / 失败 / 其他</th><th>最近一次</th><th>步骤清单</th></tr>'
      + rows + "</table></div>"
      + '<div class="acx-sub">「其他」= 暂停 / 超时 / 运行中 / 状态不认识 —— '
      + "不认识就是不认识，不许塌进成功或失败。</div>";
  }

  /* ---- ④.2 生命周期日志：把所有运行的步骤按时间摊平 ----
     原来步骤只在「点开某一次运行」里才看得到（72 次运行得点 72 次）。
     这一块把它们全摊平按时间倒序 —— 卡在第几步一眼就能看见。 */
  function renderLifecycle(d) {
    var runs = d.runs || [], flat = [];
    for (var i = 0; i < runs.length; i++) {
      var r = runs[i] || {}, steps = r.steps || [];
      for (var j = 0; j < steps.length; j++) {
        flat.push({ at: (steps[j] && steps[j].at) || "", run: r.run_id || "?", name: r.ac_name || "?", idx: j, step: steps[j] || {} });
      }
    }
    if (!flat.length) { return '<div class="acx-empty">没有步骤记录。</div>'; }
    flat.sort(function (a, b) { return a.at < b.at ? 1 : (a.at > b.at ? -1 : 0); });
    var LIMIT = 150;
    var rows = flat.slice(0, LIMIT).map(function (f) {
      var notes = (f.step.notes || []).map(function (n) {
        if (typeof n === "string") { return esc(n); }
        var k = n.kind || "", d2 = n.detail || n.message || "";
        return esc(k + (d2 ? " " + d2 : ""));
      }).join("；");
      return '<div class="acx-step ' + stepClass(f.step.status) + '">'
        + '<span class="acx-step-n">' + (f.idx + 1) + "</span>"
        + '<span class="acx-step-id">' + esc(f.name) + " · " + esc(f.run) + " · " + esc(f.step.id || "?") + "</span>"
        + '<span class="acx-step-st">' + esc(f.step.status || "?") + "</span>"
        + '<span class="acx-step-t">' + esc(hhmm(f.at)) + "</span>"
        + (notes ? '<span class="acx-step-note">' + notes + "</span>" : "")
        + "</div>";
    }).join("");
    return '<h3>生命周期日志 · 共 ' + flat.length + " 步（这里显示最新 " + Math.min(LIMIT, flat.length) + " 条）</h3>"
      + '<div class="acx-steps">' + rows + "</div>"
      + '<div class="acx-sub">按步骤时间倒序。<b>卡住的位置</b> = 同一次运行里 failed / paused 反复出现的那一步；'
      + "每行左边框颜色 = 该步状态（绿成功 / 红失败 / 黄暂停 / 灰忽略）。</div>";
  }

  function srcLine() {
    return '<p class="acx-sub">数据源：<code>' + esc(DATA.file || "?") + "</code></p>";
  }

  /**
   * ★ 这份数据是什么时候的（第 5 组红线「历史 ≠ 当前」，2026-10-04）
   *
   * 起因是实测反例：2026-10-04 07:20 那一刻本页显示「72 次运行」，
   * 而 acx.jsonl 停在 10-03 17:32（本地）—— 比 monitor 目录里其它文件
   * 停了 8.4 小时。**那 72 次是上一局的，而页面从头到尾没说过这件事。**
   *
   * 三个不许省的点：
   *   1. **时间一律标 UTC**。本工程反复被「日志是 UTC、机器是本地」咬过；
   *      只给一个不带时区的时间戳 = 又一个必然被读错的东西。
   *      「距今多久」用相对量（人话），那个与时区无关。
   *   2. **读不到时必须说读不到**，不能显示一片 0 ——「读不到」与「真的是空的」
   *      在页面上长得一模一样，是本工程最贵的一类误会。
   *   3. **「游戏还在写别的」不等于「这份数据在跟」**：那是最该报警的一态。
   */
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
  function freshnessClass(f) {
    if (!f || f.verdict === "unreadable") { return "gone"; }
    if (f.verdict === "fresh") { return "ok"; }
    if (f.verdict === "old_but_consistent") { return "old"; }
    return "bad";                       // old_while_game_alive / old_and_lagging
  }
  function renderFreshness(f) {
    if (!f) { return ""; }
    var cls = freshnessClass(f);
    var head = {
      ok: "这份数据是新的",
      old: "注意：游戏和这份数据一起停的 —— 下面是过去的情况，不是当前",
      bad: "⚠ 这份数据不是当前这一局的",
      gone: "⚠ 读不到这个文件 —— 下面的数字全部无效"
    }[cls];

    if (f.readable === false) {
      return '<div class="acx-fresh acx-fresh-gone"><b>' + esc(head) + "</b>"
        + "<div>读不到 <code>" + esc(f.this_file) + "</code>。"
        + "这不是「0 条」，是观测面坏了 —— 本页其它数字请一律当作无效。</div></div>";
    }

    var w = f.window || {};
    var rows = [];
    rows.push("<div>本页读到 <b>" + esc(f.events_read) + "</b> 条，覆盖 <code>"
      + esc(hhmm(f.first_event_at)) + " ~ " + esc(hhmm(f.last_event_at))
      + "</code>（UTC）　"
      + (w.truncated
          ? '<span class="acx-fresh-badnote">⚠ 窗口截断了：' + esc(w.note_zh) + "</span>"
          : '<span class="acx-fresh-oknote">' + esc(w.note_zh || "") + "</span>")
      + "</div>");
    rows.push("<div>这份文件最后写入 <code>" + esc(hhmm(f.this_file_write))
      + "</code>（UTC，距今 <b>" + esc(span(f.seconds_since_this_file_write))
      + "</b>）　·　游戏最后活动 <code>" + esc(hhmm(f.monitor_newest_write))
      + "</code>（" + esc(f.monitor_newest_file || "?") + "，距今 "
      + esc(span(f.seconds_since_monitor_newest_write)) + "）"
      + (f.behind_monitor_seconds > 0
          ? "　·　别的文件比它新 <b>" + esc(span(f.behind_monitor_seconds)) + "</b>"
          : "")
      + "</div>");
    rows.push('<div class="acx-fresh-verdict">判读：' + esc(f.verdict_zh) + "</div>");
    return '<div class="acx-fresh acx-fresh-' + cls + '"><b>' + esc(head) + "</b>" + rows.join("") + "</div>";
  }

  function bodyFor(section) {
    if (DATA.ok === false) { return '<div class="acx-err">' + esc(DATA.error || "未知错误") + "</div>"; }
    // ★ 三块「先说清再让读数字」，顺序不许换：
    //   ① 数据是读不到还是旧了 → ②「失败」这个数字含不含「还在动」 → ③ 数据源路径
    //   把「读不到」排在最前，是因为它会让下面**每一个**数字都变成无效的；
    //   把它排在后面 = 让读的人先看一屏数字，再被告知它们不算数。
    var gone = freshnessClass(DATA.freshness) === "gone";
    var fresh = renderFreshness(DATA.freshness);
    var cav = renderCaveat(DATA);
    var src = srcLine();
    if (gone) { return fresh + src; }
    if (section === "lifecycle") { return fresh + cav + src + renderLifecycle(DATA); }
    if (section === "runs") { return fresh + cav + src + renderTotals(DATA) + renderRuns(DATA); }
    return fresh + cav + src + renderTotals(DATA) + renderKinds(DATA) + renderScripts(DATA);
  }
  var SECTIONS = {
    content: { title: "ACX · 内容", hint: "状态机 / 脚本的本体：有哪些脚本、步骤长什么样、跑了多少次、成败分布。" },
    lifecycle: { title: "ACX · 生命周期日志", hint: "全部运行的步骤按时间摊平 —— 卡在第几步一眼看见，不用逐次点开。" },
    runs: { title: "ACX · 运行", hint: "按运行记录看：点开任意一次看它的步骤、耗时、终态、停在第几步。" }
  };

  function render(section) {
    /* section 记住上一次选的那一块 —— init() 起的轮询调 load() → render()（不带参）
       必须重画同一块，不能每次跳回 4.1。 */
    if (SECTIONS[section]) { SECTION = section; }
    var host = document.getElementById("acx-host");
    if (!host) { return; }
    if (ERROR) {
      host.innerHTML = '<div class="acx-err"><b>读不到状态机记录</b><br>' + esc(ERROR)
        + "<br><br>⚠️ 「读不到」<b>不是</b>「没跑过」—— 前者是观测面坏了，后者是事实。</div>";
      return;
    }
    if (!DATA) {
      host.innerHTML = '<div class="acx-empty">正在读状态机记录…</div>';
      // 兜底自取数：万一 init() 因为脚本顺序/时序没被调到（页面直接深链进 4.x 时
      // 最容易发生），不能永远停在这句提示上。没有数据就发一次 fetch。
      if (!ERROR && !FETCHING) load();
      return;
    }
    var sec = SECTIONS[SECTION] ? SECTION : "content";
    host.innerHTML = "<h2>" + esc(SECTIONS[sec].title) + "</h2>"
      + '<p class="acx-sub">' + esc(SECTIONS[sec].hint) + "</p>"
      + bodyFor(sec);
    bind();
  }

  function bind() {
    var host = document.getElementById("acx-host");
    if (!host) { return; }
    var heads = host.querySelectorAll(".acx-run-head");
    for (var i = 0; i < heads.length; i++) {
      heads[i].onclick = (function (el) {
        return function () {
          var idx = el.getAttribute("data-i");
          OPEN[idx] = !OPEN[idx];
          render();
        };
      })(heads[i]);
    }
  }

  function load() {
    if (FETCHING) return;
    FETCHING = true;
    return fetch("/api/acx", { cache: "no-store" })
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

if (typeof window !== "undefined") { window.AcxView = AcxView; }