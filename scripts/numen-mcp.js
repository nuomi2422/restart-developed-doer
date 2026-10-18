// numen-mcp.js -- lightweight Numen MCP (Streamable HTTP) CLI for observation/driving.
// Usage:
//   node numen-mcp.js list
//   node numen-mcp.js call <tool> ['{"companion":"..."}']
//   node numen-mcp.js status <companionId>
//   node numen-mcp.js raw <method> ['{params}']
// Config default: gpt instance mcp_server.json (override with env NUMEN_MCP_CONFIG or NUMEN_MCP_PORT/TOKEN/HOST).
const http = require('http');
const fs = require('fs');

const cfgPath = process.env.NUMEN_MCP_CONFIG ||
  'E:\\.minecraft\\versions\\The Best of Twilight Forest\\config\\numen\\mcp_server.json';
const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
const HOST = process.env.NUMEN_MCP_HOST || cfg.host || '127.0.0.1';
const PORT = parseInt(process.env.NUMEN_MCP_PORT || cfg.port || '8765', 10);
const TOKEN = process.env.NUMEN_MCP_TOKEN || cfg.token;
const CALL_TIMEOUT_MS = parseInt(process.env.NUMEN_MCP_TIMEOUT_MS || '20000', 10);
const RUN_DEADLINE_MS = parseInt(process.env.NUMEN_MCP_RUN_DEADLINE_MS || '120000', 10);

setTimeout(() => {
  console.error('MCP_RUN_DEADLINE ' + RUN_DEADLINE_MS + 'ms -- 整条命令超时，强制退出（防 shell 卡死）');
  process.exit(2);
}, RUN_DEADLINE_MS).unref();

function post(body, sid) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const headers = {
      'Authorization': 'Bearer ' + TOKEN,
      'Content-Type': 'application/json',
      'Accept': 'application/json, text/event-stream',
      'Content-Length': Buffer.byteLength(data),
    };
    if (sid) headers['Mcp-Session-Id'] = sid;
    const label = (body && body.method || '?') + '@' + HOST + ':' + PORT;
    let settled = false;
    let guard = null;
    const finish = (err, val) => {
      if (settled) return;
      settled = true;
      if (guard) clearTimeout(guard);
      if (err) reject(err); else resolve(val);
    };
    guard = setTimeout(() => {
      try { req.destroy(); } catch (e) {}
      finish(new Error('MCP_TIMEOUT ' + CALL_TIMEOUT_MS + 'ms ' + label + ' -- 游戏/MCP 未响应（未启动或正在退出）'));
    }, CALL_TIMEOUT_MS);
    const req = http.request({ host: HOST, port: PORT, path: '/mcp', method: 'POST', headers, timeout: CALL_TIMEOUT_MS }, (r) => {
      const chunks = [];
      r.on('data', (c) => chunks.push(c));
      r.on('end', () => finish(null, { status: r.statusCode, sid: r.headers['mcp-session-id'], text: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('timeout', () => {
      try { req.destroy(); } catch (e) {}
      finish(new Error('MCP_SOCKET_TIMEOUT ' + CALL_TIMEOUT_MS + 'ms ' + label));
    });
    req.on('error', (e) => finish(e));
    req.write(data);
    req.end();
  });
}

function parse(text) {
  const t = (text || '').trim();
  if (!t) return null;
  if (t.startsWith('event:') || t.startsWith('data:')) {
    for (const line of t.split(/\r?\n/)) {
      if (line.startsWith('data:')) {
        try { return JSON.parse(line.slice(5).trim()); } catch (e) {}
      }
    }
    return { raw: t };
  }
  try { return JSON.parse(t); } catch (e) { return { raw: t }; }
}

function contentText(j) {
  if (j && j.result && Array.isArray(j.result.content)) {
    return j.result.content.filter(c => c.type === 'text').map(c => c.text).join('\n');
  }
  return JSON.stringify(j, null, 2);
}

(async () => {
  const [,, cmd, a1, a2] = process.argv;
  const init = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'numen-mcp-cli', version: '1.0' } } });
  let sid = init.sid;
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' }, sid);

  if (cmd === 'list') {
    const r = await post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, sid);
    const j = parse(r.text);
    const names = (j.result && j.result.tools || []).map(t => t.name);
    console.log(JSON.stringify(names, null, 2));
  } else if (cmd === 'schema') {
    const r = await post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, sid);
    const j = parse(r.text);
    const tools = (j.result && j.result.tools || []);
    const want = a1 ? tools.filter(t => t.name === a1) : tools;
    for (const t of want) {
      console.log('### ' + t.name);
      console.log(JSON.stringify(t.inputSchema, null, 2));
    }
  } else if (cmd === 'call' || cmd === 'status' || cmd === 'raw') {
    let method, params;
    if (cmd === 'raw') { method = a1; params = a2 ? JSON.parse(a2) : {}; }
    else if (cmd === 'status') { method = 'tools/call'; params = { name: 'get_self_status', arguments: a1 ? { companion: a1 } : {} }; }
    else {
      method = 'tools/call';
      let args = {};
      if (a2) {
        if (a2.startsWith('@')) args = JSON.parse(fs.readFileSync(a2.slice(1), 'utf8'));
        else if (a2.trim().startsWith('{')) args = JSON.parse(a2);
        else args = { companion: a2 }; // bare companion name/id
      }
      params = { name: a1, arguments: args };
    }
    const r = await post({ jsonrpc: '2.0', id: 3, method, params }, sid);
    const j = parse(r.text);
    if (j && j.error) console.log('ERROR ' + JSON.stringify(j.error));
    else console.log(contentText(j));
  } else {
    console.log('usage: list | call <tool> [json] | status <companionId> | raw <method> [json]');
  }
})().catch(e => { console.error('EXCEPTION: ' + e.message); process.exit(1); });
