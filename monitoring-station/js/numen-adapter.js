var NumenAdapter = (function () {
  var listeners = [], timer = null, seen = Object.create(null), seenOrder = [];
  var connected = false, polling = false, MAX_SEEN = 6000, MAX_NEW_PER_CATEGORY = 100;
  function notify(message) { for (var i = 0; i < listeners.length; i++) try { listeners[i](message); } catch (e) {} }
  function categoryOf(event, fallback) {
    var c = event.category || fallback || "overview";
    if (c === "events") return "overview";
    if (c === "state") return "environment";
    if (c === "ai" || c === "ac" || c === "rdd") return c;
    if (c === "expmem") return "ai";
    return c;
  }
  function key(event) { return event.event_id || [event.timestamp, event.category, event.type, JSON.stringify(event.data || {})].join("|"); }
  function remember(id) {
    if (seen[id]) return false;
    seen[id] = true; seenOrder.push(id);
    if (seenOrder.length > MAX_SEEN) delete seen[seenOrder.shift()];
    return true;
  }
  function eventFromSnapshot(event, source) {
    var data = event.data || {}, text = data.kind || data.message || data.reason || data.tool || event.type || "event";
    if (data.kind && data.tool) text += " - " + data.tool;
    if (data.kind && data.step_id) text += " (" + data.step_id + ")";
    return { category: categoryOf(event, source), type: event.type || "unknown", text: text, payload: data,
      timestamp: event.timestamp, source: event.source || "numen" };
  }
  function ingest(snapshot) {
    var categories = snapshot.categories || {}, batch = [];
    for (var source in categories) {
      var events = categories[source] || [];
      for (var i = Math.max(0, events.length - MAX_NEW_PER_CATEGORY); i < events.length; i++) {
        if (remember(key(events[i]))) batch.push(eventFromSnapshot(events[i], source));
      }
    }
    if (batch.length) notify({ type: "events", payload: batch });
  }
  function ingestLog(log) {
    if (!log) return;
    var groups = { ai: "ai", context: "context", tools: "tools", state: "environment" }, batch = [];
    for (var group in groups) {
      var events = log[group] || [];
      for (var i = 0; i < events.length; i++) {
        var ev = events[i];
        if (!remember(ev.tag + "|" + ev.time + "|" + ev.text)) continue;
        batch.push({ category: groups[group], type: ev.type || "log", text: ev.text || "", payload: { tag: ev.tag, time: ev.time },
          timestamp: new Date().toISOString().slice(0, 11) + ev.time + "Z", source: "numen-latest.log" });
      }
    }
    if (batch.length) notify({ type: "events", payload: batch });
  }
  function poll() {
    if (polling) return;
    polling = true;
    var pending = 2;
    function done() { if (--pending === 0) polling = false; }
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/api/snapshot?_=" + Date.now(), true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status === 200) { try { ingest(JSON.parse(xhr.responseText)); connected = true; } catch (e) { connected = false; } } else connected = false;
      notify({ type: "connection", payload: { connected: connected, source: "numen-jsonl" } }); done();
    };
    xhr.onerror = xhr.ontimeout = done; xhr.send();
    var xhr2 = new XMLHttpRequest();
    xhr2.open("GET", "/api/numen-log?_=" + Date.now(), true);
    xhr2.onreadystatechange = function () { if (xhr2.readyState !== 4) return; if (xhr2.status === 200) try { ingestLog(JSON.parse(xhr2.responseText)); } catch (e) {} done(); };
    xhr2.onerror = xhr2.ontimeout = done; xhr2.send();
  }
  return {
    subscribe: function (listener) { listeners.push(listener); }, requestSnapshot: poll,
    sendCommand: function (command, params, requestId) { notify({ type: "commandResult", payload: { command_id: requestId, command: command, status: "UNSUPPORTED", message: "Current Numen JSONL adapter is read-only" } }); },
    start: function () { if (timer) return; poll(); timer = setInterval(poll, 2000); },
    stop: function () { if (timer) clearInterval(timer); timer = null; }, connected: function () { return connected; }
  };
}());
