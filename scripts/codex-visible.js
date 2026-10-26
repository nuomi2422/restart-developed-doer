#!/usr/bin/env node
/**
 * codex-visible.js — 让 Codex 提问「看得见进度」（2026-09-30 用户提出）
 *
 * 问题：`codex-run.ps1` 第 49 行 `$Prompt | codex exec -`，中间过程一个字都不打印，
 *   输出全走 -o 临时文件 → 用户看到的就是「干等，什么都不知道」。
 *   codex 跑得慢（gpt-6-astra 可能几分钟），这在无人值守时尤其难受。
 *
 * 解法：codex exec 支持 `--json`（JSONL 事件流），把它接上，边跑边打印。
 *
 * 用法：
 *   node codex-visible.js "你的问题"
 *   node codex-visible.js --cwd <dir> --sandbox read-only --model gpt-6-astra "问题"
 *   node codex-visible.js --quiet "问题"      ← 只打最终答案（默认带进度）
 */
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const argv = process.argv.slice(2);
const flag = (k, d) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : d; };
const has = (k) => argv.includes(k);

// 位置参数 = 不以 -- 开头、且不是任何 --xxx 的值 那个
const VALUE_OPTS = ['--cwd', '--sandbox', '--model', '--timeout', '--file'];
let prompt = '';
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a.startsWith('--')) continue;
  if (VALUE_OPTS.indexOf(argv[i - 1]) >= 0) continue;   // 这是某个选项的值
  if (!prompt) prompt = a;
}
// --file：问题从文件读，绕开一切 shell 引号/长度限制（长文、含反引号、表格都用它）
const promptFile = flag('--file', '');
if (promptFile) {
  if (!fs.existsSync(promptFile)) { console.error('[X] 文件不存在: ' + promptFile); process.exit(2); }
  prompt = fs.readFileSync(promptFile, 'utf8');
}
if (!prompt) {
  console.error('用法: node codex-visible.js [选项] "问题"');
  console.error('  --file <path>      问题从文件读（长文/含特殊字符用这个）');
  console.error('  --cwd <dir>       工作目录（默认 minecraft-numen）');
  console.error('  --sandbox <mode>  read-only | workspace-write | danger-full-access（默认 read-only）');
  console.error('  --model <name>    模型');
  console.error('  --quiet           不打进度，只出最终答案');
  console.error('  --timeout <sec>   硬超时（默认 900）');
  process.exit(2);
}

const cwd = flag('--cwd', 'D:/rdd/workspace/minecraft-numen');
const sandbox = flag('--sandbox', 'read-only');
const model = flag('--model', '');
const timeoutSec = parseInt(flag('--timeout', '900'), 10);
const quiet = has('--quiet');

const EXE = process.env.CODEX_EXE ||
  'C:/Users/admin/AppData/Local/OpenAI/Codex/bin/d23520d1e41bfb24/codex.exe';
if (!fs.existsSync(EXE)) { console.error('[X] codex.exe 不存在: ' + EXE); process.exit(2); }

const outFile = path.join(os.tmpdir(), 'codex-visible-' + Date.now() + '.txt');
const args = ['exec', '--skip-git-repo-check', '--color', 'never', '--ephemeral', '--json',
  '-s', sandbox, '-o', outFile];
if (model) args.push('-m', model);
args.push('-C', cwd, '-');

const t0 = Date.now();
let lastLine = '';
let gotAnswer = false;

console.log('┌─ codex 开始  ' + new Date().toLocaleTimeString());
console.log('│ cwd=' + cwd + '  sandbox=' + sandbox + (model ? '  model=' + model : ''));
console.log('│ 问题: ' + prompt.slice(0, 70).replace(/\n/g, ' ') + (prompt.length > 70 ? '…' : ''));
console.log('│ ────────────────────────────────');

const child = spawn(EXE, args, { stdio: ['pipe', 'pipe', 'pipe'] });

let buf = '';
child.stdout.on('data', (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    if (quiet) continue;
    let ev = null;
    try { ev = JSON.parse(line); } catch (e) { continue; }
    const msg = renderEvent(ev);
    if (msg && msg !== lastLine) {
      lastLine = msg;
      const t = ((Date.now() - t0) / 1000).toFixed(0).padStart(4) + 's │ ' + msg;
      console.log('│ ' + t);
      if (/answer|final|完成|答案/.test(msg)) gotAnswer = true;
    }
  }
});
child.stderr.on('data', (d) => {
  if (!quiet) process.stderr.write(String(d).split(/\r?\n/).filter(Boolean).map((l) => '│ ! ' + l).join('\n') + '\n');
});

const killer = setTimeout(() => {
  console.log('│ ────────────────────────────────');
  console.log('│ ⏱ 超时 ' + timeoutSec + 's，杀掉子进程');
  try { child.kill('SIGKILL'); } catch (e) {}
}, timeoutSec * 1000);

child.on('close', (code) => {
  clearTimeout(killer);
  const secs = ((Date.now() - t0) / 1000).toFixed(1);
  console.log('└─ 结束 code=' + code + '  用时 ' + secs + 's' + (gotAnswer ? '' : '  ⚠ 途中没见到答案事件'));

  if (fs.existsSync(outFile)) {
    const last = fs.readFileSync(outFile, 'utf8').trim();
    fs.unlinkSync(outFile);
    if (last) {
      console.log('');
      console.log('══════ 最终答案 ' + '═'.repeat(40));
      console.log(last);
      console.log('═'.repeat(50));
    }
  }
  process.exit(code === null ? 1 : code);
});

child.stdin.write(prompt);
child.stdin.end();

// 把 JSONL 事件翻成人话；不认识就原样摘要（别丢信息）
function renderEvent(ev) {
  const t = ev.type || ev.msg || ev.event || '';
  // 常见的几种形态都试一遍
  if (ev.type === 'error' || ev.error) return '⚠ 错误: ' + JSON.stringify(ev.error || ev).slice(0, 160);
  if (ev.type === 'task_complete' || ev.type === 'turn.completed') return '✔ 本轮完成';
  const msg = ev.msg || ev.message || (ev.item && ev.item.text) || ev.text;
  if (typeof msg === 'string' && msg.trim()) {
    const m = msg.trim().replace(/\s+/g, ' ');
    return m.length > 120 ? m.slice(0, 120) + '…' : m;
  }
  if (ev.type) return String(t).slice(0, 120);
  return '';
}