var MonitorStore = (function () {
  var pages = { overview: [], ai: [], context: [], tools: [], ac: [], rdd: [], environment: [], health: [], architecture: [], operations: [] };
  var current = "overview";
  var dropped = 0;
  var sequence = 0;
  var listeners = [];
  var connection = { connected: false, source: "numen-jsonl" };
  var commandResults = [];
  var retainedTypes = ['taskchain_snapshot', 'supervisor_input', 'supervisor_context', 'numen_context', 'supervisor_output', 'llm_request', 'llm_response', 'llm_failure'];
  var fixtures = [
    { category: "ai", type: "thinking", text: "RDD 样例：分析下一步阶段", payload: { thinking: "等待真实 RDD 事件" } },
    { category: "context", type: "context_snapshot", text: "Prompt / Context / Tools 快照（样例）", payload: { context: "MOCK" } },
    { category: "tools", type: "tool_call", text: "tool_a · UNSUPPORTED", payload: { status: "UNSUPPORTED" } },
    { category: "ac", type: "ac_step", text: "rng_build_v1 · step 1/3", payload: { status: "PENDING" } },
    { category: "environment", type: "snapshot", text: "环境快照等待接入", payload: {} },
    { category: "health", type: "heartbeat", text: "监测台自身心跳正常", payload: { status: "STABLE" } }
  ];
  function emit() { for (var i = 0; i < listeners.length; i++) { try { listeners[i](); } catch (e) {} } }
  function add(event) {
    var page = event.category || "overview";
    if (!pages[page]) { page = "overview"; dropped++; }
    event.sequence = ++sequence;
    event.timestamp = event.timestamp || new Date().toISOString();
    if ((page === 'rdd' || page === 'context') && retainedTypes.indexOf(event.type) >= 0) {
      var owner = (event.payload || {}).companionId || (event.payload || {}).companion_id;
      if (owner) pages[page] = pages[page].filter(function (old) {
        var p = old.payload || {};
        return old.type !== event.type || (p.companionId || p.companion_id) !== owner || p.actor !== (event.payload || {}).actor;
      });
    }
    pages[page].push(event);
    if (pages[page].length > 500) {
      var remove = 0;
      if (page === 'rdd' || page === 'context') {
        var ordinary = pages[page].findIndex(function (e) { return retainedTypes.indexOf(e.type) < 0; });
        if (ordinary >= 0) remove = ordinary;
      }
      pages[page].splice(remove, 1); dropped++;
    }
    emit();
  }
  function accept(message) {
    if (!message) return;
    if (message.type === "event") add(message.payload || {});
    else if (message.type === "connection") { connection = message.payload || connection; emit(); }
    else if (message.type === "commandResult") { commandResults.push(message.payload || {}); if (commandResults.length > 100) commandResults.shift(); emit(); }
  }
  function clear() { pages[current] = []; emit(); }
  function setPage(page) { if (pages[page] || ['supervisor', 'taskchain', 'numen', 'intro', 'docs'].indexOf(page) >= 0) { current = page; emit(); } }
  function getEvents(page) { return (pages[page || current] || []).slice(); }
  function inject() { var e = fixtures[sequence % fixtures.length]; add({ category: e.category, type: e.type, text: e.text, payload: e.payload, source: "mock" }); }
  function subscribe(listener) { listeners.push(listener); }
  function stats() { var total = 0; for (var page in pages) total += pages[page].length; return { queue: total, dropped: dropped, sequence: sequence, connected: !!connection.connected, commandResults: commandResults.slice() }; }
  return { accept: accept, add: add, clear: clear, setPage: setPage, getPage: function () { return current; }, getEvents: getEvents, inject: inject, subscribe: subscribe, stats: stats, health: function () { return connection; } };
}());
