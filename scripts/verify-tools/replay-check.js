#!/usr/bin/env node
'use strict';
/*
 * replay-check.js —— E1 回放对账夹具（只读，不改任何东西）。
 *
 * 回答的问题：**这批对话历史发给模型合法吗？**
 * 把监控日志里每次 LLM 请求的 wire body（llm_request.request.messages）重放一遍结构校验：
 *   - assistant.tool_calls 的每个 id，必须在下一轮 assistant 之前被对应 tool 消息应答
 *     → 否则 orphan_tool_call（2026-10-06 实机 400 的根因类）
 *   - tool 消息的 tool_call_id 必须来自紧邻的上一条 assistant.tool_calls
 *     → 否则 orphan_tool_result（压缩/瘦身把配对拆了的信号）
 *   - tool_calls 重复 id / 缺 id / 缺 function.name；tool 消息缺 tool_call_id
 *   - messages 为空；首条不是 system（回放窗口起点异常）
 *   - assistant 既无 content 又无 tool_calls
 *   - 与 llm_usage 的 truncated=true 交叉引用：被截断的请求如果有违规，样本会标 (truncated)
 *
 * 与 econ-report.js 分工：那个管 tokens/耗时/重试（钱），这个管消息合法性（正确性）。
 *
 * 数据源：<monitor>/context.jsonl(执行AI) rdd.jsonl(规划) learner.jsonl(学习) ai.jsonl(自编译)
 *         的 llm_request 事件（request = 真实发给服务商的 body）。
 *
 * 用法：
 *   node replay-check.js                        # 默认 live 监控目录，全部历史
 *   node replay-check.js --since 2026-10-06     # 只看某天(本地时区)起
 *   node replay-check.js --since 2026-10-06T18:00 --limit 50
 *   node replay-check.js --file <单个jsonl>     # 扫轮转历史文件（如 context-1791*.jsonl）
 *   node replay-check.js --json                 # 输出完整 JSON（给别的脚本吃；PS 5.1 的 ConvertFrom-Json 会被中文编码坑，请用 node 消费）
 *   node replay-check.js --selftest             # 合成样本自测（证明能抓到历史 400 类形）
 *
 * 退出码：0 = 全部合法；1 = 有违规；3 = 环境错（目录不存在）。
 */

const fs = require('fs');
const path = require('path');

const EXIT_OK = 0;
const EXIT_BAD = 1;
const EXIT_USAGE = 3;

const DEFAULT_DIR = 'E:\\.minecraft\\versions\\The Best of Twilight Forest\\config\\numen\\monitor';
const REQUEST_FILES = ['context.jsonl', 'rdd.jsonl', 'learner.jsonl', 'ai.jsonl'];

function parseArgs(argv) {
  const out = { dir: DEFAULT_DIR, file: null, since: null, until: null, json: false, limit: 20, selftest: false, help: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dir') out.dir = argv[++i];
    else if (a === '--file') out.file = argv[++i];
    else if (a === '--since') out.since = argv[++i];
    else if (a === '--until') out.until = argv[++i];
    else if (a === '--limit') out.limit = Number(argv[++i]) || 20;
    else if (a === '--json') out.json = true;
    else if (a === '--selftest') out.selftest = true;
    else if (a === '--help' || a === '-h') out.help = true;
    else { console.error(`unknown arg: ${a}`); process.exit(EXIT_USAGE); }
  }
  return out;
}

function tsOf(ms) {
  if (ms == null) return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(ms)) return new Date(ms + 'T00:00:00').getTime();
  const t = Date.parse(ms);
  return Number.isNaN(t) ? null : t;
}

/** 逐行读 JSONL；坏行跳过并计数（观测文件永远可能有半行）。 */
function readJsonl(file, onRow) {
  let bad = 0, rows = 0;
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch (e) { return { rows, bad, missing: true }; }
  for (const line of text.split(/\r?\n/)) {
    if (!line) continue;
    try { onRow(JSON.parse(line)); rows++; } catch (e) { bad++; }
  }
  return { rows, bad, missing: false };
}

function inWindow(ts, since, until) {
  if (ts == null) return true;
  if (since != null && ts < since) return false;
  if (until != null && ts > until) return false;
  return true;
}

/** 单条请求的消息序列校验；返回违规数组（空 = 合法）。 */
function validateMessages(msgs) {
  const v = [];
  if (!Array.isArray(msgs) || msgs.length === 0) {
    v.push({ type: 'empty_messages', detail: '' });
    return v;
  }
  if (msgs[0] && msgs[0].role !== 'system') {
    v.push({ type: 'first_not_system', detail: 'first=' + (msgs[0] && msgs[0].role) });
  }
  let pending = new Set();
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i] || {};
    if (m.role === 'assistant') {
      if (pending.size > 0) {
        for (const id of pending) {
          v.push({ type: 'orphan_tool_call', detail: `id=${id} (无 tool 应答，直到 msg#${i})` });
        }
        pending = new Set();
      }
      if (Array.isArray(m.tool_calls)) {
        for (const tc of m.tool_calls) {
          const id = tc && tc.id;
          const fn = tc && tc.function && tc.function.name;
          if (!id) v.push({ type: 'malformed_tool_call', detail: `msg#${i} 缺 id` });
          if (!fn) v.push({ type: 'malformed_tool_call', detail: `msg#${i} id=${id} 缺 function.name` });
          if (id) {
            if (pending.has(id)) v.push({ type: 'duplicate_tool_call_id', detail: `id=${id} msg#${i}` });
            pending.add(id);
          }
        }
      } else if (m.content == null || (typeof m.content === 'string' && m.content.trim() === '')) {
        v.push({ type: 'empty_assistant', detail: `msg#${i} 无 content 且无 tool_calls` });
      }
    } else if (m.role === 'tool') {
      const id = m.tool_call_id;
      if (!id) v.push({ type: 'missing_tool_call_id', detail: `msg#${i}` });
      else if (!pending.has(id)) v.push({ type: 'orphan_tool_result', detail: `id=${id} msg#${i}（无对应 tool_call）` });
      else pending.delete(id);
    }
  }
  if (pending.size > 0) {
    for (const id of pending) v.push({ type: 'orphan_tool_call', detail: `id=${id} (到末尾都没有 tool 应答)` });
  }
  return v;
}

/** 自测夹具：用合成样本证明校验器真能抓到历史 400 类形（tool 开头窗口/孤儿配对等）。 */
function selftest() {
  const cases = [
    { name: '合法：system+user+assistant(tc)+tool', msgs: [
        { role: 'system', content: 's' }, { role: 'user', content: 'u' },
        { role: 'assistant', tool_calls: [{ id: 'a', function: { name: 'f' } }] },
        { role: 'tool', tool_call_id: 'a', content: 'r' } ], expect: [] },
    { name: '合法：并行双 tool_call 都应答', msgs: [
        { role: 'system', content: 's' },
        { role: 'assistant', tool_calls: [{ id: 'a', function: { name: 'f' } }, { id: 'b', function: { name: 'g' } }] },
        { role: 'tool', tool_call_id: 'a', content: '1' },
        { role: 'tool', tool_call_id: 'b', content: '2' },
        { role: 'user', content: 'u' } ], expect: [] },
    { name: '历史400类形：窗口以 tool 开头（assistant 被压掉）', msgs: [
        { role: 'tool', tool_call_id: 'x', content: 'r' } ], expect: ['first_not_system', 'orphan_tool_result'] },
    { name: '孤儿 tool_call：没有 tool 应答', msgs: [
        { role: 'system', content: 's' },
        { role: 'assistant', tool_calls: [{ id: 'a', function: { name: 'f' } }] },
        { role: 'user', content: 'u' } ], expect: ['orphan_tool_call'] },
    { name: '重复 tool_call id', msgs: [
        { role: 'system', content: 's' },
        { role: 'assistant', tool_calls: [{ id: 'a', function: { name: 'f' } }, { id: 'a', function: { name: 'f' } }] },
        { role: 'tool', tool_call_id: 'a', content: '1' } ], expect: ['duplicate_tool_call_id'] },
    { name: '畸形 tool_call：缺 function.name', msgs: [
        { role: 'system', content: 's' },
        { role: 'assistant', tool_calls: [{ id: 'a' }] },
        { role: 'tool', tool_call_id: 'a', content: '1' } ], expect: ['malformed_tool_call'] },
    { name: '空 assistant（无 content 无 tool_calls）', msgs: [
        { role: 'system', content: 's' }, { role: 'assistant', content: '' } ], expect: ['empty_assistant'] },
  ];
  let fail = 0;
  for (const c of cases) {
    const got = validateMessages(c.msgs).map((v) => v.type);
    const gotSet = new Set(got);
    const ok = c.expect.every((t) => gotSet.has(t)) && (c.expect.length > 0 || got.length === 0);
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name}  → ${got.length ? got.join(',') : '（无违规）'}`);
    if (!ok) { fail++; console.log(`      期望包含: ${c.expect.join(',') || '（无）'}`); }
  }
  console.log(fail === 0 ? `selftest: ${cases.length}/${cases.length} 全过` : `selftest: ${fail} 个失败`);
  return fail === 0 ? EXIT_OK : EXIT_BAD;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('用法: node replay-check.js [--dir <monitor目录>] [--file <单个jsonl>] [--since <ISO>] [--until <ISO>] [--limit N] [--json] [--selftest]');
    process.exit(EXIT_OK);
  }
  if (args.selftest) {
    process.exit(selftest());
  }
  if (args.file) {
    args.dir = path.dirname(args.file);
  }
  const requestFiles = args.file ? [path.basename(args.file)] : REQUEST_FILES;
  if (!fs.existsSync(args.dir)) {
    console.error(`环境错：监控目录不存在: ${args.dir}`);
    process.exit(EXIT_USAGE);
  }
  const since = tsOf(args.since);
  const until = tsOf(args.until);

  // 先收 truncated 的 requestId（llm_usage），供交叉引用。
  const truncatedIds = new Set();
  const fileStats = [];
  for (const f of requestFiles) {
    const full = path.join(args.dir, f);
    const r = readJsonl(full, (row) => {
      if (row.type !== 'llm_usage') return;
      const d = row.data || {};
      if (d.truncated === true && d.requestId) truncatedIds.add(d.requestId);
    });
    fileStats.push({ name: f, rows: r.rows, bad: r.bad, missing: r.missing });
  }

  // 逐请求校验。
  let requests = 0, messagesTotal = 0;
  const roles = {};
  const perPhase = new Map();
  const violations = [];
  const violationCounts = {};
  for (const f of requestFiles) {
    const full = path.join(args.dir, f);
    readJsonl(full, (row) => {
      if (row.type !== 'llm_request') return;
      const ts = Date.parse(row.timestamp);
      if (!inWindow(Number.isNaN(ts) ? null : ts, since, until)) return;
      const d = row.data || {};
      const req = d.request;
      const msgs = req && Array.isArray(req.messages) ? req.messages : null;
      requests++;
      const phase = d.phase || '?';
      const model = d.model || '?';
      let v = [];
      if (!msgs) {
        v = [{ type: 'empty_messages', detail: 'request.messages 缺失或不是数组' }];
      } else {
        messagesTotal += msgs.length;
        for (const m of msgs) {
          const role = (m && m.role) || '?';
          roles[role] = (roles[role] || 0) + 1;
        }
        v = validateMessages(msgs);
      }
      const st = perPhase.get(phase) || { requests: 0, messages: 0, violations: 0 };
      st.requests++;
      st.messages += msgs ? msgs.length : 0;
      st.violations += v.length;
      perPhase.set(phase, st);
      for (const one of v) violationCounts[one.type] = (violationCounts[one.type] || 0) + 1;
      if (v.length > 0) {
        violations.push({
          ts: Number.isNaN(ts) ? null : new Date(ts).toISOString(),
          file: f, requestId: d.requestId || null, phase, model,
          truncated: truncatedIds.has(d.requestId),
          messages: msgs ? msgs.length : 0,
          violations: v,
        });
      }
    });
  }

  const totalViolations = violations.reduce((a, r) => a + r.violations.length, 0);

  if (args.json) {
    console.log(JSON.stringify({
      dir: args.dir, since: args.since, until: args.until,
      files: fileStats,
      requests, messagesTotal, roles,
      perPhase: [...perPhase.entries()].map(([phase, s]) => ({ phase, ...s })),
      truncatedRequests: truncatedIds.size,
      violationCounts, violationRequests: violations.length, totalViolations,
      violations,
    }, null, 2));
    process.exit(totalViolations > 0 ? EXIT_BAD : EXIT_OK);
  }

  console.log('── 回放对账（消息合法性） ──');
  console.log(`目录: ${args.dir}`);
  const win = `${args.since || '最早'} → ${args.until || '现在'}`;
  console.log(`窗口: ${win}`);
  const scanned = fileStats.filter((f) => !f.missing).map((f) => `${f.name}(${f.rows})`).join(' ');
  console.log(`扫描: ${scanned}`);
  console.log(`请求: ${requests} 个 | 消息: ${messagesTotal} 条 | 角色分布: ${Object.entries(roles).map(([k, n]) => `${k}=${n}`).join(' ')}`);
  if (truncatedIds.size > 0) console.log(`注: 窗口内有 ${truncatedIds.size} 个请求被截断(finish_reason=length)`);
  console.log('');

  if (totalViolations === 0) {
    console.log('✓ 全部合法：没有孤儿 tool_call / 孤儿 tool_result / 畸形条目。');
  } else {
    console.log(`✗ 违规: ${totalViolations} 处，涉及 ${violations.length} 个请求`);
    console.log('按类型: ' + Object.entries(violationCounts).map(([k, n]) => `${k}=${n}`).join('  '));
    console.log('');
    const flat = [];
    for (const r of violations) {
      for (const one of r.violations) flat.push({ r, one });
    }
    for (const { r, one } of flat.slice(0, args.limit)) {
      console.log(`  [${one.type}] ${r.ts || '?'} ${r.phase}/${r.model} req=${(r.requestId || '?').slice(0, 8)}${r.truncated ? ' (truncated)' : ''} — ${one.detail}`);
    }
    if (flat.length > args.limit) console.log(`  … 其余 ${flat.length - args.limit} 处（用 --limit 调大或 --json）`);
  }
  console.log('');
  console.log('相位汇总:');
  for (const [phase, s] of [...perPhase.entries()].sort((a, b) => b[1].requests - a[1].requests)) {
    console.log(`  ${phase.padEnd(18)} 请求 ${String(s.requests).padStart(4)} | 消息 ${String(s.messages).padStart(5)} | 违规 ${s.violations}`);
  }
  process.exit(totalViolations > 0 ? EXIT_BAD : EXIT_OK);
}

main();
