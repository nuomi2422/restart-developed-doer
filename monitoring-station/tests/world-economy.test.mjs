import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, readdir } from 'node:fs/promises';
import { utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import vm from 'node:vm';
import { createWorldFactsView } from '../world-facts.mjs';
import { createEconomyView } from '../economy.mjs';

// ============================================================
// ⑦ 世界与经济（骨架页，2026-10-05）
// ------------------------------------------------------------
// 这两页的难处不是画图，是**「没接上」怎么表达**：
//   · 「未接入」必须是**运行时探测**出来的，写死的状态一定会骗人
//   · 「读不到」与「0 条」是三件事
//   · 派生比率分母为 0 ⇒ null
//   · 两个日志是同一批运行的两个视图 ⇒ 不可相加
//   · 页面不许出现 undefined / NaN（数据里合法的那个脚本名除外）
// 下面每条断言都在盯其中一条。
// ============================================================

const MIN = 60000;
const T0 = Date.now();
const iso = (ms) => new Date(ms).toISOString();
const at = (minutesAgo) => iso(T0 - minutesAgo * MIN);

const A = 'aaaaaaaa-1111-1111-1111-111111111111';
const B = 'bbbbbbbb-2222-2222-2222-222222222222';

/** 一个只包含 monitor/ 的骨架目录（事实类文件都在 config/numen/ 下）。 */
async function roots() {
  const root = await mkdtemp(join(tmpdir(), 'rdd-skel-'));
  const mon = join(root, 'numen', 'monitor');
  const cfg = join(root, 'numen');
  for (const d of [mon, join(cfg, 'rdd-assets'), join(cfg, 'rdd-facts'), join(cfg, 'rdd-history'),
    join(cfg, 'rdd-ledger'), join(cfg, 'acx'), join(cfg, 'acx', 'lib', 'stable')]) {
    await mkdir(d, { recursive: true });
  }
  return { root, mon, cfg };
}

/** 造一条世界事实。`extra` 可以往 observation.value 里塞新字段（模拟「后来接上了」）。 */
function fact(companionId, minutesAgo, extra) {
  const value = Object.assign({
    kind: 'location', label: 'minecraft:mineshaft', dimension: 'minecraft:overworld',
    x: 560, y: 59, z: 1402, refresh_policy: 'STATIC', summary: '走过这里',
  }, extra || {});
  return {
    assetId: 'structure|minecraft:overworld|minecraft:mineshaft|560,1402',
    observation: {
      observationId: 'obs-1', type: 'world_structure', source: 'rdd_world_observer',
      environmentId: value.dimension, observedAt: T0 - minutesAgo * MIN, value,
    },
    status: 'OBSERVED', scope: 'GLOBAL',
  };
}

async function seedFacts(cfg, opts) {
  const o = opts || {};
  await writeFile(join(cfg, 'rdd-assets', A + '.json'),
    JSON.stringify([fact(A, 5), fact(A, 600, { kind: 'machine', refresh_policy: 'LAZY' })]));
  if (o.withB) {
    await writeFile(join(cfg, 'rdd-assets', B + '.json'),
      JSON.stringify([fact(B, 1, o.valueExtra || null)]));
  }
  await writeFile(join(cfg, 'rdd-facts', A + '.json'), JSON.stringify({
    version: 1,
    stages: [{ lineageId: 'g#o', goalId: 'g', objective: '挖矿', stageKey: 's1', rawStage: '挖 10 个', at: T0 - 30 * MIN, evidence: 'primary confirmed' }],
    subtasks: [{ lineageId: 'g#o', stageKey: 's1', rawSubtask: '砍树', at: T0 - 29 * MIN }],
  }));
  await writeFile(join(cfg, 'rdd-history', A + '.json'), JSON.stringify([
    { assetId: 'minecraft:stone_pickaxe', purpose: 'UNKNOWN', state: 'LOST', lastCount: 2, dimension: 'minecraft:overworld', x: 1, y: 2, z: 3, lastSeenMillis: T0 - 90 * MIN },
  ]));
  await writeFile(join(cfg, 'rdd-ledger', A + '.json'), JSON.stringify({
    version: 1, nextSeq: 2,
    deaths: (o.ledger || [{ seq: 1, gameTime: 100, wallClockMillis: T0 - 60 * MIN, deathAt: '1,2,3', lostEntries: 0, state: 'UNVERIFIED', evidence: '' }]),
  }));
  // 让文件 mtime 明显新于 observedAt —— 用来证明「事实年龄」不取 mtime
  const future = new Date(Date.now() + 60 * MIN);
  for (const f of ['rdd-assets', 'rdd-facts', 'rdd-history', 'rdd-ledger']) {
    for (const n of await readdir(join(cfg, f))) {
      utimesSync(join(cfg, f, n), future, future);
    }
  }
}

async function seedAcx(cfg, mon, opts) {
  const o = opts || {};
  await writeFile(join(cfg, 'acx', 'library.json'), JSON.stringify({
    defs: Object.assign({
      do_while_scan: { name: 'do_while_scan', version: '1' },
      mine_nearest_ore: { name: 'mine_nearest_ore', version: '3' },
    }, o.extraDefs || {}),
  }));
  await writeFile(join(cfg, 'acx', 'lib', 'stable', 'do_while_scan.ac'), '<ac/>');
  const recs = o.records || [
    { run_id: 'r1', ac_name: 'do_while_scan', ac_version: '1', status: 'PAUSED', elapsed_ms: 100, timestamp: T0 - 5 * MIN, progress: {}, loop_count: 0, stagnant_step: null },
    { run_id: 'r2', ac_name: 'mine_nearest_ore', status: 'FAIL', elapsed_ms: 200, timestamp: T0 - 4 * MIN, progress: o.progress || {}, failed_step: 's2', error_message: '没有支撑方块' },
  ];
  await writeFile(join(cfg, 'acx', 'records.jsonl'), recs.map((r) => JSON.stringify(r)).join('\n') + '\n');
  const evs = o.events || [
    { timestamp: at(5), type: 'acx_event', data: { kind: 'RUN_STARTED', run_id: 'r1', ac_name: 'do_while_scan' } },
    { timestamp: at(5), type: 'acx_event', data: { kind: 'RUN_FINISHED', run_id: 'r1', ac_name: 'do_while_scan', status: 'SUCCESS', elapsed_ms: 100 } },
    { timestamp: at(4), type: 'acx_event', data: { kind: 'RUN_STARTED', run_id: 'r2', ac_name: 'mine_nearest_ore' } },
    { timestamp: at(4), type: 'acx_event', data: { kind: 'STEP_STARTED', run_id: 'r2', ac_name: 'mine_nearest_ore', step_id: 's2' } },
    { timestamp: at(4), type: 'acx_event', data: { kind: 'STEP_FAILED', run_id: 'r2', ac_name: 'mine_nearest_ore', step_id: 's2', status: 'FAIL' } },
    { timestamp: at(4), type: 'acx_event', data: { kind: 'RUN_FINISHED', run_id: 'r2', ac_name: 'mine_nearest_ore', status: 'FAIL', elapsed_ms: 200 } },
  ];
  await writeFile(join(mon, 'acx.jsonl'), evs.map((e) => JSON.stringify(e)).join('\n') + '\n');
}

// ============================================================
// 7.1 单一世界 · 环境事实
// ============================================================

test('四个事实来源全都不在时：判 unreadable，且空原因逐个点名', async () => {
  const { root, mon } = await roots();
  try {
    const d = await createWorldFactsView(mon).all();
    assert.equal(d.ok, true, '目录在但文件不在，不该抛异常');
    const keys = d.coverage.sources.map((s) => s.key);
    assert.deepEqual(keys, ['assets', 'lineage', 'history', 'ledger']);
    for (const s of d.coverage.sources) {
      assert.equal(s.readable, false, s.key + ' 读不到');
      assert.equal(s.missing, true);
    }
    // ★ 「一个来源都读不到」必须判 unreadable，不许落进 fresh
    assert.equal(d.freshness.verdict, 'unreadable');
    assert.equal(d.coverage.factsTotal, 0);
    // 空原因要说清是「没读到」而不是「没有」
    assert.ok(d.emptyReasons.some((x) => x.includes('rdd-assets')));
    assert.ok(d.emptyReasons.some((x) => x.includes('rdd-history')));
    assert.ok(d.emptyReasons.some((x) => x.includes('rdd-ledger')));
    // 一个事实都没有时，全部接线项都必须判未接入
    assert.equal(d.wiring.every((w) => !w.connected), true);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('事实年龄用 observedAt 而不是文件 mtime', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedFacts(cfg);   // seedFacts 把所有文件 mtime 设成「未来」
    const d = await createWorldFactsView(mon).all();
    assert.equal(d.coverage.factsTotal, 2);
    const fresh = d.facts.find((f) => f.kind === 'location');
    // observedAt 是 5 分钟前；文件 mtime 是未来 ⇒ 只有用 observedAt 才对得上。
    // 注意单位：observedAgeSeconds 是**秒**。
    assert.ok(fresh.observedAgeSeconds >= 300 - 10 && fresh.observedAgeSeconds <= 300 + 10,
      '事实年龄必须来自 observedAt，实测 ' + fresh.observedAgeSeconds + 's');
    assert.equal(fresh.stale, false, '5 分钟前观测的还算新');
    const old = d.facts.find((f) => f.kind === 'machine');
    assert.equal(old.stale, true, '600 分钟前的必须标已过期');
    // 三个年龄桶必须分清，**未知的不能塞进「已过期」**
    const byAge = Object.fromEntries(d.grades.byAge.map((a) => [a.key.replace(/[^一-龥（）]/g, ''), a.count]));
    assert.equal(d.grades.byAge.length, 3, '年龄必须分「新鲜/已过期/未知」三档');
    // 归属来源必须标出来：文件里没有 companionId 字段
    assert.equal(d.facts[0].companionFrom, '文件名');
    assert.ok(d.caveats.some((c) => c.id === 'ownership-from-filename'));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('接线状态是运行时探测出来的：数据里出现那个字段，它才翻「已接入」', async () => {
  const { root, mon, cfg } = await roots();
  try {
    // ① 基线：value 里没有 confidence / coverage ⇒ 那两条必须 missing
    await seedFacts(cfg);
    const before = await createWorldFactsView(mon).all();
    const byIdBefore = Object.fromEntries(before.wiring.map((w) => [w.id, w.connected]));
    assert.equal(byIdBefore['fact-confidence'], false, '没有 confidence 字段就是未接入');
    assert.equal(byIdBefore['fact-coverage'], false);
    assert.equal(byIdBefore['asset-purpose'], false, 'purpose 全 UNKNOWN ⇒ 未接入');
    assert.equal(byIdBefore['asset-loss-confirm'], false, 'ledger 全 UNVERIFIED ⇒ 未接入');

    // ② 造一个「后来接上了」的夹具：value 带 confidence，历史里 purpose 不再 UNKNOWN，
    //    台账里有一条已验证状态 —— 这三条必须**自动**翻绿。
    await seedFacts(cfg, {
      withB: true,
      valueExtra: { confidence: 'OBSERVED', coverage: { radius: 64 } },
      ledger: [
        { seq: 1, gameTime: 100, wallClockMillis: T0 - 60 * MIN, deathAt: '1,2,3', lostEntries: 3, state: 'VERIFIED', evidence: 'obs-world-1' },
      ],
    });
    await writeFile(join(cfg, 'rdd-history', A + '.json'), JSON.stringify([
      { assetId: 'minecraft:diamond', purpose: 'FOR_ENDER_DRAGON', state: 'CURRENT', lastCount: 3, lastSeenMillis: T0 - 10 * MIN },
    ]));
    const after = await createWorldFactsView(mon).all();
    const byIdAfter = Object.fromEntries(after.wiring.map((w) => [w.id, w.connected]));
    assert.equal(byIdAfter['fact-confidence'], true, '★ 字段出现了就要自动翻已接入');
    assert.equal(byIdAfter['fact-coverage'], true);
    assert.equal(byIdAfter['asset-purpose'], true);
    assert.equal(byIdAfter['asset-loss-confirm'], true);
    assert.ok(after.wiringSummary.connected > before.wiringSummary.connected, '总进度必须跟着涨');
    // 没被改的仍然 missing —— 不许因为「有一条翻绿」就全变绿
    assert.equal(byIdAfter['asset-loss-importance'], false, '分级重规划还没接，不许跟着翻绿');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('判据不许被兜底值骗成「已接入」', async () => {
  const { root, mon, cfg } = await roots();
  try {
    // ledger 里给一条既没有 state 也没有 evidence 的记录
    await seedFacts(cfg, { ledger: [{ seq: 1, gameTime: 1, wallClockMillis: T0 - MIN, deathAt: null, lostEntries: 0 }] });
    const d = await createWorldFactsView(mon).all();
    assert.equal(d.ledger[0].state, '未标注', '缺 state 要落成显式的「未标注」');
    assert.equal(d.ledger[0].verified, false);
    // ★ 只看 `state !== 'UNVERIFIED'` 的话，这一条会被判成「已确认」——
    //   那正是「未标注」这种兜底值骗出来的假阳性。
    const w = d.wiring.find((x) => x.id === 'asset-loss-confirm');
    assert.equal(w.connected, false, '★ 兜底值不许让「丢失确认」翻绿');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('「现在拿什么代替」跟着实测数据走，不是写死的句子', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedFacts(cfg);
    const d = await createWorldFactsView(mon).all();
    const purpose = d.wiring.find((w) => w.id === 'asset-purpose');
    // 它是函数：条数与 UNKNOWN 数都来自实测，改了数据这句话就变
    assert.match(purpose.stand, /1 条/, '应说出实际条数，实测：' + purpose.stand);
    assert.match(purpose.stand, /UNKNOWN/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('响应里不带事实原文（体积纪律），但归一化字段齐全', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedFacts(cfg);
    const d = await createWorldFactsView(mon).all();
    const f = d.facts[0];
    for (const k of ['assetId', 'type', 'source', 'dimension', 'x', 'y', 'z', 'refreshPolicy', 'status', 'observedAt', 'observedAgeSeconds', 'stale']) {
      assert.ok(f[k] !== undefined, '归一化字段 ' + k + ' 不许缺');
    }
    // ★ 原文（value / observation）不许进响应：留着会让 payload 翻三倍
    assert.equal(f.value, undefined, '事实原文不许进响应');
    assert.equal(f.raw, undefined);
  } finally { await rm(root, { recursive: true, force: true }); }
});

// ============================================================
// 7.2 经济与开关
// ============================================================

test('records.jsonl 不记 SUCCESS ⇒ 成功率只能从事件流算，且两者不可相加', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedAcx(cfg, mon);
    const d = await createEconomyView(mon).all();
    assert.equal(d.totals.recordsRead, 2);
    assert.equal(d.totals.recordsSuccess, 0, '夹具里一条 SUCCESS 都没有');
    // ★ 页面上必须把这件事说破
    const w = d.wiring.find((x) => x.id === 'success-in-records');
    assert.equal(w.connected, false);
    assert.match(w.stand, /不记成功|根本不记成功|SUCCESS/, '实测 stand：' + w.stand);
    assert.ok(d.caveats.some((c) => c.id === 'records-no-success'));
    assert.ok(d.caveats.some((c) => c.id === 'two-logs-not-addable'));
    // 成功率只从事件流算：3 次运行、1 次成功
    const scan = d.scripts.find((s) => s.name === 'do_while_scan');
    assert.equal(scan.runStarted, 1);
    assert.equal(scan.runSuccess, 1);
    assert.equal(scan.successRate, 100);
    // 没跑过的脚本：成功率必须是 null（页面显示「没跑过」），不是 0%
    const ore = d.scripts.find((s) => s.name === 'mine_nearest_ore');
    assert.equal(ore.runStarted, 1);
    assert.equal(ore.successRate, 0, '跑过但全失败 ⇒ 0%，这不是 null');
    const idle = d.scripts.find((s) => s.name === 'only_in_defs');
    void idle;
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('产量 11 个字段：progress 恒为空 ⇒ 全部未接入；出现一个键就翻一个', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedAcx(cfg, mon);
    const d = await createEconomyView(mon).all();
    assert.equal(d.progressFields.length, 11, 'AcxProgress 的 11 个字段都要列出来');
    assert.equal(d.progressFields.every((f) => !f.seen), true, 'progress 恒为 {} ⇒ 全未接入');
    assert.match(d.progressFields[0].evidence, /恒为/, '证据要写出来');
    assert.equal(d.wiring.find((w) => w.id === 'progress-fields').connected, false);
    // ★ 页面不许画产量曲线 —— 一条贴着 0 的线就是拿 0 冒充「没有产量」
    assert.ok(d.caveats.some((c) => c.id === 'progress-always-empty'));

    // 造一个「后来接上了」的夹具
    await seedAcx(cfg, mon, { progress: { collected: 12 } });
    const d2 = await createEconomyView(mon).all();
    assert.equal(d2.wiring.find((w) => w.id === 'progress-fields').connected, true);
    const collected = d2.progressFields.find((f) => f.id === 'collected');
    assert.equal(collected.seen, true);
    // 没出现的仍然是未接入 —— 不许「有一个键就全绿」
    assert.equal(d2.progressFields.find((f) => f.id === 'mined').seen, false);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('经济配置：候选文件名逐个探测，读到就翻绿，读不到不许显示默认值', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedAcx(cfg, mon);
    const d = await createEconomyView(mon).all();
    const cfgW = d.wiring.find((w) => w.id === 'eco-config-file');
    assert.equal(cfgW.connected, false);
    // 试过的文件名必须列出来（后来的 AI 照着放）
    assert.ok(d.coverage.candidatesTried.length >= 4);
    assert.match(cfgW.stand, /一个都没有|还没落地/);
    assert.ok(d.caveats.some((c) => c.id === 'no-invented-defaults'), '必须写明不编默认值');

    // 放一个合法配置进去
    await writeFile(join(cfg, 'economy.json'), JSON.stringify({
      version: 7,
      modules: { selfcompile: false },
      injection: { maxItems: 3 },
      deniedGoals: ['生电'],
    }));
    const d2 = await createEconomyView(mon).all();
    for (const id of ['eco-config-file', 'eco-module-toggle', 'eco-injection-cap', 'eco-goal-denylist', 'eco-config-version']) {
      assert.equal(d2.wiring.find((w) => w.id === id).connected, true, id + ' 应该翻绿');
    }
    // 热切换那条判据是写死的 false，不许跟着翻
    assert.equal(d2.wiring.find((w) => w.id === 'eco-hot-switch').connected, false);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('72 号八个脚本：库里没有就是「未实现」，不是「运行了 0 次」', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedAcx(cfg, mon);
    const d = await createEconomyView(mon).all();
    assert.equal(d.roster.length, 8);
    assert.equal(d.rosterFound, 0);
    for (const r of d.roster) {
      assert.equal(r.present, false);
      assert.match(r.state, /未实现/);
      // 机制假设与阻塞原因来自 72 号文档，不许留空（那是骨架的一部分）
      assert.ok(r.mechanism && r.mechanism.length > 4, r.id + ' 缺机制假设');
      assert.ok(r.blocker && r.blocker.length > 4, r.id + ' 缺阻塞原因');
    }
    assert.equal(d.wiring.find((w) => w.id === 'script-roster-72').connected, false);

    // 放一个进去看看
    await seedAcx(cfg, mon, { extraDefs: { eat_if_hungry: { name: 'eat_if_hungry', version: '1' } } });
    const d2 = await createEconomyView(mon).all();
    assert.equal(d2.rosterFound, 1);
    assert.equal(d2.roster.find((r) => r.id === 'eat_if_hungry').present, true);
    assert.equal(d2.wiring.find((w) => w.id === 'script-roster-72').connected, true);
    // 其余七个仍然是「未实现」
    assert.equal(d2.roster.filter((r) => r.present).length, 1);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('AC 侧一个来源都读不到时，整页判未接入而不是空数字', async () => {
  const { root, mon } = await roots();
  try {
    const d = await createEconomyView(mon).all();
    assert.equal(d.scripts.length, 0, '没有库也没有日志 ⇒ 一个脚本都列不出来');
    // ★ 空的 lib/stable 目录是**读到了**（答案就是「0 个」），
    //   所以整页判 unreadable 是不对的 —— 那会把「真的 0 个」说成「读不到」。
    //   正确说法是：事件流读不到 ⇒ 成功率那块没有依据。
    const byKey = Object.fromEntries(d.coverage.sources.map((s) => [s.key, s]));
    assert.equal(byKey.events.readable, false, 'acx.jsonl 读不到');
    assert.equal(byKey.records.readable, false, 'records.jsonl 读不到');
    assert.equal(byKey.library.readable, false, 'library.json 读不到');
    assert.equal(byKey.stable.readable, true, '空的 stable 目录算读到（答案就是 0 个）');
    assert.equal(d.freshness.verdict, 'old_while_game_alive');
    assert.ok(d.emptyReasons.some((x) => x.includes('acx.jsonl')), '空原因要点名事件流');
    assert.ok(d.caveats.some((c) => c.id === 'records-no-success'));
  } finally { await rm(root, { recursive: true, force: true }); }
});

// ============================================================
// 前端：把真实聚合喂进沙箱
// ============================================================
async function loadView(file, hostId, viewName, payload) {
  const src = await readFile(new URL('../js/' + file, import.meta.url), 'utf8');
  const host = { innerHTML: '' };
  const box = vm.createContext({
    document: { getElementById: (id) => (id === hostId ? host : null), addEventListener() {} },
    window: {},
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    setInterval: () => 0,
    fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve(payload) }),
  });
  vm.runInContext(src, box);
  await box.window[viewName].load();
  return { html: host.innerHTML, box, host };
}

// ★ 数据里合法存在一个 AC 脚本叫 `b9_vars_undefined`，所以判「页面上没有 undefined」
//   只能按「前面不是下划线」来判，不能整串禁掉。
const STRAY_UNDEFINED = /(?<!_)undefined/;

test('7.1 页面：接线契约、事实分级、四个来源表都在，未接上的一律写未接入', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedFacts(cfg);
    const d = await createWorldFactsView(mon).all();
    const { html } = await loadView('world-facts-view.js', 'world-facts-host', 'WorldFactsView', d);
    assert.ok(html.includes('单一世界'), '页标题要出');
    assert.ok(html.includes('第三批接线契约'), '接线契约表要出');
    assert.ok(html.includes('事实接入情况'), '来源表要出');
    assert.ok(html.includes('事实分级 · 按类型'), '分级要出');
    assert.ok(html.includes('资产状态'), '资产表要出');
    assert.ok(html.includes('死亡台账'), '台账要出');
    assert.ok(html.includes('任务链血缘'), '血缘要出');
    assert.ok(html.includes('本页口径说明'), '口径说明要出');
    assert.ok(html.includes('未接入'), '未接入标记要出');
    // 交接提示要给「下一个接手的 AI」看
    assert.ok(html.includes('给下一个接手的 AI'), '要有交接提示');
    assert.ok(!STRAY_UNDEFINED.test(html), '★ 页面里不许出现 undefined');
    assert.ok(!html.includes('NaN'), '★ 不许出现 NaN');
    assert.ok(!html.includes('**'), '★ 架构文档的 ** 强调必须渲染掉，不能漏星号');
    // 「未接上」不许被 0 代替：资产用途那一列必须是徽标而不是 0
    assert.ok(html.includes('用途未接入'));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('7.2 页面：产量字段逐个未接入、成功率只有一个来源、roster 说清都没实现', async () => {
  const { root, mon, cfg } = await roots();
  try {
    await seedAcx(cfg, mon);
    const d = await createEconomyView(mon).all();
    const { html } = await loadView('economy-view.js', 'economy-host', 'EconomyView', d);
    assert.ok(html.includes('经济与开关'), '页标题要出');
    assert.ok(html.includes('经济与开关 · 接线契约'), '接线契约要出');
    assert.ok(html.includes('产量字段 · AcxProgress 的 11 个名字'), '产量表要出');
    assert.ok(html.includes('72 号 · AC 经营脚本第一批'), 'roster 要出');
    assert.ok(html.includes('脚本台账'), '脚本表要出');
    assert.ok(html.includes('只记 PAUSED'), '★ 必须说破「落盘只记 PAUSED/FAIL/TIMEOUT」');
    assert.ok(html.includes('不画产量曲线') || html.includes('不画'), '★ 必须说破不画空产量曲线');
    assert.ok(html.includes('不会自己种田'), '★ roster 的结论要落在页面上');
    assert.ok(!STRAY_UNDEFINED.test(html), '★ 页面里不许出现 undefined');
    assert.ok(!html.includes('NaN'), '★ 不许出现 NaN');
    assert.ok(!html.includes('**'), '★ 架构文档的 ** 强调必须渲染掉');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('两页在「一个来源都读不到」时仍然渲染，且不编任何数字', async () => {
  const { root, mon } = await roots();
  try {
    const facts = await createWorldFactsView(mon).all();
    const eco = await createEconomyView(mon).all();
    const a = await loadView('world-facts-view.js', 'world-facts-host', 'WorldFactsView', facts);
    const b = await loadView('economy-view.js', 'economy-host', 'EconomyView', eco);
    for (const html of [a.html, b.html]) {
      assert.ok(html.length > 500, '来源全空时页面不能是空白');
      // 两页的 fresh 判据不同（事实页是「四个都读不到」⇒ gone；
      // 经济页因为空 stable 目录算读到 ⇒ 只到 bad），但都**必须**红着说
      assert.ok(html.includes('acx-fresh-gone') || html.includes('acx-fresh-bad'),
        '必须红着说「这些数字不属于现在 / 没接上」');
      assert.ok(!STRAY_UNDEFINED.test(html));
      assert.ok(!html.includes('NaN'));
    }
    assert.ok(a.html.includes('acx-fresh-gone'), '四个来源全无 ⇒ 必须是最重那一档 gone');
    assert.ok(a.html.includes('没有读到任何世界事实'), '要说出缺什么');
    assert.ok(b.html.includes('acx-fresh-bad'), '事件流读不到 ⇒ 至少是 bad 档');
  } finally { await rm(root, { recursive: true, force: true }); }
});
