#!/usr/bin/env node
/**
 * survival-eval.js — AI 生存能力评测（2026-09-29）
 *
 * 起因：用户问「不知道它执行得怎么样」，而当时唯一的信号是「有没有死」。
 * 实测事故：换 thinking 极重的模型后，2.5 分钟连死 5 次、每次丢光 31 项背包。
 * 光看「死了」不足以定位，也不足以判断改动有没有让情况变好/变坏。
 *
 * 指标取自 Codex 审稿建议（按重要性排序），全部从 jsonl 可算：
 *   1. 死亡强度 / 连死率        2. 死亡恢复成功率·耗时
 *   3. 资产损失暴露             4. 有效任务进展（去重）
 *   5. 停滞率                   6. 工具协议失败率（6b 按工具明细：谁在失败、谁永远没结果）
 *   7. 决策延迟 P50/P95         8. 压缩负担
 *
 * 用法：
 *   node survival-eval.js [--since ISO] [--instance gpt|test] [--top N] [--json] [--compare a.jsonl?]
 */

'use strict';
const fs = require('fs');
const path = require('path');

const argv = process.argv.slice(2);
const flag = n => argv.includes('--' + n);
const val = n => { const i = argv.indexOf('--' + n); return i < 0 ? null : argv[i + 1]; };

const DIRS = {
  gpt: 'E:/.minecraft/versions/The Best of Twilight Forest/config/numen/monitor',
  test: 'E:/restart developing doer/rdd-selfcompile/instances/test/config/numen/monitor',
};

function load(dir) {
  const seen = new Set(), out = [];
  if (!fs.existsSync(dir)) return out;
  for (const f of fs.readdirSync(dir).filter(n => /\.jsonl$/.test(n))) {
    let lines; try { lines = fs.readFileSync(path.join(dir, f), 'utf8').split('\n').filter(Boolean); } catch (e) { continue; }
    for (const l of lines) {
      let e; try { e = JSON.parse(l); } catch (x) { continue; }
      // event_id is NOT unique (per-session counter that resets) -> dedupe on content.
      const k = e.timestamp + '|' + e.type + '|' + JSON.stringify(e.data || {}).slice(0, 200) + '|' + f.replace(/-\d+\.jsonl$/, '');
      if (seen.has(k)) continue; seen.add(k);
      e.__file = f; out.push(e);
    }
  }
  out.sort((a, b) => (Date.parse(a.timestamp) || 0) - (Date.parse(b.timestamp) || 0));
  return out;
}
const pct = (a, b) => b ? Math.round(a / b * 1000) / 10 : null;
function quantiles(arr) {
  if (!arr.length) return null;
  const s = [...arr].sort((x, y) => x - y);
  const q = p => s[Math.min(s.length - 1, Math.floor(s.length * p))];
  return { p50: q(0.5), p90: q(0.9), max: s[s.length - 1], n: s.length };
}

function evaluate(dir, sinceMs) {
  let ev = load(dir);
  if (sinceMs) ev = ev.filter(e => (Date.parse(e.timestamp) || 0) >= sinceMs);

  const m = { events: ev.length, span: null };
  if (!ev.length) return m;
  const t0 = Date.parse(ev[0].timestamp), t1 = Date.parse(ev[ev.length - 1].timestamp);
  const hours = Math.max((t1 - t0) / 3600000, 1 / 60);
  m.span = ev[0].timestamp.slice(0, 16) + ' .. ' + ev[ev.length - 1].timestamp.slice(0, 16);
  m.hours = Math.round(hours * 100) / 100;

  // ---- 1. deaths ----
  const deaths = ev.filter(e => /(^|\.)death$/i.test(e.type));
  m.deaths = deaths.length;
  m.deaths_per_hour = Math.round(deaths.length / hours * 10) / 10;
  // re-death within 10 min
  let chain = 0;
  for (let i = 1; i < deaths.length; i++) {
    const dt = (Date.parse(deaths[i].timestamp) - Date.parse(deaths[i - 1].timestamp)) / 60000;
    if (dt <= 10) chain++;
  }
  m.death_chain_rate = deaths.length > 1 ? pct(chain, deaths.length - 1) : null;

  // ---- 2. rear(/recovery) ----
  const recStart = ev.filter(e => /recover|repair_dispatch|recovery_started/i.test(e.type));
  const recOk = ev.filter(e => /recover(y|ed)_(ok|complete)|recovery_success/i.test(e.type));
  m.recovery_starts = recStart.length || null;
  m.recovery_success = recOk.length || null;
  m.recovery_success_rate = (recStart.length && recOk.length) ? pct(recOk.length, recStart.length) : null;

  // ---- 3. asset loss exposure ----
  const inval = ev.filter(e => /invalidated/i.test(e.type));
  const loses = inval.map(e => e.data?.invalidatedInventoryEntries).filter(n => typeof n === 'number');
  m.asset_invalidations = inval.length;
  m.asset_loss_total = loses.reduce((a, b) => a + b, 0);
  m.asset_loss_per_event = loses.length ? Math.round(loses.reduce((a, b) => a + b, 0) / loses.length * 10) / 10 : null;
  m.asset_loss_distinct_values = [...new Set(loses)];

  // ---- 4. effective progress ----
  const done = ev.filter(e => e.type === 'subtask_completed');
  const failed = ev.filter(e => e.type === 'subtask_failed');
  const doneIds = new Set(done.map(e => e.data?.subtask).filter(Boolean));
  m.subtask_completed_raw = done.length;
  m.subtask_completed_distinct = doneIds.size;
  m.subtask_failed = failed.length;
  m.completion_rate = (done.length + failed.length) ? pct(done.length, done.length + failed.length) : null;
  m.progress_per_hour = Math.round(doneIds.size / hours * 100) / 100;

  // ---- 5. stall rate ----
  const stalled = ev.filter(e => e.type === 'subtask_stalled');
  const stalledIds = new Set(stalled.map(e => e.data?.subtask).filter(Boolean));
  const startedIds = new Set([
    ...done.map(e => e.data?.subtask), ...failed.map(e => e.data?.subtask),
    ...stalled.map(e => e.data?.subtask),
  ].filter(Boolean));
  m.stalled_distinct = stalledIds.size;
  m.subtasks_seen = startedIds.size;
  m.stall_rate = startedIds.size ? pct(stalledIds.size, startedIds.size) : null;

  // ---- 6. tool protocol failures ----
  const calls = ev.filter(e => e.type === 'tool_call');
  const rej = ev.filter(e => e.type === 'tool_rejected');
  const protoRej = rej.filter(e => /invalid arguments|Expected BEGIN|unknown item|Deserializ|JsonSyntax|List\.isEmpty|IllegalState/i.test(e.data?.reason || ''));
  const riskRej = rej.filter(e => /risk|gate|policy|not allowed|denied/i.test(e.data?.reason || ''));
  m.tool_calls = calls.length;
  m.tool_rejected_total = rej.length;
  m.tool_protocol_failures = protoRej.length;
  m.tool_risk_gate_rejections = riskRej.length;
  m.tool_protocol_fail_rate = calls.length ? pct(protoRej.length, calls.length) : null;

  // ---- 6b. per-tool breakdown ----
  // Why this block exists: the aggregate "rejected=N" above cannot answer the two questions that
  // actually locate a broken tool -- WHICH tool, and WHICH reason. It also cannot see the tools
  // that never fail loudly: the ones that are called hundreds of times and emit no result event
  // at all, so the logs cannot tell you whether they worked.
  //
  // Two traps measured on live data, both of which silently corrupt the numbers:
  //   1. tool_call_id is NOT unique -- the per-session counter resets, so "mcp-1" is reused
  //      100+ times. Keying a Map by it under-counts calls by ~20%. Pair by event identity and
  //      drain a FIFO count per id instead.
  //   2. A result is truncated to ~303 chars, so JSON.parse always fails; and matching Chinese
  //      prose like "no background task" against a failure regex reports 96% failure for a tool
  //      that actually fails 1 time in 1739. Only the declared "success" field decides.
  const DECLARED = /"success"\s*:\s*(true|false)/;
  const byTool = {};
  const open = new Map();                       // tool_call_id -> calls still awaiting a result
  const drain = id => {
    if (id == null) return;
    const n = open.get(id) || 0;
    if (n <= 1) open.delete(id); else open.set(id, n - 1);
  };
  for (const e of ev) {
    const tool = e.data?.tool;
    if (!tool) continue;
    const t = byTool[tool] = byTool[tool] || { calls: 0, results: 0, rejected: 0, failed: 0, loops: 0, reasons: {} };
    if (e.type === 'tool_call') {
      t.calls++;
      const id = e.data?.tool_call_id;
      if (id != null) open.set(id, (open.get(id) || 0) + 1);
    } else if (e.type === 'tool_rejected') {
      t.rejected++;
      const reason = String(e.data?.reason || '?').slice(0, 120);
      t.reasons[reason] = (t.reasons[reason] || 0) + 1;
      drain(e.data?.tool_call_id);
    } else if (e.type === 'tool_result') {
      t.results++;
      const declared = DECLARED.exec(String(e.data?.result ?? ''));
      if (declared && declared[1] === 'false') t.failed++;   // absent field = not counted as failure
      drain(e.data?.tool_call_id);
    } else if (e.type === 'tool_loop') {
      // Emitted by ToolCallLoopWatch (game side) once the same tool has failed the same way
      // WARN_AT times in a row. Deliberately NOT a tool_rejected: that one means "this call
      // failed", this one means "it is repeating" -- mixing them corrupts the reason clusters.
      t.loops++;
      const fp = String(e.data?.fingerprint || '?').slice(0, 120);
      t.reasons['[loop x' + (e.data?.repeat_count ?? '?') + '] ' + fp] =
        (t.reasons['[loop x' + (e.data?.repeat_count ?? '?') + '] ' + fp] || 0) + 1;
    }
  }
  const toolRows = Object.entries(byTool).map(([tool, v]) => ({
    tool, ...v,
    zeroResult: v.calls > 0 && v.results === 0,
    topReason: Object.entries(v.reasons).sort((a, b) => b[1] - a[1])[0]?.[0] || null,
  }));
  m.tools_total = toolRows.length;
  m.tools_zero_result = toolRows.filter(t => t.zeroResult).map(t => t.tool);
  m.tools_unpaired_calls = [...open.values()].reduce((a, b) => a + b, 0);
  m.tool_loop_events = toolRows.reduce((a, t) => a + t.loops, 0);
  m.tools_in_loop = toolRows.filter(t => t.loops > 0)
    .sort((a, b) => b.loops - a.loops).map(t => t.tool + ' x' + t.loops);
  m.tools = toolRows.sort((a, b) =>
    (b.zeroResult - a.zeroResult) || (b.loops - a.loops) || ((b.rejected + b.failed) - (a.rejected + a.failed)) || (b.calls - a.calls));

  // ---- 7. decision latency ----
  const req = ev.filter(e => e.type === 'llm_request');
  const resp = ev.filter(e => e.type === 'llm_response');
  const lat = [];
  for (const r of resp) {
    const d = e2 => (e2.data?.durationMs ?? e2.data?.latencyMs);
    const ms = d(r);
    if (typeof ms === 'number') lat.push(ms);
  }
  m.decision_latency = quantiles(lat);

  // ---- 8. compaction burden ----
  const llmPhases = ev.filter(e => e.data?.phase);
  const comp = llmPhases.filter(e => e.data.phase === 'compaction');
  m.llm_phase_events = llmPhases.length;
  m.compaction_calls = comp.length;
  m.compaction_rate = llmPhases.length ? pct(comp.length, llmPhases.length) : null;
  // model split
  const byModel = {};
  llmPhases.forEach(e => {
    const mm = e.data.model || '?';
    byModel[mm] = byModel[mm] || { total: 0, compaction: 0 };
    byModel[mm].total++;
    if (e.data.phase === 'compaction') byModel[mm].compaction++;
  });
  m.by_model = Object.fromEntries(Object.entries(byModel).map(([k, v]) => [k, {
    total: v.total, compaction: v.compaction, rate: pct(v.compaction, v.total),
  }]));

  return m;
}

function fmt(m, label) {
  console.log('\n========== ' + label + ' ==========');
  if (!m.events) { console.log('  (no events)'); return; }
  console.log('  span            ' + m.span + '   (' + m.hours + 'h)');
  console.log('  events          ' + m.events);
  console.log('  --- 1 死亡 ---');
  console.log('  deaths          ' + m.deaths + '   (' + m.deaths_per_hour + '/h)');
  console.log('  death_chain%    ' + m.death_chain_rate + '   (10 分钟内再死比例)');
  console.log('  --- 3 资产损失 ---');
  console.log('  invalidations   ' + m.asset_invalidations + '   total丢=' + m.asset_loss_total + '   均值=' + m.asset_loss_per_event);
  console.log('  distinct值      ' + JSON.stringify(m.asset_loss_distinct_values));
  console.log('  --- 4 进展 ---');
  console.log('  completed(去重) ' + m.subtask_completed_distinct + ' / raw ' + m.subtask_completed_raw + '   failed=' + m.subtask_failed);
  console.log('  completion%     ' + m.completion_rate);
  console.log('  progress/h      ' + m.progress_per_hour);
  console.log('  --- 5 停滞 ---');
  console.log('  stalled distinct' + m.stalled_distinct + ' / seen ' + m.subtasks_seen + '   = ' + m.stall_rate + '%');
  console.log('  --- 6 工具协议 ---');
  console.log('  tool_calls      ' + m.tool_calls + '   rejected=' + m.tool_rejected_total + ' (协议' + m.tool_protocol_failures + ' / 风险门' + m.tool_risk_gate_rejections + ')');
  console.log('  protocol_fail%  ' + m.tool_protocol_fail_rate);
  console.log('  --- 6b 工具明细（零结果 / 循环排最前）---');
  console.log('  工具种类        ' + m.tools_total + '   永久零结果=' + (m.tools_zero_result || []).length
    + (m.tools_zero_result && m.tools_zero_result.length ? '  ' + m.tools_zero_result.join(', ') : '')
    + '   尾部未配对调用=' + m.tools_unpaired_calls);
  console.log('  同错循环事件    ' + (m.tool_loop_events || 0)
    + ((m.tools_in_loop || []).length ? '   ' + m.tools_in_loop.join(', ') : ''));
  (m.tools || []).slice(0, TOP).forEach(t => console.log('     ' + t.tool.padEnd(24)
    + ' calls=' + String(t.calls).padStart(5)
    + ' result=' + String(t.results).padStart(5)
    + ' rej=' + String(t.rejected).padStart(4)
    + ' fail=' + String(t.failed).padStart(4)
    + ' loop=' + String(t.loops || 0).padStart(3)
    + (t.zeroResult ? '   <== 零结果，从日志判断不出成功没有' : '')
    + (t.loops ? '   <== 同一错误连拒，模型在原地打转' : '')
    + (t.topReason ? '   | ' + t.topReason.slice(0, 70) : '')));
  console.log('  --- 7 延迟 ---');
  console.log('  决策延迟        ' + (m.decision_latency ? 'p50=' + m.decision_latency.p50 + ' p90=' + m.decision_latency.p90 + ' max=' + m.decision_latency.max + ' (n=' + m.decision_latency.n + ')' : '(无 durationMs 字段)'));
  console.log('  --- 8 压缩负担 ---');
  console.log('  compaction%     ' + m.compaction_rate + '   (' + m.compaction_calls + '/' + m.llm_phase_events + ')');
  Object.entries(m.by_model || {}).forEach(([k, v]) => console.log('     ' + k.padEnd(28) + ' total=' + String(v.total).padStart(5) + ' compaction=' + String(v.compaction).padStart(4) + ' = ' + v.rate + '%'));
}

const since = val('since');
// Bare ISO strings parse as LOCAL time in Node; force UTC so --since means what it says.
const sinceMs = since ? (Date.parse(since.length === 10 ? since + 'T00:00:00Z' : (since.endsWith('Z') ? since : since + 'Z')) || 0) : 0;
const want = val('instance');
const TOP = parseInt(val('top') || '12', 10) || 12;
const targets = want ? [want] : ['gpt', 'test'];
const results = {};
for (const inst of targets) results[inst] = evaluate(DIRS[inst], sinceMs);

if (flag('json')) { console.log(JSON.stringify(results, null, 1)); }
else for (const inst of targets) fmt(results[inst], inst + (since ? '  since=' + since : ''));
