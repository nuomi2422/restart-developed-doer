var RddView = (function () {
  var selected = '';
  var opened = Object.create(null);
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function companion(e) { var p = e.payload || {}; return p.companionId || p.companion_id || ''; }

  // ★ 事件类型 → 中文。owner 2026-10-05：「上下文那一整个页面，我都不容易看出来
  //   究竟是什么东西」—— 因为原来每行只印一个英文 type 名（runtime_state /
  //   llm_request / taskchain_snapshot…），读的人得自己猜它对应游戏里的什么。
  //   这里只加一行中文解释，**英文 type 名保留**（要查原始日志时还得靠它）。
  var TYPE_ZH = {
    runtime_state: '运行时状态 · 背包 / 手持 / 世界事实',
    llm_request: '发给模型的请求原文（system + user + 工具定义）',
    llm_response: '模型的回复原文（内容 + usage）',
    user_prompt: '用户输入',
    assistant: 'AI 的回答',
    turn: 'AI 回合开始',
    taskchain_snapshot: '任务链快照（目标 / 阶段 / 当前小步）',
    numen_context: '注入给执行 AI 的上下文（经验携带 + 已知世界资产）',
    supervisor_input: '规划 AI 的输入',
    supervisor_output: '规划 AI 的输出',
    supervisor_context: '规划 AI 的上下文',
    planning_knowledge: '规划知识注入',
    goal_staged: '目标已下达（含阶段数）',
    asset_snapshot: '资产快照',
    asset_mismatch: '资产不匹配',
    body_log: '游戏内事件日志',
    tool_call: '工具调用',
    tool_result: '工具结果',
    tool_rejected: '工具被拒',
    death: '死亡',
    loop_detected: '空转（同一动作重复）',
    recovery_failed: '恢复失败',
    repeat_death_nearby: '重复死亡',
    subtask_stalled: '支线卡住'
  };
  function typeLabel(t) { return TYPE_ZH[t] ? TYPE_ZH[t] + '（' + t + '）' : String(t || '?'); }

  // 元数据原来是一个裸 JSON<pre>，得自己扫字段。这里排成一行可读文本，
  // **原始 JSON 仍然保留**在下一层折叠里 —— 要查字段名还得靠它。
  function metaText(m) {
    var out = [];
    if (m.source) out.push('来源 ' + m.source);
    if (m.target) out.push('发给 ' + m.target);
    if (m.companion) out.push('同伴 ' + String(m.companion).slice(0, 8));
    if (m.taskId) out.push('任务 ' + m.taskId);
    if (m.subtaskId) out.push('小步 ' + m.subtaskId);
    if (m.inputId) out.push('输入 ' + m.inputId);
    if (m.outputId) out.push('输出 ' + m.outputId);
    return out.length ? out.join('　·　') : '（这条没有额外元数据）';
  }

  function disclosure(id, label, body, initial) {
    var isOpen = Object.prototype.hasOwnProperty.call(opened, id) ? opened[id] : initial;
    return '<details data-detail-id="' + esc(id) + '"' + (isOpen ? ' open' : '') + '><summary>' + esc(label) + '</summary>' + body + '</details>';
  }
  function contextRows(events, types) {
    var h = '';
    for (var i = events.length - 1, count = 0; i >= 0 && count < 12; i--) {
      var e = events[i], p = e.payload || {};
      if (types && types.indexOf(e.type) < 0) continue;
      // ★ 正文取值的顺序有讲究（2026-10-05）：
      //   llm_request / llm_response 的正文在 `request` / `response` 字段里，
      //   而适配器给它们的 `e.text` 只是事件名（"llm_response"）——
      //   原来把 e.text 排在前面，于是这一页对**最该看的那两条**只印了个事件名，
      //   读的人完全看不到模型到底收到了什么。⇒ request/response/result 提到 e.text 前面。
      var body = p.context || p.message || p.text || p.objective
        || p.response || p.request || p.result || e.text || p;
      var meta = { source: p.source || e.source, target: p.target, companion: companion(e), taskId: p.taskId, subtaskId: p.subtaskId, inputId: p.inputId, outputId: p.outputId };
      // summary 里给中文类型 + 原时间戳；正文里第一行是可读元信息，
      // 原文（pre）保持**一字不改**，最后再挂一层折叠放原始元数据 JSON。
      var id = e.eventId || String(e.sequence);
      h += disclosure(id, (e.timestamp || '') + ' · ' + typeLabel(e.type),
        '<div class="ctx-meta">' + esc(metaText(meta)) + '</div>'
        + '<pre class="pre">' + esc(typeof body === 'string' ? body : JSON.stringify(body, null, 2)) + '</pre>'
        + disclosure('meta-' + id, '原始元数据 JSON', '<pre class="pre">' + esc(JSON.stringify(meta, null, 2)) + '</pre>'), count === 0);
      count++;
    }
    return h || '<div class="empty">这一类还没有记录。<br>它来自 <code>context.jsonl</code> / <code>rdd.jsonl</code>；游戏跑起来并写了对应事件之后这里才会有内容。<b>这里是「还没记录」，不是「记录到了 0 条」。</b></div>';
  }
  function assetsRows(assets) {
    if (!Array.isArray(assets)) return '<div class="empty">尚未收到 asset_snapshot 观测；游戏端观测到资产后这里会出现。旧日志不能还原资产库存。</div>';
    if (!assets.length) return '<div class="empty">资产库本次观测为空</div>';
    var h = '<div class="table-scroll"><table class="table"><tr><th>资产</th><th>数量</th><th>状态</th><th>作用域</th><th>来源任务</th><th>观测时间</th></tr>';
    assets.forEach(function (a) {
      a = a || {}; var o = a.observation || {}; var v = o.value || {};
      // 资产条目有多种形态（2026-09-29 实测）：kind=base/world_base 的 value 里没有 count，
      // 数量藏在 important_items 等字典里；原来只取 value.count → 一律显示「未知」。
      var qty = v.count != null ? v.count : null;
      if (qty == null) {
        var keys = ['important_items', 'items', 'contents', 'stores'];
        for (var i = 0; i < keys.length && qty == null; i++) {
          var bag = v[keys[i]];
          if (bag && typeof bag === 'object') {
            var n = Object.keys(bag).length;
            if (n) qty = n + ' 类';
          }
        }
      }
      h += '<tr><td>' + esc(a.assetId || o.type || '?') + '</td><td>' + esc(qty == null ? '未知' : qty) + '</td><td>' + esc(a.status || v.refresh_policy || v.refresh || '—') + '</td><td>' + esc(a.scope || o.source || '—') + '</td><td>' + esc(a.originTaskNodeId || '—') + '</td><td>' + esc(o.observedAt ? new Date(o.observedAt).toLocaleString() : '未知') + '</td></tr>';
    });
    return h + '</table></div>';
  }
  // 资产是**独立事件类型** asset_snapshot，不是 taskchain_snapshot 的字段。
  // 2026-09-29 实测：rdd.jsonl 里 asset_snapshot 出现在自己的 data.assets，
  // 但这里去 taskchain_snapshot 里找 assets → 恒为空（用户报「监测台看不到资产」）。
  function pickAssetsSnapshot(events) {
    for (var i = events.length - 1; i >= 0; i--) {
      var p = events[i].payload || {};
      if (Array.isArray(p.assets)) return events[i];
    }
    return null;
  }
  function render(all, contexts, ui) {
    var state = selection(all, contexts, ui, true);
    var events = state.events;
    var snapshot = null, assetsSnapshot = null;
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      if (!snapshot && p.taskChain) snapshot = e;
    }
    assetsSnapshot = pickAssetsSnapshot(events);
    var h = '<section class="audit-intro"><h2>这次，两个 AI 到底收到了什么？</h2><p>先看最新输入与对应输出，再核对任务量和资产。这里只展示采集到的内容，不判断模型的隐藏思考。</p><div class="audit-flow"><span>目标 + 知识 → 任务链 AI</span><span>任务安排 → Numen 执行 AI</span><span>工具结果 + 资产 → 验收 / 重规划</span></div></section>';
    h += state.picker;
    // reason=task_removed 的快照**仍然带着完整 taskChain**（2026-10-03 实测：
    // 16:13:59 那条移除快照里 7 个一级阶段一个不少）。原来一律当 null 处理，
    // 于是任务一结束本页就退回「任务已移除」的空态 —— 把手里现成的链扔了。
    var removed = !!(snapshot && snapshot.payload.reason === 'task_removed');
    var chain = snapshot ? snapshot.payload.taskChain : null;
    var hasStages = !!(chain && Array.isArray(chain.primaries) && chain.primaries.length);
    if (hasStages) {
      var primaries = chain.primaries, current = primaries[chain.primaryIndex];
      var age = Date.now() - Date.parse(snapshot.timestamp);
      var fresh = !removed && age >= 0 && age <= 30000;
      h += ui.card('当前目标', ui.kv('目标', chain.description) + ui.kv('当前阶段', current ? current.description : '无') + ui.kv('快照时间', snapshot.timestamp) + ui.kv('数据时效', removed ? '任务已移除 · 这是移除那一刻的快照' : (fresh ? '近期观测' : '历史记录 · 不是当前运行证明'), removed || !fresh ? 'state-warn' : 'state-good'), 'wide');
    }
    h += RequestView.render(events.concat(contexts), events.concat(contexts), selected, ui);
    if (hasStages) {
      h += ui.card('当前阶段 · 二级任务与完成条件', ui.detail(current, chain.primaryStatus), 'wide');
      h += ui.card('资产库 · 本次观测的真实数量', assetsRows(assetsSnapshot && assetsSnapshot.payload.assets), 'wide');
      h += ui.card('完整任务链 · 按需展开', disclosure('all-primaries-' + selected, '查看全部 ' + primaries.length + ' 个阶段', '<div class="graph">' + ui.flow(chain) + '</div>' + primaries.map(function (p, index) {
        return disclosure('primary-' + selected + '-' + p.id, (index + 1) + '. ' + p.description,
          '<div class="pre">' + esc('前置资产：' + JSON.stringify(p.waitFor || [])) + '</div>' + ui.detail(p, p.current ? chain.primaryStatus : '') + disclosure('json-' + selected + '-' + p.id, '原始快照', '<pre class="pre">' + esc(JSON.stringify(p, null, 2)) + '</pre>', false), false);
      }).join(''), false), 'wide');
    } else h += ui.card('RDD 任务链', '<div class="empty">' + (removed ? '任务已移除，且移除时没有留下可展示的阶段。' : (snapshot ? '这条快照里没有阶段数据。' : '等待任务链观测；监测台不会自行创建游戏任务。')) + '</div>', 'wide');
    h += ui.supervision();
    h += ui.card('历史与排错 · 默认收起', disclosure('history-' + selected, '查看近期保留的历史事件 / 局部上下文', contextRows(events.concat(contexts.filter(function (e) { return companion(e) === selected && !!selected; }))) + '<div class="event-list">' + ui.events(all.filter(function (e) { return !companion(e) || companion(e) === selected; }).slice(-80)) + '</div>', false), 'wide');
    return h;
  }
  function hasChain(list, id) {
    for (var i = 0; i < list.length; i++) if (companion(list[i]) === id && (list[i].payload || {}).taskChain) return true;
    return false;
  }
  // 在给定同伴里挑「最近一次观测」的那个。返回 '' 表示一个都没有。
  function lastSeen(list, ids) {
    var best = '', bestAt = -1;
    ids.forEach(function (id) {
      for (var i = 0; i < list.length; i++) {
        if (companion(list[i]) !== id) continue;
        var t = Date.parse(list[i].timestamp || '') || 0;
        if (t >= bestAt) { bestAt = t; best = id; }
      }
    });
    return best;
  }
  // 2026-10-03：默认观察对象原来取 ids[最后一个]，而 ids 是**按事件到达顺序**去重收集的
  // —— 那不是活跃度。实测有一只只在早上留过一条 numen_context 的同伴，会因为排在窗口
  // 后面而抢走默认值，于是 3.1 任务链本体长期显示「等待任务链观测」（明明有链）。
  // 改成按最新观测时间选；任务链页额外优先选真有 taskChain 快照的同伴。
  // 用户在下拉里手动选过就尊重用户的选择（只要它还在窗口里）。
  function selection(all, contexts, ui, preferChain) {
    var ids = [], merged = all.concat(contexts);
    merged.forEach(function (e) { var id = companion(e); if (id && ids.indexOf(id) < 0) ids.push(id); });
    if (ids.indexOf(selected) < 0) {
      var pool = ids;
      if (preferChain) {
        var withChain = ids.filter(function (id) { return hasChain(all, id); });
        if (withChain.length) pool = withChain;
      }
      selected = lastSeen(merged, pool);
    }
    var events = all.filter(function (e) { return companion(e) === selected && !!selected; });
    var picker = ids.length ? ui.card('观察对象', '<select id="rdd-companion">' + ids.map(function (id) { return '<option value="' + esc(id) + '"' + (id === selected ? ' selected' : '') + '>' + esc(id) + '</option>'; }).join('') + '</select>', 'wide') : '';
    return { events: events, picker: picker };
  }
  function renderSupervisor(all, contexts, ui) {
    var state = selection(all, contexts, ui);
    var intro = '<section class="page-intro"><h2>任务链 AI · Supervisor</h2><p>只查看规划 AI 实际收到的目标、经验、策略约束、工具定义与输出；不混入 Numen 执行上下文。</p></section>';
    // 注意签名：request-view.js 的 renderActor = actorCard(actor, selected, events, legacy, ui)。
    // 第 2 参必须是伴侣 id 字符串(selected)、第 4 参才是 legacy 事件数组；
    // 曾把 (events, events, selected) 传成第 2/3/4 参 → actorCard 内 latest(legacy) 收到字符串 → TypeError，本页与 Numen 页点不开。
    return intro + state.picker + RequestView.renderActor('supervisor', selected, state.events.concat(contexts), state.events.concat(contexts), ui);
  }
  function renderNumen(all, contexts, ui) {
    var state = selection(all, contexts, ui);
    var intro = '<section class="page-intro"><h2>Numen AI · 执行上下文</h2><p>只查看执行 AI 实际收到的任务、世界上下文、工具目录与对应输出；不混入任务规划请求。</p></section>';
    return intro + state.picker + RequestView.renderActor('numen', selected, state.events.concat(contexts), state.events.concat(contexts), ui);
  }
  function renderTaskChain(all, contexts, ui) {
    // preferChain：这一页的本体就是任务链，默认观察对象必须是真有快照的那只同伴。
    var state = selection(all, contexts, ui, true), events = state.events, snapshot = null, assetsSnapshot = null;
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      if (!snapshot && p.taskChain) snapshot = e;
    }
    assetsSnapshot = pickAssetsSnapshot(events);
    var intro = '<section class="page-intro"><h2>任务链 · 横向推进</h2><p>一级阶段按从左到右的流程展示；当前阶段下方显示二级任务、验收条件与真实资产。</p></section>';
    var h = intro + state.picker;
    // 与 render() 同一个坑：移除快照里链是完整的，不能当 null（见上面 render 的注释）。
    var removed = !!(snapshot && snapshot.payload.reason === 'task_removed');
    var chain = snapshot ? snapshot.payload.taskChain : null;
    if (!(chain && Array.isArray(chain.primaries) && chain.primaries.length)) {
      return h + ui.card('RDD 任务链', '<div class="empty">' + (removed ? '任务已移除，且移除时没有留下可展示的阶段。' : (snapshot ? '这条快照里没有阶段数据。' : '等待任务链观测；本页不会自行创建游戏任务。')) + '</div>', 'wide');
    }
    var primaries = chain.primaries, current = primaries[chain.primaryIndex];
    // 照实展示，但必须说清这是历史快照 —— 页面叫「任务链本体」，本体没了就该讲明白。
    if (removed) h += ui.card('任务已移除', '<p class="audit-gap">下面这份链是<b>移除那一刻留下的最后一份快照</b>，<b>不代表任务现在还在跑</b>。要看当前运行情况，请让 Supervisor 另起一个任务。</p>', 'wide');
    h += ui.card('当前目标', ui.kv('目标', chain.description) + ui.kv('当前阶段', current ? current.description : '无') + ui.kv('快照时间', snapshot.timestamp) + ui.kv('数据时效', removed ? '任务已移除 · 移除时的快照' : '以快照时间为准，本页不判断模型隐藏思考', removed ? 'state-warn' : ''), 'wide');
    h += ui.card('横向任务流程', '<div class="graph task-flow">' + ui.flow(chain) + '</div>', 'wide');
    h += ui.card('当前阶段 · 二级任务与完成条件', ui.detail(current, chain.primaryStatus), 'wide');
    h += ui.card('资产库 · 本次观测的真实数量', assetsRows(assetsSnapshot && assetsSnapshot.payload.assets), 'wide');
    h += ui.supervision();
    h += ui.card('历史与排错 · 默认收起', disclosure('history-' + selected, '查看近期事件 / 局部上下文', contextRows(events.concat(contexts.filter(function (e) { return companion(e) === selected && !!selected; }))), false), 'wide');
    return h;
  }
  document.addEventListener('toggle', function (e) {
    if (e.target && e.target.isConnected && e.target.dataset && e.target.dataset.detailId) opened[e.target.dataset.detailId] = e.target.open;
  }, true);
  // Remember user intent synchronously; a journal refresh can replace the node
  // before the browser's deferred toggle event is dispatched.
  document.addEventListener('click', function (e) {
    var summary = e.target && e.target.closest ? e.target.closest('summary') : null;
    var detail = summary && summary.parentElement;
    if (detail && detail.dataset.detailId) opened[detail.dataset.detailId] = !detail.open;
  }, true);
  return { render: render, renderSupervisor: renderSupervisor, renderNumen: renderNumen, renderTaskChain: renderTaskChain, contextRows: contextRows, assetsRows: assetsRows, select: function (id) { selected = id; } };
}());
