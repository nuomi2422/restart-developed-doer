#!/usr/bin/env node
'use strict';
/*
 * econ-report.js —— E0 经济计量读数工具（只读，不改任何东西）。
 *
 * 回答的问题：
 *   1. 钱和时间都花在哪了 —— 按角色(actor)/相位(phase)/模型 聚合每次 LLM 调用：
 *      次数 / 成功失败 / 输入输出缓存 / 新处理(fresh) / 耗时。
 *   2. 工具执行慢不慢 —— 按工具聚合 tool_call→tool_result 的墙上时间
 *      （服务端按事件时间戳配对；客户端本地工具读它自报的 duration_ms）。
 *
 * 数据源：<monitor>/context.jsonl(执行AI) rdd.jsonl(规划) learner.jsonl(学习) ai.jsonl(自编译)
 *         <monitor>/tools.jsonl(工具调用/结果)
 * 事件由 NumenLlmClient 的 llm_usage / llm_response 与 tools.jsonl 产生。
 * 预算建议来自 budget_advice 事件（E1 shadow，配置 budget-policy.json 后超限才产生）；
 * 预算拦截来自 budget_block 事件（E1 enforce，拦截该角色新调用时产生，绝不静默停）。
 *
 * 用法：
 *   node econ-report.js                         # 默认 live 监控目录，全部历史
 *   node econ-report.js --since 2026-10-06      # 只看某天(本地时区)起
 *   node econ-report.js --until 2026-10-07
 *   node econ-report.js --dir <monitor 目录>
 *   node econ-report.js --json                  # 输出聚合 JSON（给别的脚本吃）
 *
 * 退出码：0 = 正常；3 = 目录不存在等环境错。
 */

const fs = require('fs');
const path = require('path');

const EXIT_OK = 0;
const EXIT_USAGE = 3;

const DEFAULT_DIR = 'D:\\rdd\\mc\\The Best of Twilight Forest\\config\\numen\\monitor';
const LLM_FILES = ['context.jsonl', 'rdd.jsonl', 'learner.jsonl', 'ai.jsonl', 'expmem.jsonl'];
const TOOLS_FILE = 'tools.jsonl';

function parseArgs(argv) {
  const out = { dir: DEFAULT_DIR, since: null, until: null, json: false, help: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dir') out.dir = argv[++i];
    else if (a === '--since') out.since = argv[++i];
    else if (a === '--until') out.until = argv[++i];
    else if (a === '--json') out.json = true;
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

function add(map, key, init) {
  let v = map.get(key);
  if (!v) { v = init(); map.set(key, v); }
  return v;
}

function fmtNum(n) {
  if (n == null) return '-';
  if (typeof n === 'number' && Math.abs(n) >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (typeof n === 'number' && Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return String(n);
}

function fmtMs(ms) {
  if (ms == null) return '-';
  if (ms >= 60000) return (ms / 60000).toFixed(1) + 'm';
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's';
  return Math.round(ms) + 'ms';
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(`用法: node econ-report.js [--dir <monitor 目录>] [--since YYYY-MM-DD] [--until YYYY-MM-DD] [--json]

默认目录: ${DEFAULT_DIR}
读数来自 llm_usage 事件（每调用一条）与 tools.jsonl 的 tool_call/tool_result 配对。`);
    process.exit(EXIT_OK);
  }
  if (!fs.existsSync(args.dir)) {
    console.error(`监控目录不存在: ${args.dir}`);
    process.exit(EXIT_USAGE);
  }
  const since = tsOf(args.since);
  const until = tsOf(args.until);
  if (args.since && since == null) { console.error(`--since 解析失败: ${args.since}`); process.exit(EXIT_USAGE); }
  if (args.until && until == null) { console.error(`--until 解析失败: ${args.until}`); process.exit(EXIT_USAGE); }

  // ---- 1) LLM 用量：按 actor|phase|model 聚合 ----
  const llm = new Map();
  const advice = new Map();
  const blocks = new Map();
  let llmBad = 0;
  const filesSeen = [];
  for (const f of LLM_FILES) {
    const full = path.join(args.dir, f);
    const r = readJsonl(full, (row) => {
      const ts0 = Date.parse(row.timestamp);
      if (!inWindow(Number.isNaN(ts0) ? null : ts0, since, until)) return;
      if (row.type === 'budget_advice') {
        const d = row.data || {};
        const v = add(advice, d.role || '?', () => ({ count: 0, exceeded: new Set(), lastTs: null, last: null }));
        v.count++;
        if (Array.isArray(d.exceeded)) for (const k of d.exceeded) v.exceeded.add(k);
        if (v.lastTs == null || (ts0 != null && ts0 >= v.lastTs)) { v.lastTs = ts0; v.last = d; }
        return;
      }
      if (row.type === 'budget_block') {
        const d = row.data || {};
        const key = `${d.actor || '?'}|${d.role || '?'}`;
        const v = add(blocks, key, () => ({ count: 0, exceeded: new Set(), lastTs: null, last: null }));
        v.count++;
        if (typeof d.exceeded === 'string') {
          for (const k of d.exceeded.split('+')) if (k) v.exceeded.add(k);
        } else if (Array.isArray(d.exceeded)) {
          for (const k of d.exceeded) v.exceeded.add(k);
        }
        if (v.lastTs == null || (ts0 != null && ts0 >= v.lastTs)) { v.lastTs = ts0; v.last = d; }
        return;
      }
      if (row.type !== 'llm_usage') return;
      const d = row.data || {};
      const ts = ts0;
      const key = `${d.actor || '?'}|${d.phase || '?'}|${d.model || '?'}`;
      const v = add(llm, key, () => ({
        calls: 0, ok: 0, failed: 0, unknownUsage: 0, truncated: 0,
        input: 0, output: 0, cacheRead: 0, cacheWrite: 0, fresh: 0, total: 0,
        durationMs: 0, durationMax: 0, toolCalls: 0, attempts: 0,
      }));
      v.calls++;
      if (d.status === 'ok') v.ok++; else v.failed++;
      if (d.usageState === 'UNKNOWN') v.unknownUsage++;
      if (d.truncated === true) v.truncated++;
      for (const k of ['input', 'output', 'cacheRead', 'cacheWrite', 'freshTokens', 'totalTokens']) {
        if (typeof d[k] === 'number') {
          const t = k === 'freshTokens' ? 'fresh' : k === 'totalTokens' ? 'total' : k;
          v[t] += d[k];
        }
      }
      if (typeof d.durationMs === 'number') {
        v.durationMs += d.durationMs;
        v.durationMax = Math.max(v.durationMax, d.durationMs);
      }
      if (typeof d.toolCalls === 'number') v.toolCalls += d.toolCalls;
      if (typeof d.attempts === 'number') v.attempts += d.attempts;
    });
    if (!r.missing) filesSeen.push(f);
    llmBad += r.bad;
  }

  // ---- 2) 工具耗时：tool_call_id 配对 ----
  const tools = new Map();
  const callsById = new Map();
  let toolsBad = 0;
  {
    const full = path.join(args.dir, TOOLS_FILE);
    const r = readJsonl(full, (row) => {
      const d = row.data || {};
      const ts = Date.parse(row.timestamp);
      if (!inWindow(Number.isNaN(ts) ? null : ts, since, until)) return;
      const name = d.tool || '?';
      const v = add(tools, name, () => ({
        calls: 0, results: 0, rejected: 0, failed: 0, unknownSuccess: 0,
        durations: [], unmatchedResults: 0,
      }));
      if (row.type === 'tool_call') {
        v.calls++;
        if (d.tool_call_id) callsById.set(d.tool_call_id, { ts, name });
      } else if (row.type === 'tool_result') {
        v.results++;
        if (typeof d.duration_ms === 'number') {
          v.durations.push(d.duration_ms);
        } else {
          const started = d.tool_call_id ? callsById.get(d.tool_call_id) : null;
          if (started && ts != null && started.ts != null && ts >= started.ts) {
            v.durations.push(ts - started.ts);
          } else {
            v.unmatchedResults++;
          }
        }
        if (d.success === false) v.failed++;
        else if (d.success === 'UNKNOWN' || d.success == null) v.unknownSuccess++;
      } else if (row.type === 'tool_rejected') {
        v.rejected++;
      }
    });
    toolsBad = r.bad;
  }

  if (args.json) {
    const dump = {
      since: args.since, until: args.until, dir: args.dir,
      llm: [...llm.entries()].map(([key, v]) => {
        const [actor, phase, model] = key.split('|');
        return { actor, phase, model, ...v, durationAvg: v.calls ? v.durationMs / v.calls : null };
      }),
      tools: [...tools.entries()].map(([tool, v]) => {
        const ds = v.durations.slice().sort((a, b) => a - b);
        const sum = ds.reduce((a, b) => a + b, 0);
        return {
          tool, calls: v.calls, results: v.results, rejected: v.rejected,
          failed: v.failed, unknownSuccess: v.unknownSuccess, unmatchedResults: v.unmatchedResults,
          durationCount: ds.length,
          durationAvg: ds.length ? sum / ds.length : null,
          durationP90: ds.length ? ds[Math.min(ds.length - 1, Math.floor(ds.length * 0.9))] : null,
          durationMax: ds.length ? ds[ds.length - 1] : null,
        };
      }),
      budgetAdvice: [...advice.entries()].map(([role, v]) => ({
        role, count: v.count, exceeded: [...v.exceeded],
        last: v.last ? {
          mode: v.last.mode, phase: v.last.phase, actor: v.last.actor,
          usedTokens: v.last.usedTokens, projectedTokens: v.last.projectedTokens,
          usedCalls: v.last.usedCalls, usedMillis: v.last.usedMillis,
          limitTokens: v.last.limitTokens, limitCalls: v.last.limitCalls, limitMinutes: v.last.limitMinutes,
        } : null,
      })),
      budgetBlocks: [...blocks.entries()].map(([key, v]) => ({
        key, count: v.count, exceeded: [...v.exceeded],
        last: v.last ? {
          mode: v.last.mode, phase: v.last.phase, stage: v.last.stage, actor: v.last.actor,
          usedTokens: v.last.usedTokens, projectedTokens: v.last.projectedTokens,
          usedCalls: v.last.usedCalls, usedMillis: v.last.usedMillis,
          limitTokens: v.last.limitTokens, limitCalls: v.last.limitCalls, limitMinutes: v.last.limitMinutes,
        } : null,
      })),
    };
    console.log(JSON.stringify(dump, null, 2));
    process.exit(EXIT_OK);
  }

  // ---- 3) 报表 ----
  console.log('');
  console.log('════ E0 经济计量报表（llm_usage + tools） ════');
  console.log(`目录  : ${args.dir}`);
  console.log(`窗口  : ${args.since || '(不限)'} → ${args.until || '(不限)'}`);
  console.log(`来源  : ${filesSeen.join(', ') || '(无 LLM 文件)'}  (坏行 ${llmBad + toolsBad})`);
  console.log('');

  const llmRows = [...llm.entries()].sort((a, b) => b[1].durationMs - a[1].durationMs);
  if (!llmRows.length) {
    console.log('LLM：窗口内没有 llm_usage 事件（事件由 2026-10-06 E0 之后的新 jar 产生）');
  } else {
    console.log('── LLM 调用（按 角色/相位/模型；改按耗时降序） ──────────────────────────');
    console.log('actor        phase            model                 calls  ok/fail  in       out      cacheR   cacheW   fresh    total    durAvg   durMax');
    for (const [key, v] of llmRows) {
      const [actor, phase, model] = key.split('|');
      console.log(
        `${actor.padEnd(12)} ${phase.padEnd(16)} ${model.padEnd(20).slice(0, 20)} ` +
        `${String(v.calls).padStart(5)}  ${String(v.ok).padStart(2)}/${String(v.failed).padEnd(2)}  ` +
        `${fmtNum(v.input).padEnd(8)} ${fmtNum(v.output).padEnd(8)} ` +
        `${fmtNum(v.cacheRead).padEnd(8)} ${fmtNum(v.cacheWrite).padEnd(8)} ` +
        `${fmtNum(v.fresh).padEnd(8)} ${fmtNum(v.total).padEnd(8)} ` +
        `${fmtMs(v.calls ? v.durationMs / v.calls : null).padEnd(8)} ${fmtMs(v.durationMax)}`);
    }
    const totals = [...llm.values()].reduce((a, v) => {
      a.calls += v.calls; a.ok += v.ok; a.failed += v.failed; a.unknownUsage += v.unknownUsage;
      a.durationMs += v.durationMs; a.toolCalls += v.toolCalls;
      a.input += v.input; a.output += v.output; a.cacheRead += v.cacheRead;
      a.cacheWrite += v.cacheWrite; a.fresh += v.fresh; a.total += v.total;
      return a;
    }, { calls: 0, ok: 0, failed: 0, unknownUsage: 0, durationMs: 0, toolCalls: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0, fresh: 0, total: 0 });
    console.log('─'.repeat(120));
    console.log(`合计: calls=${totals.calls} ok=${totals.ok} fail=${totals.failed} | in=${fmtNum(totals.input)} out=${fmtNum(totals.output)} ` +
      `cacheR=${fmtNum(totals.cacheRead)} cacheW=${fmtNum(totals.cacheWrite)} fresh=${fmtNum(totals.fresh)} total=${fmtNum(totals.total)} ` +
      `| 模型耗时合计=${fmtMs(totals.durationMs)} | tool_calls提出=${totals.toolCalls}`);
    if (totals.unknownUsage) console.log(`⚠ usage 未知(UNKNOWN)的调用: ${totals.unknownUsage} —— 「没拿到」与「真的是 0」区分开`);
    const truncRows = llmRows.filter(([, v]) => v.truncated > 0);
    if (truncRows.length) {
      const n = truncRows.reduce((a, [, v]) => a + v.truncated, 0);
      console.log(`⚠ 响应被截断(finish_reason=length): ${n} 次 —— ` +
        `${truncRows.map(([k, v]) => `${k.split('|').slice(1).join('/')}×${v.truncated}`).join(', ')}（工具调用 JSON 可能不完整）`);
    }
  }

  const adviceRows = [...advice.entries()].sort((a, b) => b[1].count - a[1].count);
  if (adviceRows.length) {
    console.log('');
    console.log('── 预算建议（E1 shadow：只建议不拦截；配置 config/numen/budget-policy.json 超限后产生） ──');
    for (const [role, v] of adviceRows) {
      const l = v.last || {};
      console.log(
        `  ${String(role).padEnd(10)} 建议 ${String(v.count).padStart(3)} 次 | 最近超: ${(v.exceeded.size ? [...v.exceeded].join('+') : '?')} ` +
        `used=${fmtNum(l.usedTokens)} projected=${fmtNum(l.projectedTokens)} ` +
        `limit(tokens=${fmtNum(l.limitTokens)} calls=${l.limitCalls} min=${l.limitMinutes}) mode=${l.mode}`);
    }
  }

  const blockRows = [...blocks.entries()].sort((a, b) => b[1].count - a[1].count);
  if (blockRows.length) {
    console.log('');
    console.log('── 预算拦截（E1 enforce：超限拦截该角色新调用；聊天栏/事件已明确提示，绝不静默停） ──');
    for (const [key, v] of blockRows) {
      const [actor, role] = key.split('|');
      const l = v.last || {};
      console.log(
        `  ${String(actor).padEnd(10)} ${String(role).padEnd(10)} 拦截 ${String(v.count).padStart(3)} 次 | 最近超: ${(v.exceeded.size ? [...v.exceeded].join('+') : '?')} ` +
        `used=${fmtNum(l.usedTokens)} projected=${fmtNum(l.projectedTokens)} ` +
        `limit(tokens=${fmtNum(l.limitTokens)} calls=${l.limitCalls} min=${l.limitMinutes}) mode=${l.mode}`);
    }
  }

  console.log('');
  const toolRows = [...tools.entries()].sort((a, b) => {
    const da = a[1].durations.reduce((x, y) => x + y, 0) / Math.max(1, a[1].durations.length);
    const db = b[1].durations.reduce((x, y) => x + y, 0) / Math.max(1, b[1].durations.length);
    return db - da;
  });
  if (!toolRows.length) {
    console.log('工具：窗口内没有 tools.jsonl 事件');
  } else {
    console.log('── 工具耗时（按平均耗时降序；服务端配对时间戳 / 本地读自报 duration_ms） ──────');
    console.log('tool                          calls  results  rej  fail  avg      p90      max');
    for (const [name, v] of toolRows) {
      const ds = v.durations.slice().sort((a, b) => a - b);
      const sum = ds.reduce((a, b) => a + b, 0);
      const avg = ds.length ? sum / ds.length : null;
      const p90 = ds.length ? ds[Math.min(ds.length - 1, Math.floor(ds.length * 0.9))] : null;
      const max = ds.length ? ds[ds.length - 1] : null;
      console.log(
        `${name.padEnd(30).slice(0, 30)} ${String(v.calls).padStart(5)}  ${String(v.results).padStart(7)}  ` +
        `${String(v.rejected).padStart(3)}  ${String(v.failed).padStart(4)}  ` +
        `${fmtMs(avg).padEnd(8)} ${fmtMs(p90).padEnd(8)} ${fmtMs(max)}`);
    }
    console.log('提示: fail 只统计明确 success=false（不含 UNKNOWN）；rej=被拒。');
  }
  console.log('');
  process.exit(EXIT_OK);
}

main();
