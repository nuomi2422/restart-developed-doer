var NumenAdapter = (function () {
  var listeners = [];
  var timer = null;
  var seen = {};
  var connected = false;
  var health = {};
  var seenOrder = [];
  var badEvents = 0;   // 2026-10-01：坏事件计数（原来抛异常静默吞掉，页面看着像"没数据"）
  function remember(id) {
    if (seen[id]) return false;
    seen[id] = true;
    seenOrder.push(id);
    if (seenOrder.length > 10000) delete seen[seenOrder.shift()];
    return true;
  }

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
    if (c === "rdd") return "rdd";
    if (c === "ac") return "ac";
    if (c === "expmem") return "ai";             // 经验库 → AI 页
    if (c === "rdd_instrumentation") return "instrumentation"; // 埋点(第八系统) → 埋点页
    if (c === "learner") return "learner";       // 2026-10-01 学习者/携带器 → 独立页
    return c;
  }
  function key(event) {
    return event.event_id || [event.timestamp, event.category, event.type, JSON.stringify(event.data || {})].join("|");
  }
  // 2026-10-01：学习者事件（learner.jsonl）的摘要文本。逐类型给一句人话。
  // 缺的字段一律**如实说「无」**，不编默认值（B21 同一类：缺失要看起来像缺失）。
  function learnerSummary(type, d) {
    var q = (d.queue_depth != null) ? ("队列=" + d.queue_depth) : "";
    if (type === "noted") {
      var src = d.snapshot_source || "none";
      var snap = (d.snapshot_present === false)
        ? "环境快照=无（本条不可分级）"
        : ("环境快照=" + src + " 解析=" + (d.snapshot_parsed ? "是" : "否") + " 长度=" + (d.snapshot_chars || 0));
      var grade = (d.carrier_target ? ("分级=" + d.carrier_target + " hp=" + d.carrier_hp) : "分级=未产出");
      return "备忘入队 " + (d.memo_id || "?") + " · " + q + " · " + snap + " · " + grade;
    }
    if (type === "carrier_assessment") {
      var sc = (d.snapshot_present === false) ? "无快照（未分级）" : ("hp=" + (d.hp != null ? d.hp : "未解析"));
      return "携带器判定 " + (d.memo_id || "?") + " · target=" + (d.target || "无") + " · " + sc
        + " · 携带=" + ((d.carry || []).length) + " 项"
        + (d.carry_meaning ? "（" + d.carry_meaning + "）" : "");
    }
    if (type === "reviewed") {
      var v = (d.verdicts || []);
      var we = v.filter(function (x) { return (x.actions || []).indexOf("WRITE_EXPERIENCE") >= 0; }).length;
      return "复盘 " + (d.review_id || "?") + " · 判定=" + (d.reviewed || 0) + " 条 · 入库=" + (d.committed || 0)
        + " · 恢复=" + (d.restored || 0) + " · 建议沉淀=" + we + " 条 · " + q;
    }
    return type + " " + JSON.stringify(d);
  }
  function ingest(snapshot) {
    var categories = snapshot.categories || {};
    for (var source in categories) {
      var events = categories[source] || [];
      for (var i = 0; i < events.length; i++) {
        var event = events[i];
        var id = key(event);
        if (!remember(id)) continue;
        var evData = event.data || {};
        var displayText = "";
        // ★ 2026-10-01 修 P0：`c` 原来**从未声明**（那是 categoryOf() 的返回值，
        //   被漏掉了）→ 第一次循环就抛 ReferenceError:c is not defined
        //   → 整个 ingest 中断 → 后面所有 category（含 rdd）都灌不进 store
        //   → 页面永远显示「等待任务链观测」，且 catch 把异常静默吞掉、无任何痕迹。
        //   这就是「数据明明在 /api/snapshot 里，页面却空白」的真正原因。
        var c = categoryOf(event, source);
        try {
          if (c === "learner") {
          // 2026-10-01：学习者事件的摘要文本。
          // 注意 why 里有「**后续级未求值**」这类**必须让用户看见**的信息（携带器短路了）
          displayText = learnerSummary(event.type, evData);
        } else if (evData.kind) {
          // AC 事件：用 kind + tool/step 显示，如 "STEP_SUCCEEDED · rdd_whereami"
          displayText = evData.kind;
          if (evData.tool) displayText += " · " + evData.tool;
          if (evData.step_id) displayText += " (" + evData.step_id + ")";
        } else {
          displayText = evData.message || evData.text || evData.objective || evData.reason || evData.tool || event.type || "event";
        }
        notify({ type: "event", payload: {
          category: c,
          eventId: id,
          type: event.type || "unknown",
          text: displayText,
          payload: evData,
          timestamp: event.timestamp,
          source: event.source || "numen"
        }});
        } catch (err) {
          // ★ 2026-10-01：单条事件坏掉，不能拖垮整页。
          //   之前没有这层：一条坏数据 → 抛异常 → 整个 ingest 中断 → 后面所有
          //   category（含 rdd 任务链）全丢，而 catch 又静默吞掉，页面看着像"没数据"。
          //   这里改成"跳过这一条 + 计数 + 报警"，其余照常。
          badEvents++;
          if (badEvents <= 5) {
            notify({ type: "connection", payload: { connected: true, badEvents: badEvents,
              lastError: String(err && err.message || err), lastErrorType: event.type,
              lastErrorCategory: source } });
          }
        }
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
        if (!remember(id)) continue;
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
        try { var snapshot = JSON.parse(xhr.responseText); ingest(snapshot); connected = snapshot.ok === true; health = { observation: snapshot.observation, rddJournal: snapshot.rddJournal, contextJournal: snapshot.contextJournal }; }
        catch (e) { connected = false; }
      } else connected = false;
      notify({ type: "connection", payload: { connected: connected, observation: health.observation, rddJournal: health.rddJournal, contextJournal: health.contextJournal } });
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
