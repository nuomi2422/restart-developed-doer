/* ============================================================
   WorldFactsView — ⑦ 世界与经济 / 7.1 单一世界 · 环境事实

   ★ 这一页是**骨架页**：架构（第三批 T3-01 / N1、N2）里有，
     数据源只接了一部分（rdd-assets / rdd-facts / rdd-history / rdd-ledger），
     而 N1 契约要求的 confidence / coverage / 三态 / 三级投影**结构上还没有**。

   ★ 所以这一页的骨架 = 三块：
     ① **接上了什么**：真实读数（事实分级 / 资产状态 / 死亡台账 / 血缘）
     ② **没接什么**：接线契约表，每行写清「需要什么 / 现在拿什么代替 / 怎么验」
     ③ **怎么接**：交接提示 —— 后来的 AI 照着做，这一页会自动翻状态

   ★ 「已接入 / 未接入」是**服务端运行时探测**出来的（见 world-facts.mjs 的 WIRING.probe），
     页面不写死。后来的 AI 把字段接上，这里自动翻绿，一行渲染代码都不用改。

   ★ 形状照 token/acx/experiment 三页：独立 view 自己 fetch，走常驻宿主 #world-facts-host。
   ============================================================ */

var WorldFactsView = (function () {
  "use strict";

  var DATA = null;
  var ERROR = "";
  var TIMER = null;
  var FETCHING = false;
  var POLL_MS = 15000;

  /* 事实清单的筛选状态（不持久化：这是「看一眼」的临时开关） */
  var filter = { type: "", dimension: "", age: "" };

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  /**
   * 转义**并**把 `**强调**` 变成粗体。
   * ★ 这些说明文字来自架构文档，里面大量用 `**` 强调。直接 esc 的话页面上
   *   会看到一堆星号 —— 那既难看，又把「哪句是关键」这个信息丢了。
   *   顺序很重要：先 esc（把 < > & 干掉）再替换星号，反过来就是 XSS 口子。
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
  /** ★ null 一律 ?，绝不 0（分页自己的纪律）。 */
  function num(v) { return (v == null || !isFinite(v)) ? "?" : String(v); }
  function shortId(id) { return id ? String(id).slice(0, 8) : "?"; }
  function nameHints(d) {
    var m = {};
    var list = (d && d.companions) || [];
    for (var i = 0; i < list.length; i++) m[list[i].id] = list[i];
    return m;
  }
  function companionLabel(d, cid) {
    var n = nameHints(d)[cid];
    if (n && n.nameHint) return n.nameHint;
    return shortId(cid);
  }

  /* ---- 数据是读不到还是旧了 ---- */
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
      ok: "四个事实来源都读到了",
      old: "注意：游戏和这些文件一起停的 —— 下面是过去的情况",
      bad: "⚠ 有事实来源读不到 —— 标了「未接入」的那几块是真的没有数据",
      gone: "⚠ 四个事实来源一个都读不到 —— 下面的数字全部无效"
    }[cls];
    var cov = d.coverage || {};
    var out = '<div class="acx-fresh acx-fresh-' + cls + '"><b>' + esc(head) + "</b>"
      + "<div>事实 <b>" + esc(cov.factsTotal) + "</b> 条（列出 " + esc(cov.factsListed)
      + "，更早的 " + esc(cov.factsDropped) + " 条只进统计）　·　资产状态 <b>" + esc(cov.historyTotal)
      + "</b> 条　·　死亡台账 <b>" + esc(cov.ledgerTotal) + "</b> 条　·　血缘 <b>" + esc(cov.lineageTotal) + "</b> 条</div>";
    if (f && f.monitor_newest_write) {
      out += "<div>最新写入 <code>" + esc(hhmm(f.monitor_newest_write)) + "</code>（UTC，距今 <b>"
        + esc(span(f.seconds_since_monitor_newest_write)) + "</b>）</div>";
    }
    if (f && f.verdict_zh) out += '<div class="acx-fresh-verdict">判读：' + esc(f.verdict_zh) + "</div>";
    return out + "</div>";
  }

  function renderSources(d) {
    var cov = d.coverage || {};
    var list = cov.sources || [];
    if (!list.length) { return ""; }
    var rows = list.map(function (s) {
      var mark = s.readable ? '<span class="sk-pill ok">已接入</span>' : '<span class="sk-pill gone">未接入</span>';
      var detail = s.readable
        ? (s.files + " 份文件 · " + s.companions + " 只同伴"
          + (s.unreadable ? " · <b>" + s.unreadable + " 份读不出</b>" : "")
          + (s.newestSeconds != null && isFinite(s.newestSeconds) ? " · 最新一份距今 " + span(s.newestSeconds) : ""))
        : esc(s.error || "读不到");
      return "<tr><td>" + esc(s.label) + "<br><small>" + esc(s.file) + "</small></td>"
        + "<td>" + mark + "</td><td>" + detail + "</td></tr>";
    }).join("");
    var missing = list.filter(function (s) { return !s.readable; });
    return "<h3>事实接入情况</h3>"
      + '<div class="sk-scroll"><table class="sk-table"><tr><th>来源</th><th>状态</th><th>这次读到什么</th></tr>'
      + rows + "</table></div>"
      + (missing.length
        ? '<div class="sk-warn-box"><b>⚠ 这几个没接上</b><div>' + esc(missing.map(function (s) { return s.label; }).join("、"))
          + "　—— 那不是「0 条事实」，是观测面没数据。</div></div>"
        : "")
      + '<div class="sk-note-box">' + escb(cov.note_zh || "") + "</div>";
  }

  /** ★ 这一页的核心表：每条契约现在的真实状态。 */
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
        + '<td class="sk-stand">' + escb(w.stand || "")
        + (w.probeError ? '<br><span class="sk-pill bad">判据执行失败：' + esc(w.probeError) + "</span>" : "")
        + "</td>"
        + '<td class="sk-verify">' + escb(w.verify || "") + "</td></tr>";
    }).join("");
    var pct = ws.total ? Math.round((ws.connected / ws.total) * 100) : 0;
    return "<h3>第三批接线契约"
      + '<span class="sk-note">已接入 ' + esc(ws.connected) + " / " + esc(ws.total) + " 条</span></h3>"
      + '<div class="sk-note-box">' + escb(ws.note_zh || "") + "</div>"
      + '<div class="sk-scroll"><table class="sk-table sk-wire">'
      + "<tr><th>包号</th><th>契约项</th><th>架构要求是什么</th><th>现在拿什么代替</th><th>怎么自测</th></tr>"
      + rows + "</table></div>"
      + '<div class="sk-next"><b>给下一个接手的 AI：</b>让上面任意一行翻绿，只需两件事 ——'
      + "① 数据里真的出现那个字段；② 把对应的 <code>probe</code> 改成能看见它（<code>world-facts.mjs</code> 的 <code>WIRING</code>）。"
      + "页面会自动跟着翻，<b>不要手改这里的状态文字</b>。"
      + "<ol>"
      + "<li>先只改数据源（让它真的写出字段），跑 <code>node --test tests/world-facts.test.mjs</code> 看红线还是否成立。</li>"
      + "<li>再改 <code>probe</code>。判据要能区分「字段缺失」和「字段是空/0」—— 那两件事不是一回事。</li>"
      + "<li><code>stand</code>（现在拿什么代替）如果是函数，让它跟着实测数据变；写死的话数据一变页面就在骗人。</li>"
      + "</ol>"
      + '<div class="sk-progress" style="width:100%"><i style="width:' + pct + '%"></i></div>'
      + "</div>";
  }

  function renderStats(d) {
    var g = d.grades || {};
    var age = (g.byAge || []);
    var fresh = 0, stale = 0, unknown = 0;
    for (var i = 0; i < age.length; i++) {
      if (/刚观测/.test(age[i].key)) fresh = age[i].count;
      else if (/已过期/.test(age[i].key)) stale = age[i].count;
      else unknown = age[i].count;
    }
    var ws = d.wiringSummary || {};
    var items = [
      { label: "世界事实", value: d.coverage.factsTotal, cls: "" },
      { label: "已过期（> " + Math.round(d.staleSeconds / 3600) + " 小时）", value: stale, cls: stale > 0 ? "warn" : "" },
      { label: "年龄未知", value: unknown, cls: unknown > 0 ? "warn" : "" },
      { label: "事实类型", value: (g.byType || []).length, cls: "" },
      { label: "维度", value: (g.byDimension || []).length, cls: "" },
      { label: "资产 LOST", value: countLost(d), cls: countLost(d) > 0 ? "bad" : "" },
      { label: "死亡已确认", value: countVerified(d) + " / " + (d.ledger || []).length, cls: countVerified(d) > 0 ? "ok" : "warn" },
      { label: "接线已接入", value: ws.connected + " / " + ws.total, cls: ws.connected ? "good" : "warn" },
    ];
    var cards = items.map(function (it) {
      return '<div class="sk-stat ' + (it.cls || "") + '"><div class="sk-stat-v">' + esc(it.value)
        + '</div><div class="sk-stat-sub">' + esc(it.label) + "</div></div>";
    }).join("");
    return "<h3>总览</h3><div class=\"sk-stats\">" + cards + "</div>";
  }
  function countLost(d) {
    var n = 0;
    for (var i = 0; i < (d.history || []).length; i++) if (d.history[i].state === "LOST") n += 1;
    return n;
  }
  function countVerified(d) {
    var n = 0;
    for (var i = 0; i < (d.ledger || []).length; i++) if (d.ledger[i].verified) n += 1;
    return n;
  }

  function tally(d, list, title, note) {
    var arr = d.grades ? (d.grades[list] || []) : [];
    if (!arr.length) {
      return "<h3>" + esc(title) + "</h3><div class=\"sk-empty\">这一维度一个值都没有。</div>";
    }
    var max = 0;
    for (var i = 0; i < arr.length; i++) max = Math.max(max, arr[i].count);
    var rows = arr.slice(0, 12).map(function (x) {
      var w = max > 0 ? Math.max(2, Math.round((x.count / max) * 100)) : 2;
      var na = /（未标注）/.test(x.key);
      return '<div class="sk-tally-row' + (na ? " na" : "") + '"><div class="sk-tally-k">' + esc(x.key)
        + '</div><div class="sk-tally-bar"><i style="width:' + w + '%"></i></div>'
        + '<div class="sk-tally-n">' + esc(x.count) + "</div></div>";
    }).join("");
    var more = arr.length > 12 ? '<div class="sk-sub">另有 ' + (arr.length - 12) + " 个取值没画（点上面的表看全部）。</div>" : "";
    return "<h3>" + esc(title) + '<span class="sk-note">' + esc(note || "") + "</span></h3>"
      + '<div class="sk-tally">' + rows + "</div>" + more;
  }

  function renderFacts(d) {
    var all = d.facts || [];
    var g = d.grades || {};
    function uniq(list) {
      var m = {}, out = [];
      for (var i = 0; i < list.length; i++) { if (!m[list[i]]) { m[list[i]] = 1; out.push(list[i]); } }
      return out;
    }
    var types = uniq((g.byType || []).map(function (x) { return x.key; }));
    var dims = uniq((g.byDimension || []).map(function (x) { return x.key; }));
    function opts(list, cur, allLabel) {
      return '<option value="">' + esc(allLabel) + '</option>'
        + list.map(function (v) {
          return '<option value="' + esc(v) + '"' + (v === cur ? " selected" : "") + ">" + esc(v) + "</option>";
        }).join("");
    }
    var rows = all.filter(function (f) {
      if (filter.type && f.type !== filter.type) return false;
      if (filter.dimension && f.dimension !== filter.dimension) return false;
      if (filter.age === "fresh" && f.stale === true) return false;
      if (filter.age === "stale" && f.stale !== true) return false;
      if (filter.age === "unknown" && f.observedAgeSeconds != null) return false;
      return true;
    });
    var body = rows.map(function (f) {
      var pos = (f.x == null && f.y == null && f.z == null)
        ? '<span class="sk-na">无坐标</span>' : esc(f.x + ", " + f.y + ", " + f.z);
      return "<tr><td>" + esc(companionLabel(d, f.companionId))
        + '<br><small>' + esc(shortId(f.companionId)) + " · 归属来自" + esc(f.companionFrom) + "</small></td>"
        + "<td>" + esc(f.type) + "</td>"
        + "<td>" + esc(f.dimension || '<span class="sk-na">未标注</span>') + "</td>"
        + "<td class=\"mono\">" + pos + "</td>"
        + "<td>" + esc(f.refreshPolicy || '<span class="sk-na">未标注</span>') + "</td>"
        + "<td>" + esc(f.status) + "</td>"
        + "<td>" + (f.observedAgeSeconds == null
          ? '<span class="sk-na">?</span>'
          : (f.stale
            ? '<span class="sk-pill old">已过期</span> ' + esc(span(f.observedAgeSeconds))
            : esc(span(f.observedAgeSeconds)))) + "</td>"
        + "<td>" + esc(f.resourceSummary || f.summary || "") + "</td></tr>";
    }).join("");
    return "<h3>事实清单"
      + '<span class="sk-note">当前 ' + rows.length + " / " + all.length + " 条"
      + (d.coverage.factsDropped ? "（另有 " + d.coverage.factsDropped + " 条更早的只进统计）" : "") + "</span></h3>"
      + '<div class="sk-sub">'
      + '按类型 <select id="wf-f-type">' + opts(types, filter.type, "全部类型") + "</select>　"
      + '按维度 <select id="wf-f-dim">' + opts(dims, filter.dimension, "全部维度") + "</select>　"
      + '按新鲜度 <select id="wf-f-age">'
      + '<option value="">全部</option>'
      + '<option value="fresh"' + (filter.age === "fresh" ? " selected" : "") + ">未过期</option>"
      + '<option value="stale"' + (filter.age === "stale" ? " selected" : "") + ">已过期</option>"
      + '<option value="unknown"' + (filter.age === "unknown" ? " selected" : "") + ">年龄未知</option>"
      + "</select></div>"
      + (body
        ? '<div class="sk-scroll"><table class="sk-table"><tr><th>同伴</th><th>类型</th><th>维度</th><th>坐标</th>'
          + "<th>刷新策略</th><th>状态</th><th>观测多久前</th><th>摘要</th></tr>" + body + "</table></div>"
        : '<div class="sk-empty">当前筛选下没有事实。<b>那不等于「世界里没有」</b>，只等于「这一批观测里没有」。</div>');
  }

  function renderAssets(d) {
    var rows = d.history || [];
    var body = rows.map(function (h) {
      var purpose = (!h.purpose || h.purpose === "UNKNOWN")
        ? '<span class="sk-pill gone">用途未接入</span>' : esc(h.purpose);
      return "<tr><td>" + esc(companionLabel(d, h.companionId)) + '<br><small>' + esc(shortId(h.companionId)) + "</small></td>"
        + "<td>" + esc(h.assetId) + "</td>"
        + "<td>" + purpose + "</td>"
        + "<td>" + (h.state === "LOST" ? '<span class="sk-pill bad">LOST</span>'
          : (h.state === "CURRENT" ? '<span class="sk-pill ok">CURRENT</span>' : esc(h.state))) + "</td>"
        + "<td class=\"mono\">" + esc(num(h.lastCount)) + "</td>"
        + "<td>" + (h.lastSeenAgeSeconds == null ? '<span class="sk-na">?</span>' : esc(span(h.lastSeenAgeSeconds))) + "</td>"
        + "<td class=\"mono\">" + (h.x == null ? '<span class="sk-na">无</span>'
          : esc(h.x + ", " + h.y + ", " + h.z) + (h.dimension ? "<br><small>" + esc(h.dimension) + "</small>" : "")) + "</td></tr>";
    }).join("");
    var purposeUnknown = 0;
    for (var i = 0; i < rows.length; i++) if (!rows[i].purpose || rows[i].purpose === "UNKNOWN") purposeUnknown += 1;
    return "<h3>资产状态"
      + '<span class="sk-note">' + rows.length + " 条 · 用途未接入 " + purposeUnknown + " 条</span></h3>"
      + (body
        ? '<div class="sk-scroll"><table class="sk-table"><tr><th>同伴</th><th>资产</th><th>用途</th><th>状态</th>'
          + "<th>上次数量</th><th>最后见到</th><th>位置</th></tr>" + body + "</table></div>"
          + '<div class="sk-note-box"><b>LOST 是「状态被标成丢了」，不是「确认丢了」。</b>'
          + "确认丢失要走死亡台账那条线，而它的绝大多数记录仍是 UNVERIFIED、evidence 为空 —— "
          + "两个表之间的对应关系目前<b>结构上就没有</b>。</div>"
        : '<div class="sk-empty">没有读到资产状态历史（rdd-history）。</div>');
  }

  function renderLedger(d) {
    var rows = d.ledger || [];
    var body = rows.map(function (l) {
      return "<tr><td>" + esc(companionLabel(d, l.companionId)) + "</td>"
        + "<td class=\"mono\">" + esc(num(l.seq)) + "</td>"
        + "<td>" + (l.deathAt ? esc(l.deathAt) : '<span class="sk-na">未记录</span>') + "</td>"
        + "<td class=\"mono\">" + esc(num(l.lostEntries)) + "</td>"
        + "<td>" + (l.verified
          ? '<span class="sk-pill ok">' + esc(l.state) + "</span>"
          : '<span class="sk-pill gone">' + esc(l.state) + "</span>") + "</td>"
        + "<td>" + (l.evidence ? esc(l.evidence) : '<span class="sk-na">空 —— 没有证据</span>') + "</td>"
        + "<td>" + (l.wallClockMillis == null ? '<span class="sk-na">?</span>'
          : esc(hhmm(new Date(l.wallClockMillis).toISOString()))) + "</td></tr>";
    }).join("");
    return "<h3>死亡台账"
      + '<span class="sk-note">' + rows.length + " 次 · 这一列答的是「这次死亡到底丢了什么、有没有证据」</span></h3>"
      + (body
        ? '<div class="sk-scroll"><table class="sk-table"><tr><th>同伴</th><th>序号</th><th>死亡位置</th>'
          + "<th>丢了几条</th><th>状态</th><th>证据</th><th>时刻（UTC）</th></tr>" + body + "</table></div>"
          + '<div class="sk-warn-box">★ <b>UNVERIFIED 就是「还没确认」。</b>它<b>不是</b>「丢了 0 条」也不是「丢了但没记」—— '
          + "这一列现在<b>答不出问题</b>，所以「死亡重要度 → 分级重规划」整层都还没接上（见上面接线表）。</div>"
        : '<div class="sk-empty">没有读到死亡台账（rdd-ledger）。</div>');
  }

  function renderLineage(d) {
    var rows = d.lineage || [];
    var body = rows.map(function (l) {
      return "<tr><td>" + (l.kind === "stages" ? '<span class="sk-pill ok">一级阶段</span>' : '<span class="sk-pill">二级小步</span>') + "</td>"
        + "<td>" + esc(companionLabel(d, l.companionId)) + "</td>"
        + "<td>" + esc(l.text || "") + "</td>"
        + "<td>" + (l.evidence ? esc(l.evidence) : '<span class="sk-na">未记录</span>') + "</td>"
        + "<td>" + (l.at == null ? '<span class="sk-na">?</span>' : esc(hhmm(new Date(l.at).toISOString()))) + "</td></tr>";
    }).join("");
    return "<h3>任务链血缘"
      + '<span class="sk-note">最近 ' + rows.length + " 条 · 这里记的是「这条事实<b>怎么来的</b>」，不是「它有多可信」</span></h3>"
      + (body
        ? '<div class="sk-scroll"><table class="sk-table"><tr><th>层级</th><th>同伴</th><th>内容</th>'
          + "<th>证据来源</th><th>记录时刻</th></tr>" + body + "</table></div>"
        : '<div class="sk-empty">没有读到血缘（rdd-facts）。</div>');
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
    var host = document.getElementById("world-facts-host");
    if (!host) { return; }
    if (ERROR) {
      host.innerHTML = '<div class="acx-err"><b>读不到环境事实</b><br>' + esc(ERROR)
        + "<br><br>⚠️「读不到」<b>不是</b>「世界里没有东西」。</div>";
      return;
    }
    if (!DATA) {
      host.innerHTML = '<div class="sk-empty">正在读环境事实（rdd-assets / rdd-facts / rdd-history / rdd-ledger）…</div>';
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
    host.innerHTML = '<div class="sk-head">单一世界 · 环境事实</div>'
      + '<p class="sk-sub">「世界是什么样」应该<b>只有一份</b>。现在这一份拆在四个文件里，'
      + "这一页把它们拼成同一个视图，并逐条回答第三批 N1/N2 契约里的问题<b>现在答不答得上</b>。"
      + "答不上的写「未接入」，<b>不写 0、不编默认值</b>。</p>"
      + renderFreshness(DATA)
      + empty
      + renderStats(DATA)
      + renderWiring(DATA)
      + renderSources(DATA)
      + tally(DATA, "byType", "事实分级 · 按类型", "架构里的「分级供给」—— 现在能分的第一维就是类型")
      + tally(DATA, "byDimension", "事实分级 · 按维度", "同伴与维度隔离是 N1 的验收项之一")
      + tally(DATA, "byRefreshPolicy", "事实分级 · 按刷新策略", "LAZY = 线索（用前要重核）；STATIC = 静态事实")
      + tally(DATA, "byAge", "事实分级 · 按观测年龄", "过期不当当前（N1）；年龄未知的单独一栏，不塞进「旧」")
      + renderFacts(DATA)
      + renderAssets(DATA)
      + renderLedger(DATA)
      + renderLineage(DATA)
      + renderCaveats(DATA);
  }

  function onEvent(ev) {
    var t = ev.target;
    if (!t || !t.id) { return; }
    if (t.id === "wf-f-type") { filter.type = t.value; render(); return; }
    if (t.id === "wf-f-dim") { filter.dimension = t.value; render(); return; }
    if (t.id === "wf-f-age") { filter.age = t.value; render(); return; }
  }

  function load() {
    if (FETCHING) return;
    FETCHING = true;
    return fetch("/api/world-facts", { cache: "no-store" })
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
    if (typeof document !== "undefined" && document.addEventListener) document.addEventListener("change", onEvent);
    load();
    if (!TIMER) TIMER = setInterval(load, POLL_MS);
  }

  return { render: render, load: load, init: init, filter: filter };
})();

if (typeof window !== "undefined") { window.WorldFactsView = WorldFactsView; }