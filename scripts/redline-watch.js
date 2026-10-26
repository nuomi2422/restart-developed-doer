// redline-watch.js -- 红线/assist 在线巡检器：一次采样，输出紧凑状态 + 新增异常
// 用法: node redline-watch.js [companion]   默认 rdd
// 依赖: 读 live 实例（= gpt 实例 = The Best of Twilight Forest）mcp_server.json 连 Numen MCP；读 monitor/*.jsonl 统计异常
const http = require('http');
const fs = require('fs');
const path = require('path');

const cfgPath = process.env.NUMEN_MCP_CONFIG ||
  'D:\\rdd\\mc\\The Best of Twilight Forest\\config\\numen\\mcp_server.json';
let cfg;
try {
  cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
} catch (e) {
  console.error('CONFIG_UNREADABLE ' + cfgPath + ' -- ' + e.message);
  process.exit(2);
}
const MON = process.env.NUMEN_MONITOR_DIR || path.join(path.dirname(cfgPath), 'monitor');
const HOST = process.env.NUMEN_MCP_HOST || cfg.host || '127.0.0.1';
const PORT = parseInt(process.env.NUMEN_MCP_PORT || cfg.port || '8765', 10);
const COMPANION = process.argv[2] || 'rdd';
const RUN_TAG = process.env.NUMEN_RUN_TAG || 'default';
const STATE = path.join(__dirname, 'redline-watch.state.' + RUN_TAG + '.json');
const CALL_TIMEOUT_MS = parseInt(process.env.NUMEN_MCP_TIMEOUT_MS || '15000', 10);
const RUN_DEADLINE_MS = parseInt(process.env.NUMEN_MCP_RUN_DEADLINE_MS || '60000', 10);

setTimeout(() => {
  console.error('WATCH_RUN_DEADLINE ' + RUN_DEADLINE_MS + 'ms -- 巡检超时强制退出（游戏可能在退出，防 shell 卡死）');
  process.exit(2);
}, RUN_DEADLINE_MS).unref();

function post(body, sid) {
  return new Promise((res, rej) => {
    const data = JSON.stringify(body);
    const headers = {
      Authorization: 'Bearer ' + cfg.token, 'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream', 'Content-Length': Buffer.byteLength(data),
    };
    if (sid) headers['Mcp-Session-Id'] = sid;
    const label = (body && body.method || '?') + '@' + HOST + ':' + PORT;
    let settled = false;
    let guard = null;
    const finish = (err, val) => {
      if (settled) return;
      settled = true;
      if (guard) clearTimeout(guard);
      if (err) rej(err); else res(val);
    };
    guard = setTimeout(() => {
      try { req.destroy(); } catch (e) {}
      finish(new Error('MCP_TIMEOUT ' + CALL_TIMEOUT_MS + 'ms ' + label + ' -- 游戏/MCP 未响应（未启动或正在退出）'));
    }, CALL_TIMEOUT_MS);
    const req = http.request({ host: HOST, port: PORT, path: '/mcp', method: 'POST', headers, timeout: CALL_TIMEOUT_MS }, r => {
      const c = []; r.on('data', x => c.push(x));
      r.on('end', () => finish(null, { sid: r.headers['mcp-session-id'], text: Buffer.concat(c).toString('utf8') }));
    });
    req.on('timeout', () => {
      try { req.destroy(); } catch (e) {}
      finish(new Error('MCP_SOCKET_TIMEOUT ' + CALL_TIMEOUT_MS + 'ms ' + label));
    });
    req.on('error', e => finish(e));
    req.write(data); req.end();
  });
}
function parse(t) {
  const s = (t || '').trim();
  if (s.startsWith('event:') || s.startsWith('data:')) {
    for (const l of s.split(/\r?\n/)) if (l.startsWith('data:')) { try { return JSON.parse(l.slice(5).trim()); } catch (e) {} }
    return null;
  }
  try { return JSON.parse(s); } catch (e) { return null; }
}
function txt(j) { return (j && j.result && j.result.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n'); }
async function call(sid, name, args) {
  const r = await post({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name, arguments: args } }, sid);
  const j = parse(r.text);
  try { return JSON.parse(txt(j)); } catch (e) { return { _raw: txt(j) }; }
}

const ANOM = new Set(['subtask_failed', 'loop_detected', 'subtask_stalled', 'subtask_stall_escalated', 'primary_waiting', 'subtask_capability_gap', 'companion_assets_invalidated', 'recovery_failed']);
const GOOD = new Set(['subtask_completed', 'dependency_met', 'early_achievement', 'primary_completed']);

(async () => {
  const init = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'redline-watch', version: '1' } } });
  const sid = init.sid;
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' }, sid);
  const st = await call(sid, 'rdd_status', { companion: COMPANION });
  const inv = await call(sid, 'rdd_get_inventory', { companion: COMPANION });
  const d = st.data || {};
  const items = (inv.data && inv.data.items) || {};
  const keys = ['minecraft:coal', 'minecraft:iron_ingot', 'minecraft:raw_iron', 'minecraft:cooked_beef', 'minecraft:mutton', 'minecraft:cobblestone', 'minecraft:jungle_log', 'minecraft:white_wool'];

  // 统计近 400 行异常/好事 + 按 event_id 去重新增
  const lines = fs.existsSync(path.join(MON, 'rdd.jsonl'))
    ? fs.readFileSync(path.join(MON, 'rdd.jsonl'), 'utf8').trim().split(/\r?\n/).slice(-400) : [];
  let lastId = 0; try { lastId = JSON.parse(fs.readFileSync(STATE, 'utf8')).lastEventId || 0; } catch (e) {}
  let maxId = lastId; const newAnom = []; const newGood = []; const counts = {};
  for (const l of lines) {
    let e; try { e = JSON.parse(l); } catch (x) { continue; }
    const id = parseInt(String(e.event_id || '').replace(/[^0-9]/g, '')) || 0;
    if (id > maxId) maxId = id;
    counts[e.type] = (counts[e.type] || 0) + 1;
    if (id > lastId) {
      if (ANOM.has(e.type)) newAnom.push(e.type);
      if (GOOD.has(e.type)) newGood.push(e.type);
    }
  }
  fs.writeFileSync(STATE, JSON.stringify({ lastEventId: maxId, ts: new Date().toISOString() }), 'utf8');

  const line = [
    new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    'P=' + (d.primary_status || '?'),
    'S=' + (d.subtask_status || '?'),
    'match=' + d.condition_matches,
    'food=' + (d.condition_groups ? d.condition_groups.food : '?'),
    'inv{' + keys.map(k => k.split(':')[1] + ':' + (items[k] || 0)).join(' ') + '}',
    'NEW_ANOM=[' + newAnom.join(',') + ']',
    'NEW_GOOD=[' + newGood.join(',') + ']',
  ].join(' | ');
  console.log(line);
})().catch(e => { console.error('WATCH_ERR ' + e.message); process.exit(1); });
