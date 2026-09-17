var NumenAdapter = (function () {
  var listeners = [], timer = null, seen = Object.create(null), seenOrder = [];
  var connected = false, polling = false, MAX_SEEN = 200;
  function notify(message) { for (var i = 0; i < listeners.length; i++) try { listeners[i](message); } catch (e) {} }
  function key(event) { return event.event_id || [event.timestamp, event.category, event.type, JSON.stringify(event.data || {})].join("|"); }
  function remember(id) {
    if (seen[id]) return false;
    seen[id] = true; seenOrder.push(id);
    if (seenOrder.length > MAX_SEEN) delete seen[seenOrder.shift()];
    return true;
  }
  function eventFromSnapshot(event) {
    var data = event.data || {};
    return { category: "rdd", type: "taskchain_snapshot", text: data.reason || "taskchain_snapshot", payload: data,
      timestamp: event.timestamp, source: event.source || "numen" };
  }
  function ingest(snapshot) {
    var events = (snapshot.categories && snapshot.categories.rdd) || [], batch = [];
    for (var i = 0; i < events.length; i++) if (remember(key(events[i]))) batch.push(eventFromSnapshot(events[i]));
    if (batch.length) notify({ type: "events", payload: batch });
  }
  function poll() {
    if (polling) return;
    polling = true;
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/api/taskchain?_=" + Date.now(), true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status === 200) { try { ingest(JSON.parse(xhr.responseText)); connected = true; } catch (e) { connected = false; } } else connected = false;
      notify({ type: "connection", payload: { connected: connected, source: "rdd-taskchain-jsonl" } });
      polling = false;
    };
    xhr.onerror = xhr.ontimeout = function () { connected = false; polling = false; };
    xhr.send();
  }
  return {
    subscribe: function (listener) { listeners.push(listener); }, requestSnapshot: poll,
    sendCommand: function (command, params, requestId) { notify({ type: "commandResult", payload: { command_id: requestId, command: command, status: "UNSUPPORTED", message: "Current Numen JSONL adapter is read-only" } }); },
    start: function () { if (timer) return; poll(); timer = setInterval(poll, 2000); },
    stop: function () { if (timer) clearInterval(timer); timer = null; }, connected: function () { return connected; }
  };
}());
