/* ============================================================
   semantics-view.js —— 3.6 语义资产（P0-C）
   
   WHY THIS EXISTS (2026-10-25，用户原话「他的语义在哪里？我没有在监测台看到」):
     规划侧一直按“这件东西对这个目标重要”办事，语义表也一直在盘上
     （config/numen/rdd-semantics/<uuid>.json），但监测台从没读过它。
     这一页只做一件事：把那张表原样摊开，让“凭什么这么判”能被对账。
   
   口径（与 semantics.mjs 一一对应，别在这里偷偷改语义）：
     * 只读：不写回、不重算 importance、不排序成“看起来像优先级”的样子；
     * 四态分开：目录不存在 / 没有文件 / 文件损坏 / 正常，各有各的文案；
     * 来源要写：source 为空显示「来源未知」，不美化成「派生自需求清单」；
     * IRRELEVANT 显示「已降级、未删除」——它不是被删了，只是不再服务当前目标。
   ============================================================ */
(function () {
  "use strict";
  var host = null;
  var last = null;

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function badge(kind, text) {
    return '<span class="badge ' + kind + '">' + esc(text) + '</span>';
  }

  function importanceBadge(v) {
    if (v === "IMPORTANT") return badge("bad", "IMPORTANT · 关键");
    if (v === "SECONDARY") return badge("warn", "SECONDARY · 次要");
    if (v === "IRRELEVANT") return badge("ok", "IRRELEVANT · 已降级未删除");
    return badge("warn", v || "UNKNOWN");
  }

  function noteCard(text, kind) {
    return '<div class="card"><div class="muted">' + esc(text) + '</div></div>';
  }

  function renderOne(c) {
    var h = '<div class="card"><div class="row"><b>' + esc(c.companionId.slice(0, 8)) + '</b>'
      + ' <span class="muted">' + esc(c.file || "") + '</span> '
      + (c.stale ? badge("warn", "STALE · 超过 10 分钟未重派生") : badge("ok", "较新"))
      + (c.state && c.state !== "OK" ? " " + badge("warn", c.state) : "")
      + '</div>';
    if (c.note) h += '<div class="muted">' + esc(c.note) + '</div>';
    if (!c.entries || !c.entries.length) {
      h += '<div class="muted">这个同伴没有语义条目（EMPTY 不是 0 件资产，是还没派生过）。</div></div>';
      return h;
    }
    h += '<table class="tbl"><thead><tr>'
      + '<th>assetKey</th><th>重要度</th><th>nature</th><th>服务目标</th><th>来源</th><th>替代</th>'
      + '</tr></thead><tbody>';
    for (var i = 0; i < c.entries.length; i++) {
      var e = c.entries[i];
      h += '<tr><td><code>' + esc(e.assetKey) + '</code></td>'
        + '<td>' + importanceBadge(e.importance) + '</td>'
        + '<td>' + esc(e.nature) + '</td>'
        + '<td class="muted">' + esc(e.servesGoal || "—") + '</td>'
        + '<td>' + (e.source ? esc(e.source) : badge("warn", "来源未知")) + '</td>'
        + '<td class="muted">' + (e.substitutes && e.substitutes.length ? esc(e.substitutes.join(" / ")) : "—") + '</td></tr>';
    }
    h += '</tbody></table></div>';
    return h;
  }

  function renderBody(data) {
    var h = "";
    if (!data || data.ok === false) {
      return noteCard("接口读不到：" + (data && data.error ? data.error : "未知错误") + "。这里显示的是「读不到」，不是「0 条」。");
    }
    if (data.state === "NO_DIRECTORY") {
      return noteCard("没有 rdd-semantics 目录 —— 说明还没有派生过语义（不是 0 条）。触发条件：生成过需求清单并换过一级目标。");
    }
    if (data.state === "NO_FILES") {
      return noteCard("目录在、但没有语义文件（尚未派生）。");
    }
    h += '<div class="card"><div class="row">'
      + badge(data.state === "OK" ? "ok" : "warn", data.state)
      + ' <span class="muted">共 ' + esc(String(data.totalCount || 0)) + ' 条 · ' + esc(String((data.companions || []).length)) + ' 个同伴 · 生成于 '
      + esc(data.generatedAt || "") + '</span></div>'
      + '<div class="muted">语义是**派生**：CRITICAL 需求 → IMPORTANT，其余 → SECONDARY，换目标后不再服务的键降为 IRRELEVANT（只降标记、不删除）。</div></div>';
    var cs = data.companions || [];
    for (var i = 0; i < cs.length; i++) h += renderOne(cs[i]);
    return h;
  }

  function paint(data) {
    last = data;
    if (!host) return;
    host.innerHTML = renderBody(data);
  }

  function fetchNow() {
    return fetch("/api/rdd-semantics", { cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(paint)
      .catch(function (e) { paint({ ok: false, error: String(e && e.message || e) }); });
  }

  window.SemanticsView = {
    init: function () {
      host = document.getElementById("semantics-host");
      if (!host) return;
      // 不要在这里改 hidden：显示/隐藏由 monitor-app 的 showHost(ext.host) 统一管，
      // 在这里 unhide 会让这一页出现在所有其它页签下面（经济页踩过同款）。
      fetchNow();
      // 语义换目标才会变，30 秒一次足够；比 RDD 事件流慢一个量级，避免白读盘。
      setInterval(fetchNow, 30000);
    },
    render: function () {
      if (!host) host = document.getElementById("semantics-host");
      if (last) paint(last); else fetchNow();
    },
    data: function () { return last; }
  };
}());
