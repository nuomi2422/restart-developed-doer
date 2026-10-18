/* chain-view.js · 经验链可见面（2026-10-02 新增）
 *
 * 与 numen-adapter.js 的 SSE 数据流**完全独立**：这一页自己 fetch /api/chain。
 * 理由：经验链里有一半证据在**游戏没在跑的时候也该看得到**（经验库落盘、待办文件），
 * 走 SSE 的话游戏一关整页就空了 —— 而那正是最需要看的时候。
 *
 * ★ 这一页是**体检报告**，不是仪表盘：
 *   断点卡片置顶（先看见问题），数据卡在下（再看细节）。
 *   断点分两类：measured（有真实数字，标了 measured）/ static（属于游戏侧代码的静态事实，
 *   标了 static 并列出 file:line 证据）。**不假装是实测。**
 */
(function () {
  var state = { data: null, loading: false, err: "", at: "" };

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  function card(t, b, cls) {
    return '<div class="card ' + (cls || "") + '"><div class="card-title">' + esc(t) + "</div>" + b + "</div>";
  }
  function kv(k, v, cls) {
    return '<div class="kv"><span class="k">' + esc(k) + '</span><span class="v ' + (cls || "") + '">' + esc(v) + "</span></div>";
  }
  function n(x) { return x == null ? "—" : String(x); }

  function fetchChain() {
    if (state.loading) return;
    state.loading = true;
    fetch("/api/chain", { cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        state.data = j;
        state.err = j && j.ok === false ? String(j.error || "后端返回失败") : "";
        state.at = new Date().toLocaleTimeString();
        state.loading = false;
        if (ChainView.onUpdate) ChainView.onUpdate();
      })
      .catch(function (e) {
        state.err = "拉不到 /api/chain：" + e.message + "（监测台后端在跑吗？端口 8776）";
        state.loading = false;
        if (ChainView.onUpdate) ChainView.onUpdate();
      });
  }

  // ── 断点卡片：置顶 ──────────────────────────────────────────────
  function gapCard(g) {
    var isBlocker = g.severity === "blocker";
    var head = '<div class="chain-gap ' + (isBlocker ? "blocker" : "warn") + '">'
      + '<div class="chain-gap-head"><span class="sev">' + (isBlocker ? "断" : "疑") + "</span>"
      + '<span class="gid">' + esc(g.id) + "</span>"
      + '<span class="gtitle">' + esc(g.title) + "</span></div>";
    var body = "<p>" + esc(g.detail) + "</p>";
    if (g.consequence) body += '<p class="cons">' + esc(g.consequence) + "</p>";
    if (g.mismatch) {
      body += '<div class="mm"><div class="mm-col"><b>学习者出（七字段）</b><code>'
        + esc((g.mismatch.learnerSeven || []).join(" · ")) + "</code></div>"
        + '<div class="mm-col"><b>experience_learn 要（十字段）</b><code>'
        + esc((g.mismatch.experienceLearnTen || []).join(" · ")) + "</code></div>"
        + '<div class="mm-verdict">' + (g.mismatch.mappingExists ? "有映射" : "★ 全仓无映射代码") + "</div></div>";
    }
    if (g.conflicts && g.conflicts.length) {
      body += '<div class="conflicts">';
      g.conflicts.forEach(function (c) {
        body += '<div class="conflict"><b>' + esc(c.file) + "</b>"
          + '<div>' + kv("磁盘上", c.onDisk + " 条") + kv("代码读到", c.readableByStore + " 条", "bad") + "</div>"
          + "<p>" + esc(c.why) + "</p><p>" + esc(c.consequence) + "</p>"
          + '<p class="fix">' + esc(c.fix) + "</p></div>";
      });
      body += "</div>";
    }
    if (g.measured) {
      body += '<div class="measured"><b>实测</b><code>' + esc(JSON.stringify(g.measured)) + "</code></div>";
    } else {
      body += '<div class="measured static-note"><b>口径</b>这是游戏侧代码的静态事实，本页读不到运行时数字（下方列了源码位置）</div>';
    }
    if (g.evidence && g.evidence.length) {
      body += '<details><summary>证据（' + g.evidence.length + " 条）</summary><ul class=\"ev\">"
        + g.evidence.map(function (e) { return "<li>" + esc(e) + "</li>"; }).join("") + "</ul></details>";
    }
    return head + body + "</div>";
  }

  function gapsSection() {
    var d = state.data;
    if (!d || !d.gaps) return card("断点清单", '<div class="empty">尚未加载</div>');
    var blockers = d.gaps.filter(function (g) { return g.severity === "blocker"; });
    var warns = d.gaps.filter(function (g) { return g.severity !== "blocker"; });
    var h = '<div class="chain-summary">'
      + '<div class="cs blocker"><b>' + blockers.length + "</b><span>断（走不通）</span></div>"
      + '<div class="cs warn"><b>' + warns.length + "</b><span>疑（有风险）</span></div>"
      + '<div class="cs ok"><b>' + d.gaps.length + "</b><span>本页共列</span></div></div>";
    h += '<p class="audit-gap">这一页<b>只回答一个问题：经验链每一段通不通</b>。'
      + "「断」= 代码里根本不存在这一步或数据读不出来；「疑」= 通了但有已知风险。"
      + "每条都能展开看证据（源码位置或实测数字）。</p>";
    return card("断点清单 · " + d.gaps.length + " 条（先看这个）",
      h + '<div class="gaps">' + d.gaps.map(gapCard).join("") + "</div>", "wide");
  }

  // ── 数据卡 ──────────────────────────────────────────────────────
  function deathCard() {
    var x = state.data.death;
    var h = kv("死亡事件总数", n(x.total)) + kv("带结构化字段", n(x.withStructured), x.withStructured ? "" : "bad")
      + kv("旧形状（旧 jar 写的）", n(x.legacyShape), x.legacyShape ? "warn" : "")
      + kv("饿死（type=starvation_death）", n(x.starvationDeath), x.starvationDeath ? "warn" : "");
    if (x.deathKindDist && x.deathKindDist.length) {
      h += '<div class="dist">' + x.deathKindDist.map(function (d) {
        return '<span class="chip">' + esc(d.kind) + " ×" + d.n + "</span>";
      }).join("") + "</div>";
    }
    if (x.structured && x.structured.length) {
      h += '<table class="tbl"><thead><tr><th>时间</th><th>type</th><th>causeId</th><th>causeMsg</th><th>kind</th><th>attacker</th></tr></thead><tbody>'
        + x.structured.map(function (d) {
          return "<tr><td>" + esc(String(d.timestamp || "").slice(11, 19)) + "</td><td>" + esc(d.type)
            + "</td><td><code>" + esc(d.deathCauseId) + "</code></td><td>" + esc(d.deathCauseMsg)
            + "</td><td>" + esc(d.deathKind) + "</td><td>" + esc(d.deathAttacker || "—") + "</td></tr>";
        }).join("") + "</tbody></table>";
    }
    if (x.legacySample && x.legacySample.length) {
      h += '<details><summary>旧形状样本（' + x.legacyShape + ' 条，这里看 ' + x.legacySample.length + " 条）<code>data</code> 只有这几个键</summary><table class=\"tbl\"><tbody>"
        + x.legacySample.map(function (d) {
          return "<tr><td>" + esc(String(d.timestamp || "").slice(11, 19)) + "</td><td>" + esc(d.type)
            + "</td><td>reason=" + esc(d.reason) + "</td><td><code>" + esc((d.dataKeys || []).join(",")) + "</code></td></tr>";
        }).join("") + "</tbody></table></details>";
    }
    return card("① 死因结构化", h, "wide");
  }

  function planningCard() {
    var x = state.data.planning;
    var h = kv("数据源", x.source) + kv("planning_knowledge 事件", n(x.planningKnowledge))
      + kv("有内容（text_chars>0）", n(x.withContent))
      + kv("明确为空（gaps 里有说明）", n(x.emptyByGaps), x.emptyByGaps ? "warn" : "")
      + kv("旧 schema 不可判", n(x.noSchema), "")
      + kv("注入字数 P50 / 最大", n(x.textChars.p50) + " / " + n(x.textChars.max));
    h += '<div class="dist">' + (x.byStage || []).map(function (s) {
      var ok = s.withContent === s.total;
      return '<span class="chip ' + (ok ? "ok" : "warn") + '">' + esc(s.stage) + " " + s.withContent + "/" + s.total + "</span>";
    }).join("") + "</div>";
    h += '<p class="audit-gap">★ 数据源是 <code>expmem.jsonl</code>，<b>不是 <code>rdd.jsonl</code></b> —— 实测 rdd.jsonl 里 planning_* 事件 0 条。'
      + "正确判据是 <code>text_chars &gt; 0</code>，不是 <code>injected</code>（两套 schema 混用，"
      + (x.noSchema ? "本次读到 " + x.noSchema + " 条旧事件没有这个字段" : "") + "）。</p>";
    if (x.sample && x.sample.length) {
      h += '<table class="tbl"><thead><tr><th>时间</th><th>stage</th><th>chars</th><th>chosen</th><th>来源</th></tr></thead><tbody>'
        + x.sample.map(function (s) {
          return "<tr><td>" + esc(String(s.timestamp || "").slice(11, 19)) + "</td><td>" + esc(s.stage)
            + "</td><td>" + esc(s.text_chars) + "</td><td>" + esc(s.chosen)
            + "</td><td><code>" + esc((s.sources || []).map(function (x2) { return x2.id; }).join(" / ")) + "</code></td></tr>";
        }).join("") + "</tbody></table>";
    }
    return card("② 规划者取知识", h, "wide");
  }

  function injectionCard() {
    var x = state.data.injection;
    var h = kv("runtime_state 行数", n(x.runtimeStates)) + kv("llm_request 行数", n(x.llmRequests));
    h += "<div class=\"dist\">" + Object.keys(x.tagHitCounts).map(function (k) {
      var v = x.tagHitCounts[k];
      return '<span class="chip ' + (v ? "ok" : "bad") + '">&lt;' + esc(k) + "&gt; " + v + "</span>";
    }).join("") + "</div>";
    h += '<p class="audit-gap">' + esc(x.note) + "</p>";
    h += '<details><summary>工具名命中（<b>不是</b>注入正文，别混）</summary><div class="dist">'
      + Object.keys(x.toolNameHits).map(function (k) {
        return '<span class="chip">' + esc(k) + " " + x.toolNameHits[k] + "</span>";
      }).join("") + "</div></details>";
    if (x.latest) {
      h += '<p class="audit-gap">下面就是<b>干活的 AI 真实收到的那段文字</b>（第 ' + esc(n(x.latest.turn)) + " 轮，"
        + esc(n(x.latest.ctxChars)) + " 字符）：</p>";
      h += '<div class="ctxdump">' + esc(x.latest.currentTaskTag || "") + "</div>";
      h += '<div class="ctxdump">' + esc(x.latest.carryTag || "") + "</div>";
    }
    if (x.experienceTagAbsent) {
      h += '<p class="audit-gap bad">★ <code>&lt;experience&gt;</code> 命中 <b>' + x.tagHitCounts.experience
        + "</b> 次 —— " + esc(x.experienceTagAbsentWhy) + "</p>";
      h += '<p class="audit-gap">' + esc(x.toolSchemaOnly) + "</p>";
    }
    return card("③ 注入证据 · AI 到底收到了什么", h, "wide");
  }

  function storeCard() {
    var x = state.data.experienceStore;
    var h = kv("磁盘上条目", n(x.totalOnDisk)) + kv("代码可读", n(x.readableByStore), x.readableByStore ? "" : "bad")
      + kv("形状冲突文件", n(x.conflicts.length), x.conflicts.length ? "bad" : "");
    h += '<table class="tbl"><thead><tr><th>文件</th><th>形状</th><th>条目</th><th>可读</th><th>说明</th></tr></thead><tbody>'
      + x.files.map(function (f) {
        return "<tr><td><code>" + esc(f.file) + "</code></td><td>" + esc(f.shape)
          + "</td><td>" + esc(n(f.entries)) + "</td><td>" + (f.readableAsJsonl ? "是" : '<b class="bad">否</b>')
          + "</td><td>" + esc(f.note) + "</td></tr>";
      }).join("") + "</tbody></table>";
    if (x.builtin) {
      h += '<p class="audit-gap">对照：内置经验 <code>knowledge/builtin-experience.jsonl</code> 是<b>真 JSONL</b>（'
        + esc(n(x.builtin.entries)) + " 条），由 <code>BuiltinExperience.parse</code> 逐行读，<b>没有这个问题</b>。</p>";
    }
    if (x.learnerMemos && x.learnerMemos.length) {
      h += "<h4>待办文件（干活的 AI 写的）</h4><table class=\"tbl\"><thead><tr><th>文件</th><th>条数</th><th>最近几条</th></tr></thead><tbody>"
        + x.learnerMemos.map(function (mm) {
          var last = (mm.memos || []).slice(-3).map(function (m) { return esc(m.problem || "").slice(0, 40); }).join(" / ");
          return "<tr><td><code>" + esc(mm.file) + "</code></td><td>" + esc(n(mm.count)) + "</td><td>" + (last || "—") + "</td></tr>";
        }).join("") + "</tbody></table>";
    }
    return card("④ 正式经验库落盘", h, "wide");
  }

  function learnerCard() {
    var x = state.data.learner;
    var h = kv("事件总数", n(x.total));
    h += '<div class="dist">' + (x.byType || []).map(function (t) {
      return '<span class="chip">' + esc(t.type) + " ×" + t.n + "</span>";
    }).join("") + "</div>";
    h += '<p class="audit-gap">' + esc(x.note) + "</p>";
    if (x.noted && x.noted.length) {
      h += "<details><summary>待办（noted " + x.noted.length + " 条）</summary><ul class=\"ev\">"
        + x.noted.map(function (d) {
          return "<li><b>" + esc(d.problem || d.title || "?") + "</b><br><span class=\"dim\">"
            + esc(String(d.snapshot || "").slice(0, 120)) + "</span></li>";
        }).join("") + "</ul></details>";
    }
    if (x.reviewed && x.reviewed.length) {
      h += "<details><summary>学习者判定（reviewed " + x.reviewed.length + " 条）</summary><ul class=\"ev\">"
        + x.reviewed.map(function (d) {
          return "<li><code>" + esc(JSON.stringify(d).slice(0, 300)) + "</code></li>";
        }).join("") + "</ul></details>";
    }
    return card("⑤ 学习者 · 待办 / 携带器 / 判定", h, "wide");
  }

  function render() {
    // ★ 2026-10-03：目标从 #monitor-content 改成 #chain-host。
    //   #monitor-content 归 monitor-app 的 render() 管（它整块重建，会把这里
    //   刚写进去的东西连同宿主一起抹掉）。宿主现在是 index.html 里的常驻元素。
    var el = document.getElementById("chain-host");
    if (!el) return;
    if (state.err) { el.innerHTML = card("经验链 · 拉取失败", '<div class="empty">' + esc(state.err) + "</div>", "wide"); return; }
    if (!state.data) { el.innerHTML = card("经验链", '<div class="empty">正在拉取 /api/chain…</div>', "wide"); return; }
    var d = state.data;
    el.innerHTML = gapsSection()
      + deathCard() + planningCard() + injectionCard() + storeCard() + learnerCard()
      + card("这一页的边界", '<p class="audit-gap">'
        + "数据源：<code>monitor/{instrumentation,expmem,context,learner}.jsonl</code> + <code>config/numen/experience-*.jsonl</code><br>"
        + "本页<b>只读观察</b>，不改游戏状态、不发 MCP 指令。<br>"
        + "⚠️ <b>本页是体检报告</b>：断点置顶是为了先看见问题，不是说其它部分不重要。<br>"
        + "⚠️ 采样窗口：各日志只读尾部（4MB / 500 条）。<code>planning_policy</code> 本次读到 "
        + esc(n(d.planning.planningPolicy)) + " 条（历史上有），可能是窗口外 —— <b>别把 0 读成「不存在」</b>。<br>"
        + "刷新时间：<code>" + esc(d.timestamp) + "</code></p>", "wide");
  }

  var ChainView = {
    fetch: fetchChain,
    render: render,
    onUpdate: null,
    start: function () {
      fetchChain();
      setInterval(fetchChain, 8000);
    },
  };
  window.ChainView = ChainView;
}());
