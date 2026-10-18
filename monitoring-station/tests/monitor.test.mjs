import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises';
import { utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import vm from 'node:vm';
import { readJsonlTail } from '../tail-jsonl.mjs';

test('large journals retain recent UTF-8 records and tolerate a partial append', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'rdd-monitor-'));
  try {
    const file = join(dir, 'rdd.jsonl');
    const row = JSON.stringify({ type: 'taskchain_snapshot', data: { text: '任务链·钻石' } });
    await writeFile(file, (row + '\n').repeat(90000) + '{"partial":');
    const rows = await readJsonlTail(file, 2048, 5);
    assert.equal(rows.length, 5);
    assert.ok(rows.every(r => r.data.text === '任务链·钻石'));
    await writeFile(file, 'bad\n' + row + '\n');
    assert.equal((await readJsonlTail(file))[0].type, 'invalid_jsonl');
    await writeFile(file, '');
    assert.deepEqual(await readJsonlTail(file), []);
    await assert.rejects(readJsonlTail(join(dir, 'missing.jsonl')), { code: 'ENOENT' });
  } finally { await rm(dir, { recursive: true, force: true }); }
});

async function loadViews() {
  const box = vm.createContext({ document: { addEventListener() {} } });
  // monitor-nav.js 必须一起加载：setPage 的白名单改成问 MonitorNav.subIds()，
  // 不加载它的话 navSubs() 返回空，setPage('docs') 会被静默丢掉（2026-10-03）。
  // 只调 subIds/resolve，不调 fromHash —— vm 里没有 location，调了才会炸。
  for (const name of ['monitor-nav.js', 'monitor-store.js', 'request-view.js', 'rdd-view.js']) {
    vm.runInContext(await readFile(new URL('../js/' + name, import.meta.url), 'utf8'), box);
  }
  return box;
}
const ui = { card: (title, body) => title + body, kv: (k, v) => k + v, flow: () => '', detail: () => '', events: () => '', supervision: () => '' };
// 阶段不能是空数组：空数组时 rdd-view 会走「这条快照里没有阶段数据」的空态，
// 下面关于资产表的断言就落不到实处了。真实快照（2026-10-03 实测）都带一级阶段。
function snapshot(id, assets, extra = {}) {
  return { category: 'rdd', type: 'taskchain_snapshot', timestamp: '2020-01-01T00:00:00Z', payload: {
    companionId: id, taskChain: { goalId: id, description: 'goal-' + id, primaries: [{ id: 'p1', description: '阶段-' + id }], primaryIndex: 0 }, assets, ...extra
  } };
}
test('RDD view isolates companions and treats removal and absent assets honestly', async () => {
  const { RddView } = await loadViews();
  const a = snapshot('a', [{ assetId: 'secret-a', observation: { value: { count: 3 } } }]);
  const b = snapshot('b', []);
  RddView.select('b');
  let html = RddView.render([a, b], [{ type: 'user_prompt', payload: { companion_id: 'a', text: 'private-a' } }], ui);
  assert.ok(!html.includes('secret-a'));
  assert.ok(!html.includes('private-a'));
  assert.ok(html.includes('本次观测为空'));
  assert.ok(html.includes('历史记录'));
  html = RddView.render([a, snapshot('b', undefined)], [], ui);
  // 这条断言原本写的是「未包含资产库」，但 rdd-view 早就把那句换成了更完整也更诚实的
  // 「尚未收到 asset_snapshot 观测…旧日志不能还原资产库存」—— 测试是陈旧的（改造前就已红）。
  // 断言的**意图**是「没收到资产快照时必须如实说没收到，不能拿别的字段凑」，
  // 所以改成匹配现在的原话，而不是把代码退回旧措辞。
  assert.ok(html.includes('尚未收到 asset_snapshot 观测'), '缺资产快照时必须明说没收到');
  assert.ok(!html.includes('本次观测为空'));
  html = RddView.render([b, snapshot('b', [], { reason: 'task_removed' })], [], ui);
  assert.ok(html.includes('任务已移除'));
  assert.ok(html.includes('移除那一刻的快照'), '时效必须标明是移除时的快照，不能说成近期观测');
  assert.ok(html.includes('当前目标'), '移除快照里的阶段要照实展示，而不是退回空态');
});
// 2026-10-03 实测踩到的：reason=task_removed 的快照**带着完整 taskChain**（7 个一级阶段）。
// 旧代码把移除一律当 null → 任务一结束 3.1 就显示「等待任务链观测」，
// 既把「已结束」说成「没观测到」，又扔掉了手里现成的链。这条锁住修正后的行为。
test('a removed task still shows the chain it left behind and says so', async () => {
  const { RddView } = await loadViews();
  const stages = [{ id: 'p1', description: '石器起步' }, { id: 'p2', description: '村庄补给' }];
  const removed = { category: 'rdd', type: 'taskchain_snapshot', timestamp: '2020-01-01T00:00:00Z', payload: {
    companionId: 'a', reason: 'task_removed', assetCount: 0,
    taskChain: { goalId: 'a', description: '挖 10 块圆石', primaries: stages, primaryIndex: 0, primaryStatus: 'RUNNING' }
  } };
  const html = RddView.renderTaskChain([removed], [], ui);
  assert.ok(html.includes('当前目标'), '移除也要照实展示留给我的链');
  assert.ok(html.includes('挖 10 块圆石'));
  assert.ok(html.includes('任务已移除'));
  assert.ok(html.includes('不代表任务现在还在跑'), '必须标明这是历史快照而不是当前运行');
  assert.ok(!html.includes('等待任务链观测'), '已结束不能被说成「没观测到」');
});
test('contexts show complete recorded text, escape markup, and remain available across pages', async () => {
  const { MonitorStore, RddView } = await loadViews();
  const text = '<script>alert(1)</script>\n' + '上下文'.repeat(400);
  MonitorStore.add({ category: 'context', type: 'user_prompt', payload: { companion_id: 'a', text } });
  MonitorStore.setPage('rdd');
  assert.equal(MonitorStore.getEvents().length, 0);
  const rows = MonitorStore.getEvents('context');
  assert.equal(rows.length, 1);
  const html = RddView.contextRows(rows);
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('上下文'.repeat(400)));
  assert.ok(!html.includes('<script>'));
  MonitorStore.setPage('docs');
  assert.equal(MonitorStore.getPage(), 'docs');
});
test('asset count zero is preserved rather than rendered as an object or unknown', async () => {
  const { RddView } = await loadViews();
  const html = RddView.assetsRows([{ assetId: 'minecraft:stick', observation: { value: { count: 0 } } }]);
  assert.ok(html.includes('<td>0</td>'));
  assert.ok(!html.includes('[object Object]'));
});

test('periodic snapshots cannot evict supervisor context from the browser store', async () => {
  const { MonitorStore } = await loadViews();
  MonitorStore.add({ category: 'rdd', type: 'supervisor_input', payload: { companionId: 'a', text: 'keep-me' } });
  for (let i = 0; i < 1000; i++) MonitorStore.add(snapshot('a', []));
  MonitorStore.add(snapshot('b', []));
  const rows = MonitorStore.getEvents('rdd');
  assert.equal(rows.length, 3);
  assert.equal(rows[0].payload.text, 'keep-me');
});

test('latest requests separate actors and companions and never borrow another request response', async () => {
  const { RequestView } = await loadViews();
  const event = (type, actor, id, timestamp, extra = {}) => ({ type, timestamp, payload: { actor, requestId: id, companionId: 'a', ...extra } });
  const events = [
    event('llm_request', 'supervisor', 'old', '2026-01-01T00:00:00Z', { request: { messages: [{ role: 'user', content: 'outdated-plan' }] } }),
    event('llm_response', 'supervisor', 'old', '2026-01-01T00:00:01Z', { response: { content: 'wrong-old-answer' } }),
    event('llm_request', 'numen', 'execute', '2026-01-01T00:00:03Z', { request: { messages: [{ role: 'user', content: 'execute-only' }], tools: [{ function: { name: 'mine' } }] } }),
    event('llm_request', 'supervisor', 'new', '2026-01-01T00:00:02Z', { status: 'dispatched', request: { messages: [{ role: 'system', content: '<knowledge>precise facts</knowledge>' }, { role: 'user', content: 'new-plan' }], tools: [] } }),
    event('llm_request', 'numen', 'private', '2026-01-01T00:00:04Z', { companionId: 'b', request: { messages: [{ role: 'user', content: 'private-companion' }] } })
  ];
  const html = RequestView.render(events, [], 'a', ui);
  const [supervisor, numen] = html.split('执行 AI · Numen');
  assert.ok(supervisor.includes('new-plan'));
  assert.ok(supervisor.includes('&lt;knowledge&gt;precise facts&lt;/knowledge&gt;'));
  assert.ok(supervisor.includes('尚无匹配回复'));
  assert.ok(!supervisor.includes('execute-only'));
  assert.ok(numen.includes('execute-only'));
  assert.ok(!html.includes('wrong-old-answer'));
  assert.ok(!html.includes('outdated-plan'));
  assert.ok(!html.includes('private-companion'));
  assert.ok(html.includes('非今天'));
});

test('raw request text is not truncated and browser store retains request under telemetry pressure', async () => {
  const { RequestView, MonitorStore } = await loadViews();
  const full = '测试全文'.repeat(2000) + 'END-OF-CONTEXT';
  const request = { category: 'context', type: 'llm_request', timestamp: '2026-01-01T00:00:00Z', payload: { actor: 'numen', companionId: 'a', requestId: 'r1', request: { messages: [{ role: 'user', content: full }] } } };
  MonitorStore.add(request);
  for (let i = 0; i < 600; i++) MonitorStore.add({ category: 'context', type: 'inventory', payload: { companionId: 'a' } });
  const html = RequestView.render(MonitorStore.getEvents('context'), [], 'a', ui);
  assert.ok(html.includes(full));
});

// ============================================================
// 第 5 组红线「历史 ≠ 当前」—— 数据新鲜度读数（第 5 组第一条）
// ============================================================
// 起因是实测反例：2026-10-04 07:20 那一刻，ACX 页显示「72 次运行」，
// 而 acx.jsonl 停在 10-03 17:32（本地），比 monitor 目录里其它文件
// 停了 8.4 小时 —— 页上那串数字是上一局的，页面从头到尾没说过这件事。
//
// 下面这些断言钉的是**判据本身**，不是某次的具体数字。

const HOUR = 3600_000;
/** 造一个 monitor 目录：mineAgoMs / otherAgoMs 各自「多久以前」写过。 */
async function stageDir(dir, mineAgoMs, otherAgoMs, nowMs) {
  const mine = join(dir, 'acx.jsonl');
  await writeFile(mine, '{"timestamp":"2020-01-01T00:00:00Z","data":{"kind":"RUN_STARTED"}}\n');
  await writeFile(join(dir, 'ai.jsonl'), '{"timestamp":"2020-01-01T00:00:00Z"}\n');
  utimesSync(mine, (nowMs - mineAgoMs) / 1000, (nowMs - mineAgoMs) / 1000);
  utimesSync(join(dir, 'ai.jsonl'), (nowMs - otherAgoMs) / 1000, (nowMs - otherAgoMs) / 1000);
  return mine;
}

test('the read window reports truncation on both axes and leaves the legacy reader untouched', async () => {
  const { readJsonlWindow, readJsonlTail } = await import('../tail-jsonl.mjs');
  const dir = await mkdtemp(join(tmpdir(), 'rdd-win-'));
  try {
    const row = JSON.stringify({ timestamp: '2020-01-01T00:00:00Z', type: 'x', data: { pad: 'p'.repeat(200) } });

    // 只撞「条数」上限：文件小，但只允许留 5 条
    const small = join(dir, 'small.jsonl');
    await writeFile(small, (row + '\n').repeat(50));
    const w1 = await readJsonlWindow(small, 8 * 1024 * 1024, 5);
    assert.equal(w1.events.length, 5);
    assert.equal(w1.linesInWindow, 50);
    assert.equal(w1.linesDropped, 45);
    assert.equal(w1.bytesDropped, 0);
    assert.equal(w1.truncated, true);          // 只报另一个维度就等于没报

    // 只撞「字节」上限
    const big = join(dir, 'big.jsonl');
    await writeFile(big, (row + '\n').repeat(2000));
    const w2 = await readJsonlWindow(big, 4096, 100000);
    assert.ok(w2.bytesDropped > 0);
    assert.equal(w2.linesDropped, 0);
    assert.equal(w2.truncated, true);

    // 没截断
    const fits = join(dir, 'fits.jsonl');
    await writeFile(fits, (row + '\n').repeat(3));
    const w3 = await readJsonlWindow(fits, 8 * 1024 * 1024, 500);
    assert.equal(w3.truncated, false);
    assert.equal(w3.bytesDropped, 0);
    assert.equal(w3.linesDropped, 0);

    // 旧函数行为必须逐字节不变（4 个既有调用方靠它）
    const legacy = await readJsonlTail(small, 8 * 1024 * 1024, 5);
    assert.deepEqual(legacy, w1.events);
    // 文件不存在照样抛，不能悄悄给空数组（那正是「0 的第三种含义」）
    await assert.rejects(readJsonlWindow(join(dir, 'nope.jsonl')), { code: 'ENOENT' });
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('five freshness verdicts stay apart, and unreadable never degrades into fresh', async () => {
  const { readJsonlWindow } = await import('../tail-jsonl.mjs');
  const { freshnessOf } = await import('../freshness.mjs');
  const NOW = Date.UTC(2026, 9, 4, 0, 0, 0);       // 固定 now ⇒ 判据可复现
  const read = async (mineAgoMs, otherAgoMs) => {
    const dir = await mkdtemp(join(tmpdir(), 'rdd-fresh-'));
    try {
      const mine = await stageDir(dir, mineAgoMs, otherAgoMs, NOW);
      const win = await readJsonlWindow(mine, 1 << 20, 100);
      return await freshnessOf({ monitorDir: dir, fileName: 'acx.jsonl', window: win, nowMs: NOW });
    } finally { await rm(dir, { recursive: true, force: true }); }
  };

  // ① 刚写过 ⇒ fresh
  const a = await read(30_000, 30_000);
  assert.equal(a.verdict, 'fresh');
  assert.equal(a.stale, false);

  // ② ★ 最危险的一态：游戏还在写别的，这份数据已经 3 小时没更新
  const b = await read(3 * HOUR, 30_000);
  assert.equal(b.verdict, 'old_while_game_alive');
  assert.equal(b.alive, true);
  assert.equal(b.stale, true);
  assert.equal(b.lagging, true);

  // ③ ★ 这一条是第一版的洞：这份恰好是目录里**最新**的一份 ⇒ behind<=0 ⇒ lagging 必须 false，
  //    但它 5 小时没写了 ⇒ stale 仍须为 true，verdict 不得落回 fresh。
  //    （若把 stale 只按相对量判，这种情况会被判成「数据是新的」——实测最该告警的情况反而漏掉。）
  const c = await read(5 * HOUR, 5 * HOUR + 20_000);
  assert.equal(c.behind_monitor_seconds <= 0, true);
  assert.equal(c.lagging, false);
  assert.equal(c.stale, true);
  assert.equal(c.verdict, 'old_but_consistent');

  // ④ 游戏停了、这份停得更早 ⇒ old_and_lagging
  const d = await read(9 * HOUR, 5 * HOUR);
  assert.equal(d.verdict, 'old_and_lagging');
  assert.equal(d.alive, false);                   // alive 只看目录最新，不看本页

  // 五态互不相同
  assert.equal(new Set([a.verdict, b.verdict, c.verdict, d.verdict]).size, 4);

  // ⑤ ★ 读不到文件：全部 null/false，且 verdict 必须是 unreadable。
  //    「年龄算不出来 ⇒ stale=false ⇒ 落进 fresh」是同一个病（未知被当成好），
  //    所以必须先拦 unreadable。
  const dir = await mkdtemp(join(tmpdir(), 'rdd-gone-'));
  try {
    await writeFile(join(dir, 'ai.jsonl'), '{}\n');
    utimesSync(join(dir, 'ai.jsonl'), NOW / 1000, NOW / 1000);
    let win = null;
    try { win = await readJsonlWindow(join(dir, 'acx.jsonl'), 1 << 20, 10); } catch { win = null; }
    const r = await freshnessOf({ monitorDir: dir, fileName: 'acx.jsonl', window: win, nowMs: NOW });
    assert.equal(r.readable, false);
    assert.equal(r.this_file_write, null);        // 不是 0
    assert.equal(r.window, null);
    assert.equal(r.behind_monitor_seconds, null); // 不是 0
    assert.equal(r.verdict, 'unreadable');        // 不是 fresh
  } finally { await rm(dir, { recursive: true, force: true }); }
});

/**
 * ACX 页的新鲜度条。**显示层才是人真正读的东西** —— 后端算对了、
 * 页面没画出来，等于没做；反过来页面画了「新的」而数据是旧的，比不画更坏。
 */
async function loadAcxView(payload) {
  const src = await readFile(new URL('../js/acx-view.js', import.meta.url), 'utf8');
  const host = { innerHTML: '', querySelectorAll: () => [] };
  const box = vm.createContext({
    document: { getElementById: id => (id === 'acx-host' ? host : null), addEventListener() {} },
    window: {},
    setInterval: () => 0,
    fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(payload) })
  });
  vm.runInContext(src, box);
  await box.window.AcxView.load();
  return host.innerHTML;
}

function acxPayload(freshness, extra) {
  return Object.assign({
    ok: true, file: 'C:/.../monitor/acx.jsonl',
    totals: { runs: 72, success: 26, paused: 16, failed: 21, timeout: 1, running: 1, rejected: 0 },
    kinds: { RUN_FINISHED: 126 },
    kindsSplit: { 'STEP_FAILED|PAUSED': 53, 'STEP_FAILED|FAIL': 29 },
    caveat: {
      steps_logged_as_failed: 82, of_which_really_failed: 29, of_which_still_in_flight: 53,
      runs_waiting_steps: 5, note: '引擎把已受理也记成 STEP_FAILED'
    },
    runs: []
  }, extra || {}, { freshness });
}
function fresh(verdict, extra) {
  return Object.assign({
    this_file: 'acx.jsonl', readable: true,
    this_file_write: '2026-10-03T09:32:33.849Z',
    seconds_since_this_file_write: 50189,
    first_event_at: '2026-10-02T09:53:07.478Z', last_event_at: '2026-10-03T09:32:33.849Z',
    events_read: 2538,
    window: { truncated: false, note_zh: '整个文件都在读取窗口里（2538 条，无截断）', bytes_dropped: 0, lines_dropped: 0 },
    monitor_newest_file: 'context.jsonl', monitor_newest_write: '2026-10-03T17:56:18.030Z',
    seconds_since_monitor_newest_write: 19400,
    behind_monitor_seconds: 30224,
    alive: true, stale: true, lagging: true,
    verdict,
    verdict_zh: {
      fresh: '这份数据是新的',
      old_while_game_alive: '★ 游戏还在写别的，但这份数据已经很久没更新 —— 数字不是当前这一局的',
      old_but_consistent: '游戏和这份数据一起停的 —— 数据自洽，但已经是过去的情况',
      old_and_lagging: '★ 游戏早就停了，而这份数据比其它文件停得更早'
    }[verdict]
  }, extra || {});
}

test('the ACX page says when its numbers are from, and refuses to render them when the file is unreadable', async () => {
  // ① 最危险的一态：游戏还在写别的，这份数据 8.4 小时没更新 ⇒ 必须红着说「不是当前」
  const bad = await loadAcxView(acxPayload(fresh('old_while_game_alive')));
  assert.ok(bad.includes('acx-fresh-bad'), '没跟上要红边');
  assert.ok(bad.includes('这份数据不是当前这一局'));
  assert.ok(bad.includes('UTC'), '时间戳必须标时区 —— 本工程反复被 UTC/本地咬过');
  assert.ok(bad.includes('游戏最后活动'), '要给出参照物：游戏最后活动');
  assert.ok(bad.includes('context.jsonl'), '要说清参照的是哪个文件');
  assert.ok(bad.includes('别的文件比它新'), '相对落后量也要报');
  // caveat 必须排在新鲜度条**后面**（先说数据是谁的，再说数字怎么读）
  assert.ok(bad.indexOf('acx-fresh-bad') < bad.indexOf('acx-caveat'));

  // ② 新数据 ⇒ 绿、且不许出现「不是当前」
  const ok = await loadAcxView(acxPayload(fresh('fresh', { stale: false, lagging: false })));
  assert.ok(ok.includes('acx-fresh-ok'));
  assert.ok(!ok.includes('不是当前这一局'));

  // ③ 游戏和这份一起停 ⇒ 黄、说「过去的情况」，不许说成失败也不许说成新的
  const old = await loadAcxView(acxPayload(fresh('old_but_consistent')));
  assert.ok(old.includes('acx-fresh-old'));
  assert.ok(old.includes('过去的情况'));
  assert.ok(!old.includes('不是当前这一局'));

  // ④ ★ 读不到 ⇒ 除了说读不到，**下面一个数字都不许渲染**
  //    （本页会显示「72 次运行」。文件读不到却还显示 72 = 在替不存在的东西背书。）
  const gone = await loadAcxView(acxPayload({
    this_file: 'acx.jsonl', readable: false, this_file_write: null,
    seconds_since_this_file_write: null, first_event_at: null, last_event_at: null,
    events_read: 0, window: null, monitor_newest_file: 'context.jsonl',
    monitor_newest_write: '2026-10-03T17:56:18.030Z', seconds_since_monitor_newest_write: 19400,
    behind_monitor_seconds: null, alive: true, stale: false, lagging: false,
    verdict: 'unreadable', verdict_zh: '★ 读不到这个文件 —— 下面的数字全部无效'
  }));
  assert.ok(gone.includes('acx-fresh-gone'));
  assert.ok(gone.includes('读不到'));
  assert.ok(gone.includes('不是「0 条」'), '要说清「读不到」与「0 条」不是一回事');
  assert.ok(!gone.includes('72'), '★ 读不到时「72 次运行」绝不许出现');
  assert.ok(!gone.includes('STEP_FAILED'), '★ 读不到时事件分布也不许出现');

  // ⑤ 窗口截断要说出来，不能让「最近 4000 条」看起来像全量
  const cut = await loadAcxView(acxPayload(fresh('fresh', {
    stale: false, lagging: false,
    window: { truncated: true, note_zh: '这个文件共 532000 字节，本次只读了尾部 3990 字节 / 15 条', bytes_dropped: 527904, lines_dropped: 0 }
  })));
  assert.ok(cut.includes('窗口截断了'));
  assert.ok(cut.includes('532000'), '要把截断掉的量写出来');
});

/* ============================================================
   ①.6 Token 消耗速率（2026-10-04 新增）
   ------------------------------------------------------------
   这一页的难处不在画图，在**三个口径不许混**：
     台账 totalTokens（只算 fresh）≠ 逐轮 totalTokens 之和；
     逐轮没有 completionTokens ⇒ 输出是推算值；
     速率分母是窗口全长（含空闲），不是「真在跑的那部分」。
   下面每条断言都在盯其中一条。
   ============================================================ */

// ★ 用真实 now 造夹具，不能写死一个时刻：createTokenView.all() 内部用 Date.now()
//   算窗口边界，写死的 T0 一旦比真实时间晚，所有轮次都会落进「未来」，
//   5 分钟窗口会把 100 分钟前那一轮也算进去 —— 那种红是测试自己造出来的假象。
const T0 = Date.now();
const iso = ms => new Date(ms).toISOString();

/** 造一个 monitor 目录：<root>/numen/monitor + <root>/numen/companions/<uuid>/stats.json */
async function tokenFixture(turns, opts) {
  const root = await mkdtemp(join(tmpdir(), 'rdd-token-'));
  const monitorDir = join(root, 'numen', 'monitor');
  await mkdir(monitorDir, { recursive: true });
  const lines = turns.map(t => JSON.stringify(t)).join('\n') + '\n';
  await writeFile(join(monitorDir, 'rdd.jsonl'), lines);
  if (opts && opts.brain) {
    await writeFile(join(monitorDir, 'brain.jsonl'), opts.brain.map(b => JSON.stringify(b)).join('\n') + '\n');
  }
  if (opts && opts.ledger) {
    for (const [id, stat] of Object.entries(opts.ledger)) {
      const dir = join(root, 'numen', 'companions', id);
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, 'stats.json'), JSON.stringify(stat));
    }
  }
  return { root, monitorDir };
}
const turn = (minutesAgo, prompt, total, extra) => Object.assign({
  type: 'llm_response',
  timestamp: iso(T0 - minutesAgo * 60000),
  data: Object.assign({ promptTokens: prompt, totalTokens: total }, extra || {})
}, {});

test('token rate differences per-turn usage, counts nothing as zero, and never adds two ledgers', async () => {
  const { createTokenView } = await import('../token-view.mjs');
  const fixture = await tokenFixture([
    turn(1, 1000, 1200, { actor: 'numen', model: 'deepseek-v4-flash' }),
    turn(3, 2000, 2400, { actor: 'numen', model: 'deepseek-v4-flash' }),
    turn(100, 5000, 5500, { actor: 'supervisor', model: 'deepseek-v4-flash' }),
    // ★ 这一轮没有 usage：必须被跳过，**不许当成 0 token**
    turn(2, undefined, undefined, { actor: 'numen', status: 'failed' }),
    { type: 'llm_request', timestamp: iso(T0 - 50000), data: { model: 'deepseek-v4-flash' } }
  ], {
    ledger: {
      'ed1606db-0000-0000-0000-000000000001': {
        totalTokens: 1000, input: 400, output: 600, cacheRead: 600, cacheWrite: 0
      }
    },
    brain: [
      { type: 'gate', timestamp: iso(T0 - 1000), data: { companion_id: 'ed1606db-0000-0000-0000-000000000001', context_tokens: 22678, model_window: 1000000, model_window_known: true, auto_compact_threshold: 987000, compacting: false, conversation_msgs: 240, blocker: 'none' } },
      // 第二只同伴：窗口未知 ⇒ 水位百分比必须是 null，不许拿 0 冒充「没占」
      { type: 'gate', timestamp: iso(T0 - 2000), data: { companion_id: 'ed1606db-0000-0000-0000-000000000002', context_tokens: 5000, model_window: 0, model_window_known: false } }
    ]
  });
  try {
    const d = await createTokenView(fixture.monitorDir).all();
    assert.equal(d.ok, true);
    assert.equal(d.coverage.turnsRead, 3, '缺 usage 的轮次不算数，也不当 0');

    const byId = Object.fromEntries(d.rate.map(w => [w.id, w]));
    assert.equal(byId['5m'].turns, 2);
    assert.equal(byId['5m'].total, 3600);
    // ★ 分母 = 窗口全长 5 分钟（含空闲），不是「那两轮实际占的 2 分钟」
    assert.equal(byId['5m'].tokensPerMin, 720, '速率分母必须是窗口全长');
    assert.equal(byId['5m'].spanMinutes, 5);
    assert.equal(byId['5m'].idle, false);
    assert.equal(byId['2h'].turns, 3);
    assert.equal(byId['all'].total, 9100);
    assert.equal(byId['all'].idle, false);
    assert.equal(byId['24h'].idle, false);

    // 输出 = total - prompt 是推算值，页面必须标「推算」：(1200-1000)+(2400-2000)+(5500-5000)=1100
    assert.equal(byId['all'].output, 1100);

    // 分桶：t1/t2 同一分钟（T0-1 与 T0-3 落在同一分钟里要看具体毫秒，
    // 所以这里只断言「不补空分钟」这一条硬纪律：桶数 <= 轮数
    assert.ok(d.buckets.buckets.length <= 3, '每分钟一根，不许补空分钟');
    assert.equal(d.buckets.buckets.reduce((a, b) => a + b.total, 0), 9100, '分桶总额必须等于逐轮合计');

    // 按角色分组：只查内容，不查顺序（排序策略不是这一页要保证的东西）
    const actorTotals = Object.fromEntries(d.byActor.map(g => [g.key, g.turns]));
    assert.deepEqual(actorTotals, { numen: 2, supervisor: 1 });
    assert.equal(d.byActor.find(g => g.key === 'supervisor').total, 5500);

    // 台账命中率分母 = cacheRead + input + cacheWrite = 600+400+0 = 1000 ⇒ 60%
    const led = d.ledger.companions[0];
    assert.equal(led.cacheHitRate, 60);
    assert.ok(d.caveats.some(c => c.id === 'two-ledgers-not-addable'), '必须说清两块台账不可相加');
    assert.ok(d.caveats.some(c => c.id === 'output-is-derived'), '必须说清输出是推算值');

    const known = d.contextWindow.companions.find(c => c.id.endsWith('0001'));
    const unknown = d.contextWindow.companions.find(c => c.id.endsWith('0002'));
    assert.equal(known.waterPercent, 2.3);
    assert.equal(known.headroomTokens, 987000 - 22678);
    assert.equal(unknown.waterPercent, null, '窗口未知时水位不许拿 0 冒充');
  } finally { await rm(fixture.root, { recursive: true, force: true }); }
});

test('token page refuses to show numbers when its source is unreadable', async () => {
  const src = await readFile(new URL('../js/token-view.js', import.meta.url), 'utf8');
  const host = { innerHTML: '' };
  // payload 走可变引用：vm 里的 fetch 是**沙箱全局**，view 闭包抓的是它，
  // 所以第二次换数据必须改这个引用，改 box.window.fetch 是不生效的（view 根本不读 window.fetch）。
  let payload = {
        ok: true,
        coverage: { turnsRead: 639, firstTurnAt: iso(T0 - 60000000), lastTurnAt: iso(T0), filesSkipped: ['context-1.jsonl'], note_zh: '只读了 15 / 29 份文件' },
        // ★ 最危险的一态：游戏还在写别的，这份数据停了很久
        freshness: Object.assign(fresh('old_while_game_alive'), { this_file: 'rdd.jsonl' }),
        rate: [{ id: '5m', label: '近 5 分钟', turns: 0, tokensPerMin: 0, turnsPerMin: 0, spanMinutes: 5, idle: true }],
        buckets: { buckets: [{ minute: iso(T0), turns: 4, total: 107795 }], peak: 107795, truncatedMinutes: 0 },
        byActor: [{ key: 'numen', turns: 634, prompt: 1, output: 2, total: 3, models: { 'deepseek-v4-flash': 634 } }],
        byModel: [],
        turns: [{ at: iso(T0), actor: 'numen', model: 'deepseek-v4-flash', promptTokens: 1, outputTokens: 2, totalTokens: 3, finishReason: 'stop' }],
        ledger: { readable: true, companions: [{ id: 'ed1606db', totalTokens: 8, input: 4, output: 4, cacheRead: 4, cacheWrite: 0, cacheHitRate: 33, mtime: iso(T0), secondsSinceWrite: 10 }], note_zh: '台账 note' },
        contextWindow: { readable: true, has_records: true, companions: [{ id: 'ed1606db', at: iso(T0), contextTokens: 22678, modelWindow: 1000000, autoCompactThreshold: 987000, waterPercent: 2.3, headroomTokens: 964322, compacting: false, conversationMsgs: 240 }], note_zh: '水位 note' },
        caveats: [{ id: 'two-ledgers-not-addable', note_zh: '台账只算 fresh，两块不要相加' }],
        emptyReasons: []
  };
  const box = vm.createContext({
    document: { getElementById: id => (id === 'token-host' ? host : null), addEventListener() {} },
    window: {}, setInterval: () => 0,
    fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(payload) })
  });
  vm.runInContext(src, box);
  await box.window.TokenView.load();
  const html = host.innerHTML;

  assert.ok(html.includes('消耗速率 · token / 分钟'), '速率卡要出');
  assert.ok(html.includes('空闲'), '窗口内没轮次要说「空闲」而不是 0');
  assert.ok(html.includes('会话台账 · 累计'), '台账要出');
  assert.ok(html.includes('上下文水位'), '水位要出');
  assert.ok(html.includes('不能和上面的逐轮合计相加'), '★ 台账必须明说不可相加');
  assert.ok(html.includes('推算'), '输出必须标推算');
  assert.ok(html.includes('窗口全长'), '分母口径要说出来');
  assert.ok(html.includes('acx-fresh-bad'), '数据不是当前这一局 ⇒ 必须红着说');
  assert.ok(html.includes('只读了 15 / 29 份文件'), '少读了哪几个文件必须说，否则速率看起来像覆盖全量');
  assert.ok(html.includes('tok-bar') || html.includes('tok-chart'), '每分钟柱要画');
  assert.ok(!html.includes('undefined'));

  // ★ 读不到 ⇒ 一个数字都不许渲染
  host.innerHTML = '';
  payload = {
    ok: true,
    coverage: { turnsRead: 0, firstTurnAt: null, lastTurnAt: null, filesSkipped: [], note_zh: '' },
    freshness: { readable: false, this_file: 'rdd.jsonl', verdict: 'unreadable', verdict_zh: '读不到' },
    rate: [{ id: 'all', label: '全部', turns: 639, tokensPerMin: 5311, turnsPerMin: 0.27, spanMinutes: 2349, idle: false }],
    buckets: { buckets: [], peak: 0 }, byActor: [], byModel: [], turns: [],
    ledger: { readable: true, companions: [{ id: 'x', totalTokens: 8124996, input: 1, output: 2, cacheRead: 3, cacheWrite: 0, cacheHitRate: 50, mtime: iso(T0), secondsSinceWrite: 1 }], note_zh: '' },
    contextWindow: { readable: true, has_records: true, companions: [{ id: 'x', at: iso(T0), contextTokens: 1, modelWindow: 2, autoCompactThreshold: 3, waterPercent: 50, headroomTokens: 2, compacting: false }], note_zh: '' },
    caveats: [], emptyReasons: []
  };
  await box.window.TokenView.load();
  const gone = host.innerHTML;
  assert.ok(gone.includes('acx-fresh-gone'), '要说读不到');
  assert.ok(!gone.includes('5311'), '★ 读不到时速率绝不许出现');
  assert.ok(!gone.includes('8124996'), '★ 读不到时台账绝不许出现');
  assert.ok(!gone.includes('会话台账'), '★ 读不到时连表头都不许出');
});

/**
 * 经验链页的逐节点新鲜度 + 逐文件采样上限。
 *
 * ★ 这批的起因是一个**页面上从来没人能发现的静默失真**：
 *   本页原来一刀切 4MB / 500 条。events.jsonl 涨到 7.6MB 之后，
 *   「⑦ 任务结果」节点只显示 **28** 条 task_finished，而文件里实际有 **3550** 条 ——
 *   窗口藏掉了 98.6%，而页面上没有任何一处说这件事。
 *   「有多少条」也有第三种含义（窗口没覆盖到），和「0 条」那三种同型。
 */
async function stageChainDir(nowMs) {
  const root = await mkdtemp(join(tmpdir(), 'rdd-chain-'));
  const mon = join(root, 'monitor');
  await mkdir(mon, { recursive: true });
  const line = (o) => JSON.stringify(Object.assign({ timestamp: '2026-01-01T00:00:00Z' }, o));
  // events.jsonl：故意写超过 4MB（默认上限）但不超过它自己的 16MB 上限
  await writeFile(join(mon, 'events.jsonl'),
    (line({ category: 'events', type: 'task_finished', data: { id: 't1', task: 'mine', status: 'done', pad: 'x'.repeat(300) } }) + '\n').repeat(6000));
  // tools.jsonl：超过 4MB —— 本页刻意不给它抬上限（理由见 SAMPLING 注释）
  await writeFile(join(mon, 'tools.jsonl'),
    (line({ category: 'tools', type: 'tool_call', data: { tool: 'goto', pad: 'y'.repeat(300) } }) + '\n').repeat(6000));
  await writeFile(join(mon, 'instrumentation.jsonl'), line({ category: 'rdd_instrumentation', type: 'death', data: {} }) + '\n');
  await writeFile(join(mon, 'brain.jsonl'), line({ category: 'brain', type: 'gate', data: { blocker: 'none' } }) + '\n');
  await writeFile(join(mon, 'learner.jsonl'),
    line({ category: 'learner', type: 'intake', data: { enqueued: 2 } }) + '\n'
    + line({ category: 'learner', type: 'noted', data: { memo_id: 'm1' } }) + '\n');
  await writeFile(join(root, 'experience-11111111-1111-1111-1111-111111111111.jsonl'),
    line({ category: 'expmem', type: 'learned', data: { title: 't', type: 'FAILURE' } }) + '\n');
  await writeFile(join(root, 'learner-memos-11111111-1111-1111-1111-111111111111.json'), '[]');
  for (const n of ['events.jsonl', 'tools.jsonl', 'instrumentation.jsonl', 'brain.jsonl', 'learner.jsonl']) {
    utimesSync(join(mon, n), nowMs / 1000, nowMs / 1000);
  }
  return { root, mon };
}

test('the chain page reports each node’s own freshness and never counts a window as the whole file', async () => {
  const { createExperienceChainView } = await import('../experience-chain-view.mjs');
  const now = Date.UTC(2026, 9, 4, 0, 0, 0);
  const { root, mon } = await stageChainDir(now);
  try {
    const d = await createExperienceChainView(mon).all();
    const byId = Object.fromEntries(d.nodes.map(n => [n.id, n]));

    // ① 三个「结构上不可观测」的节点必须给 null，不能编一个时间出来
    for (const id of ['recall', 'correlate', 'verify']) {
      assert.equal(byId[id].freshness, null, `${id} 没有真实文件来源，freshness 必须是 null`);
    }

    // ② ★ 数据由 readLearnerView / readExperienceStoreView 提供的四个节点：
    //    必须判成「读到了」，且 events_read 必须是 null（本次没量）而不是 0。
    //    第一版把「没传 window」也算进 readable ⇒ 这四个全被判成 unreadable，
    //    而它们的 age/behind 都算得出来 —— **明明读到了却说读不到**。
    for (const id of ['gate', 'noted', 'queue', 'store']) {
      const f = byId[id].freshness;
      assert.notEqual(f, null, `${id} 应该有 freshness`);
      assert.equal(f.readable, true, `${id} 明明读到了，不许判 unreadable`);
      assert.notEqual(f.verdict, 'unreadable', `${id} 不许是 unreadable`);
      assert.equal(f.events_read, null, `${id} 本次没量窗口 ⇒ events_read 必须是 null，不是 0`);
      assert.equal(f.first_event_at, null, `${id} 同理，时间范围也不能编`);
    }

    // ③ 跨目录的节点（候选队列 / 经验库在 config/numen 下）：
    //    被测目录是 config，但「游戏最后活动」的参照系必须恒为 monitor/
    for (const id of ['queue', 'store']) {
      assert.notEqual(byId[id].freshness.file_dir, mon, `${id} 的文件不在 monitor/ 下`);
      assert.equal(byId[id].freshness.reference_dir, mon, `${id} 的参照系必须是 monitor/`);
    }

    // ④ ★ 逐文件采样：events.jsonl 超过默认 4MB 但没超过它自己的 16MB ⇒ 不许被截断
    assert.equal(d.windows['events.jsonl'].truncated, false, 'events.jsonl 有专属上限，不该被 4MB 截断');
    assert.equal(byId.outcome.count, 6000, '⑥⑦ 的条数必须是文件里的真实条数，不是窗口里的一半');
    assert.deepEqual(
      Object.values(d.outcomes.byStatus).reduce((a, b) => a + b, 0),
      d.outcomes.withStatus, 'byStatus 合计必须等于 withStatus');
    assert.equal(d.sampling.perFile['events.jsonl'].maxBytes, 16 * 1024 * 1024, '上限要逐文件报出来');

    // ⑤ ★ tools.jsonl 刻意不抬上限 ⇒ 截断为真，且**两处**话都必须自己降级。
    //    只报其中一个 = 另一个仍然在拿窗口当全量。
    assert.equal(d.windows['tools.jsonl'].truncated, true, 'tools.jsonl 超过 4MB ⇒ 截断');
    const edge = d.edges.find(e => e.from === 'recall' && e.to === 'gate');
    assert.match(edge.measured, /文件共\s*\d+\s*字节/, '那条边必须给出字节证据');
    assert.match(edge.measured, /最近/, '那条边必须说清是「最近 N 条」');
    // 盲区那条：措辞随归因读数变化，但**有一条铁律**——
    //   任何情况下都不许说「全文件 0 行」。要么点名被盲掉的工具，
    //   要么明说「覆盖了本次窗口里内脑调过的全部工具」。
    //   （stageChainDir 不建 companions/ ⇒ 归因侧读到 0 只同伴 ⇒ 走后者那条分支。）
    const what = d.blindSpots.find(b => b.id === 'tools-jsonl-missing-find-tools').what;
    assert.ok(!/全文件 0 行/.test(what), '★ 盲区那条永远不许说「全文件 0 行」');
    assert.match(what, /覆盖了本次窗口|不记/, '要么点名被盲掉的工具，要么明说覆盖了本次窗口');
  } finally { await rm(root, { recursive: true, force: true }); }
});

/**
 * 经验链页的逐节点新鲜度**显示**：后端算对了而页面没画，等于没做；
 * 反过来页面画了「新的」而数据是旧的，比不画更坏。
 */
async function loadChainView(payload) {
  const src = await readFile(new URL('../js/experience-chain-view.js', import.meta.url), 'utf8');
  const host = { innerHTML: '', querySelectorAll: () => [] };
  const tip = { innerHTML: '', style: {}, className: '', contains: () => true };
  // bindTips 用 document.querySelectorAll('[data-node]') 给每个节点挂 click，
  // 详情浮层的内容由那个 handler 产生 ⇒ 这里把 handler 抓下来，等于「真的点一下」。
  const clicks = [];
  const fakeEl = (attr, val) => ({
    getAttribute: a => (a === attr ? val : null),
    addEventListener: (ev, fn) => { if (ev === 'click') clicks.push({ val, fn }); }
  });
  const byNode = new Map(), byEdge = new Map();
  for (const n of payload.nodes) { byNode.set(n.id, fakeEl('data-node', n.id)); }
  for (const e of (payload.edges || [])) { byEdge.set(e.from + '->' + e.to, fakeEl('data-edge', e.from + '->' + e.to)); }
  const box = vm.createContext({
    document: {
      getElementById: id => (id === 'experience-chain' ? host : null),
      // showTip 是 document.createElement('div') 动态建浮层再 appendChild，
      // 不是 getElementById ⇒ 这两个都要桩，否则点节点时炸 createElement。
      createElement: () => tip,
      body: { appendChild() {} },
      addEventListener() {},
      querySelectorAll: sel => (sel === '[data-node]' ? [...byNode.values()] : (sel === '[data-edge]' ? [...byEdge.values()] : []))
    },
    window: {}, setInterval: () => 0, clearInterval: () => {},
    fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(payload) })
  });
  vm.runInContext(src, box);
  // 这个 view 导出 {init, render, reload} —— 没有 load()（load 是内部函数，
  // 由 init / reload 触发）。init() 会抓住容器并发起第一次 fetch，
  // 所以「等一拍」就能拿到已渲染的 HTML。
  box.window.ExperienceChainView.init();
  await new Promise(r => setImmediate(r));
  await new Promise(r => setImmediate(r));
  const clickNode = (id) => {
    const hit = clicks.find(c => c.val === id);
    if (!hit) { throw new Error('no click handler bound for node ' + id); }
    hit.fn.call({ getAttribute: () => id });
    return tip.innerHTML;
  };
  return { host: host.innerHTML, clickNode, clicks };
}

function chainPayload() {
  const f = (verdict, extra) => Object.assign({
    this_file: 'events.jsonl', readable: true, file_dir: 'C:/mon', reference_dir: 'C:/mon',
    this_file_write: '2026-10-03T09:32:33.849Z', seconds_since_this_file_write: 50189,
    first_event_at: '2026-10-02T09:53:07.478Z', last_event_at: '2026-10-03T09:32:33.849Z',
    events_read: 19232,
    window: { truncated: false, note_zh: '整个文件都在读取窗口里', bytes_dropped: 0, lines_dropped: 0 },
    monitor_newest_file: 'context.jsonl', monitor_newest_write: '2026-10-03T17:56:18.030Z',
    seconds_since_monitor_newest_write: 19400, behind_monitor_seconds: 30224,
    alive: false, stale: true, lagging: true, verdict,
    verdict_zh: {
      fresh: '这份数据是新的',
      old_but_consistent: '游戏和这份数据一起停的 —— 已经是过去的情况',
      old_and_lagging: '★ 它比其它文件停得更早',
      old_while_game_alive: '★ 游戏还在写而它旧了',
      unreadable: '★ 读不到'
    }[verdict]
  }, extra || {});
  const n = (id, idx, label, count, fresh) => ({
    id, idx, label, count, present: count !== null, source: id + ' 的来源',
    latest: { a: 1 }, sample: [{ b: 2 }], lastAt: '2026-10-03T09:32:33.849Z', freshness: fresh
  });
  return {
    ok: true, monitorDir: 'C:/mon', configDir: 'C:/numen', timestamp: '2026-01-01T00:00:00Z',
    sampling: {
      perFile: { 'events.jsonl': { maxBytes: 16777216, maxEvents: 40000 }, 'tools.jsonl': { maxBytes: 4194304, maxEvents: 500 } },
      default: { maxBytes: 4194304, maxEvents: 500 },
      note: '「0 条」有三种含义'
    },
    windows: {
      'events.jsonl': { truncated: false, size_bytes: 7999796, window_bytes: 7999796, bytes_dropped: 0, events_read: 19232, lines_in_window: 19232, lines_dropped: 0 },
      'tools.jsonl': { truncated: true, size_bytes: 11848819, window_bytes: 4194304, bytes_dropped: 7654515, events_read: 500, lines_in_window: 5500, lines_dropped: 5000 }
    },
    nodes: [
      n('events', '①', '事件', 224, f('old_and_lagging')),
      n('outcome', '⑦', '任务结果', 3550, f('old_but_consistent', { behind_monitor_seconds: 24 })),
      n('recall', '⑥', '召回留痕', 0, null),          // 结构上不可观测 ⇒ 不许编时间
      n('noview', '⑧', '没量窗口的节点', 7, f('fresh', {
        stale: false, lagging: false, events_read: null, first_event_at: null, last_event_at: null, window: null,
        verdict_zh: '这份数据是新的'
      }))
    ],
    edges: [{ from: 'events', to: 'gate', state: 'measured', measured: '224 条 → 9 次', evidence: [], why: 'x', blind: false }],
    brain: { source: 'brain.jsonl', total: 2, sample: [] },
    outcomes: { source: 'events.jsonl#task_finished', total: 3550, withStatus: 3550, statusAbsent: 0, byStatus: { done: 96, failed: 56 }, sample: [] },
    blindSpots: [{ id: 'tools-jsonl-missing-find-tools', what: '只读了尾部', so: '看不见', truth: 'chat.jsonl' }],
    store: { totalOnDisk: 83, readableByStore: 24, files: [], conflicts: [] },
    learner: { total: 51, byType: [], noted: [], carrier: [], reviewed: [] }
  };
}

test('the chain page badges every node with its own age and never invents one for unsourced nodes', async () => {
  const p = chainPayload();
  const { host, clickNode } = await loadChainView(p);

  // ① 每个节点挂自己的徽标，颜色按 verdict 分档
  assert.ok(host.includes('expn-fresh bad'), '落后最多的节点要红（bad）');
  assert.ok(host.includes('expn-fresh old'), '只落后 24 秒但也旧了 ⇒ 黄（old），不塌进 bad');
  assert.ok(host.includes('expn-fresh ok'), '新数据要绿');
  assert.ok(host.includes('expn-fresh none'), '结构上不可观测的节点要标「无源」而不是留空');
  // ★ 徽标必须带时长 —— 只给颜色的话读的人不知道到底旧多久
  assert.match(host, /旧·[^<]*小时/, '徽标要写出「旧了多久」');
  assert.ok(host.includes('无源'), '⑥ 必须显示「无源」');

  // ② ★ 采样窗口改成逐文件之后，页面不许再打印 undefined
  //    （我改了后端 sampling 的形状而前端还读 maxBytes/maxEvents，
  //     探针只测了 .mjs 没测 .js，页面就会显示「undefined 字节」。）
  assert.ok(!host.includes('undefined'), '★ 页面里不许出现 undefined');
  assert.match(host, /逐文件/, '要说明上限是逐文件给的');
  assert.match(host, /tools\.jsonl/, '被截断的文件要在这里被点名');

  // ③ 节点详情：新鲜度排在「条数」之前（先说这是哪一段的，再给数字）
  const dOutcome = clickNode('outcome');
  assert.ok(dOutcome.indexOf('这一格的数字是什么时候的') < dOutcome.indexOf('条数：'),
    '★ 新鲜度必须排在条数之前');
  assert.ok(dOutcome.includes('UTC'), '时间戳必须标时区');
  assert.ok(dOutcome.includes('游戏最后活动'), '要给参照物');
  assert.ok(dOutcome.includes('判读：'), '要给出判读结论');

  // ④ 结构上不可观测的节点：详情里要说清「那是事实，不是页面没读」
  const dRecall = clickNode('recall');
  assert.match(dRecall, /结构上不可观测/, '⑥ 要明说结构上不可观测');
  assert.ok(!/\d+\s*小时/.test(dRecall.split('条数：')[0] || ''), '⑥ 不许编一个时长出来');

  // ⑤ 本次没量窗口的节点：必须说「没量」而不是显示 0 条
  const dNoview = clickNode('noview');
  assert.match(dNoview, /没有量/, '⑧ 要说本次没量');
  assert.ok(!dNoview.includes('本次读到 <b>0</b>'), '⑧ 不许显示「本次读到 0 条」');
});

// ============================================================
// 第 5 组红线第三条「被提及 ≠ 被调用」—— 三源工具归因
// ============================================================
// 起因是我自己被判错三次：第一次读 ai.jsonl（结构上没工具名）⇒ 假零；
// 第二次读 tools.jsonl（「find_tools 全文件 0 行」）⇒ 又一次假零。
// 真判据是内脑自己对话里的 assistant.tool_calls[].name。
//
// 下面这些断言钉的是**分类规则**，不是某次的具体数字。

async function stageAttribution(root) {
  const mon = join(root, 'monitor');
  const cfg = join(root, 'numen');
  await mkdir(join(cfg, 'companions', 'aaaaaaaa-1111-1111-1111-111111111111'), { recursive: true });
  await mkdir(mon, { recursive: true });
  const j = (o) => JSON.stringify(Object.assign({ timestamp: '2026-01-01T00:00:00Z' }, o));
  // A · 引擎日志：记 goto / acx_resume，**完全不记 find_tools / todowrite**
  await writeFile(join(mon, 'tools.jsonl'),
    j({ category: 'tools', type: 'tool_call', tool: 'goto', data: {} }) + '\n'
    + j({ category: 'tools', type: 'tool_call', tool: 'goto', data: {} }) + '\n'
    + j({ category: 'tools', type: 'tool_call', tool: 'acx_resume', data: {} }) + '\n'
    + j({ category: 'tools', type: 'tool_result', tool: 'goto', data: {} }) + '\n');   // 非 tool_call，不该被数
  // B · 内脑自己的对话：真调了 find_tools ×3 / todowrite ×2 / goto ×1；
  //     另外只在**结果文本**里提到 experience_verify（拦截消息），从未调用。
  const chat = [
    j({ role: 'assistant', content: '我先取工具定义', tool_calls: [{ id: 'c1', name: 'find_tools', arguments: '{}' }] }),
    j({ role: 'tool', tool_call_id: 'c1', content: '{"success":false,"message":"工具 mine 的参数定义尚未取回。先调 find_tools(\\"select:mine\\") 拿到完整定义再调用它"}' }),
    j({ role: 'assistant', content: '', tool_calls: [{ id: 'c2', name: 'todowrite', arguments: '{}' }, { id: 'c3', name: 'find_tools', arguments: '{}' }] }),
    j({ role: 'tool', tool_call_id: 'c3', content: '<functions expanded="experience_learn"></functions>' }),
    j({ role: 'assistant', content: '我要用 experience_verify 回报', extras: { reasoning_content: '应该调 experience_verify' } }),
    j({ role: 'tool', tool_call_id: 'c4', content: '{"success":false,"message":"工具 experience_verify 不可用。你没有取回它。"}' }),
    j({ role: 'assistant', content: '', tool_calls: [{ id: 'c5', name: 'goto', arguments: '{}' }] }),
  ].join('\n') + '\n';
  await writeFile(join(cfg, 'companions', 'aaaaaaaa-1111-1111-1111-111111111111', 'chat.jsonl'), chat);
  return { mon, cfg };
}

test('three sources are told apart: engine journal, inner-brain calls, and mentions that are not calls', async () => {
  const { createToolAttributionView } = await import('../tool-attribution.mjs');
  const root = await mkdtemp(join(tmpdir(), 'rdd-attr-'));
  try {
    const { mon, cfg } = await stageAttribution(root);
    const d = await createToolAttributionView(mon, cfg).all();
    const by = Object.fromEntries(d.tools.map(t => [t.name, t]));

    // ① 三列必须都在，且列名自带「提及不是调用」的语义
    assert.ok(by.find_tools, 'find_tools 必须在表里');
    assert.equal(by.find_tools.journal_calls, 0, '★ 引擎日志不记 find_tools');
    assert.equal(by.find_tools.brain_calls, 2, '★ 内脑自己真调了 2 次（assistant.tool_calls）');
    assert.ok(by.find_tools.mentioned_in_result > 0, '拦截消息里提到过它');
    assert.equal(by.find_tools.verdict, 'blind_to_journal', 'verdict 必须是 blind_to_journal');

    // ② ★ 这就是害我判错三次的那一列：只被提、从没被调
    assert.ok(by.experience_verify, 'experience_verify 必须在表里（它只出现在文本里）');
    assert.equal(by.experience_verify.brain_calls, 0, '★ 它从没被内脑调用过');
    assert.ok(by.experience_verify.mentioned_in_result + by.experience_verify.mentioned_in_prose > 0,
      '但它在结果文本与散文里被提到过 —— 拿这个当调用数就是我犯的错');
    // ★ 它必须**留在表里**并被标成 mention_only。
    //   第一版的表按 A ∪ B 建名字，而它既不在 A 也不在 B ⇒ 整行都不在表里
    //   ——「我最需要看见的那一行，正好被过滤器滤掉了」，又一个同病实例。
    assert.equal(by.experience_verify.verdict, 'mention_only', '只被提、从没被调 ⇒ verdict=mention_only');
    assert.equal(by.experience_verify.blocked_attempts, 1, '★ 拦截消息里能精确抠出「它被拦过 1 次」');
    assert.equal(d.totals.mention_only, 2, '★ mention_only 有两个：mine 与 experience_verify（都被拦过、都没被调）');
    assert.equal(d.totals.blocked_attempts, 2, '被拦总次数 = mine 1 + experience_verify 1');

    // ③ todowrite 是常驻工具却同样不被引擎日志记 ⇒ 「盲区」不是「不重要」
    assert.equal(by.todowrite.journal_calls, 0);
    assert.equal(by.todowrite.brain_calls, 1);

    // ④ 两个来源都有的，以及只有引擎日志有的
    assert.equal(by.goto.brain_calls, 1);
    assert.equal(by.goto.journal_calls, 2);
    assert.equal(by.goto.verdict, 'both');
    assert.equal(by.acx_resume.journal_calls, 1);
    assert.equal(by.acx_resume.brain_calls, 0);
    assert.equal(by.acx_resume.verdict, 'journal_only');

    // ⑤ type=tool_result 不该被算成调用
    assert.equal(d.totals.journal_calls, 3, '只有 3 条 tool_call（goto×2 + acx_resume×1）');

    // ⑥ ★ 两个分类都是**窗口相关**的，必须自带说明 + 可机读的标志
    assert.match(d.blind_to_journal_note, /窗口/, 'blind_to_journal 必须说明窗口会造假盲');
    assert.match(d.journal_only_note, /不可证/, 'journal_only 必须说「一定是 MCP 调的」不可证');
    assert.equal(typeof d.totals.window_sensitive.blind_to_journal, 'boolean');
    assert.equal(typeof d.totals.window_sensitive.journal_only, 'boolean');

    // ⑦ 三个来源各自的窗口读数与「它不覆盖什么」都要在
    assert.ok(d.sources.journal.window && 'size_bytes' in d.sources.journal.window);
    assert.ok(d.sources.brain.window && 'per_cap_bytes' in d.sources.brain.window);
    assert.match(d.sources.mentions.what, /不是调用/, 'C 来源必须自称不是调用');
    assert.ok(d.caveats.length >= 4, 'caveats 至少四条');
    assert.ok(d.per_companion.length >= 1);
    assert.equal(d.per_companion[0].brain_calls, 4, '内脑真调用合计 2(find_tools)+1(todowrite)+1(goto)=4');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('attribution survives a missing chat directory and still reports the engine side', async () => {
  const { createToolAttributionView } = await import('../tool-attribution.mjs');
  const root = await mkdtemp(join(tmpdir(), 'rdd-attr2-'));
  try {
    const mon = join(root, 'monitor');
    await mkdir(mon, { recursive: true });
    await writeFile(join(mon, 'tools.jsonl'),
      JSON.stringify({ type: 'tool_call', tool: 'goto', timestamp: '2026-01-01T00:00:00Z' }) + '\n');
    // 故意不建 companions/ ⇒ B 侧读不到
    const d = await createToolAttributionView(mon, join(root, 'nope')).all();
    assert.equal(d.ok, true, '★ B 侧读不到不许让整个接口挂掉');
    assert.equal(d.totals.journal_calls, 1, 'A 侧仍然要给数');
    assert.equal(d.totals.brain_calls, 0);
    assert.equal(d.sources.brain.window.companions, 0);
    assert.equal(d.per_companion.length, 0);
    // ★ 0 只同伴与「内脑真的一次都没调」不是一回事 —— 窗口说明必须能看出来
    assert.match(d.sources.brain.window.note_zh, /同伴|范围|下界|截断|0/);
  } finally { await rm(root, { recursive: true, force: true }); }
});
// ============================================================
// 第 5 组红线第四条「未知 ≠ 零」
// ============================================================
// ★ 这条红线的要害不在页面层（页面早就显示 ? 了），而在**数据结构层**：
//   实测 `node()` 对「结构上不可观测」的三个节点照样算出 count=0，
//   于是对象里同时存在 present:false 与 count:0 —— 任何只看 count 的下游
//   都会把它读成「0 条」。
// �� 立场：**未知在数据里就不能是一个数字。**

test('the unknown-vs-zero module refuses to put a number on an unknown', async () => {
  const { measure, unobservable, unknown, known, truncated, STATE, auditColumn, DISPLAY } =
    await import('../unknown-vs-zero.mjs');

  // ① 三种「未知」都不许带数字 —— 违反就抛，而不是悄悄放过去
  for (const [fn, label] of [[unobservable, 'unobservable'], [unknown, 'unknown']]) {
    const m = fn();
    assert.equal(m.count, null, `${label} 的 count 必须是 null`);
    assert.equal(m.is_number, false, `${label} 不许说自己是数字`);
  }
  assert.throws(() => measure({ state: STATE.UNOBSERVABLE, count: 0 }),
    /不许带数字/, 'unobservable 带数字必须抛 —— 那是本模块存在的理由');
  assert.throws(() => measure({ state: STATE.UNKNOWN, count: 5 }), /不许带数字/);
  // ★ 反过来：说「量到了」却没给数字也要抛（否则又是一条恒真的 known）
  assert.throws(() => measure({ state: STATE.KNOWN }), /必须带数字/);

  // ② 0 是合法值，且必须与「未知」区分得开
  const z = known(0);
  assert.equal(z.count, 0, '真的 0 条就是 0，不许变成 ?');
  assert.equal(z.is_number, true);
  assert.equal(z.display, '0');
  assert.notEqual(unobservable().display, z.display, '「0」与「无观测点」显示必须不同');

  // ③ 截断的数字是下界，显示成 ≥N
  const t = truncated(120);
  assert.equal(t.count, 120);
  assert.equal(t.display, '≥120', '截断的数必须显示成下界，不能与全量同形');
  assert.equal(t.is_number, true, '截断仍然是「量到了」，只是不完整');

  // ④ 显示口径只有一份，别处不许另写
  assert.equal(DISPLAY[STATE.KNOWN](7), '7');
  assert.equal(DISPLAY[STATE.UNKNOWN](null), '读不到');
  assert.equal(DISPLAY[STATE.UNOBSERVABLE](null), '无观测点');
  assert.equal(unobservable().reason_zh.includes('结构上'), true, 'unobservable 要说清「没有落盘的地方」');

  // ⑤ 审计：真 0 与没量到必须分开计数
  const col = [known(3), known(0), unobservable(), unknown(), truncated(9)];
  const a = auditColumn(col);
  assert.equal(a.total, 5);
  assert.equal(a.known, 2, '两个是完整量到的');
  assert.equal(a.truncated, 1);
  assert.equal(a.unknown, 1);
  assert.equal(a.unobservable, 1);
  assert.equal(a.zeros, 1, '★ 真 0 只有 1 个 —— 不能把没量到的算进去');
  assert.match(a.note_zh, /不是 0/, '审计结论必须明说「没量到的不是 0」');
});

test('the chain page never stores 0 for a node it could not measure', async () => {
  const { createExperienceChainView } = await import('../experience-chain-view.mjs');
  const now = Date.UTC(2026, 9, 4, 0, 0, 0);
  const { root, mon } = await stageChainDir(now);
  try {
    const d = await createExperienceChainView(mon).all();
    const by = Object.fromEntries(d.nodes.map(n => [n.id, n]));

    // ① ★ 三个结构上不可观测的节点：count 必须是 null，**不许是 0**
    for (const id of ['recall', 'correlate', 'verify']) {
      assert.equal(by[id].count, null, `★ ${id} 的 count 必须是 null 而不是 0`);
      assert.equal(by[id].measure.state, 'unobservable');
      assert.equal(by[id].measure.reason, 'no_sink_exists');
      assert.equal(by[id].measure.display, '无观测点');
      assert.equal(by[id].readable, false, `${id} 也不许说 readable:true`);
    }
    // ★ 这条正是本 commit 要修的那个 bug —— 旧码这里是 count:0 + present:false + readable:true
    const zeroish = d.nodes.filter(n => !n.measure.is_number && n.count === 0);
    assert.deepEqual(zeroish, [], '★ 不许存在「不是数字但 count 是 0」的节点');

    // ② 量到的节点照旧给真数字
    assert.equal(typeof by.events.count, 'number', '量到的节点必须给数字');
    assert.equal(by.events.measure.state, 'known');
    assert.equal(by.events.measure.is_number, true);

    // ③ 审计框要把真 0 与没量到分开
    assert.ok(d.audit, '响应里必须有 audit');
    assert.equal(d.audit.total, d.nodes.length);
    assert.equal(d.audit.unobservable, 3, '三个节点是「没有观测点」');
    assert.match(d.audit.note_zh, /不是 0/);

    // ④ 每个节点都带 measure —— 页面不再自己猜
    for (const n of d.nodes) {
      assert.ok(n.measure, `${n.id} 必须带 measure`);
      assert.ok(typeof n.measure.display === 'string', `${n.id} 的 measure 必须自带 display`);
    }
  } finally { await rm(root, { recursive: true, force: true }); }
});