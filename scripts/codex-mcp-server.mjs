#!/usr/bin/env node
// codex-mcp-server.mjs — 特殊模式 C 层桥（把 Codex / GPT-5.6-terra 包成 MCP，供 O 调用）
//
// 角色：O（OpenCode 免费 AI）通过 MCP 与 C（Codex 里的 gpt-5.6-terra）对话，
// 用于高级代码审核 / 架构审查 / 难题升级（不是每步都参与）。
//
// 协议：MCP over stdio（换行分隔 JSON-RPC 2.0），零第三方依赖，避免 ESM/NODE_PATH 依赖坑。
// 传输：本进程 stdout 只输出 JSON-RPC；所有诊断走 stderr。
//
// 工具：
//   codex_health               → codex 版本探活
//   codex_ask(prompt,cwd,sandbox,model)    → codex exec 非交互问答（默认 read-only，不改盘）
//   codex_review(prompt,base,commit,uncommitted,cwd) → codex review 代码审核
//
// 环境变量：
//   CODEX_EXE        codex 可执行文件（默认 Codex 桌面版自带）
//   CODEX_CWD        默认工作目录（默认 Numen 仓库）
//   CODEX_TIMEOUT_MS 单次调用上限，默认 1200000（20 分钟）

import { spawn } from 'node:child_process';
import { readFileSync, rmSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import readline from 'node:readline';

const CODEX_EXE = process.env.CODEX_EXE ||
  'C:\\Users\\admin\\AppData\\Local\\OpenAI\\Codex\\bin\\d23520d1e41bfb24\\codex.exe';
const DEFAULT_CWD = process.env.CODEX_CWD || 'D:\\rdd\\workspace\\minecraft-numen';
const TIMEOUT_MS = parseInt(process.env.CODEX_TIMEOUT_MS || '1200000', 10);

function log(...a) { process.stderr.write('[codex-bridge] ' + a.join(' ') + '\n'); }
function stripAnsi(s) { return String(s || '').replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, ''); }

function run(args, { input = '', cwd = DEFAULT_CWD, timeoutMs = TIMEOUT_MS } = {}) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(CODEX_EXE, args, { cwd, windowsHide: true, env: process.env });
    } catch (e) {
      resolve({ code: -1, stdout: '', stderr: String(e.message), timedOut: false });
      return;
    }
    let out = '', err = '', settled = false, timedOut = false;
    const finish = (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ code, stdout: out, stderr: err, timedOut });
    };
    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill(); } catch (e) {}
      setTimeout(() => finish(-2), 5000);
    }, timeoutMs);
    child.stdout.on('data', d => out += d.toString('utf8'));
    child.stderr.on('data', d => err += d.toString('utf8'));
    child.on('error', e => { err += '\n' + String(e.message); finish(-1); });
    child.on('close', code => finish(code));
    try {
      if (input) child.stdin.write(input);
      child.stdin.end();
    } catch (e) { /* stdin may be closed */ }
  });
}

function readLast(file) {
  try { return readFileSync(file, 'utf8').trim(); } catch (e) { return ''; }
}

async function codexHealth() {
  const r = await run(['--version'], { cwd: DEFAULT_CWD, timeoutMs: 60000 });
  return stripAnsi(r.stdout || r.stderr).trim() || '(no version output)';
}

async function codexAsk(args) {
  const prompt = String(args.prompt || '');
  if (!prompt.trim()) throw new Error('codex_ask 需要非空 prompt');
  const cwd = args.cwd || DEFAULT_CWD;
  const sandbox = args.sandbox || 'read-only';
  const tmp = mkdtempSync(path.join(tmpdir(), 'codex-bridge-'));
  const lastFile = path.join(tmp, 'last.txt');
  const cli = ['exec', '--skip-git-repo-check', '--color', 'never', '--ephemeral',
    '-s', sandbox, '-o', lastFile];
  if (args.model) cli.push('-m', String(args.model));
  if (args.cwd) cli.push('-C', cwd);
  cli.push('-');
  log(`exec cwd=${cwd} sandbox=${sandbox} promptChars=${prompt.length}`);
  const r = await run(cli, { input: prompt, cwd });
  const text = readLast(lastFile) || stripAnsi(r.stdout).trim();
  try { rmSync(tmp, { recursive: true, force: true }); } catch (e) {}
  if (!text) {
    return { text: `[codex_ask 无输出] code=${r.code} timedOut=${r.timedOut}\n${stripAnsi(r.stderr).slice(-4000)}`, isError: true };
  }
  return { text, isError: r.code !== 0 && !text };
}

async function codexReview(args) {
  const cwd = args.cwd || DEFAULT_CWD;
  const cli = ['review'];
  if (args.base) cli.push('--base', String(args.base));
  if (args.commit) cli.push('--commit', String(args.commit));
  if (args.uncommitted) cli.push('--uncommitted');
  if (args.prompt) cli.push(String(args.prompt));
  log(`review cwd=${cwd} args=${cli.slice(1).join(' ')}`);
  const r = await run(cli, { cwd });
  const text = stripAnsi(r.stdout).trim() || stripAnsi(r.stderr).slice(-4000);
  return { text: text || '(review 无输出)', isError: r.code !== 0 && !text };
}

const TOOLS = [
  {
    name: 'codex_health',
    description: '探活：返回 Codex CLI 版本，确认 C 层链路可用。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'codex_ask',
    description: '向 Codex(GPT-5.6-terra) 非交互提问：用于高级代码审核/架构审查/难题升级。默认 read-only 沙箱不改盘。',
    inputSchema: {
      type: 'object',
      properties: {
        prompt: { type: 'string', description: '问题/审核指令（可附代码路径与要求）' },
        cwd: { type: 'string', description: '工作目录，默认 Numen 仓库' },
        sandbox: { type: 'string', enum: ['read-only', 'workspace-write', 'danger-full-access'], description: '默认 read-only' },
        model: { type: 'string', description: '覆盖模型，默认 config.toml 的 gpt-5.6-terra' },
      },
      required: ['prompt'],
      additionalProperties: false,
    },
  },
  {
    name: 'codex_review',
    description: '让 Codex 对当前仓库做代码审核（可用 uncommitted/base/commit 指定范围）。',
    inputSchema: {
      type: 'object',
      properties: {
        prompt: { type: 'string', description: '自定义审核指令' },
        base: { type: 'string', description: '对比基线分支' },
        commit: { type: 'string', description: '审核某 commit' },
        uncommitted: { type: 'boolean', description: '审核未提交改动' },
        cwd: { type: 'string', description: '工作目录，默认 Numen 仓库' },
      },
      additionalProperties: false,
    },
  },
];

// ---------- MCP stdio 协议 ----------
const SERVER_INFO = { name: 'codex-bridge', version: '0.1.0' };
let queue = Promise.resolve(); // 串行化，避免并发 codex

function send(msg) { process.stdout.write(JSON.stringify(msg) + '\n'); }

async function callTool(name, args) {
  if (name === 'codex_health') return { text: await codexHealth(), isError: false };
  if (name === 'codex_ask') return await codexAsk(args || {});
  if (name === 'codex_review') return await codexReview(args || {});
  return { text: `未知工具: ${name}`, isError: true };
}

function handle(msg) {
  const { id, method, params } = msg;
  if (method === 'initialize') {
    const pv = (params && params.protocolVersion) || '2024-11-05';
    send({ jsonrpc: '2.0', id, result: { protocolVersion: pv, capabilities: { tools: {} }, serverInfo: SERVER_INFO } });
    return;
  }
  if (method === 'notifications/initialized' || method === 'initialized') return; // notification
  if (method === 'ping') { send({ jsonrpc: '2.0', id, result: {} }); return; }
  if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: TOOLS } });
    return;
  }
  if (method === 'tools/call') {
    const name = params && params.name;
    const args = (params && params.arguments) || {};
    queue = queue.then(async () => {
      try {
        const r = await callTool(name, args);
        send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: r.text }], isError: !!r.isError } });
      } catch (e) {
        send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: '错误: ' + e.message }], isError: true } });
      }
    });
    return;
  }
  if (id !== undefined && id !== null) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
  }
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  const s = line.trim();
  if (!s) return;
  let msg;
  try { msg = JSON.parse(s); } catch (e) { log('bad json line'); return; }
  try { handle(msg); } catch (e) { log('handle error: ' + e.message); }
});
process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));
log('started; CODEX_EXE=' + CODEX_EXE);
