// redline-regression.js -- 红线基础能力回归测试（规划→判定→执行→推进）
// 前置：MC(gpt) 在跑、MCP 8765 enabled、同伴在线。
// 用法: node redline-regression.js [companion=rdd]
// 判据:
//   T1 连接/同伴   : list_companions 有同伴
//   T2 执行→打勾   : rdd_submit 派可成的任务(mine) → subtask_status 变 COMPLETED/PASS
//   T3 规划/生成   : enqueue /goal → 出现 active 链 + 有 condition(Stage-B 展开)
//   (推进 T2 内隐含: 完成即 primary COMPLETED/推进)
// 输出: 每项 PASS/FAIL + 证据；退出码 0=全过 / 1=有 FAIL
const http = require('http');
const fs = require('fs');
const path = require('path');

const cfgPath = process.env.NUMEN_MCP_CONFIG ||
  'D:\\rdd\\mc\\The Best of Twilight Forest\\config\\numen\\mcp_server.json';
const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
const COMP = process.argv[2] || 'rdd';
const T2_TIMEOUT_MS = parseInt(process.env.T2_TIMEOUT_MS || '300000', 10); // 5min
const T3_TIMEOUT_MS = parseInt(process.env.T3_TIMEOUT_MS || '180000', 10); // 3min
const OUT = 'D:\\rdd\\docs\\rdd-docs\\rdd架构\\特殊模式\\_regression\\redline-regression-latest.json';

function post(body, sid) {
  return new Promise((res, rej) => {
    const data = JSON.stringify(body);
    const headers = { Authorization: 'Bearer ' + cfg.token, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'Content-Length': Buffer.byteLength(data) };
    if (sid) headers['Mcp-Session-Id'] = sid;
    const req = http.request({ host: cfg.host, port: cfg.port, path: '/mcp', method: 'POST', headers }, r => { const c = []; r.on('data', x => c.push(x)); r.on('end', () => res({ sid: r.headers['mcp-session-id'], text: Buffer.concat(c).toString('utf8') })); });
    req.on('error', rej); req.write(data); req.end();
  });
}
function parse(t) { const s = (t || '').trim(); if (s.startsWith('event:') || s.startsWith('data:')) { for (const l of s.split(/\r?\n/)) if (l.startsWith('data:')) { try { return JSON.parse(l.slice(5).trim()); } catch (e) {} } return null; } try { return JSON.parse(s); } catch (e) { return null; } }
function txt(j) { return (j && j.result && j.result.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n'); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const out = { startedAt: new Date().toISOString(), companion: COMP, tests: [], pass: true };
  const init = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'redline-regression', version: '1' } } });
  let sid = init.sid;
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' }, sid);
  async function call(name, args) { const r = await post({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name, arguments: args } }, sid); const j = parse(r.text); try { return JSON.parse(txt(j)); } catch (e) { return { _raw: txt(j), error: j && j.error }; } }
  async function status() { return await call('rdd_status', { companion: COMP }); }
  function rec(id, ok, detail) { out.tests.push({ id, ok, detail }); if (!ok) out.pass = false; console.log((ok ? 'PASS ' : 'FAIL ') + id + ' :: ' + detail); }

  // T1 连接/同伴
  const comps = await call('list_companions', {});
  const ctext = comps._raw || JSON.stringify(comps);
  rec('T1-compannion', /Live companions/.test(ctext) && ctext.toLowerCase().includes(COMP.toLowerCase()), ctext.split('\n').slice(0, 3).join(' | '));

  // T2 执行→打勾（mine jungle_log）
  const inv0 = await call('rdd_get_inventory', { companion: COMP });
  const j0 = inv0.data && inv0.data.items ? (inv0.data.items['minecraft:jungle_log'] || 0) : 0;
  const target = j0 + 3;
  await call('rdd_submit', { goal: '红线回归：采集', primary_goal: '采集丛林原木', subtask: '采集丛林原木使达到' + target, asset_key: 'minecraft:jungle_log', minimum: target, task_type: 'mine', args: JSON.stringify({ item: 'minecraft:jungle_log', count: target }), companion: COMP });
  let t2 = false, t2detail = '', lastSt = '';
  const t2start = Date.now();
  while (Date.now() - t2start < T2_TIMEOUT_MS) {
    await sleep(8000);
    const st = await status(); const d = st.data || {};
    lastSt = d.subtask_status + '/' + d.primary_status + ' match=' + d.condition_matches;
    if (d.subtask_status === 'COMPLETED' || d.primary_status === 'COMPLETED') { t2 = true; t2detail = lastSt; break; }
    if (d.subtask_status === 'FAILED') { t2detail = lastSt + ' (FAILED)'; }
  }
  if (!t2detail) t2detail = lastSt + ' (timeout)';
  rec('T2-execute-complete', t2, t2detail);

  // T3 规划/生成（/goal → active 链 + condition(Stage-B)）
  await call('enqueue', { companion: COMP, text: '/goal 在新世界安身：做石制工具、采集食物、挖铁做铁套、绑床。' });
  let t3 = false, t3detail = '';
  const t3start = Date.now();
  while (Date.now() - t3start < T3_TIMEOUT_MS) {
    await sleep(8000);
    const st = await status(); const d = st.data || {};
    t3detail = 'active=' + d.active + ' primary=' + d.primary_status + ' cond=' + JSON.stringify(d.condition || null);
    if (d.active === true && d.condition) { t3 = true; break; }
  }
  rec('T3-plan-stageB', t3, t3detail);

  out.finishedAt = new Date().toISOString();
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
  console.log('\n=== 红线回归结果: ' + (out.pass ? 'ALL PASS' : 'HAS FAIL') + ' ===');
  console.log('report: ' + OUT);
  process.exit(out.pass ? 0 : 1);
})().catch(e => { console.error('REGRESSION_ERR ' + e.message); process.exit(1); });
