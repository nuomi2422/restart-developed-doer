var RequestView = (function () {
  var opened = Object.create(null);
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function owner(e) { var p = e.payload || {}; return p.companionId || p.companion_id || ''; }
  function latest(events, predicate) {
    return events.filter(predicate).sort(function (a, b) { return Date.parse(b.timestamp) - Date.parse(a.timestamp) || (b.sequence || 0) - (a.sequence || 0); })[0];
  }
  function raw(value) { return '<pre class="pre audit-text">' + esc(typeof value === 'string' ? value : JSON.stringify(value, null, 2)) + '</pre>'; }
  function fold(id, title, body, initial) {
    var isOpen = Object.prototype.hasOwnProperty.call(opened, id) ? opened[id] : !!initial;
    return '<details data-audit-id="' + esc(id) + '"' + (isOpen ? ' open' : '') + '><summary>' + esc(title) + '</summary>' + body + '</details>';
  }
  function time(e) {
    if (!e) return '未采集';
    var date = new Date(e.timestamp);
    return (date.toDateString() === new Date().toDateString() ? '今天 ' : '非今天 · ') + date.toLocaleString();
  }
  function missing(text) { return '<p class="audit-gap">' + esc(text) + '</p>'; }
  var phases = { stage_a: '首次生成阶段', stage_b: '展开当前阶段', fallback: '完整规划回退', single_pass: '首次完整规划', stream: '执行回合', chat: '执行回合', execution: '执行回合', execution_retry: '执行重试', compaction: '整理对话记忆', goal_judging: '验收当前目标' };
  function messages(request, prefix) {
    var list = request.messages;
    // Anthropic-style wire bodies have system separate from messages.
    var h = request.system !== undefined ? fold(prefix + '-system', '系统规则 · 实际发送原文', raw(request.system), true) : '';
    if (!Array.isArray(list)) return h + missing('未采集到 messages，无法确认完整对话输入。');
    var roles = { system: '系统规则', developer: '开发规则', user: '输入上下文', assistant: '之前的 AI 回复', tool: '工具执行结果' };
    list.forEach(function (m, i) {
      h += fold(prefix + '-message-' + i, (i + 1) + '. ' + (roles[m.role] || m.role || '消息') + (m.name ? ' · ' + m.name : ''), raw(m.content === undefined ? m : m.content) + (m.tool_calls ? raw(m.tool_calls) : ''), m.role === 'system' || i === list.length - 1);
    });
    return h;
  }
  function tools(request, prefix) {
    if (!Array.isArray(request.tools)) return missing('此请求没有 tools 字段；不能据此推断 Numen 没有工具。');
    return '<p>' + request.tools.length + ' 个工具实际随本次请求发送。工具是否执行成功需看结果。</p>'
      + request.tools.map(function (tool, i) {
        var t = tool.function || tool;
        return fold(prefix + '-tool-' + i, t.name || '未命名工具', '<p>' + esc(t.description || '无描述') + '</p>' + raw(t.parameters || t.input_schema || t), false);
      }).join('');
  }
  function actorCard(actor, selected, events, legacy, ui) {
    var label = actor === 'supervisor' ? '任务链 AI · Supervisor' : '执行 AI · Numen';
    var purpose = actor === 'supervisor' ? '负责生成 / 重规划任务。检查目标、知识库和规划依据。' : '负责执行任务。检查本次收到的任务量、环境信息、工具和回应。';
    var pool = events.filter(function (e) { return owner(e) === selected && !!selected && (e.payload || {}).actor === actor; });
    var requestEvent = latest(pool, function (e) { return e.type === 'llm_request'; });
    var h = '<p class="audit-purpose">' + purpose + '</p>';
    if (!requestEvent) {
      h += missing('未采集到这位 AI 实际发送的完整请求。下面的旧版记录仅供定位，不能证明完整注入正确。');
      var partial = latest(legacy, function (e) { return actor === 'supervisor' ? e.type === 'supervisor_context' || e.type === 'supervisor_input' : e.type === 'numen_context' || e.type === 'supervisor_output' || e.type === 'user_prompt'; });
      if (partial) {
        var old = partial.payload || {};
        h += '<p class="audit-time">最近局部记录：' + esc(time(partial)) + '</p>'
          + fold('partial-' + actor + '-' + selected, actor === 'supervisor' ? '旧版已记录的目标 / 规划输入' : '旧版已记录的 RDD 注入 / 入队文字', raw(old.context || old.objective || old.message || old.text || old), true)
          + fold('partial-raw-' + actor + '-' + selected, '来源字段 · ' + partial.type, raw(old), false);
      }
      else h += '<p>局部输入也没有记录。下一次实际请求发生后才会出现。</p>';
    } else {
      var p = requestEvent.payload, id = actor + '-' + selected + '-' + p.requestId, request = p.request || {};
      var response = latest(pool, function (e) { return (e.type === 'llm_response' || e.type === 'llm_failure') && e.payload.requestId === p.requestId; });
      h += '<div class="audit-status">最近采集的请求 · ' + esc(phases[p.phase] || p.phase || '模型请求') + '</div>'
        + '<p class="audit-time">' + esc(time(requestEvent)) + ' · ' + esc(p.model || request.model || '模型未记录') + '</p>'
        + '<p class="audit-note">' + (p.status === 'dispatched' ? '已提交给 HTTP 客户端；是否收到回复见下方。' : '采集状态：' + esc(p.status || '未标注发送状态')) + ' 凭据与隐藏推理不展示。</p>';
      if (p.truncated || p.complete === false) h += missing('此条采集不完整，请勿用它判断完整注入。');
      // Every actual message is visible. No guessed decomposition of knowledge and task text.
      h += fold(id + '-input', '① 实际输入 · 系统规则、知识与任务全文', messages(request, id), true);
      h += fold(id + '-tools', '② 本次可用工具 · ' + (Array.isArray(request.tools) ? request.tools.length : '未采集') + ' 个', tools(request, id), false);
      h += fold(id + '-response', '③ 对应输出 · ' + (response ? response.type === 'llm_failure' ? '请求失败' : '已收到' : '尚无匹配回复'), response ? raw(response.payload.response || response.payload.error || response.payload) : missing('没有同一请求编号的输出；可能仍在等待或日志缺失，不拿上一轮回复代替。'), true);
      h += fold(id + '-wire', '核对完整请求与关联信息', raw(p), false);
    }
    return ui.card(label, h, 'audit-actor ' + actor);
  }
  function render(events, legacy, selected, ui) {
    var local = legacy.filter(function (e) { return owner(e) === selected && !!selected; });
    return '<div class="audit-pair">' + actorCard('supervisor', selected, events, local, ui) + actorCard('numen', selected, events, local, ui) + '</div>';
  }
  document.addEventListener('toggle', function (e) {
    if (e.target && e.target.isConnected && e.target.dataset && e.target.dataset.auditId) opened[e.target.dataset.auditId] = e.target.open;
  }, true);
  document.addEventListener('click', function (e) {
    var summary = e.target && e.target.closest ? e.target.closest('summary') : null;
    var detail = summary && summary.parentElement;
    if (detail && detail.dataset.auditId) opened[detail.dataset.auditId] = !detail.open;
  }, true);
  return { render: render, renderActor: actorCard, fold: fold, raw: raw, latest: latest };
}());
