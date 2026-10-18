/* ============================================================
   ExperienceLibraryView — 经验库「按内容」视图
   回答的是「经验库里到底装了什么」，而不只是「有多少条」。

   ★ 为什么单独一页（owner 2026-10-03：「经验库很乱、难以识别」）：
     断点页只给条数，于是 66 条看着像「知识很多」。实际上只有 24 个不同标题，
     64% 是重复的 —— 三个同伴各存了一份同样的种子数据。
     「条数」与「知识量」在这套库里不是一回事，只给条数就等于骗人。
   ★ 形状照 experience-chain-view.js：独立 view 自己 fetch，不走 SSE，
     所以暂停刷新时它照样显示最后一次读数（那是有用的数据，不是实时数据）。
   ============================================================ */

var ExperienceLibraryView = (function () {
  "use strict";

  var DATA = null;      // 最后一次读数
  var ERROR = "";       // 读不到的原因（如实显示，不留空白页）
  var OPEN = {};        // 哪些条目展开了
  var FILTER = "";      // 标题过滤

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  function clip(s, n) {
    var t = String(s == null ? "" : s);
    return t.length <= n ? t : t.slice(0, n) + "…";
  }
  function bytes(n) {
    if (n == null) return "?";
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1048576).toFixed(1) + " MB";
  }

  // ---------- 三句大白话结论（owner 要的就是这个，不藏在数据里） ----------
  function renderPlain(d) {
    if (!d.plain || !d.plain.length) { return ""; }
    return '<div class="expl-plain">' + d.plain.map(function (p) {
      return "<div>" + esc(p) + "</div>";
    }).join("") + "</div>";
  }

  // ---------- 文件清单：形状冲突必须显式标出来 ----------
  function renderFiles(d) {
    if (!d.files || !d.files.length) {
      return '<div class="expl-empty">配置根目录里没有 experience-&lt;uuid&gt;.jsonl 这样的经验库文件。</div>';
    }
    var rows = d.files.map(function (f) {
      // ★ readAsZero：条目在那儿但程序读不进去。这是「0 有三种含义」里最危险的那种，
      //   所以单独一行红着说，不许混在普通行里。
      var badge = f.readAsZero
        ? '<span class="expl-badge bad">程序读到 0 条</span>'
        : '<span class="expl-badge ok">程序能读</span>';
      return '<tr class="' + (f.readAsZero ? "row-bad" : "") + '">'
        + "<td><code>" + esc(f.file) + "</code></td>"
        + "<td>" + esc(f.shapeZh) + "</td>"
        + '<td class="num">' + f.entries + "</td>"
        + "<td>" + bytes(f.bytes) + "</td>"
        + "<td>" + badge + "</td>"
        + "<td>" + (f.error ? '<span class="expl-bad-text">' + esc(f.error) + "</span>" : "") + "</td>"
        + "</tr>";
    }).join("");

    return '<h3>库文件（' + d.files.length + ' 个）</h3>'
      + '<table class="expl-table"><thead><tr>'
      + "<th>文件</th><th>形状</th><th>条目</th><th>大小</th><th>程序能读吗</th><th>问题</th>"
      + "</tr></thead><tbody>" + rows + "</tbody></table>"
      + '<p class="expl-note">「整文件一个数组」这种形状，程序逐行读时会整条链抛异常并被跳过 '
      + "⇒ 那里的条目<b>代码读不到</b>。上表把这种文件单独标红，别把它的条数当知识量。</p>";
  }

  // ---------- 一条经验的七项（可展开看原文） ----------
  function renderSeven(e) {
    var keys = Object.keys(e.seven);
    var rows = keys.map(function (k) {
      var s = e.seven[k];
      var v = s.value
        ? '<div class="expl-seven-value">' + esc(s.value) + "</div>"
        : '<div class="expl-seven-empty">（没填）</div>';
      return '<div class="expl-seven-row' + (s.filled ? "" : " is-empty") + '">'
        + '<span class="expl-seven-zh">' + esc(s.zh) + "</span>" + v + "</div>";
    }).join("");
    return '<div class="expl-seven">' + rows + "</div>";
  }

  // ---------- 按内容聚合的条目列表 ----------
  function renderByTitle(d) {
    var list = d.byTitle || [];
    if (!list.length) {
      return '<div class="expl-empty">经验库读到 0 条内容。'
        + '<br>⚠️ 「读到 0 条」有三种可能：真的没有 / 采样窗口没覆盖 / <b>程序读不进去</b>。'
        + "上面那张表能分开这三种。</div>";
    }
    var shown = list.filter(function (e) {
      if (!FILTER) { return true; }
      return (e.title || "").toLowerCase().indexOf(FILTER.toLowerCase()) >= 0;
    });

    var cards = shown.map(function (e, i) {
      var idx = list.indexOf(e);
      var open = !!OPEN[idx];
      var flags = [];
      // ★ 三件要让人一眼看见的事：重复在哪几个库 / 类型冲突 / 七项填了几
      if (e.companions > 1) {
        flags.push('<span class="expl-badge dup">出现在 ' + e.companions + " 个同伴的库</span>");
      }
      if (e.typeConflict) {
        flags.push('<span class="expl-badge bad">类型不一致：'
          + esc((e.typesSeen || []).join(" / ")) + "</span>");
      }
      if (e.sevenComplete) {
        flags.push('<span class="expl-badge ok">七项填齐</span>');
      } else {
        flags.push('<span class="expl-badge warn">七项填了 '
          + e.sevenFilled + "/7</span>");
      }
      if (e.retracted) { flags.push('<span class="expl-badge bad">已撤回</span>'); }
      if (e.verifiedAt > 0) {
        flags.push('<span class="expl-badge ok">验证过 ' + e.verifiedCount + " 次</span>");
      }
      if (e.maturity) {
        flags.push('<span class="expl-badge">' + esc(e.maturity) + "</span>");
      }

      return '<div class="expl-card' + (open ? " open" : "") + '">'
        + '<div class="expl-card-head" data-idx="' + idx + '">'
        + '<span class="expl-caret">' + (open ? "▾" : "▸") + "</span>"
        + '<span class="expl-title">' + esc(e.title) + "</span>"
        + '<span class="expl-flags">' + flags.join("") + "</span>"
        + "</div>"
        + (open ? '<div class="expl-card-body">'
          + '<div class="expl-meta">'
          + "<span>类型：" + esc(e.type || "（空）") + "</span>"
          + "<span>优先级：" + (e.priority == null ? "?" : e.priority) + "</span>"
          + "<span>来源文件：<code>" + esc(e.primaryFile || "?") + "</code></span>"
          + "</div>"
          + renderSeven(e)
          + "</div>" : "")
        + "</div>";
    }).join("");

    return '<h3>内容（' + list.length + ' 份不同内容'
      + (FILTER ? "，筛出 " + shown.length + " 份" : "") + "）</h3>"
      + '<input class="expl-filter" id="expl-filter" placeholder="按标题筛…" value="'
      + esc(FILTER) + '">'
      + (FILTER && !shown.length ? '<div class="expl-empty">没有匹配「' + esc(FILTER) + '」的。</div>' : "")
      + (cards || '<div class="expl-empty">没有匹配的内容。</div>');
  }

  // ---------- 总览 ----------
  function renderTotals(d) {
    var t = d.totals || {};
    var cards = [
      { k: "账面条数", v: t.totalEntries, hint: "磁盘上数出来的" },
      { k: "不同内容", v: t.distinctTitles, hint: "★ 这才是知识量" },
      { k: "重复率", v: t.duplicateRatio + "%", hint: "同一条存进几个同伴的库" },
      { k: "程序读不到", v: t.unreadableEntries, hint: "条目在那儿但代码读到 0 条" },
      { k: "七项填齐", v: t.sevenComplete, hint: "七项都非空" },
    ];
    return '<div class="expl-cards">'
      + cards.map(function (c) {
        return '<div class="expl-stat"><div class="expl-stat-k">' + esc(c.k) + "</div>"
          + '<div class="expl-stat-v">' + esc(c.v) + "</div>"
          + '<div class="expl-stat-h">' + esc(c.hint) + "</div></div>";
      }).join("")
      + "</div>";
  }

  function render() {
    var host = document.getElementById("explib-host");
    if (!host) { return; }
    if (ERROR) {
      host.innerHTML = '<div class="expl-err"><b>读不到经验库</b><br>' + esc(ERROR)
        + '<br><br>⚠️ 「读不到」<b>不是</b>「经验库是空的」—— 前者是观测面坏了，后者是事实。'
        + "两者必须分开，否则你会以为经验库没东西。</div>";
      return;
    }
    if (!DATA) {
      host.innerHTML = '<div class="expl-empty">正在读经验库…</div>';
      return;
    }
    var d = DATA;
    host.innerHTML = '<h2>经验库 · 按内容</h2>'
      + (d.ok === false
        ? '<div class="expl-err">' + esc(d.error || "未知错误") + "</div>"
        : renderPlain(d) + renderTotals(d) + renderFiles(d) + renderByTitle(d));
    bind();
  }

  // ---------- 交互 ----------
  function bind() {
    var host = document.getElementById("explib-host");
    if (!host) { return; }
    var heads = host.querySelectorAll(".expl-card-head");
    for (var i = 0; i < heads.length; i++) {
      heads[i].onclick = (function (el) {
        return function () {
          var idx = el.getAttribute("data-idx");
          OPEN[idx] = !OPEN[idx];
          render();
        };
      })(heads[i]);
    }
    var f = document.getElementById("expl-filter");
    if (f) {
      f.oninput = function () {
        FILTER = f.value || "";
        // 重绘后焦点会丢，输入框体验很差 ⇒ 重新聚焦并把光标放到末尾
        var pos = FILTER.length;
        render();
        var f2 = document.getElementById("expl-filter");
        if (f2) { f2.focus(); try { f2.setSelectionRange(pos, pos); } catch (e) { } }
      };
    }
  }

  function load() {
    return fetch("/api/experience-library", { cache: "no-store" })
      .then(function (r) {
        if (!r.ok) { throw new Error("HTTP " + r.status); }
        return r.json();
      })
      .then(function (j) {
        DATA = j;
        ERROR = "";
        render();
      })
      .catch(function (e) {
        ERROR = String(e && e.message ? e.message : e);
        render();
      });
  }

  return {
    render: render,
    load: load,
    init: function () { load(); },
  };
})();

if (typeof window !== "undefined") { window.ExperienceLibraryView = ExperienceLibraryView; }