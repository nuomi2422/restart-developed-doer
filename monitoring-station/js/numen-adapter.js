var NumenAdapter = (function () {
  var listeners = [];
  var timer = null;
  var seen = {};
  var connected = false;

  function notify(message) {
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](message); } catch (e) {}
    }
  }
  function categoryOf(event, fallback) {
    var c = event.category || fallback || "overview";
    if (c === "events") return "overview";
    if (c === "state") return "environment";
    if (c === "ai") return "ai";
    if (c === "ac") return "ac";
    if (c === "rdd") return "rdd";                  // 任务链拥有独立页面，不能混入 AC。
    if (c === "expmem") return "ai";             // 经验模块进 AI 分页
    return c;
  }
  function key(event) {
    return event.event_id || [event.timestamp, event.category, event.type, JSON.stringify(event.data || {})].join("|");
  }
  function ingest(snapshot) {
    var categories = snapshot.categories || {};
    for (var source in categories) {
      var events = categories[source] || [];
      for (var i = 0; i < events.length; i++) {
        var event = events[i];
        var id = key(event);
        if (seen[id]) continue;
        seen[id] = true;
        var evData = event.data || {};
        var displayText = "";
        if (evData.kind) {
          // AC 事件：用 kind + tool/step 显示，如 "STEP_SUCCEEDED · rdd_whereami"
          displayText = evData.kind;
          if (evData.tool) displayText += " · " + evData.tool;
          if (evData.step_id) displayText += " (" + evData.step_id + ")";
        } else {
          displayText = evData.message || evData.reason || evData.tool || event.type || "event";
        }
        notify({ type: "event", payload: {
          category: categoryOf(event, source),
          type: event.type || "unknown",
          text: displayText,
          payload: evData,
          timestamp: event.timestamp,
          source: event.source || "numen"
        }});
      }
    }
  }
  function ingestLog(log) {
    // /api/numen-log 返回 { ai:[], context:[], tools:[] }，结构不同于 JSONL snapshot
    if (!log) return;
    var groups = { ai: "ai", context: "context", tools: "tools", state: "environment" };
    for (var group in groups) {
      var events = log[group] || [];
      for (var i = 0; i < events.length; i++) {
        var ev = events[i];
        var id = ev.tag + "|" + ev.time + "|" + ev.text;
        if (seen[id]) continue;
        seen[id] = true;
        var category = groups[group];
        var today = new Date().toISOString().slice(0, 11); // YYYY-MM-DDT 前缀
        notify({ type: "event", payload: {
          category: category,
          type: ev.type || "log",
          text: ev.text || "",
          payload: { tag: ev.tag, time: ev.time },
          timestamp: today + ev.time + "Z",
          source: "numen-latest.log"
        }});
      }
    }
  }
  function poll() {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/api/snapshot?_=" + new Date().getTime(), true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status === 200) {
        try { ingest(JSON.parse(xhr.responseText)); connected = true; }
        catch (e) { connected = false; }
      } else connected = false;
      notify({ type: "connection", payload: { connected: connected } });
    };
    xhr.send();

    // 第二路：Numen AI 决策日志（thinking/context/tools）
    var xhr2 = new XMLHttpRequest();
    xhr2.open("GET", "/api/numen-log?_=" + new Date().getTime(), true);
    xhr2.onreadystatechange = function () {
      if (xhr2.readyState !== 4) return;
      if (xhr2.status === 200) {
        try { ingestLog(JSON.parse(xhr2.responseText)); } catch (e) {}
      }
    };
    xhr2.send();
  }
  return {
    subscribe: function (listener) { listeners.push(listener); },
    requestSnapshot: poll,
    sendCommand: function (command, params, requestId) {
      notify({ type: "commandResult", payload: { command_id: requestId, command: command, status: "UNSUPPORTED", message: "当前 Numen JSONL Adapter 只读" } });
    },
    start: function () { if (timer) return; poll(); timer = setInterval(poll, 1000); },
    stop: function () { if (timer) clearInterval(timer); timer = null; },
    connected: function () { return connected; }
  };
}());
