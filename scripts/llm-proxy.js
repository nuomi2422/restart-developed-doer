// llm-proxy.js -- 本地 OpenAI 兼容代理：把游戏内的 LLM 请求转投 OpenRouter 免费模型，
// 并在中间做**上下文瘦身**（这是重点：实测游戏单次请求已达 207k tokens，太烧）。
//
// 为什么需要它（2026-09-28 实测）：
//   config\numen\providers.json 里那条写着 "ctx": 512000 —— 游戏以为有 512k 可用，
//   于是永不裁剪，context 一路涨到 207k tokens/次。对照 providers.json.bak-before-ctx-20260918
//   是同一份配置但**没有 ctx 字段**，说明这个 512k 是 2026-09-18 加上去的，就是它放开了闸门。
//
// 游戏怎么指向它：ProviderLibrary 的 Entry 字段是
//   id;name;provider;model;apiKey;baseUrl;reasoningEffort;proxy;ctx
//   —— baseUrl 是逐条可配的，所以新加一条 provider="openai" + baseUrl=本代理 即可。
//
// 用法:
//   node llm-proxy.js                         # 默认 127.0.0.1:8790，模型 stealth/space-bunny-alpha
//   OPENROUTER_API_KEY=... node llm-proxy.js 8000 --model stealth/space-bunny-alpha
// 环境变量:
//   OPENROUTER_API_KEY / OPENROUTER_KEY     API key（默认从 opencode.json 读）
//   PROXY_TARGET_MODEL                      转投的模型（默认 stealth/space-bunny-alpha）
//   PROXY_MAX_INPUT_TOKENS                  瘦身上限（默认 60000；0=不瘦身）
//   PROXY_KEEP_RECENT                       至少保留最近几条（默认 12）
//   PROXY_LOG_TOKENS                        1=打印每次请求的 tokens 与裁剪量（默认 1）
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.argv[2] || process.env.PROXY_PORT || '8790', 10);
const MODEL_ARG = (() => { const i = process.argv.indexOf('--model'); return i >= 0 ? process.argv[i + 1] : ''; })();
const TARGET_MODEL = MODEL_ARG || process.env.PROXY_TARGET_MODEL || 'nvidia/nemotron-3.5-lightning:free';
const MAX_INPUT_TOKENS = parseInt(process.env.PROXY_MAX_INPUT_TOKENS || '60000', 10);
const KEEP_RECENT = parseInt(process.env.PROXY_KEEP_RECENT || '12', 10);
const LOG_TOKENS = process.env.PROXY_LOG_TOKENS !== '0';

function readKey() {
  if (process.env.OPENROUTER_API_KEY) return process.env.OPENROUTER_API_KEY;
  if (process.env.OPENROUTER_KEY) return process.env.OPENROUTER_KEY;
  const p = 'C:\\Users\\admin\\.config\\opencode\\opencode.json';
  try {
    const j = JSON.parse(fs.readFileSync(p, 'utf8'));
    return (j.provider && j.provider.openrouter && j.provider.openrouter.options && j.provider.openrouter.options.apiKey) || '';
  } catch (e) { return ''; }
}
const KEY = readKey();

/** 粗估 token：CJK 约 1 char≈1 token，ASCII 约 4 char≈1 token。取保守上界。 */
function estTokens(s) {
  if (!s) return 0;
  let cjk = 0, other = 0;
  for (const ch of String(s)) { if (ch.charCodeAt(0) > 0x2E80) cjk++; else other++; }
  return cjk + Math.ceil(other / 4);
}
function msgTokens(m) {
  let t = estTokens(m.content);
  if (typeof m.content !== 'string' && m.content) t = estTokens(JSON.stringify(m.content));
  if (m.tool_calls) t += estTokens(JSON.stringify(m.tool_calls));
  if (m.name) t += estTokens(m.name);
  return t + 8;
}
function reqTokens(msgs, tools) {
  let t = 0;
  for (const m of msgs) t += msgTokens(m);
  if (tools && tools.length) t += estTokens(JSON.stringify(tools));
  return t;
}

/**
 * 把消息切成"不可分割的组"，再从最旧丢起。
 *
 * 为什么必须按组丢：OpenAI 协议要求 assistant(tool_calls) 后面必须紧跟它对应的
 * 每一条 tool 消息（tool_call_id 要对上）。逐条丢会把一个 tool 消息和它的父助手
 * 拆散 → 上游直接 400。所以一个"组"= 一条普通消息，或"带 tool_calls 的助手 + 其后
 * 全部 tool 回应"。只整组丢，永远不留孤儿。
 */
function groupMessages(msgs) {
  const groups = [];
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i];
    if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
      const g = [m];
      let j = i + 1;
      while (j < msgs.length && msgs[j].role === 'tool') { g.push(msgs[j]); j++; }
      groups.push({ kind: 'assistant_tools', msgs: g });
      i = j - 1;
    } else {
      groups.push({ kind: m.role || 'other', msgs: [m] });
    }
  }
  return groups;
}

function slim(messages, tools) {
  if (!MAX_INPUT_TOKENS || messages.length <= KEEP_RECENT) {
    return { messages, dropped: 0, estBefore: reqTokens(messages, tools), estAfter: reqTokens(messages, tools) };
  }
  const estBefore = reqTokens(messages, tools);
  if (estBefore <= MAX_INPUT_TOKENS) return { messages, dropped: 0, estBefore, estAfter: estBefore };

  const system = messages.filter(m => m.role === 'system');
  const rest = messages.filter(m => m.role !== 'system');
  const groups = groupMessages(rest);

  // 从最新往回留，至少留 KEEP_RECENT 条
  const budget = Math.max(4000, MAX_INPUT_TOKENS - reqTokens(system, tools));
  const kept = [];
  let acc = 0, keptCount = 0, cut = groups.length;
  for (let i = groups.length - 1; i >= 0; i--) {
    const g = groups[i];
    const gt = reqTokens(g.msgs, null);
    if (acc + gt > budget && keptCount >= KEEP_RECENT) { cut = i + 1; break; }
    acc += gt;
    keptCount += g.msgs.length;
    kept.unshift(...g.msgs);
    if (i === 0) cut = 0;
  }
  const out = system.concat(kept);
  const estAfter = reqTokens(out, tools);
  return { messages: out, dropped: groups.length - (cut === 0 ? 0 : groups.length - cut), estBefore, estAfter };
}

function log(msg) { if (LOG_TOKENS) console.log('[llm-proxy] ' + msg); }

/**
 * 免费模型的上游会抖：实测出现过 HTTP 200 但 body 是
 * {"message":"Provider returned an empty response","code":502,"error_type":"provider_unavailable"}。
 * 这种情况按状态码判不出来（它是 200），必须**看内容**再重试。
 */
function shouldRetry(status, text) {
  if (status >= 500) return true;
  const t = String(text || '');
  return /Provider returned an empty response|provider_unavailable|"code":\s*502|rate.?limit|"code":\s*429/i.test(t);
}
const RETRIES = parseInt(process.env.PROXY_RETRIES || '3', 10);

function callUpstream(payload, body, cb, tries) {
  tries = tries || 0;
  const rq = https.request({
    host: 'openrouter.ai', path: '/api/v1/chat/completions', method: 'POST',
    headers: {
      Authorization: 'Bearer ' + KEY,
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
      'HTTP-Referer': 'http://127.0.0.1:' + PORT,
      'X-Title': 'numen-llm-proxy',
    },
  }, (up) => {
    if (payload.stream) {
      if (up.statusCode && up.statusCode >= 500 && tries < RETRIES) {
        up.resume();
        log('upstream ' + up.statusCode + ' -> retry ' + (tries + 1));
        return setTimeout(() => callUpstream(payload, body, cb, tries + 1), 800 * (tries + 1));
      }
      cb(null, up, true);
      return;
    }
    const chunks = [];
    up.on('data', c => chunks.push(c));
    up.on('end', () => {
      const t = Buffer.concat(chunks).toString('utf8');
      if (shouldRetry(up.statusCode, t) && tries < RETRIES) {
        log('upstream flaky (' + up.statusCode + ') -> retry ' + (tries + 1) + ' :: ' + t.slice(0, 120));
        return setTimeout(() => callUpstream(payload, body, cb, tries + 1), 900 * (tries + 1));
      }
      cb(null, { status: up.statusCode, text: t }, false);
    });
  });
  rq.on('error', (e) => {
    if (tries < RETRIES) {
      log('upstream error ' + e.message + ' -> retry ' + (tries + 1));
      return setTimeout(() => callUpstream(payload, body, cb, tries + 1), 900 * (tries + 1));
    }
    cb(e);
  });
  rq.setTimeout(180000, () => { rq.destroy(new Error('proxy upstream timeout 180s')); });
  rq.write(body);
  rq.end();
}

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => { body += c; });
  req.on('end', () => {
    if (req.method !== 'POST' || !/\/chat\/completions/.test(req.url)) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        object: 'list',
        data: [{ id: TARGET_MODEL, object: 'model', owned_by: 'openrouter-proxy' }],
        proxy: 'llm-proxy', hint: 'POST /v1/chat/completions',
      }));
      return;
    }
    let payload;
    try { payload = JSON.parse(body || '{}'); } catch (e) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'bad json: ' + e.message } }));
      return;
    }
    const s = slim(payload.messages || [], payload.tools);
    const toolsCount = (payload.tools || []).length;
    log('in chars=' + body.length + ' msgs=' + (payload.messages || []).length + ' tools=' + toolsCount
      + ' | est ' + s.estBefore + ' -> ' + s.estAfter + ' tok (dropped ' + s.dropped + ' groups)'
      + ' | model=' + (payload.model || '(none)') + ' -> ' + TARGET_MODEL);
    if (!KEY) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'no OPENROUTER api key (env or opencode.json)' } }));
      return;
    }
    const out = Object.assign({}, payload, { model: TARGET_MODEL, messages: s.messages });
    const data = JSON.stringify(out);
    callUpstream(out, data, (err, up, isStream) => {
      if (err) {
        log('giving up: ' + err.message);
        if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'proxy upstream error: ' + err.message } }));
        return;
      }
      if (isStream) {
        res.writeHead(up.statusCode || 200, { 'Content-Type': up.headers['content-type'] || 'text/event-stream', 'Cache-Control': 'no-cache' });
        up.pipe(res);
        return;
      }
      let usage = '';
      try { const j = JSON.parse(up.text); if (j.usage) usage = ' usage=' + JSON.stringify(j.usage).slice(0, 160); } catch (e) {}
      log('up status=' + up.status + usage);
      res.writeHead(up.status || 200, { 'Content-Type': 'application/json' });
      res.end(up.text);
    });
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('[llm-proxy] listening on http://127.0.0.1:' + PORT);
  console.log('[llm-proxy] target model = ' + TARGET_MODEL);
  console.log('[llm-proxy] slimming: MAX_INPUT_TOKENS=' + MAX_INPUT_TOKENS + ' KEEP_RECENT=' + KEEP_RECENT);
  console.log('[llm-proxy] key loaded = ' + (KEY ? 'yes' : 'NO'));
});
