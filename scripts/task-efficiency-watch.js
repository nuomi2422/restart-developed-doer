// task-efficiency-watch.js -- 任务效率监察：发现"很久没完成任何任务"或"资产/背包长期不变"
// 就判定效率过低并报出来。这是「让系统自己发现异常」的一环：人不用一直盯着。
//
// 三个独立信号（任一长期不动 = 大概率卡住或极慢）：
//   1) subtask 轮次不换      —— 同一个 subtask id 挂了太久
//   2) asset 总数不变        —— 规划层的目标没进展
//   3) 背包指纹不变          —— 执行层没在真正干活（哪怕它在"动"）
// 另给滚动效率：每分钟完成子任务数。
//
// 硬约束（2026-09-28 血坑）：任何 HTTP 都必须有超时 + 整条命令有 deadline。
// 游戏正在退出时端口会"活着但不答"，无超时 = 永久挂死 = 上层 shell 卡住。
//
// 用法: node task-efficiency-watch.js [companion] [--once] [--tag <runTag>]
//   --once   只采样一次（默认循环，Ctrl-C 退出；Ctrl-C 前会自动落盘状态）
//   --tag    状态文件与输出按该标签隔离（多实例/多次运行不串味）
// 环境: NUMEN_MCP_CONFIG / NUMEN_MCP_HOST / NUMEN_MCP_PORT / NUMEN_MONITOR_DIR
//       NUMEN_MCP_TIMEOUT_MS(15000) NUMEN_WATCH_RUN_DEADLINE_MS(0=不限,循环模式)
//       SAMPLE_SECONDS(20) / STUCK_SECONDS(600) / FLAT_SECONDS(900) / FAILED_SECONDS(240)
//
// 四个信号（2026-09-28 夜 实测加固）：
//   subtask_flat  同一 subtask id 挂太久（轮次不换）
//   assets_flat   规划层资产总数不涨
//   res_flat      ★背包**净资源总量**不涨（不是"指纹变没变"——churn 会骗过指纹）
//   failed_stuck  ★子任务 FAILED 且 id 不换（最直白的卡住，比等 600s 早得多）
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
const HOST = process.env.NUMEN_MCP_HOST || cfg.host || '127.0.0.1';
const PORT = parseInt(process.env.NUMEN_MCP_PORT || cfg.port || '8765', 10);
const MON = process.env.NUMEN_MONITOR_DIR || path.join(path.dirname(cfgPath), 'monitor');

const argv = process.argv.slice(2);
const COMPANION = (argv.find(a => !a.startsWith('--')) || 'rdd');
const ONCE = argv.includes('--once');
const TAG = (() => { const i = argv.indexOf('--tag'); return i >= 0 && argv[i + 1] ? argv[i + 1] : 'default'; })();
const SAMPLE_SECONDS = parseInt(process.env.SAMPLE_SECONDS || '20', 10);
const STUCK_SECONDS = parseInt(process.env.STUCK_SECONDS || '600', 10);
const FLAT_SECONDS = parseInt(process.env.FLAT_SECONDS || '900', 10);
/** 子任务 FAILED 且 id 不换超过这么久 = 卡住。实测 255s 就该有人看一眼，故默认 240s。 */
const FAILED_SECONDS = parseInt(process.env.FAILED_SECONDS || '240', 10);
/**
 * 乒乓循环：同一个子任务 id 在这个窗口内被**进入**这么多次 = 在反复来回。
 * 这是 GLM 外脑 2026-09-28 点出的 D1 漏洞：A↔B 交替、每次各自都不到 600s，
 * 所有计时器每轮都被重置 → 四个信号全瞎，而"左右横跳"正是最典型的那种空转。
 */
const LOOP_WINDOW_SECONDS = parseInt(process.env.LOOP_WINDOW_SECONDS || '900', 10);
const LOOP_ENTRIES = parseInt(process.env.LOOP_ENTRIES || '4', 10);
/**
 * 失败重试风暴：窗口内 subtask_failed 事件达到这个数 = 在反复失败重试。
 * GLM 指出的 D2：FAIL 之后换个新 id，failed_stuck 立刻清零，看不出"一直在失败"。
 * 实测实例：22:50:42 fail=266s 却 sub_age=0s（换了新 id）。
 */
const FAIL_STORM_WINDOW_SECONDS = parseInt(process.env.FAIL_STORM_WINDOW_SECONDS || '900', 10);
const FAIL_STORM_COUNT = parseInt(process.env.FAIL_STORM_COUNT || '3', 10);
/**
 * ★ 左右横跳检测（用户 2026-09-28 点名，2026-09-29 补）：
 * 之前的四个信号**结构上看不到横跳** —— 它只看任务状态/资产/背包，而横跳时状态可能一直是
 * RUNNING、背包也在动。横跳的本质是**物理上**的：走的路很多、但净位移几乎为零。
 * 所以这里直接量位置：滑动窗口内累计路程 >= OSC_PATH_MIN，而两端净位移 <= OSC_NET_MAX，
 * 就是"在原地来回蹭"。窗口用采样条数（≈ 采样数 × SAMPLE_SECONDS 秒）。
 */
const OSC_WINDOW_SAMPLES = parseInt(process.env.OSC_WINDOW_SAMPLES || '15', 10);
const OSC_PATH_MIN = parseFloat(process.env.OSC_PATH_MIN || '28');   // 累计走了这么多格
const OSC_NET_MAX = parseFloat(process.env.OSC_NET_MAX || '6');      // 净位移却不到这么多格
/** 单次采样位移超过它，按"传送/复活"处理，不计入路程（否则复活会被误判成暴走）。 */
const OSC_TELEPORT = parseFloat(process.env.OSC_TELEPORT || '24');
const CALL_TIMEOUT_MS = parseInt(process.env.NUMEN_MCP_TIMEOUT_MS || '15000', 10);
const RUN_DEADLINE_MS = parseInt(process.env.NUMEN_WATCH_RUN_DEADLINE_MS || '0', 10);

const STATE = path.join(__dirname, 'task-efficiency.state.' + TAG + '.json');
const OUT = path.join(__dirname, 'task-efficiency.' + TAG + '.jsonl');

if (RUN_DEADLINE_MS > 0) {
  setTimeout(() => {
    console.error('WATCH_RUN_DEADLINE ' + RUN_DEADLINE_MS + 'ms -- 监察超时强制退出（游戏可能在退出，防 shell 卡死）');
    process.exit(2);
  }, RUN_DEADLINE_MS).unref();
}

function post(body, sid) {
  return new Promise((res, rej) => {
    const data = JSON.stringify(body);
    const headers = {
      Authorization: 'Bearer ' + cfg.token, 'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream', 'Content-Length': Buffer.byteLength(data),
    };
    if (sid) headers['Mcp-Session-Id'] = sid;
    const label = (body && body.method || '?') + '@' + HOST + ':' + PORT;
    let settled = false, guard = null;
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

/** 背包指纹：只取"数量会变的东西"，工具/盔甲/盾牌这些静态件不算进展。 */
function invFingerprint(items) {
  const volatile = {};
  for (const k of Object.keys(items || {})) {
    if (/sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots|shield|bucket|torch/.test(k)) continue;
    volatile[k] = items[k];
  }
  const keys = Object.keys(volatile).sort();
  return keys.length ? keys.map(k => k.replace('minecraft:', '') + '=' + volatile[k]).join(',') : '(empty)';
}

/**
 * 资源总量 = 非工具类物品的件数之和。
 *
 * <p>为什么不能只看"指纹变没变"(2026-09-28 实测的教训)：同伴把泥土捡起来又丢掉,
 * 指纹每次都变、`inv_age` 永远 0 —— 「在 churn 但没变富」这种假装忙反而躲过了检测。
 * 真正该看的是**净增长**:总量不涨 = 没产出,不管它动得多欢。
 */
function resourceTotal(items) {
  let total = 0;
  for (const k of Object.keys(items || {})) {
    if (/sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots|shield|bucket|torch/.test(k)) continue;
    const n = Number(items[k]);
    if (isFinite(n)) total += n;
  }
  return total;
}

function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { return null; }
}
function saveState(s) {
  try { fs.writeFileSync(STATE, JSON.stringify(s), 'utf8'); } catch (e) {}
}

(async () => {
  const init = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'task-efficiency-watch', version: '1' } } });
  const sid = init.sid;
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' }, sid);

  let st = loadState() || {
    startedAtSec: null, subtask: null, subtaskSince: 0, completions: 0,
    lastEventId: 0, assets: null, assetsSince: 0, invFp: null, invSince: 0,
    resTotal: null, resSince: 0, failedSince: 0, lastSample: null,
    subEntries: {}, failTimes: [],
  };
  if (!st.subEntries) st.subEntries = {};
  if (!st.failTimes) st.failTimes = [];

  for (;;) {
    let st2, inv, self2 = null, worldAssets = null;
    try {
      st2 = (await call(sid, 'rdd_status', { companion: COMPANION })).data || {};
      inv = (await call(sid, 'rdd_get_inventory', { companion: COMPANION })).data || {};
      // 位置：横跳检测的唯一数据源。get_self_status 返回的就是本体对象（不是 .data 包装）。
      try {
        const s = await call(sid, 'get_self_status', { companion: COMPANION });
        self2 = (s && s.position) ? s : ((s && s.data && s.data.position) ? s.data : null);
      } catch (e) { self2 = null; }
      // 世界资产（基地/箱子已知存量）：规划器盲点检测用。见 §检测报告 26。
      try { worldAssets = await call(sid, 'rdd_assets', { companion: COMPANION }); } catch (e) { worldAssets = null; }
    } catch (e) {
      console.error('SAMPLE_ERR ' + e.message);
      if (ONCE) process.exit(3);
      await new Promise(r => setTimeout(r, SAMPLE_SECONDS * 1000));
      continue;
    }

    // 统一用墙钟秒：rdd_status 不保证给 game_time，用采样数折算"刻"是假刻度，
    // 阈值会失去意义。秒就秒，判据只要求单调可比。
    const now = Math.round(Date.now() / 1000);
    if (st.startedAtSec == null) st.startedAtSec = now;

    // ① 轮次是否在换（同时记"进入次数"——乒乓循环靠它抓，见 LOOP_ENTRIES）
    const sub = st2.subtask || '(none)';
    if (st.subtask !== sub) {
      st.subtask = sub;
      st.subtaskSince = now;
      if (!st.subEntries[sub]) st.subEntries[sub] = [];
      st.subEntries[sub].push(now);
    }
    const subtaskAge = now - st.subtaskSince;
    // 清掉窗口外的进入记录，并算最大进入次数
    const loopCut = now - LOOP_WINDOW_SECONDS;
    let topEntries = 0, topId = '';
    for (const k of Object.keys(st.subEntries)) {
      st.subEntries[k] = st.subEntries[k].filter(t => t >= loopCut);
      if (!st.subEntries[k].length) delete st.subEntries[k];
      else if (st.subEntries[k].length > topEntries) { topEntries = st.subEntries[k].length; topId = k; }
    }

    // ② 资产总数是否在动
    const assets = st2.assets != null ? st2.assets : null;
    if (assets != null && st.assets !== assets) { st.assets = assets; st.assetsSince = now; }
    const assetAge = assets != null ? now - st.assetsSince : 0;

    // ③ 背包指纹是否在动（保留作诊断：能看出"在 churn"）
    const fp = invFingerprint((inv.items) || {});
    if (st.invFp !== fp) { st.invFp = fp; st.invSince = now; }
    const invAge = now - st.invSince;

    // ③b 净资源增长（真正的产出信号）：总量不涨 = 没变富，不管背包怎么翻腾
    const resNow = resourceTotal(inv.items || {});
    if (st.resTotal == null || resNow > st.resTotal) { st.resTotal = resNow; st.resSince = now; }
    else if (resNow < st.resTotal) { st.resTotal = resNow; }   // 消耗了不算产出，但别谎报"在涨"
    const resAge = now - st.resSince;

    // ⑧ 左右横跳：窗口内**走了很多路、净位移却几乎为零**。
    // 这是用户最烦的那个 bug，而前四个信号结构上抓不到它（状态可能一直 RUNNING）。
    let pathLen = 0, netDisp = 0, oscillating = false;
    if (self2 && self2.position) {
      if (!st.oscPos) st.oscPos = [];
      const p = self2.position;
      st.oscPos.push({ x: p.x, y: p.y, z: p.z });
      while (st.oscPos.length > OSC_WINDOW_SAMPLES) st.oscPos.shift();
      // 每次从窗口重算（自纠，不累积漂移）；超过传送阈值的一段不计入路程。
      for (let i = 1; i < st.oscPos.length; i++) {
        const a = st.oscPos[i - 1], b = st.oscPos[i];
        const d = Math.hypot(b.x - a.x, b.y - a.y, b.z - a.z);
        if (d <= OSC_TELEPORT) pathLen += d;
      }
      const first = st.oscPos[0], last = st.oscPos[st.oscPos.length - 1];
      netDisp = Math.hypot(last.x - first.x, last.y - first.y, last.z - first.z);
      oscillating = st.oscPos.length >= OSC_WINDOW_SAMPLES
        && pathLen >= OSC_PATH_MIN && netDisp <= OSC_NET_MAX;
    }

    // ④ 失败久挂：子任务 FAILED 且 id 一直不换 —— 最直白的"卡住"，比等 600s 阈值早得多
    const failedNow = String(st2.subtask_status || '').toUpperCase() === 'FAILED';
    if (failedNow) { if (!st.failedSince) st.failedSince = now; }
    else { st.failedSince = 0; }
    const failedAge = st.failedSince ? now - st.failedSince : 0;

    // 完成/失败计数（事件流去重）。失败也要数——失败重试风暴靠窗口失败数抓（D2）
    const rddPath = path.join(MON, 'rdd.jsonl');
    let newDone = 0, newFail = 0, lastId = st.lastEventId || 0;
    if (fs.existsSync(rddPath)) {
      const lines = fs.readFileSync(rddPath, 'utf8').trim().split(/\r?\n/).slice(-600);
      for (const l of lines) {
        let e; try { e = JSON.parse(l); } catch (x) { continue; }
        const id = parseInt(String(e.event_id || '').replace(/[^0-9]/g, ''), 10) || 0;
        if (id > lastId) lastId = id;
        if (id > (st.lastEventId || 0)) {
          if (e.type === 'subtask_completed') newDone++;
          else if (e.type === 'subtask_failed') newFail++;
        }
      }
    }
    st.lastEventId = lastId;
    st.completions += newDone;
    for (let i = 0; i < newFail; i++) st.failTimes.push(now);
    st.failTimes = st.failTimes.filter(t => t >= now - FAIL_STORM_WINDOW_SECONDS);
    const failWindow = st.failTimes.length;
    const minutes = Math.max(1, (now - st.startedAtSec) / 60);
    const perMin = st.completions / minutes;

    // 裁决：任一长期不动 / 反复 = 效率过低
    const signals = [];
    if (subtaskAge >= STUCK_SECONDS) signals.push('subtask_flat=' + subtaskAge + 's(' + sub + ')');
    if (assets != null && assetAge >= FLAT_SECONDS) signals.push('assets_flat=' + assetAge + 's(' + assets + ')');
    if (resAge >= FLAT_SECONDS) signals.push('res_flat=' + resAge + 's(' + resNow + ')');
    if (failedAge >= FAILED_SECONDS) signals.push('failed_stuck=' + failedAge + 's');
    if (topEntries >= LOOP_ENTRIES) signals.push('subtask_loop=' + topId + 'x' + topEntries);
    if (failWindow >= FAIL_STORM_COUNT) signals.push('fail_storm=' + failWindow + '/' + (FAIL_STORM_WINDOW_SECONDS / 60) + 'min');
    if (oscillating) signals.push('OSCILLATING path=' + pathLen.toFixed(1) + ' net=' + netDisp.toFixed(1));
    // ⑨ 规划器盲点：当前目标要的东西，世界资产里（基地/箱子）已经有足够多 → 规划器很可能没看见
    //    （见 自编译统一版\26-检测报告-规划器基地视野盲点-20260929.md）。
    //
    // 2026-09-30 修 TDZ：这三个变量原来声明在下面（第 305 行），而上���第 302 行就用 `plannerGap`
    // —— `let` 在声明前访问会抛 `ReferenceError: Cannot access 'plannerGap' before initialization`，
    // 于是**每一次采样都直接 EXCEPTION 退出**，任务效率监察这一层等于从来没跑起来过。
    // （同类形状已在别处栽过：`MutationCompiler` 先 `ByteArrayOutputStream` 后解码、
    //   `planRevision` 先用后存 —— 都是"两端语义不一致/时序错位"这一族。）
    let plannerGap = false, gapDetail = '', stockHave = null;
    if (plannerGap) signals.push('planner_gap ' + gapDetail);
    try {
      const arr = (worldAssets && (worldAssets.assets || (worldAssets.data && worldAssets.data.assets))) || [];
      const stock = {};
      for (const a of arr) {
        const v = a && a.observation && a.observation.value;
        if (!v || typeof v !== 'object') continue;
        const items = v.important_items;
        if (items && typeof items === 'object') {
          for (const k of Object.keys(items)) stock[k] = (stock[k] || 0) + (Number(items[k]) || 0);
        }
      }
      const cond = st2.condition;
      if (cond && cond.asset_key) {
        const need = Number(cond.minimum) || 0;
        const have = stock[cond.asset_key];
        if (have != null) {
          stockHave = have;
          if (need > 0 && have >= need) {
            plannerGap = true;
            gapDetail = cond.asset_key + ' need=' + need + ' worldHas=' + have;
          }
        }
      }
    } catch (e) { /* 检测是增益，出错不影响主流程 */ }

    const verdict = signals.length ? (perMin < 0.15 ? 'STUCK' : 'SLOW') : 'OK';

    const line = {
      ts: new Date().toISOString(), tag: TAG, companion: COMPANION, verdict,
      subtask: sub, subtask_age_s: subtaskAge, subtask_entered: topEntries,
      primary: st2.primary_status, sub_status: st2.subtask_status,
      condition_matches: st2.condition_matches,
      assets: assets, assets_age_s: assetAge,
      inv_age_s: invAge, res_total: resNow, res_age_s: resAge, failed_age_s: failedAge,
      fail_window: failWindow,
      pos: (self2 && self2.position) ? self2.position : null,
      path_len: Math.round(pathLen * 10) / 10, net_disp: Math.round(netDisp * 10) / 10,
      oscillating: oscillating,
      completions_total: st.completions,
      per_min: Math.round(perMin * 100) / 100, minutes: Math.round(minutes * 10) / 10,
      signals: signals, inv_fp: fp.slice(0, 220),
    };
    fs.appendFileSync(OUT, JSON.stringify(line) + '\n', 'utf8');
    st.lastSample = line.ts;
    saveState(st);

    console.log([
      new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      verdict,
      'P=' + (st2.primary_status || '?') + '/S=' + (st2.subtask_status || '?'),
      'sub_age=' + subtaskAge + 's', 'entered=' + topEntries,
      'res=' + resNow + '(' + resAge + 's)', 'failWin=' + failWindow,
      'path=' + pathLen.toFixed(1) + '/net=' + netDisp.toFixed(1),
      'done=' + st.completions + '/' + perMin.toFixed(2) + 'perMin',
      signals.length ? ('SIG[' + signals.join(' ') + ']') : '',
    ].join(' | '));

    if (ONCE) break;
    await new Promise(r => setTimeout(r, SAMPLE_SECONDS * 1000));
  }
})().catch(e => { console.error('EXCEPTION: ' + e.message); process.exit(1); });
