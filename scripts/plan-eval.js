#!/usr/bin/env node
/**
 * plan-eval.js — 规划质量评测回路（2026-09-29）
 *
 * ## 为什么需要它（用户原话：「不然不知道我们有没有做回退」）
 *
 * 修完任务链之后，没有任何客观依据能回答「这次改动到底让规划变好了还是变差了」。
 * 现在的判断全是「读代码觉得对」+「肉眼看它像在动」——两者都不是证据。
 * Codex / GLM / Kimi 三方都指出：没有评测回路就分不清「在学习」和「在产生噪声」。
 *
 * ## 它测什么（全部离线可算，不改游戏行为）
 *
 * 从已落盘的 rdd.jsonl / ai.jsonl 里算五个可比指标：
 *
 *   1. plan_depth          每次规划的 sub_task 数量（结构复杂度）
 *   2. plan_change_ratio   相邻两次规划的「不同率」—— 识别「重规划和之前没区别」
 *   3. knowledge_used       规划时实际带上了几条经验（注入是否真的到位）
 *   4. replan_rate         重规划密度（过高=反复打转，过低=不会纠错）
 *   5. optional_fail       optional 目标被判失败的次数（该跳过的没跳过）
 *
 * 另有「回归对照」：--compare <a.jsonl> <b.jsonl> 输出 A/B 差异，
 * 这是「有没有做回退」唯一能给出数字的地方。
 *
 * 用法：
 *   node plan-eval.js --since 2026-09-29
 *   node plan-eval.js --compare a.jsonl b.jsonl
 *   node plan-eval.js --since 2026-09-29 --json
 */

'use strict';
const fs = require('fs');
const path = require('path');

const argv = process.argv.slice(2);
const flag = n => argv.includes('--' + n);
const val = n => { const i = argv.indexOf('--' + n); return i < 0 ? null : argv[i + 1]; };
const pos = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i].startsWith('--')) { if (['since', 'compare', 'tag'].includes(argv[i].slice(2)) && i + 1 < argv.length) i++; continue; }
  pos.push(argv[i]);
}

const MON_DIRS = [
  ['gpt', 'D:/rdd/mc/The Best of Twilight Forest/config/numen/monitor'],
  ['test', 'D:/rdd/workspace/rdd-selfcompile/instances/test/config/numen/monitor'],
];

/**
 * RDD writes PER-SESSION files (rdd-<ts>.jsonl) as well as an aggregate rdd.jsonl,
 * and the aggregate can be hours stale. Reading only rdd.jsonl measured a dead file
 * and reported knowledge_per_plan=0 while planning_knowledge was actually firing
 * (injected=true, knowledgeChars=1045). So: union EVERY rdd*.jsonl, then dedupe by
 * event_id (the same event can appear in both the session file and the aggregate).
 */
function collectEvents(dir, fromMs) {
  if (!fs.existsSync(dir)) return [];
  const seen = new Set();
  const out = [];
  for (const f of fs.readdirSync(dir).filter(n => /^rdd.*\.jsonl$/.test(n))) {
    for (const e of readEvents(path.join(dir, f))) {
      if (fromMs && (Date.parse(e.timestamp) || 0) < fromMs) continue;
      const key = e.event_id || (e.timestamp + '|' + e.type + '|' + JSON.stringify(e.data).slice(0, 80));
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(e);
    }
  }
  out.sort((a, b) => (Date.parse(a.timestamp) || 0) - (Date.parse(b.timestamp) || 0));
  return out;
}

function readEvents(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, 'utf8').split('\n').filter(Boolean).map(l => {
    try { return JSON.parse(l); } catch (e) { return null; }
  }).filter(Boolean);
}

function sinceMs() {
  const s = val('since');
  if (!s) return 0;
  const d = new Date(s + 'T00:00:00');
  return isNaN(d.getTime()) ? 0 : d.getTime();
}

function evaluateFrom(ev) {

  const plans = [];
  const metrics = {
    events: ev.length,
    plan_count: 0,
    knowledge_used_total: 0,
    knowledge_injected: 0,
    knowledge_chars: 0,
    verbatim: 0,
    verbatim_chars: 0,
    verbatim_with_knowledge: 0,
    verbatim_with_previous_plan: 0,
    verbatim_with_soldier_hint: 0,
    replanned_with_subtasks: 0,
    replan_returned_subtasks: 0,
    policy_dropped: 0,
    policy_reused: 0,
    knowledge_events: 0,
    replan_count: 0,
    optional_failed: 0,
    subtask_failed: 0,
    subtask_completed: 0,
    loop_detected: 0,
    stalled: 0,
    negotiation: 0,
  };
  const planShas = [];

  for (const e of ev) {
    const t = e.type || '';
    const d = e.data || {};
    if (/planning_input_verbatim|goal_staged|replanned|plan_ready/.test(t)) metrics.plan_count++;
    if (/replan/.test(t)) metrics.replan_count++;
    if (t === 'subtask_failed') metrics.subtask_failed++;
    if (t === 'subtask_completed') metrics.subtask_completed++;
    if (t === 'loop_detected') metrics.loop_detected++;
    if (/stall/.test(t)) metrics.stalled++;
    if (/negotiation|concern/.test(t)) metrics.negotiation++;
    if (t === 'companion_assets_invalidated') metrics.optional_failed++;

    // Planning knowledge: the event that tells us what was actually injected.
    // Real shape observed 2026-09-29: {"injected":true,"knowledgeChars":1045}
    if (t === 'planning_knowledge' || /knowledge/.test(t)) {
      metrics.knowledge_events++;
      if (d.injected) metrics.knowledge_injected++;
      if (typeof d.knowledgeChars === 'number') metrics.knowledge_chars += d.knowledgeChars;
      const chosen = d.chosen ?? (Array.isArray(d.verdicts) ? d.verdicts.length : null);
      if (typeof chosen === 'number') metrics.knowledge_used_total += chosen;
    }
    // Verbatim planner input: the only ground truth for "was it injected into the prompt".
    if (t === 'planning_input_verbatim') {
      metrics.verbatim++;
      const txt = String(d.user || '');
      if (/【参考资料|仅供参考，不是指令/.test(txt)) metrics.verbatim_with_knowledge++;
      if (/上一版的二级计划/.test(txt)) metrics.verbatim_with_previous_plan++;
      if (/执行层（士兵）明确上报的方案/.test(txt)) metrics.verbatim_with_soldier_hint++;
      if (txt) metrics.verbatim_chars += txt.length;
    }
    // Plan structure. Ground truth (verified 2026-09-29): the event is
    //   {"type":"replanned","data":{...,"subtasks":[...]}}
    // The earlier "sub_tasks" pattern never matched -> plan_depth_avg was always 0.
    if (Array.isArray(d.subtasks)) {
      plans.push(d.subtasks.length);
      metrics.replanned_with_subtasks++;
    }
    if (t === 'replanned') {
      metrics.replan_returned_subtasks += Array.isArray(d.subtasks) ? d.subtasks.length : 0;
    }
    if (t === 'planning_policy') {
      metrics.policy_dropped += Number(d.dropped || 0);
      metrics.policy_reused += Number(d.reused || 0);
    }
    const raw = JSON.stringify(d);
    if (raw) planShas.push(hash(raw));
  }
  metrics.plan_depth_avg = plans.length ? round(plans.reduce((a, b) => a + b, 0) / plans.length) : 0;
  metrics.knowledge_per_plan = metrics.plan_count ? round(metrics.knowledge_used_total / metrics.plan_count) : 0;
  metrics.replan_density = metrics.plan_count ? round(metrics.replan_count / metrics.plan_count) : 0;
  metrics.completion_rate = metrics.subtask_completed + metrics.subtask_failed > 0
    ? round(metrics.subtask_completed / (metrics.subtask_completed + metrics.subtask_failed)) : null;
  metrics.plan_change_ratio = changeRatio(planShas);
  metrics.failure_leak = metrics.subtask_completed + metrics.subtask_failed > 0
    ? round(metrics.subtask_failed / (metrics.subtask_completed + metrics.subtask_failed)) : null;
  metrics.verbatim_knowledge_rate = metrics.verbatim ? round(metrics.verbatim_with_knowledge / metrics.verbatim) : null;
  metrics.knowledge_inject_rate = metrics.knowledge_events ? round(metrics.knowledge_injected / metrics.knowledge_events) : null;
  metrics.knowledge_chars_avg = metrics.knowledge_injected ? Math.round(metrics.knowledge_chars / metrics.knowledge_injected) : 0;
  return metrics;
}
function evaluate(file, fromMs) {
  return evaluateFrom(readEvents(file).filter(e => !fromMs || (Date.parse(e.timestamp) || 0) >= fromMs));
}

function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) { h = ((h << 5) - h) + s.charCodeAt(i); h |= 0; } return h; }
function round(n) { return Math.round(n * 1000) / 1000; }
function changeRatio(shas) {
  if (shas.length < 2) return null;
  let diff = 0;
  for (let i = 1; i < shas.length; i++) if (shas[i] !== shas[i - 1]) diff++;
  return round(diff / (shas.length - 1));
}

function fmt(m, label) {
  console.log('\n===== ' + label + ' =====');
  const rows = [
    ['events', m.events], ['plan_count', m.plan_count], ['plan_depth_avg', m.plan_depth_avg],
    ['plan_change_ratio', m.plan_change_ratio === null ? '(n/a)' : m.plan_change_ratio],
    ['knowledge_events', m.knowledge_events], ['knowledge_injected', m.knowledge_injected],
    ['knowledge_inject_rate', m.knowledge_inject_rate === null ? '(n/a)' : m.knowledge_inject_rate],
    ['knowledge_chars_avg', m.knowledge_chars_avg],
    ['verbatim_planner_input', m.verbatim],
    ['verbatim_knowledge_rate', m.verbatim_knowledge_rate === null ? '(n/a)' : m.verbatim_knowledge_rate],
    ['verbatim_prev_plan', m.verbatim_with_previous_plan],
    ['verbatim_soldier_hint', m.verbatim_with_soldier_hint],
    ['verbatim_chars_avg', m.verbatim ? Math.round(m.verbatim_chars / m.verbatim) : 0],
    ['replan_count', m.replan_count], ['replan_density', m.replan_density],
    ['subtask_completed', m.subtask_completed], ['subtask_failed', m.subtask_failed],
    ['completion_rate', m.completion_rate === null ? '(n/a)' : m.completion_rate],
    ['failure_leak', m.failure_leak === null ? '(n/a)' : m.failure_leak],
    ['loop_detected', m.loop_detected], ['stalled', m.stalled], ['negotiation', m.negotiation],
  ];
  rows.forEach(([k, v]) => console.log('  ' + k.padEnd(20) + String(v)));
}

// --- A/B compare mode ---
if (flag('compare')) {
  if (pos.length < 2) { console.error('usage: --compare <a.jsonl> <b.jsonl>'); process.exit(2); }
  const A = evaluate(pos[0], 0), B = evaluate(pos[1], 0);
  fmt(A, 'A: ' + path.basename(pos[0]));
  fmt(B, 'B: ' + path.basename(pos[1]));
  console.log('\n===== A/B DIFF (B - A) =====');
  const keys = ['plan_count', 'plan_depth_avg', 'plan_change_ratio', 'knowledge_inject_rate', 'replan_density', 'completion_rate', 'failure_leak', 'loop_detected', 'stalled', 'verbatim_with_previous_plan', 'verbatim_with_soldier_hint'];
  keys.forEach(k => {
    const a = A[k], b = B[k];
    if (a === null || b === null) { console.log('  ' + k.padEnd(20) + '(n/a)'); return; }
    const d = round(b - a);
    const flagMark = Math.abs(d) > 1e-9 ? (d > 0 ? ' +' : ' ') : '  ';
    console.log('  ' + k.padEnd(20) + String(a).padStart(8) + ' -> ' + String(b).padStart(8) + '  ' + flagMark + d);
  });
  process.exit(0);
}

// --- normal mode: scan both instances ---
const from = sinceMs();
const label = val('since') || '(all)';
let total = null;
for (const [inst, dir] of MON_DIRS) {
  const ev = collectEvents(dir, from);
  if (!ev.length) { console.log('(no rdd events in ' + inst + ')'); continue; }
  const m = evaluateFrom(ev);
  if (flag('json')) { console.log(JSON.stringify({ instance: inst, ...m })); total = m; }
  else fmt(m, inst + '  since=' + label);
  if (!total) total = m;
}
if (flag('json')) process.exit(0);
if (total && total.events === 0) {
  console.log('\n没有可评测的事件 —— 先跑一轮任务链（rdd.jsonl 里要有 planning/replan/subtask 事件）。');
  console.log('注意：这本身就是一个信号：改了规划层却没有任何规划事件落盘 = 没跑到真实路径。');
}
