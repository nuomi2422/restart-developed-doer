/* ============================================================
   EconomyView — ⑦ 世界与经济 / 7.2 经济与开关

   ★ 这一页也是**骨架页**，分两半：
     · 上半「经营侧」：**有真实数据**（acx 库、磁盘脚本、落盘记录、运行事件流）
     · 下半「配置侧」：**结构上还没有**（能不能关模块 / 限经验注入 / 禁目标 / 热切换）

   ★ 三条不许省的纪律（服务端同款，见 economy.mjs 文件头）：
     ① records.jsonl **不记 SUCCESS** ⇒ 「这里没有成功记录」≠「没有成功运行」。
        成功率只从 monitor/acx.jsonl 的 RUN_FINISHED 算，两个来源不可相加。
     ② 产量指标整层未接入，证据是「progress 恒为 {}」—— 页面列出 11 个字段逐个标，
        **不画一条空的产量曲线**（画出来就是拿 0 冒充没产量）。
     ③ 经济配置一个文件都没读到 ⇒ **不显示任何默认值**。编一个「模块默认全开」出来
        就是在骗人，而这一页存在的全部意义就是不骗人。

   ★ 「已接入 / 未接入」由服务端运行时探测（economy.mjs 的 WIRING.probe），
     后来的 AI 接上数据源，这一行自动翻绿，渲染代码不用动。

   ★ 形状照前几页：独立 view 自己 fetch，走常驻宿主 #economy-host。
   ============================================================ */

var EconomyView = (function () {
  "use strict";

  var DATA = null;
  var ERROR = "";
  var TIMER = null;
  var FETCHING = false;
  var POLL_MS = 15000;
  var showAllScripts = false;

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  /**
   * 转义**并**把 `**强调**` 变粗体。这些文字来自架构文档、大量用 `**` 强调，
   * 直接 esc 的话页面上是一堆星号（既难看，又丢了「哪句是关键」）。
   * 顺序：先 esc 再替换星号 —— 反过来就是 XSS 口子。
   */
  function escb(s) {
    return esc(s).replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
  }
  function hhmm(iso) {
    if (!iso) { return "?"; }
    var m = String(iso).match(/T(\d\d):(\d\d):(\d\d)/);
    return m ? (m[1] + ":" + m[2] + ":" + m[3]) : String(iso).slice(0, 19);
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
  /** ★ null 一律 ?，绝不 0。 */
  function num(v) { return (v == null || !isFinite(v)) ? "?" : String(v); }
  function ms(v) {
    if (v == null) { return "?"; }
    return v >= 1000 ? (Math.round(v / 100) / 10) + " 秒" : v + " 毫秒";
  }

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
      ok: "AC 侧四个来源都读到了",
      old: "注意：游戏和这些文件一起停的 —— 下面是过去的情况",
      bad: "⚠ 有来源没接上 —— 标了「未接入」的那几块是真的没有数据",
      gone: "⚠ AC 侧一个来源都读不到 —— 下面的数字全部无效"
    }[cls];
    var cov = d.coverage || {};
    var out = '<div class="acx-fresh acx-fresh-' + cls + '"><b>' + esc(head) + "</b>"
      + "<div>脚本 <b>" + esc(cov.scriptsTotal) + "</b> 个（列出 " + esc(cov.scriptsListed)
      + "，更冷的 " + esc(cov.scriptsDropped) + " 个没列）</div>";
    var rows = (cov.sources || []).map(function (s) {
      return (s.readable ? "✓ " : "✗ ") + esc(s.label);
    });
    out += "<div>来源：" + rows.join("　·　") + "</div>";
    if (f && f.verdict_zh) out += '<div class="acx-fresh-verdict">判读：' + esc(f.verdict_zh) + "</div>";
    return out + "</div>";
  }

  function renderStats(d) {
    var t = d.totals || {};
    var ws = d.wiringSummary || {};
    var srcs = d.coverage ? d.coverage.sources : [];
    var eventsReadable = false;
    for (var i = 0; i < srcs.length; i++) if (srcs[i].key === "events") eventsReadable = srcs[i].readable;
    var items = [
      { label: "脚本（库 ∪ 磁盘 ∪ 日志）", value: t.scripts, cls: "" },
      { label: "磁盘 stable 脚本", value: t.onDiskStable, cls: "" },
      { label: "跑过的次数", value: t.runStarted, cls: "" },
      { label: "成功率（只从事件流算）", value: eventsReadable && t.runStarted ? Math.round((t.runSuccess / t.runStarted) * 1000) / 10 + "%" : "未接入", cls: eventsReadable && t.runStarted ? "ok" : "na" },
      { label: "落盘记录条数", value: t.recordsRead, cls: "" },
      { label: "落盘里的 SUCCESS", value: t.recordsSuccess, cls: t.recordsSuccess ? "ok" : "warn" },
      { label: "72 号八脚本已实现", value: d.rosterFound + " / 8", cls: d.rosterFound ? "good" : "warn" },
      { label: "接线已接入", value: ws.connected + " / " + ws.total, cls: ws.connected ? "good" : "warn" },
    ];
    var cards = items.map(function (it) {
      var na = it.cls === "na";
      return '<div class="sk-stat ' + (na ? "na " : "") + (it.cls || "") + '">'
        + '<div class="sk-stat-v' + (na ? " sk-na" : "") + '">' + esc(it.value) + '</div>'
        + '<div class="sk-stat-sub">' + esc(it.label) + "</div></div>";
    }).join("");
    return "<h3>总览</h3><div class=\"sk-stats\">" + cards + "</div>"
      + '<div class="sk-warn-box"><b>⚠ 为什么「成功率」只有一个来源？</b><div>'
      + "落盘日志 <code>records.jsonl</code> 实测 <b>只记 PAUSED / FAIL / TIMEOUT</b>，SUCCESS 一条都不落 —— "
      + "所以「落盘里没有成功记录」<b>不是</b>「没有成功运行」。成功率只能从 "
      + "<code>monitor/acx.jsonl</code> 的 RUN_FINISHED 算，而<b>这两个来源不能相加</b>（同一批运行的两个视图）。</div></div>";
  }

  /** ★ 接线契约表 —— 与「单一世界」页同一套形状。 */
  function renderWiring(d) {
    var list = d.wiring || [];
    var ws = d.wiringSummary || {};
    var rows = list.map(function (w) {
      var mark = w.connected
        ? '<span class="sk-pill ok">已接入</span>'
        : '<span class="sk-pill gone">未接入</span>';
      return "<tr><td><span class=\"sk-owner\">" + esc(w.owner) + "</span></td>"
        + "<td><b>" + esc(w.name) + "</b><br>" + mark + "</td>"
        + '<td class="sk-need">' + escb(w.need) + "</td>"
        + '<td class="sk-stand">' + escb(w.stand || "") + "</td>"
        + '<td class="sk-verify">' + escb(w.verify || "") + "</td></tr>";
    }).join("");
    return "<h3>经济与开关 · 接线契约"
      + '<span class="sk-note">已接入 ' + esc(ws.connected) + " / " + esc(ws.total) + " 条</span></h3>"
      + '<div class="sk-note-box">' + escb(ws.note_zh || "") + "</div>"
      + '<div class="sk-scroll"><table class="sk-table sk-wire">'
      + "<tr><th>包号</th><th>契约项</th><th>架构要求是什么</th><th>现在拿什么代替</th><th>怎么自测</th></tr>"
      + rows + "</table></div>"
      + '<div class="sk-next"><b>给下一个接手的 AI：</b>'
      + "① <b>配置类</b>（模块开关 / 经验注入上限 / 目标禁用 / 版本）：把配置写到 "
      + "<code>config/numen/</code> 下候选文件名之一（页面会把试过的名字都列出来），"
      + "带对应的键，然后改 <code>economy.mjs</code> 里那条的 <code>probe</code>。"
      + "② <b>产量类</b>：让运行时把 11 个字段写进 <code>records.jsonl</code> 的 <code>progress</code> —— "
      + "<b>判据要能区分「字段缺失」和「字段是 0」</b>，那两件事不是一回事。"
      + "<ol>"
      + "<li>不要手改这一页的状态文字；状态是探测出来的。</li>"
      + "<li>不要为了让它变绿而往数据里塞占位值 —— 那就把「未接入」变成假接入了。</li>"
      + "<li>改完跑 <code>node --test tests/economy.test.mjs</code>。</li>"
      + "</ol></div>";
  }

  /** 产量字段表：11 个逐个列，全未接入时逐条给出证据。 */
  function renderProgress(d) {
    var fields = d.progressFields || [];
    var seen = 0;
    for (var i = 0; i < fields.length; i++) if (fields[i].seen) seen += 1;
    var rows = fields.map(function (f) {
      return "<tr><td class=\"mono\"><code>" + esc(f.id) + "</code></td>"
        + "<td>" + esc(f.zh) + "</td>"
        + "<td>" + (f.seen ? '<span class="sk-pill ok">已接入</span>' : '<span class="sk-pill gone">未接入</span>') + "</td>"
        + '<td class="sk-stand">' + escb(f.evidence) + "</td></tr>";
    }).join("");
    return "<h3>产量字段 · AcxProgress 的 11 个名字"
      + '<span class="sk-note">已接入 ' + seen + " / " + fields.length + " 个</span></h3>"
      + '<div class="sk-note-box">这 11 个是 72 号文档实测出来的<b>唯一</b>产量落点 —— '
      + "「AcxProgress.FIELDS 只有 11 个，产量指标必须落进这 11 个名字」。"
      + "一个都没接上时，本页<b>不画产量曲线</b>：画一条贴着 0 的线，就是在拿「0」冒充「没有产量」。</div>"
      + '<div class="sk-scroll"><table class="sk-table"><tr><th>字段</th><th>含义</th><th>状态</th><th>证据</th></tr>'
      + rows + "</table></div>";
  }

  function renderRoster(d) {
    var rows = d.roster || [];
    var body = rows.map(function (r) {
      var mark = r.present
        ? (r.runStarted > 0 ? '<span class="sk-pill ok">已实现 · 跑过</span>' : '<span class="sk-pill warn">在库但没跑过</span>')
        : '<span class="sk-pill gone">未实现</span>';
      return "<tr><td>" + esc(r.order) + "</td>"
        + "<td class=\"mono\"><code>" + esc(r.id) + "</code></td>"
        + "<td>" + escb(r.mechanism) + "</td>"
        + "<td>" + mark + "</td>"
        + '<td class="sk-stand">' + escb(r.blocker) + "</td></tr>";
    }).join("");
    return "<h3>72 号 · AC 经营脚本第一批（八个）"
      + '<span class="sk-note">实测命中 ' + esc(d.rosterFound) + " / 8 个</span></h3>"
      + '<div class="sk-note-box">这一列的「机制假设」与「阻塞原因」来自 72 号规划文档，'
      + "<b>不是本页发明的</b>；状态由「acx 库里有没有这个名字」<b>探测</b>得出。"
      + "八个都还没实现 —— 也就是说，<b>她现在不会自己种田、不会自己吃饭、不会自己睡</b>。</div>"
      + '<div class="sk-scroll"><table class="sk-table"><tr><th>顺序</th><th>脚本</th><th>机制假设</th>'
      + "<th>状态</th><th>已知阻塞</th></tr>" + body + "</table></div>";
  }

  function renderScripts(d) {
    var list = d.scripts || [];
    var shown = showAllScripts ? list : list.slice(0, 25);
    var body = shown.map(function (s) {
      var inLib = s.inLibrary
        ? '<span class="sk-pill ok">库</span>' : '<span class="sk-pill">仅日志</span>';
      var stable = s.onDiskStable
        ? '<span class="sk-pill ok">stable</span>'
        : '<span class="sk-pill muted">非 stable</span>';
      return "<tr><td class=\"mono\">" + esc(s.name) + "</td>"
        + "<td>" + inLib + " " + stable + "</td>"
        + "<td class=\"mono\">" + esc(s.runStarted) + "</td>"
        + "<td class=\"mono\">" + esc(s.runSuccess) + "</td>"
        + "<td class=\"mono\">" + esc(s.runFail) + "</td>"
        + "<td>" + (s.successRate == null ? '<span class="sk-na">没跑过</span>' : esc(s.successRate) + "%") + "</td>"
        + "<td class=\"mono\">" + esc(s.records) + "</td>"
        + "<td class=\"mono\">" + esc(s.stepFailed) + "</td>"
        + "<td>" + (s.lastAt ? esc(hhmm(s.lastAt)) : '<span class="sk-na">?</span>') + "</td>"
        + "<td>" + (s.lastStatus ? esc(s.lastStatus) : '<span class="sk-na">?</span>') + "</td>"
        + '<td class="sk-stand">' + esc(s.lastError || "") + "</td></tr>";
    }).join("");
    return "<h3>脚本台账"
      + '<span class="sk-note">当前显示 ' + shown.length + " / " + list.length + " 个</span></h3>"
      + '<div class="sk-sub">「跑 / 成功 / 失败」来自 <code>monitor/acx.jsonl</code>；'
      + "「落盘」来自 <code>records.jsonl</code>。<b>两列不是同一个口径，不能相加</b>；"
      + "「在库」也不等于「是可自动批准的 stable 脚本」。</div>"
      + (body
        ? '<div class="sk-scroll"><table class="sk-table"><tr><th>脚本</th><th>位置</th><th>跑</th>'
          + "<th>成功</th><th>失败</th><th>成功率</th><th>落盘</th><th>失败步骤</th><th>最近</th>"
          + "<th>最近终态</th><th>最近报错</th></tr>" + body + "</table></div>"
          + (list.length > 25
            ? '<button class="sk-chip" data-eco-all="1">' + (showAllScripts ? "只看前 25 个" : "显示全部 " + list.length + " 个") + "</button>"
            : "")
        : '<div class="sk-empty">既没读到脚本库，也没读到磁盘脚本 —— 下面没有脚本可列。</div>');
  }

  function renderCaveats(d) {
    var out = "<h3>本页口径说明</h3><div class=\"sk-caveats\">";
    var list = d.caveats || [];
    for (var i = 0; i < list.length; i++) {
      out += '<div class="sk-caveat"><b>·</b> ' + escb(list[i].note_zh) + "</div>";
    }
    return out + "</div>";
  }

  function render() {
    var host = document.getElementById("economy-host");
    if (!host) { return; }
    if (ERROR) {
      host.innerHTML = '<div class="acx-err"><b>读不到经济与开关</b><br>' + esc(ERROR)
        + "<br><br>⚠️「读不到」<b>不是</b>「没有经营」。</div>";
      return;
    }
    if (!DATA) {
      host.innerHTML = '<div class="sk-empty">正在读经济与开关（acx 库 / 磁盘脚本 / 落盘记录 / 运行事件流）…</div>';
      if (!FETCHING) load();
      return;
    }
    if (DATA.ok === false) {
      host.innerHTML = '<div class="acx-err">' + esc(DATA.error || "未知错误") + "</div>";
      return;
    }
    var reasons = DATA.emptyReasons || [];
    var empty = reasons.length
      ? '<div class="sk-warn-box"><b>⚠ 这一页有来源没接上</b><div>'
        + reasons.map(function (r) { return esc(r); }).join("<br>") + "</div></div>"
      : "";
    host.innerHTML = '<div class="sk-head">经济与开关</div>'
      + '<p class="sk-sub">上半是<b>经营侧</b>：她现在有哪些脚本、跑了多久、成败如何 —— 这部分有真实读数。'
      + "下半是<b>配置侧</b>（能不能关模块、限经验注入、禁目标）："
      + "那是第三批 T3-07 的「经济/开关装置」，<b>目前结构上还没有</b>，所以整块是「未接入」。"
      + "凡是没接上的都写「未接入」，<b>不写 0、不编默认值</b>。</p>"
      + renderFreshness(DATA)
      + empty
      + renderStats(DATA)
      + renderWiring(DATA)
      + renderProgress(DATA)
      + renderRoster(DATA)
      + renderScripts(DATA)
      + renderCaveats(DATA);
  }

  function onEvent(ev) {
    var t = ev.target;
    if (!t || !t.closest) { return; }
    if (t.closest("[data-eco-all]")) { showAllScripts = !showAllScripts; render(); return; }
  }

  function load() {
    if (FETCHING) return;
    FETCHING = true;
    return fetch("/api/economy", { cache: "no-store" })
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
    if (typeof document !== "undefined" && document.addEventListener) document.addEventListener("click", onEvent);
    load();
    if (!TIMER) TIMER = setInterval(load, POLL_MS);
  }

  return { render: render, load: load, init: init };
})();

if (typeof window !== "undefined") { window.EconomyView = EconomyView; }