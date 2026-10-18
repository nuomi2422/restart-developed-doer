// drop-all.js -- 清空同伴背包（逐项 drop_items），保留白名单（如心契誓约指南）。
// 用法: node drop-all.js [companion=rdd]
const http = require('http');
const fs = require('fs');
const cfg = JSON.parse(fs.readFileSync('E:\\.minecraft\\versions\\The Best of Twilight Forest\\config\\numen\\mcp_server.json', 'utf8'));
const COMP = process.argv[2] || 'rdd';
const KEEP = new Set(['maidmarriage:heart_pact_guide', 'touhou_little_maid:smart_slab_init']);
function post(body, sid) {
  return new Promise((res, rej) => {
    const data = JSON.stringify(body);
    const h = { Authorization: 'Bearer ' + cfg.token, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'Content-Length': Buffer.byteLength(data) };
    if (sid) h['Mcp-Session-Id'] = sid;
    const r = http.request({ host: cfg.host, port: cfg.port, path: '/mcp', method: 'POST', headers: h }, resp => { const c = []; resp.on('data', x => c.push(x)); resp.on('end', () => res({ sid: resp.headers['mcp-session-id'], text: Buffer.concat(c).toString('utf8') })); });
    r.on('error', rej); r.write(data); r.end();
  });
}
function parse(t) { const s = (t || '').trim(); if (s.startsWith('event:') || s.startsWith('data:')) { for (const l of s.split(/\r?\n/)) if (l.startsWith('data:')) { try { return JSON.parse(l.slice(5).trim()); } catch (e) {} } return null; } try { return JSON.parse(s); } catch (e) { return null; } }
function txt(j) { return (j && j.result && j.result.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n'); }
(async () => {
  const init = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'drop-all', version: '1' } } });
  let sid = init.sid;
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' }, sid);
  async function call(name, args) { const r = await post({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name, arguments: args } }, sid); return parse(r.text); }
  const inv = await call('rdd_get_inventory', { companion: COMP });
  let items = {};
  try { items = JSON.parse(txt(inv)).data.items || {}; } catch (e) {}
  const list = Object.entries(items).filter(([k]) => !KEEP.has(k));
  console.log('dropping ' + list.length + ' item types...');
  for (const [id, n] of list) {
    const j = await call('drop_items', { companion: COMP, item_id: id, count: Math.min(n, 999) });
    const ok = j && j.result && !j.result.isError;
    console.log((ok ? 'drop ' : 'FAIL ') + id + ' x' + n);
  }
  console.log('done');
})().catch(e => { console.error('ERR ' + e.message); process.exit(1); });
