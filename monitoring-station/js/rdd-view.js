var RddView = (function () {
  var selected = '';
  var opened = Object.create(null);
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function companion(e) { var p = e.payload || {}; return p.companionId || p.companion_id || ''; }
  function disclosure(id, label, body, initial) {
    var isOpen = Object.prototype.hasOwnProperty.call(opened, id) ? opened[id] : initial;
    return '<details data-detail-id="' + esc(id) + '"' + (isOpen ? ' open' : '') + '><summary>' + esc(label) + '</summary>' + body + '</details>';
  }
  function contextRows(events, types) {
    var h = '';
    for (var i = events.length - 1, count = 0; i >= 0 && count < 12; i--) {
      var e = events[i], p = e.payload || {};
      if (types && types.indexOf(e.type) < 0) continue;
      var body = p.context || p.message || p.text || p.objective || e.text || p;
      var bodyText = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
      if (bodyText.length > 16000) bodyText = bodyText.slice(0, 16000) + '\n\n[Preview truncated; the source journal is unchanged.]';
      var meta = { source: p.source || e.source, target: p.target, companion: companion(e), taskId: p.taskId, subtaskId: p.subtaskId, inputId: p.inputId, outputId: p.outputId };
      h += disclosure(e.eventId || String(e.sequence), (e.timestamp || '') + ' · ' + e.type,
        '<pre class="pre">' + esc(bodyText) + '</pre><pre class="pre">' + esc(JSON.stringify(meta, null, 2)) + '</pre>', count === 0);
      count++;
    }
    return h || '<div class="empty">尚未记录此上下文</div>';
  }
  function assetsRows(assets) {
    if (!Array.isArray(assets)) return '<div class="empty">此快照未包含资产库；新版插件观测后显示。旧日志不能还原资产库存。</div>';
    if (!assets.length) return '<div class="empty">资产库本次观测为空</div>';
    var h = '<div class="table-scroll"><table class="table"><tr><th>资产</th><th>数量</th><th>状态</th><th>作用域</th><th>来源任务</th><th>观测时间</th></tr>';
    assets.forEach(function (a) {
      a = a || {}; var o = a.observation || {};
      h += '<tr><td>' + esc(a.assetId) + '</td><td>' + esc(o.value && o.value.count != null ? o.value.count : '未知') + '</td><td>' + esc(a.status) + '</td><td>' + esc(a.scope) + '</td><td>' + esc(a.originTaskNodeId) + '</td><td>' + esc(o.observedAt ? new Date(o.observedAt).toLocaleString() : '未知') + '</td></tr>';
    });
    return h + '</table></div>';
  }
  function render(all, contexts, ui) {
    var ids = [];
    all.concat(contexts).forEach(function (e) { var id = companion(e); if (id && ids.indexOf(id) < 0) ids.push(id); });
    if (ids.indexOf(selected) < 0) selected = ids[ids.length - 1] || '';
    var events = all.filter(function (e) { return companion(e) === selected && !!selected; });
    var snapshot = null, assetsSnapshot = null;
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      if (!snapshot && p.taskChain) snapshot = e;
      if (!assetsSnapshot && e.type === 'taskchain_snapshot' && p.taskChain) assetsSnapshot = e;
    }
    var h = '<section class="audit-intro"><h2>这次，两个 AI 到底收到了什么？</h2><p>先看最新输入与对应输出，再核对任务量和资产。这里只展示采集到的内容，不判断模型的隐藏思考。</p><div class="audit-flow"><span>目标 + 知识 → 任务链 AI</span><span>任务安排 → Numen 执行 AI</span><span>工具结果 + 资产 → 验收 / 重规划</span></div></section>';
    if (ids.length) h += ui.card('观察对象', '<select id="rdd-companion">' + ids.map(function (id) { return '<option value="' + esc(id) + '"' + (id === selected ? ' selected' : '') + '>' + esc(id) + '</option>'; }).join('') + '</select>', 'wide');
    var chain = snapshot && snapshot.payload.reason !== 'task_removed' ? snapshot.payload.taskChain : null;
    if (chain && Array.isArray(chain.primaries)) {
      var primaries = chain.primaries, current = primaries[chain.primaryIndex];
      var age = Date.now() - Date.parse(snapshot.timestamp);
      var fresh = age >= 0 && age <= 30000;
      h += ui.card('当前目标', ui.kv('目标', chain.description) + ui.kv('当前阶段', current ? current.description : '无') + ui.kv('快照时间', snapshot.timestamp) + ui.kv('数据时效', fresh ? '近期观测' : '历史记录 · 不是当前运行证明', fresh ? 'state-good' : 'state-warn'), 'wide');
    }
    h += RequestView.render(events.concat(contexts), events.concat(contexts), selected, ui);
    if (chain && Array.isArray(chain.primaries)) {
      h += ui.card('当前阶段 · 二级任务与完成条件', ui.detail(current, chain.primaryStatus), 'wide');
      h += ui.card('资产库 · 本次观测的真实数量', assetsRows(assetsSnapshot && assetsSnapshot.payload.assets), 'wide');
      h += ui.card('完整任务链 · 按需展开', disclosure('all-primaries-' + selected, '查看全部 ' + primaries.length + ' 个阶段', '<div class="graph">' + ui.flow(chain) + '</div>' + primaries.map(function (p, index) {
        return disclosure('primary-' + selected + '-' + p.id, (index + 1) + '. ' + p.description,
          '<div class="pre">' + esc('前置资产：' + JSON.stringify(p.waitFor || [])) + '</div>' + ui.detail(p, p.current ? chain.primaryStatus : '') + disclosure('json-' + selected + '-' + p.id, '原始快照', '<pre class="pre">' + esc(JSON.stringify(p, null, 2)) + '</pre>', false), false);
      }).join(''), false), 'wide');
    } else h += ui.card('RDD 任务链', '<div class="empty">' + (snapshot ? '任务已移除，以下保留历史上下文。' : '等待任务链观测；监测台不会自行创建游戏任务。') + '</div>', 'wide');
    h += ui.supervision();
    h += ui.card('历史与排错 · 默认收起', disclosure('history-' + selected, '查看近期保留的历史事件 / 局部上下文', contextRows(events.concat(contexts.filter(function (e) { return companion(e) === selected && !!selected; }))) + '<div class="event-list">' + ui.events(all.filter(function (e) { return !companion(e) || companion(e) === selected; }).slice(-80)) + '</div>', false), 'wide');
    return h;
  }
  function selection(all, contexts, ui) {
    var ids = [];
    all.concat(contexts).forEach(function (e) { var id = companion(e); if (id && ids.indexOf(id) < 0) ids.push(id); });
    if (ids.indexOf(selected) < 0) selected = ids[ids.length - 1] || '';
    var events = all.filter(function (e) { return companion(e) === selected && !!selected; });
    var picker = ids.length ? ui.card('观察对象', '<select id="rdd-companion">' + ids.map(function (id) { return '<option value="' + esc(id) + '"' + (id === selected ? ' selected' : '') + '>' + esc(id) + '</option>'; }).join('') + '</select>', 'wide') : '';
    return { events: events, picker: picker };
  }
  function renderSupervisor(all, contexts, ui) {
    var state = selection(all, contexts, ui);
    var intro = '<section class="page-intro"><h2>任务链 AI · Supervisor</h2><p>只查看规划 AI 实际收到的目标、经验、策略约束、工具定义与输出；不混入 Numen 执行上下文。</p></section>';
    return intro + state.picker + RequestView.renderActor('supervisor', state.events.concat(contexts), state.events.concat(contexts), selected, ui);
  }
  function renderNumen(all, contexts, ui) {
    var state = selection(all, contexts, ui);
    var intro = '<section class="page-intro"><h2>Numen AI · 执行上下文</h2><p>只查看执行 AI 实际收到的任务、世界上下文、工具目录与对应输出；不混入任务规划请求。</p></section>';
    return intro + state.picker + RequestView.renderActor('numen', state.events.concat(contexts), state.events.concat(contexts), selected, ui);
  }
  function renderTaskChain(all, contexts, ui) {
    var state = selection(all, contexts, ui), events = state.events, snapshot = null, assetsSnapshot = null;
    for (var i = events.length - 1; i >= 0; i--) {
      var e = events[i], p = e.payload || {};
      if (!snapshot && p.taskChain) snapshot = e;
      if (!assetsSnapshot && e.type === 'taskchain_snapshot' && p.taskChain) assetsSnapshot = e;
    }
    var intro = '<section class="page-intro"><h2>任务链 · 横向推进</h2><p>一级阶段按从左到右的流程展示；当前阶段下方显示二级任务、验收条件与真实资产。</p></section>';
    var h = intro + state.picker;
    var chain = snapshot && snapshot.payload.reason !== 'task_removed' ? snapshot.payload.taskChain : null;
    if (!chain || !Array.isArray(chain.primaries)) return h + ui.card('RDD 任务链', '<div class="empty">等待任务链观测；本页不会自行创建游戏任务。</div>', 'wide');
    var primaries = chain.primaries, current = primaries[chain.primaryIndex];
    h += ui.card('当前目标', ui.kv('目标', chain.description) + ui.kv('当前阶段', current ? current.description : '无') + ui.kv('快照时间', snapshot.timestamp), 'wide');
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
